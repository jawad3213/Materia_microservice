package com.materia.grpc.errors;

import io.grpc.StatusRuntimeException;

import java.util.function.Supplier;

/**
 * Makes a call through a stub and throws what the called use case threw ({@link GrpcErrors#toException}), so an
 * adapter that became a gRPC client keeps its callers' error handling.
 */
public final class GrpcCalls {

    private GrpcCalls() {
    }

    public static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException failure) {
            throw GrpcErrors.toException(failure);
        }
    }
}
