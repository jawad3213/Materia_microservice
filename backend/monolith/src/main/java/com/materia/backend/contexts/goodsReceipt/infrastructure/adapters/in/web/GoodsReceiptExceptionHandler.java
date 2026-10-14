package com.materia.backend.contexts.goodsReceipt.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.goodsReceipt.domain.exceptions.GoodsReceiptBusinessException;
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
 * How the goodsReceipt context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GoodsReceiptExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptExceptionHandler.class);

    @ExceptionHandler(GoodsReceiptBusinessException.class)
    public ResponseEntity<ErrorResponse> handleGoodsReceiptBusinessException(
            GoodsReceiptBusinessException ex, WebRequest request) {
        log.error("Goods receipt exception: {}", ex.getMessage(), ex);
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "Goods Receipt Error",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }
}
