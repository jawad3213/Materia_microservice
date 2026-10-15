package com.materia.backend.identity;

import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves identity.v1 (UserDirectory, ServiceTokens) to the other services on {@code app.grpc.server.port}, with the
 * interceptors every service installs. Every call needs a valid token, except asking for a service token.
 */
@Component
public class IdentityGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IdentityGrpcServer.class);

    private final Server server;
    private volatile boolean running;

    public IdentityGrpcServer(List<BindableService> services, TokenVerifier verifier,
                              @Value("${app.grpc.server.port:9091}") int port) {
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(verifier,
                Set.of(ServiceTokensGrpc.getIssueServiceTokenMethod().getFullMethodName()));
        NettyServerBuilder builder = NettyServerBuilder.forPort(port);
        // The last interceptor runs first: trace id, then authentication, then error translation around the handler.
        services.forEach(service -> builder.addService(ServerInterceptors.intercept(service,
                new ExceptionTranslatingServerInterceptor(), authentication, new TraceIdServerInterceptor())));
        this.server = builder.build();
    }

    /** The port the server listens on: the configured one, or the one picked for port 0 (tests). */
    public int port() {
        return server.getPort();
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
            log.info("Identity gRPC server listening on port {}", server.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the identity gRPC server", e);
        }
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Early: the other services may call it as soon as they start. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }
}
