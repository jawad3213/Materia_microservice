package com.materia.backend.gateway.exception;

import com.materia.backend.common.application.exceptions.BusinessException;
import com.materia.backend.common.application.exceptions.NotFoundException;
import com.materia.backend.common.application.exceptions.ValidationException;
import com.materia.backend.common.attachment.domain.exceptions.AttachmentAccessDeniedException;
import com.materia.backend.common.infrastructure.web.ErrorResponse;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The shared exceptions of every service (business rules, validation, security, rate limiting, database conflicts,
 * anything unexpected), answered in one error format. Each context's own exceptions are answered by its own advice
 * ({@code <Context>ExceptionHandler}), which is checked first; this one is checked last.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex, WebRequest request) {
        log.error("Business exception: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Business Error",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(
            NotFoundException ex, WebRequest request) {
        log.error("Not found exception: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Not Found",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            ValidationException ex, WebRequest request) {
        log.error("Validation exception: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                ex.hasErrors() ? ex.getErrors() : null
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException ex, WebRequest request) {
        log.error("Constraint violation exception: {}", ex.getMessage());
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                errors.put(String.valueOf(violation.getPropertyPath()), violation.getMessage())
        );

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                "VALIDATION_ERROR",
                "Validation failed",
                request,
                errors
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        log.error("Illegal argument exception: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                "VALIDATION_ERROR",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(
            IllegalStateException ex, WebRequest request) {
        log.error("Illegal state exception: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Business Error",
                "ILLEGAL_STATE",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> handleOptimisticLockException(
            RuntimeException ex, WebRequest request) {
        log.error("Optimistic lock exception: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                "CONCURRENT_MODIFICATION",
                "The resource was modified by another request. Please reload and try again.",
                request,
                null
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceededException(
            RateLimitExceededException ex, WebRequest request) {
        log.warn("Rate limit violation: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "RATE_LIMIT_EXCEEDED",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Access Denied",
                "FORBIDDEN",
                "You do not have permission to access this resource",
                request,
                null
        );
    }

    /** The user may change the document but not this file (only its uploader or an administrator may remove it). */
    @ExceptionHandler(AttachmentAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAttachmentAccessDenied(AttachmentAccessDeniedException ex, WebRequest request) {
        log.warn("Attachment access denied: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Access Denied", ex.getErrorCode(), ex.getFormattedMessage(),
                request, null);
    }

    /**
     * A database constraint refused the change: most often a delete of a record other documents still reference
     * (a supplier with orders, a material on order lines), or a duplicate unique value.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        String detail = ex.getMostSpecificCause().getMessage();
        log.warn("Data integrity violation: {}", detail);
        boolean referenced = detail != null && detail.contains("foreign key");
        String message = referenced
                ? "This record is referenced by other documents and cannot be changed or deleted"
                : "The change conflicts with existing data (a value that must be unique is already used)";
        return buildErrorResponse(HttpStatus.CONFLICT, "Data Conflict",
                referenced ? "REFERENCED_RECORD" : "DATA_INTEGRITY_VIOLATION", message, request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(
            Exception ex, WebRequest request) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "INTERNAL_SERVER_ERROR",
                ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred",
                request,
                null
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ErrorResponse errorResponse = buildErrorResponseBody(
                status.value(),
                "Validation Error",
                "VALIDATION_ERROR",
                "Validation failed",
                request,
                errors
        );
        return new ResponseEntity<>(errorResponse, status);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String error, String errorCode,
                                                             String message, WebRequest request,
                                                             Map<String, String> validationErrors) {
        return ApiErrors.response(status, error, errorCode, message, request, validationErrors);
    }

    private ErrorResponse buildErrorResponseBody(int status, String error, String errorCode, String message,
                                                 WebRequest request, Map<String, String> validationErrors) {
        return ApiErrors.body(status, error, errorCode, message, request, validationErrors);
    }
}
