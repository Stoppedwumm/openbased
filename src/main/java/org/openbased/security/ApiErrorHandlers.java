package org.openbased.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.openbased.common.ErrorWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401/403 responses with both the RFC 6750 {@code WWW-Authenticate} header and the standard error body. */
public class ApiErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ErrorWriter errors;

    public ApiErrorHandlers(ErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        bearer.commence(request, response, e);
        if (e instanceof OAuth2AuthenticationException oauth) {
            String description = oauth.getError().getDescription();
            errors.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN",
                    description != null ? description : "The access token is invalid.");
        } else {
            errors.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    "Authentication is required.");
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        errors.write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied.");
    }
}
