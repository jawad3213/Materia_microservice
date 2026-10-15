package com.materia.backend.contexts.masterData.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.masterData.domain.exceptions.DuplicateMaterialCodeException;
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
 * How the masterData context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MasterDataExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MasterDataExceptionHandler.class);

    // ----- FINDING-005: DuplicateMaterialCodeException → 409 -----
    @ExceptionHandler(DuplicateMaterialCodeException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMaterialCodeException(
            DuplicateMaterialCodeException ex, WebRequest request) {
        log.warn("Duplicate material code: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "Duplicate Material Code",
                "DUPLICATE_MATERIAL_CODE",
                ex.getMessage(),
                request,
                null
        );
    }
}
