package org.openbased.security;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.openbased.common.ErrorWriter;
import org.openbased.config.OpenBasedProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiting per user (or client, or IP address for anonymous requests). Limits are
 * configured with {@code openbased.rate-limit.*}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final OpenBasedProperties.RateLimit config;
    private final ErrorWriter errors;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(OpenBasedProperties.RateLimit config, ErrorWriter errors) {
        this.config = config;
        this.errors = errors;
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        long windowMillis = config.getWindow().toMillis();
        long windowStart = now - (now % windowMillis);
        String key = key(request);
        int count;
        synchronized (windows) {
            Window window = windows.get(key);
            if (window == null || window.start != windowStart) {
                if (windows.size() > 100_000) {
                    windows.values().removeIf(w -> w.start != windowStart);
                }
                window = new Window(windowStart);
                windows.put(key, window);
            }
            count = ++window.count;
        }
        long resetSeconds = (windowStart + windowMillis) / 1000;
        int remaining = Math.max(0, config.getLimit() - count);
        response.setHeader("X-RateLimit-Limit", String.valueOf(config.getLimit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetSeconds));
        if (count > config.getLimit()) {
            long retryAfter = Math.max(1, resetSeconds - now / 1000);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            errors.write(request, response, 429, "RATE_LIMITED", "Too many requests. Retry after " + retryAfter + " seconds.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String key(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiAuthentication api) {
            return "p:" + api.getPrincipal().name();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
