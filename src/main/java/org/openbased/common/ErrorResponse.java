package org.openbased.common;

import java.time.Instant;

/** The standard error body returned by every endpoint. */
public record ErrorResponse(String error, String message, String requestId, Instant timestamp) {
}
