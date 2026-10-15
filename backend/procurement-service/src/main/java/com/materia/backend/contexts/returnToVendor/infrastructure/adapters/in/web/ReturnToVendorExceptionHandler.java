package com.materia.backend.contexts.returnToVendor.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.returnToVendor.domain.exceptions.ReturnToVendorBusinessException;
import com.materia.backend.contexts.returnToVendor.domain.exceptions.ReturnToVendorNotFoundException;
import com.materia.backend.contexts.returnToVendor.domain.exceptions.ReturnToVendorValidationException;
import com.materia.backend.gateway.exception.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * How the returnToVendor context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReturnToVendorExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReturnToVendorExceptionHandler.class);

    @ExceptionHandler(ReturnToVendorNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReturnToVendorNotFoundException(ReturnToVendorNotFoundException ex, WebRequest request) {
        log.error("Return to vendor not found: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.NOT_FOUND, "Return Not Found", "RETURN_TO_VENDOR_NOT_FOUND", ex.getMessage(), request, null);
    }

    @ExceptionHandler(ReturnToVendorValidationException.class)
    public ResponseEntity<ErrorResponse> handleReturnToVendorValidationException(ReturnToVendorValidationException ex, WebRequest request) {
        log.error("Return to vendor validation error: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.BAD_REQUEST, "Return Validation Error", "RETURN_TO_VENDOR_VALIDATION_ERROR",
                ex.getMessage(), request, null);
    }

    /** Return operations refused by a business rule (quantities, status, order state) answer 409, like the rest of the chain. */
    @ExceptionHandler(ReturnToVendorBusinessException.class)
    public ResponseEntity<ErrorResponse> handleReturnToVendorBusinessException(ReturnToVendorBusinessException ex, WebRequest request) {
        log.error("Return to vendor rule violation: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.CONFLICT, "Return Error", "RETURN_TO_VENDOR_RULE_VIOLATION", ex.getMessage(), request, null);
    }
}
