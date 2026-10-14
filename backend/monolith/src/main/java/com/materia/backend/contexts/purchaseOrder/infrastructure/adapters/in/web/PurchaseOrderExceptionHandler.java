package com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.purchaseOrder.domain.exceptions.PurchaseOrderBusinessException;
import com.materia.backend.contexts.purchaseOrder.domain.exceptions.PurchaseOrderNotFoundException;
import com.materia.backend.contexts.purchaseOrder.domain.exceptions.PurchaseOrderValidationException;
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
 * How the purchaseOrder context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PurchaseOrderExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderExceptionHandler.class);

    @ExceptionHandler(PurchaseOrderBusinessException.class)
    public ResponseEntity<ErrorResponse> handlePurchaseOrderBusinessException(
            PurchaseOrderBusinessException ex, WebRequest request) {
        log.error("Purchase order exception: {}", ex.getMessage(), ex);
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "Purchase Order Error",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePurchaseOrderNotFoundException(
            PurchaseOrderNotFoundException ex, WebRequest request) {
        log.error("Purchase order not found exception: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.NOT_FOUND,
                "Purchase Order Not Found",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(PurchaseOrderValidationException.class)
    public ResponseEntity<ErrorResponse> handlePurchaseOrderValidationException(
            PurchaseOrderValidationException ex, WebRequest request) {
        log.error("Purchase order validation exception: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.BAD_REQUEST,
                "Purchase Order Validation Error",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                ex.hasErrors() ? ex.getErrors() : null
        );
    }
}
