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
package org.keycloak.social.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.keycloak.OAuth2Constants;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;

import javax.ws.rs.GET;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

//import org.keycloak.protocol.oidc.OIDCLoginProtocol;

//import java.util.UUID;
//import org.apache.commons.codec.binary.StringUtils;


/**
 * 
 * @author yong.jiang
 */
public class WeiXinIdentityProvider extends AbstractOAuth2IdentityProvider<OAuth2IdentityProviderConfig>
		implements SocialIdentityProvider<OAuth2IdentityProviderConfig> {

	public static final String AUTH_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";
	public static final String TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";

	public static final String DEFAULT_SCOPE = "snsapi_base";
	public static final String DEFAULT_RESPONSE_TYPE = "code";
	public static final String WEIXIN_REDIRECT_FRAGMENT = "wechat_redirect";

	public static final String PROFILE_URL = "https://qyapi.weixin.qq.com/cgi-bin/user/getuserinfo";

	public static final String OAUTH2_PARAMETER_CLIENT_ID = "appid";
	public static final String OAUTH2_PARAMETER_CLIENT_SECRET = "secret";
	public static final String OAUTH2_PARAMETER_RESPONSE_TYPE = "response_type";

	public static final String OPENID = "openid";
	public static final String WECHATFLAG = "micromessenger";

	public static final String WEIXIN_CORP_ID = "corpid";
	public static final String WEIXIN_CORP_SECRET = "corpsecret";


	private static DefaultCacheManager _cacheManager;
	private String ACCESS_TOKEN_KEY = "access_token";
	private String ACCESS_TOKEN_CACHE_KEY = "xsyx_sso_access_token";

	public static String WECHAT_WORK_CACHE_NAME = "xsyx_sso";

	public static Cache<String, String> sso_cache = get_cache();

	private static DefaultCacheManager getCacheManager() {
		if (_cacheManager == null) {
			ConfigurationBuilder config = new ConfigurationBuilder();
			_cacheManager = new DefaultCacheManager();
			_cacheManager.defineConfiguration(WECHAT_WORK_CACHE_NAME, config.build());
		}
		return _cacheManager;
	}

	private static Cache<String, String> get_cache() {
		try {
			Cache<String, String> cache = getCacheManager().getCache(WECHAT_WORK_CACHE_NAME);
			logger.info(cache);
			return cache;
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace(System.out);
		}
		return null;
	}

	private String get_access_token() {
		try {
			String token = sso_cache.get(ACCESS_TOKEN_CACHE_KEY);
			if (token == null) {
				JsonNode j = _renew_access_token();
				token = getJsonProperty(j, ACCESS_TOKEN_KEY);
				long timeout = Integer.valueOf(getJsonProperty(j, "expires_in"));
				sso_cache.put(ACCESS_TOKEN_CACHE_KEY, token, timeout, TimeUnit.SECONDS);
			}
			return token;
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace(System.out);
		}
		return null;
	}

	private JsonNode _renew_access_token() {
		String corpid = getConfig().getClientId();
		String corpsecret = getConfig().getClientSecret();
		try {
			JsonNode j = SimpleHttp.doGet(TOKEN_URL, session)
					.param(WEIXIN_CORP_ID, corpid)
					.param(WEIXIN_CORP_SECRET, corpsecret).asJson();
			return j;
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace(System.out);
		}
		return null;
	}

	private String reset_access_token() {
		sso_cache.remove(ACCESS_TOKEN_CACHE_KEY);
		return get_access_token();
	}

	public WeiXinIdentityProvider(KeycloakSession session, OAuth2IdentityProviderConfig config) {
		super(session, config);
		config.setAuthorizationUrl(AUTH_URL);
		config.setTokenUrl(TOKEN_URL);
	}

	@Override
	public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
		return new Endpoint(callback, realm, event);
	}

	@Override
	protected boolean supportsExternalExchange() {
		return true;
	}

	@Override
	protected BrokeredIdentityContext extractIdentityFromProfile(EventBuilder event, JsonNode profile) {

		BrokeredIdentityContext user = new BrokeredIdentityContext(
				(getJsonProperty(profile, "UserId")));
		
		user.setUsername(getJsonProperty(profile, "UserId"));
		user.setBrokerUserId(getJsonProperty(profile, "UserId"));
		user.setModelUsername(getJsonProperty(profile, "UserId"));
		user.setName(getJsonProperty(profile, "DeviceId"));
		user.setIdpConfig(getConfig());
		user.setIdp(this);
		AbstractJsonUserAttributeMapper.storeUserProfileForMapper(user, profile, getConfig().getAlias());
		return user;
	}

	public BrokeredIdentityContext getFederatedIdentity(String authorizationCode) {
		String accessToken = get_access_token();
		if (accessToken == null) {
			throw new IdentityBrokerException("No access token available");
		}
		BrokeredIdentityContext context = null;
		try {
			JsonNode profile;
			profile = SimpleHttp.doGet(PROFILE_URL, session)
							.param(ACCESS_TOKEN_KEY, accessToken)
							.param("code", authorizationCode)
							.asJson();
			logger.info("profile first " + profile.toString());
			Integer errcode = Integer.valueOf(getJsonProperty(profile, "errcode"));
			if (errcode == 42001 || errcode == 40014) {
				reset_access_token();
				profile = SimpleHttp.doGet(PROFILE_URL, session)
						.param(ACCESS_TOKEN_KEY, accessToken)
						.param("code", authorizationCode)
						.asJson();
				logger.info("profile retried " + profile.toString());
			}
			if (errcode != 0) {
				throw new IdentityBrokerException("get user info failed, please retry");
			}

			logger.info("get userInfo =" + profile.toString());
			context = extractIdentityFromProfile(null, profile);
		} catch (IOException e) {
			logger.error(e);
			e.printStackTrace(System.out);
		}
		context.getContextData().put(FEDERATED_ACCESS_TOKEN, accessToken);
		return context;
	}

	@Override
	public Response performLogin(AuthenticationRequest request) {
		try {
			URI authorizationUrl = createAuthorizationUrl(request).build();
			logger.info("auth url" + authorizationUrl.toString());
			return Response.seeOther(authorizationUrl).build();
		} catch (Exception e) {
			e.printStackTrace(System.out);
			throw new IdentityBrokerException("Could not create authentication request.", e);
		}
	}

	@Override
	protected String getDefaultScopes() {
		return DEFAULT_SCOPE;
	}

	@Override
	protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {

		final UriBuilder uriBuilder;

		uriBuilder = UriBuilder.fromUri(getConfig().getAuthorizationUrl());
		uriBuilder
				.queryParam(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
				.queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri())
				.queryParam(OAUTH2_PARAMETER_RESPONSE_TYPE, DEFAULT_RESPONSE_TYPE)
				.queryParam(OAUTH2_PARAMETER_SCOPE, getConfig().getDefaultScope())
				.queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded())
				;

