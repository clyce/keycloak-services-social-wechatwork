/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.social.wechat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.core.*;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

public class WechatWorkIdentityProvider
        extends AbstractOAuth2IdentityProvider<WechatWorkProviderConfig>
        implements SocialIdentityProvider<WechatWorkProviderConfig> {

    public static final String AUTH_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";
    public static final String QRCODE_AUTH_URL =
            "https://open.work.weixin.qq.com/wwopen/sso/qrConnect"; // 企业微信外使用
    public static final String TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";

    public static final String DEFAULT_SCOPE = "snsapi_base";
    public static final String DEFAULT_RESPONSE_TYPE = "code";
    public static final String WEIXIN_REDIRECT_FRAGMENT = "wechat_redirect";

    public static final String PROFILE_URL = "https://qyapi.weixin.qq.com/cgi-bin/user/getuserinfo";
    public static final String PROFILE_DETAIL_URL = "https://qyapi.weixin.qq.com/cgi-bin/user/get";
    public static final String USER_DETAIL_URL = "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserdetail";

    public static final String OAUTH2_PARAMETER_CLIENT_ID = "appid";
    public static final String OAUTH2_PARAMETER_AGENT_ID = "agentId";
    public static final String OAUTH2_PARAMETER_RESPONSE_TYPE = "response_type";

    public static final String WEIXIN_CORP_ID = "corpid";
    public static final String WEIXIN_CORP_SECRET = "corpsecret";
    public static final String PROFILE_MOBILE = "mobile";
    public static final String PROFILE_GENDER = "gender";
    public static final String PROFILE_STATUS = "status";
    public static final String PROFILE_ENABLE = "enable";
    public static final String PROFILE_USERID = "userid";

    private final String ACCESS_TOKEN_KEY = "access_token";
    private final String ACCESS_TOKEN_CACHE_KEY = "wechat_work_sso_access_token";

    private static final DefaultCacheManager cacheManager = new DefaultCacheManager();
    private static final String WECHAT_WORK_CACHE_NAME = "wechat_work_sso";
    private static final ConcurrentMap<String, Cache<String, String>> caches =
            new ConcurrentHashMap<>();
    protected KeycloakSession session;

    private static Cache<String, String> createCache(String suffix) {
        try {
            String cacheName = WECHAT_WORK_CACHE_NAME + ":" + suffix;

            ConfigurationBuilder config = new ConfigurationBuilder();
            cacheManager.defineConfiguration(cacheName, config.build());

            Cache<String, String> cache = cacheManager.getCache(cacheName);
            logger.info(cache);
            return cache;
        } catch (Exception e) {
            logger.error(e);
            e.printStackTrace(System.out);
            throw e;
        }
    }

    private Cache<String, String> getCache() {
        return caches.computeIfAbsent(
                getConfig().getClientId() + ":" + getConfig().getAgentId(),
                WechatWorkIdentityProvider::createCache);
    }

    private String getAccessToken() {
        try {
            String token = getCache().get(ACCESS_TOKEN_CACHE_KEY);
            if (token == null) {
                JsonNode j = renewAccessToken();
                if (j == null) {
                    j = renewAccessToken();
                    if (j == null) {
                        throw new Exception("renew access token error");
                    }
                    logger.debug("retry in renew access token " + j);
                }
                token = getJsonProperty(j, ACCESS_TOKEN_KEY);
                long timeout = Integer.parseInt(getJsonProperty(j, "expires_in"));
                getCache().put(ACCESS_TOKEN_CACHE_KEY, token, timeout, TimeUnit.SECONDS);
            }
            return token;
        } catch (Exception e) {
            logger.error(e);
            e.printStackTrace(System.out);
        }
        return null;
    }

    private JsonNode renewAccessToken() {
        try {
            return SimpleHttp.doGet(TOKEN_URL, session)
                    .param(WEIXIN_CORP_ID, getConfig().getClientId())
                    .param(WEIXIN_CORP_SECRET, getConfig().getClientSecret())
                    .asJson();
        } catch (Exception e) {
            logger.error(e);
            e.printStackTrace(System.out);
        }
        return null;
    }

    private String resetAccessToken() {
        getCache().remove(ACCESS_TOKEN_CACHE_KEY);
        return getAccessToken();
    }

    public WechatWorkIdentityProvider(KeycloakSession session, WechatWorkProviderConfig config) {
        super(session, config);
        config.setAuthorizationUrl(AUTH_URL);
        config.setQrcodeAuthorizationUrl(QRCODE_AUTH_URL);
        config.setTokenUrl(TOKEN_URL);
        this.session = session;
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new org.keycloak.social.wechat.Endpoint(this, callback, realm, event);
    }

    @Override
    protected boolean supportsExternalExchange() {
        return true;
    }

    @Override
    protected BrokeredIdentityContext extractIdentityFromProfile(
            EventBuilder event, JsonNode profile) {
        logger.info(profile.toString());
        // profile: see https://work.weixin.qq.com/api/doc#90000/90135/90196
        BrokeredIdentityContext identity =
                new BrokeredIdentityContext((getJsonProperty(profile, "userid")));

        identity.setUsername(getJsonProperty(profile, "userid").toLowerCase());
        identity.setBrokerUserId(getJsonProperty(profile, "userid").toLowerCase());
        identity.setModelUsername(getJsonProperty(profile, "userid").toLowerCase());
        String email = getJsonProperty(profile, "biz_mail");
        if (email == null || email.length() == 0) {
            email = getJsonProperty(profile, "email");
        }
        if (email != null) {
            identity.setFirstName(email.split("@")[0].toLowerCase());
            identity.setEmail(email);
        }
        identity.setLastName(getJsonProperty(profile, "name"));
        // 手机号码，第三方仅通讯录应用可获取
        identity.setUserAttribute(PROFILE_MOBILE, getJsonProperty(profile, "mobile"));
        // 性别。0表示未定义，1表示男性，2表示女性
        identity.setUserAttribute(PROFILE_GENDER, getJsonProperty(profile, "gender"));
        // 激活状态: 1=已激活，2=已禁用，4=未激活。
        // 已激活代表已激活企业微信或已关注微工作台（原企业号）。未激活代表既未激活企业微信又未关注微工作台（原企业号）。
        identity.setUserAttribute(PROFILE_STATUS, getJsonProperty(profile, "status"));
        // 成员启用状态。1表示启用的成员，0表示被禁用。注意，服务商调用接口不会返回此字段
        identity.setUserAttribute(PROFILE_ENABLE, getJsonProperty(profile, "enable"));
        identity.setUserAttribute(PROFILE_USERID, getJsonProperty(profile, "userid"));

        identity.setIdpConfig(getConfig());
        identity.setIdp(this);
        AbstractJsonUserAttributeMapper.storeUserProfileForMapper(
                identity, profile, getConfig().getAlias());
        return identity;
    }

    public BrokeredIdentityContext getFederatedIdentity(String authorizationCode) {
        logger.info("getting federated identity");

        String accessToken = getAccessToken();
        if (accessToken == null) {
            throw new IdentityBrokerException("No access token available");
        }
        BrokeredIdentityContext context = null;
        try {
            JsonNode profile;
            profile =
                    SimpleHttp.doGet(PROFILE_URL, session)
                            .param(ACCESS_TOKEN_KEY, accessToken)
                            .param("code", authorizationCode)
                            .asJson();
            // {"UserId":"ZhongXun","DeviceId":"10000556333395ZN","errcode":0,"errmsg":"ok"}
            // 全局错误码 https://work.weixin.qq.com/api/doc/90001/90148/90455
            // 42001	access_token已过期
            // 40014	不合法的access_token
            logger.info("profile in federation " + profile.toString());
            long errorCode = profile.get("errcode").asInt();
            if (errorCode == 42001 || errorCode == 40014) {
                accessToken = resetAccessToken();
                profile =
                        SimpleHttp.doGet(PROFILE_URL, session)
                                .param(ACCESS_TOKEN_KEY, accessToken)
                                .param("code", authorizationCode)
                                .asJson();
                logger.info("profile retried " + profile.toString());
            }
            if (errorCode != 0) {
                throw new IdentityBrokerException("get user info failed, please retry");
            }
            profile =
                    SimpleHttp.doGet(PROFILE_DETAIL_URL, session)
                            .param(ACCESS_TOKEN_KEY, accessToken)
                            .param("userid", getJsonProperty(profile, "UserId"))
                            .asJson();
            context = extractIdentityFromProfile(null, profile);
            context.getContextData().put(FEDERATED_ACCESS_TOKEN, accessToken);
        } catch (Exception e) {
            logger.error(e);
            e.printStackTrace(System.out);
        }
        return context;
    }

    @Override
    protected String getDefaultScopes() {
        return DEFAULT_SCOPE;
    }

    @Override
    protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
        logger.info("Creating Auth Url...");

        final UriBuilder uriBuilder;

        String ua = request.getSession().getContext().getRequestHeaders().getHeaderString("user-agent");

        logger.info("构建授权链接。user-agent=" + ua + ", request=" + request);

        if (ua.contains("wxwork")) {
            logger.infov("企业微信内部浏览器，构建授权链接参数列表：{0}={1}; {2}={3}; {4}={5}; {6}={7}; {8}={9}; {10}={11}", "auth Url", getConfig().getAuthorizationUrl(), OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId(), OAUTH2_PARAMETER_REDIRECT_URI,
                    request.getRedirectUri(), OAUTH2_PARAMETER_RESPONSE_TYPE, DEFAULT_RESPONSE_TYPE, OAUTH2_PARAMETER_SCOPE,
                    getConfig().getDefaultScope(), OAUTH2_PARAMETER_STATE, request.getState().getEncoded());

            uriBuilder = UriBuilder.fromUri(getConfig().getAuthorizationUrl());
            uriBuilder
                    .queryParam(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                    .queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri())
                    .queryParam(OAUTH2_PARAMETER_RESPONSE_TYPE, DEFAULT_RESPONSE_TYPE)
                    .queryParam(OAUTH2_PARAMETER_SCOPE, getConfig().getDefaultScope())
                    .queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded());
            uriBuilder.fragment(WEIXIN_REDIRECT_FRAGMENT);
        } else {
            logger.infov("企业微信外部浏览器，构建授权链接参数列表：{0}={1}; {2}={3}; {4}={5}; {6}={7}; {8}={9}; {10}={11}", "authUrl", getConfig().getQrcodeAuthorizationUrl(), OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId(),
                    OAUTH2_PARAMETER_AGENT_ID, getConfig().getAgentId(),
                    OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri(),
                    OAUTH2_PARAMETER_STATE, request.getState().getEncoded());

            uriBuilder = UriBuilder.fromUri(getConfig().getQrcodeAuthorizationUrl());
            uriBuilder
                    .queryParam(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                    .queryParam(OAUTH2_PARAMETER_AGENT_ID.toLowerCase(), getConfig().getAgentId())
                    .queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri())
                    .queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded());
        }

        logger.info("授权链接是：" + uriBuilder.build().toString());
        return uriBuilder;
    }

    @Override
    public void updateBrokeredUser(
            KeycloakSession session, RealmModel realm, UserModel user, BrokeredIdentityContext context) {
        user.setSingleAttribute(PROFILE_MOBILE, context.getUserAttribute(PROFILE_MOBILE));
        user.setSingleAttribute(PROFILE_GENDER, context.getUserAttribute(PROFILE_GENDER));
        user.setSingleAttribute(PROFILE_STATUS, context.getUserAttribute(PROFILE_STATUS));
        user.setSingleAttribute(PROFILE_ENABLE, context.getUserAttribute(PROFILE_ENABLE));
        user.setSingleAttribute(PROFILE_USERID, context.getUserAttribute(PROFILE_USERID));

        user.setUsername(context.getUsername());
        user.setFirstName(context.getFirstName());
        user.setLastName(context.getLastName());
        user.setEmail(context.getEmail());
    }
}
