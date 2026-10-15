package com.materia.grpc.auth;

import com.materia.grpc.GrpcHeaders;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Optional;
import java.util.Set;

/**
 * Refuses a call without a valid bearer token ({@code UNAUTHENTICATED}) before any handler runs, and exposes the
 * verified caller through {@link GrpcCaller}. Being inside the network grants nothing: a call is accepted for a
 * user, never for where it comes from. The only exceptions are the methods listed as public: the one that issues
 * service tokens, which a caller cannot already hold one for.
 */
public class AuthenticationServerInterceptor implements ServerInterceptor {

    private static final String BEARER = "Bearer ";

    private final TokenVerifier verifier;
    private final Set<String> publicMethods;

    public AuthenticationServerInterceptor(TokenVerifier verifier) {
        this(verifier, Set.of());
    }

    /** @param publicMethods full method names ({@code package.Service/Method}) callable without a token */
    public AuthenticationServerInterceptor(TokenVerifier verifier, Set<String> publicMethods) {
        this.verifier = verifier;
        this.publicMethods = Set.copyOf(publicMethods);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        if (publicMethods.contains(call.getMethodDescriptor().getFullMethodName())) {
            return next.startCall(call, headers);
        }
        String token = bearerToken(headers.get(GrpcHeaders.AUTHORIZATION));
        Optional<Caller> caller = token == null ? Optional.empty() : verify(token);
        if (caller.isEmpty()) {
            call.close(Status.UNAUTHENTICATED.withDescription("A valid bearer token is required"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        Context context = Context.current()
                .withValue(GrpcCaller.CALLER, caller.get())
                .withValue(GrpcCaller.TOKEN, token);
        return Contexts.interceptCall(context, call, headers, next);
    }

    private Optional<Caller> verify(String token) {
        try {
            return verifier.verify(token);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) return null;
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