//		String loginHint = request.getAuthenticationSession().getClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM);
//		if (getConfig().isLoginHint() && loginHint != null) {
//			uriBuilder.queryParam(OIDCLoginProtocol.LOGIN_HINT_PARAM, loginHint);
//		}

//		String prompt = getConfig().getPrompt();
//		if (prompt == null || prompt.isEmpty()) {
//			prompt = request.getAuthenticationSession().getClientNote(OAuth2Constants.PROMPT);
//		}
//		if (prompt != null) {
//			uriBuilder.queryParam(OAuth2Constants.PROMPT, prompt);
//		}
//
//		String acr = request.getAuthenticationSession().getClientNote(OAuth2Constants.ACR_VALUES);
//		if (acr != null) {
//			uriBuilder.queryParam(OAuth2Constants.ACR_VALUES, acr);
//		}
		uriBuilder.fragment(WEIXIN_REDIRECT_FRAGMENT);

		return uriBuilder;
	}

	protected class Endpoint {
		protected AuthenticationCallback callback;
		protected RealmModel realm;
		protected EventBuilder event;

		@Context
		protected KeycloakSession session;

		@Context
		protected ClientConnection clientConnection;

		@Context
		protected HttpHeaders headers;

		@Context
		protected UriInfo uriInfo;

		public Endpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event) {
			this.callback = callback;
			this.realm = realm;
			this.event = event;
		}

		@GET
		public Response authResponse(@QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_STATE) String state,
				@QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_CODE) String authorizationCode,
				@QueryParam(OAuth2Constants.ERROR) String error) {
			logger.info("OAUTH2_PARAMETER_CODE=" + authorizationCode);

			if (error != null) {
				logger.error(error + " for broker login " + getConfig().getProviderId());
				if (error.equals(ACCESS_DENIED)) {
					return callback.cancelled(state);
				} else {
					return callback.error(state, Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
				}
			}

			try {
				BrokeredIdentityContext federatedIdentity;
				if (authorizationCode != null) {
//					String response = generateTokenRequest(authorizationCode).asString();
//					logger.info("response=" + response);
					federatedIdentity = getFederatedIdentity(authorizationCode);

					federatedIdentity.setIdpConfig(getConfig());
					federatedIdentity.setIdp(WeiXinIdentityProvider.this);
					federatedIdentity.setCode(state);

					return callback.authenticated(federatedIdentity);
				}
			} catch (WebApplicationException e) {
				e.printStackTrace(System.out);
				return e.getResponse();
			} catch (Exception e) {
				logger.error("Failed to make identity provider oauth callback", e);
				e.printStackTrace(System.out);
			}
			event.event(EventType.LOGIN);
			event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
			return ErrorPage.error(session, null, Response.Status.BAD_GATEWAY,
					Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
		}

		public SimpleHttp generateTokenRequest(String authorizationCode) {
			return SimpleHttp.doGet(getConfig().getTokenUrl(), session)
//					.param(WEIXIN_CORP_ID, getConfig().getClientId())
//					.param(WEIXIN_CORP_SECRET, getConfig().getClientSecret())
//					.param(OAUTH2_PARAMETER_CODE, authorizationCode)
//					.param(OAUTH2_PARAMETER_GRANT_TYPE, OAUTH2_GRANT_TYPE_AUTHORIZATION_CODE)
					;
		}
	}
}
