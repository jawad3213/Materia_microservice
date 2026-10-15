package com.materia.grpc;

import io.grpc.Metadata;

/** The metadata (gRPC headers and trailers) the Materia services exchange. */
public final class GrpcHeaders {

    /** {@code Bearer <JWT>}: the user on whose behalf the call is made. */
    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** The trace id the gateway gave the user's request (X-Gateway-Trace-Id), so every service logs the same one. */
    public static final Metadata.Key<String> TRACE_ID =
            Metadata.Key.of("x-gateway-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Trailer: the {@code errorCode} of a business exception, so the caller rebuilds the same exception. */
    public static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("materia-error-code", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcHeaders() {
    }
}
