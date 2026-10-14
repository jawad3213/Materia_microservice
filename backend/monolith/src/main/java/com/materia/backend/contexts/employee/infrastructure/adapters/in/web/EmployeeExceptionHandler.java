package com.materia.backend.contexts.employee.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.employee.domain.exceptions.EmailAlreadyInUseException;
import com.materia.backend.contexts.employee.domain.exceptions.EmployeeCodeAlreadyExistsException;
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
 * How the employee context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmployeeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EmployeeExceptionHandler.class);

    @ExceptionHandler(EmployeeCodeAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmployeeCodeAlreadyExistsException(
            EmployeeCodeAlreadyExistsException ex, WebRequest request) {
        log.warn("Employee code already exists: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "Employee Code Conflict",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyInUseException(
            EmailAlreadyInUseException ex, WebRequest request) {
        log.warn("Employee email already in use: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "Employee Email Conflict",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }
}
