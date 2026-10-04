package org.openbased.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.openbased.token.ApiToken;
import org.openbased.token.TokenService;
import org.openbased.user.User;
import org.openbased.user.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * Authenticates bearer tokens: {@code ob_pat_} personal access tokens are looked up in the database,
 * everything else is validated as a signed OAuth2 access token issued by this server.
 */
public class ApiAuthenticationManager implements AuthenticationManager {

    private final TokenService tokens;
    private final UserRepository users;
    private final JwtAuthenticationProvider jwtProvider;

    public ApiAuthenticationManager(TokenService tokens, UserRepository users, JwtDecoder jwtDecoder) {
        this.tokens = tokens;
        this.users = users;
        this.jwtProvider = new JwtAuthenticationProvider(jwtDecoder);
        this.jwtProvider.setJwtAuthenticationConverter(this::fromJwt);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((BearerTokenAuthenticationToken) authentication).getToken();
        if (token.startsWith(TokenService.PREFIX)) {
            return fromPersonalToken(token);
        }
        return jwtProvider.authenticate(authentication);
    }

    private ApiAuthentication fromPersonalToken(String token) {
        ApiToken apiToken = tokens.resolve(token).orElseThrow(() -> invalid("The token is invalid, expired or revoked"));
        requireActiveUser(apiToken.getUserId());
        ApiPrincipal principal = new ApiPrincipal(apiToken.getUserId(), null, Set.copyOf(apiToken.getScopes()),
                ApiPrincipal.TokenType.PERSONAL, apiToken.getId(), apiToken.getExpiresAt());
        return new ApiAuthentication(principal, token);
    }

    private ApiAuthentication fromJwt(Jwt jwt) {
        String subject = jwt.getSubject();
        String clientId = clientId(jwt);
        String userId = null;
        if (subject != null && !subject.startsWith("client:")) {
            requireActiveUser(subject);
            userId = subject;
        }
        ApiPrincipal principal = new ApiPrincipal(userId, clientId, scopes(jwt), ApiPrincipal.TokenType.OAUTH2, null,
                jwt.getExpiresAt());
        return new ApiAuthentication(principal, jwt.getTokenValue());
    }

    private void requireActiveUser(String userId) {
        if (users.findById(userId).filter(User::isEnabled).isEmpty()) {
            throw invalid("The token's user no longer exists or is disabled");
        }
    }

    private static String clientId(Jwt jwt) {
        Object azp = jwt.getClaims().get("client_id");
        if (azp != null) {
            return azp.toString();
        }
        return jwt.getAudience() == null || jwt.getAudience().isEmpty() ? null : jwt.getAudience().get(0);
    }

    private static Set<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaims().get("scope");
        Set<String> scopes = new LinkedHashSet<>();
        if (scope instanceof Collection<?> c) {
            c.forEach(s -> scopes.add(s.toString()));
        } else if (scope instanceof String s && !s.isBlank()) {
            scopes.addAll(Set.of(s.trim().split("\\s+")));
        }
        return Set.copyOf(scopes);
    }

    private static OAuth2AuthenticationException invalid(String message) {
        return new OAuth2AuthenticationException(BearerTokenErrors.invalidToken(message));
    }
}
