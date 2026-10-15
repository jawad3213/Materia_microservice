package com.materia.grpc.auth;

import io.grpc.Context;

import java.util.Optional;

/** The caller of the gRPC call being served, set by {@link AuthenticationServerInterceptor}. */
public final class GrpcCaller {

    static final Context.Key<Caller> CALLER = Context.key("materia-caller");
    static final Context.Key<String> TOKEN = Context.key("materia-caller-token");

    private GrpcCaller() {
    }

    public static Optional<Caller> current() {
        return Optional.ofNullable(CALLER.get());
    }

    /** The caller's token, to forward when this call makes another one ({@link CallerTokenClientInterceptor}). */
    public static Optional<String> currentToken() {
        return Optional.ofNullable(TOKEN.get());
    }
}
