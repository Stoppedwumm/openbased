package org.openbased.common;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the standard error body from servlet filters, where controller advice does not apply. */
@Component
public class ErrorWriter {

    private final ObjectMapper objectMapper;

    public ErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ErrorResponse body(HttpServletRequest request, String error, String message) {
        return new ErrorResponse(error, message, RequestIds.of(request), Instant.now());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String error,
            String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body(request, error, message));
    }
}
