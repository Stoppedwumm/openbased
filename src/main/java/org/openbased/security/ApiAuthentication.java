package org.openbased.security;

import java.util.stream.Collectors;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** An authenticated API caller, produced from either an OAuth2 access token or a personal access token. */
public class ApiAuthentication extends AbstractAuthenticationToken {

    private final ApiPrincipal principal;
    private final String token;

    public ApiAuthentication(ApiPrincipal principal, String token) {
        super(principal.scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                .collect(Collectors.toSet()));
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public ApiPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.name();
    }
}
