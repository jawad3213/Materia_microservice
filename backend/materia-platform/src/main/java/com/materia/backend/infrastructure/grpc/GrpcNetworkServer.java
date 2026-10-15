package com.materia.backend.infrastructure.grpc;

import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves a service's gRPC contracts to the other services on a network port, with the interceptors every service
 * installs (trace id, then authentication, then error translation around the handler). Every call needs a valid
 * token, except the methods listed as open (identity's service-token issuing). Created by each service's gRPC
 * configuration.
 */
public class GrpcNetworkServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcNetworkServer.class);

    private final String name;
    private final Server server;
    private volatile boolean running;

    public GrpcNetworkServer(String name, int port, List<BindableService> services, TokenVerifier verifier,
                             Set<String> openMethods) {
        this.name = name;
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(verifier, openMethods);
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
            log.info("{} gRPC server listening on port {}", name, server.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the " + name + " gRPC server", e);
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
