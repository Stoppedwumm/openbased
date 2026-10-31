package org.openbased.security;

import java.time.Instant;
import java.util.Set;

/**
 * The caller of an API request.
 *
 * @param userId the acting user, or {@code null} for a client-credentials client without a service user
 * @param clientId the OAuth2 client that obtained the token, or {@code null} for personal access tokens
 * @param scopes the scopes granted to the token
 * @param tokenType how the caller authenticated
 * @param tokenId the personal access token ID, when {@code tokenType} is {@link TokenType#PERSONAL}
 * @param expiresAt when the token expires, if it does
 */
public record ApiPrincipal(String userId, String clientId, Set<String> scopes, TokenType tokenType,
        String tokenId, Instant expiresAt) {

    public enum TokenType {
        OAUTH2,
        PERSONAL
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public String name() {
        return userId != null ? userId : "client:" + clientId;
    }
}
