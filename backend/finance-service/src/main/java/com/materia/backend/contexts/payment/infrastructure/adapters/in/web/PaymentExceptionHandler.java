package com.materia.backend.contexts.payment.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentAlreadyCompletedException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentAlreadyExistsException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentAmountMismatchException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentNoInvoicesException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentNotCancellableException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentNotCompletableException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentNotFoundException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentNotModifiableException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentNotPreparableException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentRuleViolationException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentSupplierMismatchException;
import com.materia.backend.contexts.payment.domain.exceptions.PaymentValidationException;
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
 * How the payment context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFoundException(PaymentNotFoundException ex, WebRequest request) {
        log.error("Payment not found: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.NOT_FOUND, "Payment Not Found", "PAYMENT_NOT_FOUND", ex.getMessage(), request, null);
    }

    @ExceptionHandler({PaymentValidationException.class, PaymentNoInvoicesException.class})
    public ResponseEntity<ErrorResponse> handlePaymentValidationException(RuntimeException ex, WebRequest request) {
        log.error("Payment validation error: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.BAD_REQUEST, "Payment Validation Error", "PAYMENT_VALIDATION_ERROR",
                ex.getMessage(), request, null);
    }

    /** Payment operations refused by a business rule answer 409, like invoices and purchase orders. */
    @ExceptionHandler({PaymentRuleViolationException.class, PaymentAmountMismatchException.class,
            PaymentSupplierMismatchException.class, PaymentNotModifiableException.class, PaymentNotPreparableException.class,
            PaymentNotCompletableException.class, PaymentNotCancellableException.class, PaymentAlreadyCompletedException.class,
            PaymentAlreadyExistsException.class})
    public ResponseEntity<ErrorResponse> handlePaymentRuleViolation(RuntimeException ex, WebRequest request) {
        log.error("Payment rule violation: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.CONFLICT, "Payment Error", "PAYMENT_RULE_VIOLATION", ex.getMessage(), request, null);
    }
}
