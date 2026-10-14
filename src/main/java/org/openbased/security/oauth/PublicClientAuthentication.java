package org.openbased.security.oauth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Client authentication for public clients (no secret) on the requests where Spring Authorization Server has
 * no built-in support: refreshing a token and revoking one. A public client identifies itself with
 * {@code client_id}; the refresh token or token being revoked is the credential.
 */
public final class PublicClientAuthentication {

    /** Marks authentication requests produced by {@link Converter}, so {@link Provider} handles only those. */
    static final String MARKER = PublicClientAuthentication.class.getName();

    private PublicClientAuthentication() {
    }

    public static class Converter implements AuthenticationConverter {

        private final AuthorizationServerSettings settings;

        public Converter(AuthorizationServerSettings settings) {
            this.settings = settings;
        }

        @Override
        public Authentication convert(HttpServletRequest request) {
            if (!HttpMethod.POST.matches(request.getMethod()) || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) {
                return null;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean refresh = path.equals(settings.getTokenEndpoint())
                    && AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
            boolean revoke = path.equals(settings.getTokenRevocationEndpoint());
            String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
            if (!(refresh || revoke) || clientId == null || clientId.isBlank()) {
                return null;
            }
            if (request.getParameterValues(OAuth2ParameterNames.CLIENT_ID).length != 1) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
            }
            return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null,
                    Map.of(MARKER, true));
        }
    }

    public static class Provider implements org.springframework.security.authentication.AuthenticationProvider {

        private final RegisteredClientRepository clients;

        public Provider(RegisteredClientRepository clients) {
            this.clients = clients;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                    || !Boolean.TRUE.equals(token.getAdditionalParameters().get(MARKER))) {
                return null;
            }
            RegisteredClient client = clients.findByClientId((String) token.getPrincipal());
            if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
