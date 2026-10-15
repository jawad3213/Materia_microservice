package com.materia.backend.support.fixtures;

import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.Caller;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * A channel to real gRPC services, in process and on the caller's thread, with the interceptors the application
 * installs: tests reach another context through its contract, as production does (GrpcConfig).
 */
public final class InProcessGrpc {

    /** The only token the test servers accept; the test channels always send it. */
    public static final String TEST_TOKEN = "test-caller-token";

    private InProcessGrpc() {
    }

    public static ManagedChannel serving(BindableService... services) {
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder server = InProcessServerBuilder.forName(name).directExecutor();
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(token ->
                TEST_TOKEN.equals(token) ? Optional.of(new Caller("test-user", List.of("ROLE_ADMIN"))) : Optional.empty());
        for (BindableService service : services) {
            server.addService(ServerInterceptors.intercept(service, new ExceptionTranslatingServerInterceptor(),
                    authentication, new TraceIdServerInterceptor()));
        }
        try {
            server.build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return InProcessChannelBuilder.forName(name)
                .directExecutor()
                .intercept(new CallerTokenClientInterceptor(() -> Optional.of(TEST_TOKEN)))
                .build();
    }
}
