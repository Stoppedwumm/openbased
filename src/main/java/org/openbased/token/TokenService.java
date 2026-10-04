package org.openbased.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.security.ApiPrincipal;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {

    public static final String PREFIX = "ob_pat_";

    private final ApiTokenRepository tokens;
    private final OpenBasedProperties properties;

    public TokenService(ApiTokenRepository tokens, OpenBasedProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    public record Created(ApiToken token, String plaintext) {
    }

    /**
     * Creates a token. Requested scopes must be held both by the user and by the credential used to make
     * the request, so a token can never be used to mint a more powerful one.
     */
    @Transactional
    public Created create(User user, ApiPrincipal caller, String name, Set<String> scopes, Long expiresIn) {
        if (scopes == null || scopes.isEmpty()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "scopes must not be empty");
        }
        Set<String> requested = new LinkedHashSet<>(scopes);
        for (String scope : requested) {
            if (!Scopes.ALL.contains(scope)) {
                throw ApiException.unprocessable("UNKNOWN_SCOPE", "Unknown scope: " + scope);
            }
            if (!user.hasPermission(scope) || !caller.scopes().contains(scope)) {
                throw ApiException.forbidden("PERMISSION_DENIED", "You cannot grant the scope " + scope + ".");
            }
        }
        Duration ttl = expiresIn == null ? properties.getTokens().getPersonalTokenDefaultTtl()
                : Duration.ofSeconds(expiresIn);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(properties.getTokens().getPersonalTokenMaxTtl()) > 0) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "expiresIn must be between 1 and "
                    + properties.getTokens().getPersonalTokenMaxTtl().toSeconds() + " seconds");
        }
        String plaintext = PREFIX + Ids.randomAlphanumeric(40);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ApiToken token = new ApiToken();
        token.setId(Ids.prefixed("token"));
        token.setUserId(user.getId());
        token.setName(name);
        token.setScopes(requested);
        token.setTokenHash(hash(plaintext));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(ttl));
        tokens.save(token);
        return new Created(token, plaintext);
    }

    public List<ApiToken> list(String userId) {
        return tokens.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void revoke(String userId, String tokenId) {
        ApiToken token = tokens.findById(tokenId).filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("TOKEN_NOT_FOUND", "The requested token does not exist."));
        tokens.delete(token);
    }

    /** Resolves a plaintext token, returning empty when unknown or expired. */
    public Optional<ApiToken> resolve(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<ApiToken> token = tokens.findByTokenHash(hash(plaintext))
                .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(now));
        token.ifPresent(t -> {
            if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(now.minusSeconds(60))) {
                tokens.touch(t.getId(), now);
            }
        });
        return token;
    }

    static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
