package org.keycloak.social.wechat;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityProvider;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

class Endpoint extends WechatWorkIdentityProvider {
    private final WechatWorkIdentityProvider wechatWorkIdentityProvider;
    protected IdentityProvider.AuthenticationCallback callback;
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

    public Endpoint(WechatWorkIdentityProvider wechatWorkIdentityProvider, IdentityProvider.AuthenticationCallback callback, RealmModel realm, EventBuilder event) {
        super(wechatWorkIdentityProvider.session, wechatWorkIdentityProvider.getConfig());
        this.wechatWorkIdentityProvider = wechatWorkIdentityProvider;
        this.callback = callback;
        this.realm = realm;
        this.event = event;
    }

    @GET
    public Response authResponse(
            @QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_STATE) String state,
            @QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_CODE) String authorizationCode,
            @QueryParam(OAuth2Constants.ERROR) String error,
            @QueryParam("appid") String client_id) {
        AbstractOAuth2IdentityProvider.logger.info("OAUTH2_PARAMETER_CODE=" + authorizationCode);

        // 以下样版代码从 AbstractOAuth2IdentityProvider 里获取的。
        if (state == null) {
            return errorIdentityProviderLogin(Messages.IDENTITY_PROVIDER_MISSING_STATE_ERROR);
        }
        try {
            AuthenticationSessionModel authSession =
                    this.callback.getAndVerifyAuthenticationSession(state);

            if (session != null) {
                session.getContext().setAuthenticationSession(authSession);
            }

            if (error != null) {
                AbstractOAuth2IdentityProvider.logger.error(error + " for broker login " + wechatWorkIdentityProvider.getConfig().getProviderId());
                if (error.equals(AbstractOAuth2IdentityProvider.ACCESS_DENIED)) {
                    return callback.cancelled(wechatWorkIdentityProvider.getConfig());
                } else if (error.equals(OAuthErrorException.LOGIN_REQUIRED)
                        || error.equals(OAuthErrorException.INTERACTION_REQUIRED)) {
                    return callback.error(error);
                } else {
                    return callback.error(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
                }
            }

            if (authorizationCode != null) {
                BrokeredIdentityContext federatedIdentity = wechatWorkIdentityProvider.getFederatedIdentity(authorizationCode);

                federatedIdentity.setIdpConfig(wechatWorkIdentityProvider.getConfig());
                federatedIdentity.setIdp(wechatWorkIdentityProvider);
                federatedIdentity.setAuthenticationSession(authSession);

                return callback.authenticated(federatedIdentity);
            }
        } catch (WebApplicationException e) {
            e.printStackTrace(System.out);
            return e.getResponse();
        } catch (Exception e) {
            AbstractOAuth2IdentityProvider.logger.error("Failed to make identity provider oauth callback", e);
            e.printStackTrace(System.out);
        }
        return errorIdentityProviderLogin(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
    }

    private Response errorIdentityProviderLogin(String message) {
        event.event(EventType.IDENTITY_PROVIDER_LOGIN);
        event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
        return ErrorPage.error(session, null, Response.Status.BAD_GATEWAY, message);
    }
}
