package org.openbased.security.oauth;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A refresh token that was replaced by rotation. Presenting it again means it was copied, so the whole
 * authorization it belonged to is revoked (refresh token reuse detection, OAuth 2.0 Security BCP §4.14).
 */
@Entity
@Table(name = "oauth2_used_refresh_tokens", indexes = @Index(columnList = "authorizationId"))
public class UsedRefreshToken {

    @Id
    private String tokenHash;
    private String authorizationId;
    private Instant usedAt;
    private Instant expiresAt;

    protected UsedRefreshToken() {
    }

    UsedRefreshToken(String tokenHash, String authorizationId, Instant usedAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.authorizationId = authorizationId;
        this.usedAt = usedAt;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public String getAuthorizationId() { return authorizationId; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
