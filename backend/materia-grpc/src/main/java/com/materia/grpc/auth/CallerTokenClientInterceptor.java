package com.materia.grpc.auth;

import com.materia.grpc.GrpcHeaders;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sends the token of the user the call is made for, so the called service applies that user's rights rather than
 * the calling service's. The source is the service's to choose: the token of the REST request being served, the one
 * of the gRPC call being served ({@link GrpcCaller#currentToken()}), or a service token for a background job.
 */
public class CallerTokenClientInterceptor implements ClientInterceptor {

    private final Supplier<Optional<String>> token;

    public CallerTokenClientInterceptor(Supplier<Optional<String>> token) {
        this.token = token;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                token.get().ifPresent(value -> headers.put(GrpcHeaders.AUTHORIZATION, "Bearer " + value));
                super.start(responseListener, headers);
            }
        };
    }
}
