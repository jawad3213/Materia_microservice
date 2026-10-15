package com.materia.grpc.errors;

import com.materia.backend.common.application.exceptions.BusinessException;
import com.materia.backend.common.application.exceptions.NotFoundException;
import com.materia.backend.common.application.exceptions.ValidationException;
import com.materia.grpc.GrpcHeaders;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.springframework.security.access.AccessDeniedException;

/**
 * One mapping between the shared exceptions and gRPC status codes, used in both directions: the server turns an
 * exception into a status ({@link #toStatusException}), the client turns the status back into the same exception
 * ({@link #toException}), so an adapter that becomes a gRPC client throws what it threw before.
 *
 * <p>Business messages are meant for users and travel as they are. Anything unexpected becomes {@code INTERNAL}
 * with a generic message: a stack trace or SQL never leaves the service.
 */
public final class GrpcErrors {

    static final String INTERNAL_MESSAGE = "Internal error";

    private GrpcErrors() {
    }

    public static StatusRuntimeException toStatusException(Throwable error) {
        if (error instanceof StatusRuntimeException status) return status;
        if (error instanceof StatusException status) {
            return status.getStatus().asRuntimeException(status.getTrailers());
        }
        Metadata trailers = new Metadata();
        if (error instanceof BusinessException business && business.getErrorCode() != null) {
            trailers.put(GrpcHeaders.ERROR_CODE, business.getErrorCode());
        }
        return statusOf(error).asRuntimeException(trailers);
    }

    private static Status statusOf(Throwable error) {
        // Subclasses first: NotFoundException and ValidationException are BusinessExceptions.
        if (error instanceof NotFoundException) return Status.NOT_FOUND.withDescription(error.getMessage());
        if (error instanceof ValidationException) return Status.INVALID_ARGUMENT.withDescription(error.getMessage());
        if (error instanceof BusinessException) return Status.FAILED_PRECONDITION.withDescription(error.getMessage());
        if (error instanceof AccessDeniedException) return Status.PERMISSION_DENIED.withDescription(error.getMessage());
        if (error instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT.withDescription(error.getMessage());
        return Status.INTERNAL.withDescription(INTERNAL_MESSAGE);
    }

    /**
     * The exception a caller throws for a failed call. Business failures come back as the shared exceptions;
     * transport failures ({@code UNAVAILABLE}, {@code DEADLINE_EXCEEDED}, ...) stay {@link StatusRuntimeException}s,
     * for retries and circuit breakers to tell apart.
     */
    public static RuntimeException toException(StatusRuntimeException failure) {
        Status status = failure.getStatus();
        String message = status.getDescription() != null ? status.getDescription() : status.getCode().name();
        Metadata trailers = Status.trailersFromThrowable(failure);
        String errorCode = trailers != null ? trailers.get(GrpcHeaders.ERROR_CODE) : null;
        return switch (status.getCode()) {
            case NOT_FOUND -> errorCode != null ? new NotFoundException(message, errorCode) : new NotFoundException(message);
            case INVALID_ARGUMENT -> new ValidationException(message);
            case FAILED_PRECONDITION ->
                    errorCode != null ? new BusinessException(message, errorCode) : new BusinessException(message);
            case PERMISSION_DENIED -> new AccessDeniedException(message);
            default -> failure;
        };
    }
}
