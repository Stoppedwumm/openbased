package org.openbased.security;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.openbased.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Reads the bearer token from the {@code Authorization} header. Browsers cannot set headers on
 * {@code <video>} or WebSocket requests, so short-lived OAuth2 access tokens (never personal access
 * tokens) are additionally accepted in an {@code access_token} query parameter on playback streams and the
 * event socket only. Plain media IDs therefore never become permanent download links.
 */
public class ApiBearerTokenResolver implements BearerTokenResolver {

    private static final Pattern QUERY_TOKEN_PATHS =
            Pattern.compile("^/api/v1/(events|playback/[^/]+/stream)$");

    @Override
    public String resolve(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return null;
            }
            String token = header.substring(7).trim();
            if (token.isEmpty()) {
                throw new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Bearer token is malformed"));
            }
            return token;
        }
        if ("GET".equals(request.getMethod()) && QUERY_TOKEN_PATHS.matcher(request.getRequestURI()).matches()) {
            String token = request.getParameter("access_token");
            if (token != null && !token.isBlank() && !token.startsWith(TokenService.PREFIX)) {
                return token;
            }
        }
        return null;
    }
}
