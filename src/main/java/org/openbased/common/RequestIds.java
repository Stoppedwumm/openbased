package org.openbased.common;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request an ID that is echoed in {@code X-Request-Id} and in error bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {

    public static final String ATTRIBUTE = RequestIds.class.getName() + ".id";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = Ids.ulid();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    public static String of(HttpServletRequest request) {
        Object id = request == null ? null : request.getAttribute(ATTRIBUTE);
        return id != null ? id.toString() : Ids.ulid();
    }
}
