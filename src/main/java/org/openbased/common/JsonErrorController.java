package org.openbased.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Renders container-level errors (outside Spring MVC) with the standard error body. */
@RestController
public class JsonErrorController implements ErrorController {

    private final ErrorWriter errors;

    public JsonErrorController(ErrorWriter errors) {
        this.errors = errors;
    }

    @RequestMapping("/error")
    ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer i ? HttpStatus.resolve(i) : null;
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String error = status.name();
        if (status.is5xxServerError()) {
            error = "INTERNAL_ERROR";
        }
        // Container errors may carry a useful reason (e.g. which OAuth2 parameter was rejected).
        Object reason = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        String message = status.is4xxClientError() && reason instanceof String r && !r.isBlank()
                ? r : status.getReasonPhrase();
        return ResponseEntity.status(status).body(errors.body(request, error, message));
    }
}
