package com.materia.backend.contexts.invoice.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceAlreadyExistsException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceAlreadyPaidException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceAlreadyVerifiedException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceCancellationException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceLineValidationException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceNotFoundException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceNotModifiableException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceNotPayableException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceNotVerifiableException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoicePurchaseOrderMismatchException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceRuleViolationException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceSupplierMismatchException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceValidationException;
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
 * How the invoice context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvoiceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceExceptionHandler.class);

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleInvoiceNotFoundException(InvoiceNotFoundException ex, WebRequest request) {
        log.error("Invoice not found: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.NOT_FOUND, "Invoice Not Found", "INVOICE_NOT_FOUND", ex.getMessage(), request, null);
    }

    @ExceptionHandler({InvoiceValidationException.class, InvoiceLineValidationException.class})
    public ResponseEntity<ErrorResponse> handleInvoiceValidationException(RuntimeException ex, WebRequest request) {
        log.error("Invoice validation error: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.BAD_REQUEST, "Invoice Validation Error", "INVOICE_VALIDATION_ERROR",
                ex.getMessage(), request, null);
    }

    /** Invoice operations refused by a business rule answer 409, like purchase orders and goods receipts. */
    @ExceptionHandler({InvoiceRuleViolationException.class, InvoiceNotModifiableException.class,
            InvoiceNotVerifiableException.class, InvoiceNotPayableException.class, InvoiceCancellationException.class,
            InvoiceAlreadyPaidException.class, InvoiceAlreadyVerifiedException.class, InvoiceAlreadyExistsException.class,
            InvoiceSupplierMismatchException.class, InvoicePurchaseOrderMismatchException.class})
    public ResponseEntity<ErrorResponse> handleInvoiceRuleViolation(RuntimeException ex, WebRequest request) {
        log.error("Invoice rule violation: {}", ex.getMessage());
        return ApiErrors.response(HttpStatus.CONFLICT, "Invoice Error", "INVOICE_RULE_VIOLATION", ex.getMessage(), request, null);
    }
}
