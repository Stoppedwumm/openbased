package org.openbased.token;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.security.AccessService;
import org.openbased.security.ApiPrincipal;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tokens")
@Tag(name = "API Tokens")
public class TokenController {

    private final TokenService tokens;
    private final AccessService access;

    public TokenController(TokenService tokens, AccessService access) {
        this.tokens = tokens;
        this.access = access;
    }

    public record TokenSummary(String id, String name, Set<String> scopes, Instant createdAt, Instant expiresAt,
            Instant lastUsedAt) {
        static TokenSummary of(ApiToken t) {
            return new TokenSummary(t.getId(), t.getName(), t.getScopes(), t.getCreatedAt(), t.getExpiresAt(),
                    t.getLastUsedAt());
        }
    }

    public record TokenList(List<TokenSummary> items) {
    }

    public record CreateTokenRequest(@NotBlank @Size(max = 100) String name, @NotEmpty Set<String> scopes,
            Long expiresIn) {
    }

    public record CreatedToken(String id, String name, Set<String> scopes, String token, Instant createdAt,
            Instant expiresAt) {
    }

    @GetMapping
    @Operation(summary = "List the current user's personal access tokens (metadata only)")
    public TokenList list() {
        access.require(Scopes.PROFILE);
        User user = access.user();
        return new TokenList(tokens.list(user.getId()).stream().map(TokenSummary::of).toList());
    }

    @PostMapping
    @Operation(summary = "Create a personal access token; the plaintext token is returned only once")
    public ResponseEntity<CreatedToken> create(@Valid @RequestBody CreateTokenRequest request) {
        access.require(Scopes.PROFILE);
        User user = access.user();
        TokenService.Created created = tokens.create(user, access.principal(), request.name(), request.scopes(),
                request.expiresIn());
        ApiToken t = created.token();
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedToken(t.getId(), t.getName(),
                t.getScopes(), created.plaintext(), t.getCreatedAt(), t.getExpiresAt()));
    }

    /**
     * Lets a personal access token revoke itself, e.g. when an app signs out. Needs no scope: holding the
     * token is enough to give it up. Literal paths take precedence over {@code /{tokenId}}.
     */
    @DeleteMapping("/current")
    @Operation(summary = "Revoke the personal access token used for this request (sign out)")
    public ResponseEntity<Void> revokeCurrent() {
        ApiPrincipal principal = access.principal();
        if (principal.tokenType() != ApiPrincipal.TokenType.PERSONAL) {
            throw ApiException.badRequest("Only personal access tokens can revoke themselves. "
                    + "OAuth2 access tokens expire on their own.");
        }
        tokens.revoke(principal.userId(), principal.tokenId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{tokenId}")
    @Operation(summary = "Revoke a token")
    public ResponseEntity<Void> revoke(@PathVariable String tokenId) {
        access.require(Scopes.PROFILE);
        tokens.revoke(access.user().getId(), tokenId);
        return ResponseEntity.noContent().build();
    }
}
