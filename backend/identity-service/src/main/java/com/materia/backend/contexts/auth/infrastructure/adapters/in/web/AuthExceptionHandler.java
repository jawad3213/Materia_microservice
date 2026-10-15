package com.materia.backend.contexts.auth.infrastructure.adapters.in.web;

import com.materia.backend.common.infrastructure.web.ErrorResponse;
import com.materia.backend.contexts.auth.domain.exceptions.AccountLockedException;
import com.materia.backend.contexts.auth.domain.exceptions.AuthenticationFailedException;
import com.materia.backend.contexts.auth.domain.exceptions.EmailAlreadyExistsException;
import com.materia.backend.contexts.auth.domain.exceptions.InvalidCredentialsException;
import com.materia.backend.contexts.auth.domain.exceptions.InvalidTokenException;
import com.materia.backend.contexts.auth.domain.exceptions.TokenExpiredException;
import com.materia.backend.contexts.auth.domain.exceptions.UserAlreadyExistsException;
import com.materia.backend.contexts.auth.domain.exceptions.UserNotFoundException;
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
 * How the auth context's exceptions answer over HTTP. Checked before the shared handler
 * ({@code GlobalExceptionHandler}), which answers everything else; it leaves with the context when its service is
 * extracted.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailedException(
            AuthenticationFailedException ex, WebRequest request) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.UNAUTHORIZED,
                "Authentication Failed",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExistsException(
            UserAlreadyExistsException ex, WebRequest request) {
        log.warn("User already exists: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "User Already Exists",
                ex.getErrorCode(),
                ex.getFormattedMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTokenException(
            InvalidTokenException ex, WebRequest request) {
        log.warn("Invalid token: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.BAD_REQUEST,
                "Invalid Token",
                "AUTH_INVALID_TOKEN",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpiredException(
            TokenExpiredException ex, WebRequest request) {
        log.warn("Token expired: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.BAD_REQUEST,
                "Token Expired",
                "AUTH_TOKEN_EXPIRED",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(
            UserNotFoundException ex, WebRequest request) {
        log.warn("User not found: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.NOT_FOUND,
                "User Not Found",
                "AUTH_USER_NOT_FOUND",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExistsException(
            EmailAlreadyExistsException ex, WebRequest request) {
        log.warn("Email already exists: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.CONFLICT,
                "Email Already Exists",
                "AUTH_EMAIL_ALREADY_EXISTS",
                ex.getMessage(),
                request,
                null
        );
    }

    // ----- FINDING-006: AccountLockedException → 401 -----
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLockedException(
            AccountLockedException ex, WebRequest request) {
        log.warn("Account locked: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.UNAUTHORIZED,
                "Account Locked",
                "AUTH_ACCOUNT_LOCKED",
                ex.getMessage(),
                request,
                null
        );
    }

    // ----- FINDING-007: InvalidCredentialsException → 401 -----
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(
            InvalidCredentialsException ex, WebRequest request) {
        log.warn("Invalid credentials: {}", ex.getMessage());
        return ApiErrors.response(
                HttpStatus.UNAUTHORIZED,
                "Invalid Credentials",
                "AUTH_INVALID_CREDENTIALS",
                ex.getMessage(),
                request,
                null
        );
    }
}
