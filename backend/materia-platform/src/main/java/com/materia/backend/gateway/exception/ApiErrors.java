package com.materia.backend.gateway.exception;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.Map;

/** The one error body every service answers with, for the shared handler and each context's own. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ResponseEntity<ErrorResponse> response(HttpStatus status, String error, String errorCode,
                                                         String message, WebRequest request,
                                                         Map<String, String> validationErrors) {
        return new ResponseEntity<>(body(status.value(), error, errorCode, message, request, validationErrors), status);
    }

    public static ErrorResponse body(int status, String error, String errorCode, String message, WebRequest request,
                                     Map<String, String> validationErrors) {
        ErrorResponse.ErrorResponseBuilder builder = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .error(error)
                .errorCode(errorCode)
                .message(message)
                .path(request.getDescription(false));
        if (validationErrors != null && !validationErrors.isEmpty()) {
            builder.validationErrors(validationErrors);
        }
        return builder.build();
    }
}
