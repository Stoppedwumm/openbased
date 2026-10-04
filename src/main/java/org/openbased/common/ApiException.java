package org.openbased.common;

import org.springframework.http.HttpStatus;

/**
 * An error that is reported to the client using the standard error body.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus status() { return status; }
    public String error() { return error; }

    public static ApiException notFound(String error, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, error, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException forbidden(String error, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, error, message);
    }

    public static ApiException conflict(String error, String message) {
        return new ApiException(HttpStatus.CONFLICT, error, message);
    }

    public static ApiException unprocessable(String error, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error, message);
    }
}
