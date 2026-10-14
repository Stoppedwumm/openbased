package org.openbased.security.oauth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Generates refresh tokens for every client that is registered for the {@code refresh_token} grant, including
 * public clients (native and TV apps). Spring Authorization Server's own generator refuses public clients; here
 * they are protected by rotation and reuse detection instead ({@link RefreshTokenReuseDetector}).
 */
public class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
                || !context.getRegisteredClient().getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            return null;
        }
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), issuedAt, expiresAt);
    }
}
