package com.materia.backend.infrastructure.grpc;

import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Every call between the future services goes through its gRPC contract, inside this one application: each context
 * serves its contracts ({@code adapters/in/grpc}) on an in-process server, and the other contexts call them through
 * stubs on an in-process channel, with the interceptors every service installs (token, trace id, deadline, errors).
 *
 * <p>Server and channel run calls on the caller's thread, so a call joins the caller's transaction exactly as the
 * direct call it replaced. Separate transactions come with the extraction itself, when each service gets its own
 * address: a stub then points at another channel and nothing else changes.
 */
@Configuration
public class GrpcConfig {

    /** Unique per application context, so several test contexts can live in one JVM. */
    private final String serverName = "materia-" + UUID.randomUUID();

    @Bean
    public InProcessGrpcServer grpcServer(List<BindableService> services, TokenVerifier verifier) {
        InProcessServerBuilder server = InProcessServerBuilder.forName(serverName).directExecutor();
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(verifier);
        // The last interceptor runs first: trace id, then authentication, then error translation around the handler.
        services.forEach(service -> server.addService(ServerInterceptors.intercept(service,
                new ExceptionTranslatingServerInterceptor(), authentication, new TraceIdServerInterceptor())));
        return new InProcessGrpcServer(server.build());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel grpcChannel(OutgoingCallerToken tokens,
                                      @Value("${app.grpc.client.deadline:30s}") Duration deadline) {
        return InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .intercept(new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                        new DefaultDeadlineClientInterceptor(deadline))
                .build();
    }

    @Bean
    public UserDirectoryGrpc.UserDirectoryBlockingStub userDirectoryStub(ManagedChannel grpcChannel) {
        return UserDirectoryGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public MaterialCatalogGrpc.MaterialCatalogBlockingStub materialCatalogStub(ManagedChannel grpcChannel) {
        return MaterialCatalogGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public PurchaseOrderLookupGrpc.PurchaseOrderLookupBlockingStub purchaseOrderLookupStub(ManagedChannel grpcChannel) {
        return PurchaseOrderLookupGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public GoodsReceiptLookupGrpc.GoodsReceiptLookupBlockingStub goodsReceiptLookupStub(ManagedChannel grpcChannel) {
        return GoodsReceiptLookupGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public ReplenishmentGrpc.ReplenishmentBlockingStub replenishmentStub(ManagedChannel grpcChannel) {
        return ReplenishmentGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public ProcurementMetricsGrpc.ProcurementMetricsBlockingStub procurementMetricsStub(ManagedChannel grpcChannel) {
        return ProcurementMetricsGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public FinanceMetricsGrpc.FinanceMetricsBlockingStub financeMetricsStub(ManagedChannel grpcChannel) {
        return FinanceMetricsGrpc.newBlockingStub(grpcChannel);
    }

    @Bean
    public MasterDataMetricsGrpc.MasterDataMetricsBlockingStub masterDataMetricsStub(ManagedChannel grpcChannel) {
        return MasterDataMetricsGrpc.newBlockingStub(grpcChannel);
    }

    /** Started before the application takes requests or runs its start-up jobs, stopped after. */
    public static final class InProcessGrpcServer implements SmartLifecycle {

        private final Server server;
        private volatile boolean running;

        InProcessGrpcServer(Server server) {
            this.server = server;
        }

        @Override
        public void start() {
            try {
                server.start();
                running = true;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start the in-process gRPC server", e);
            }
        }

        @Override
        public void stop() {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.shutdownNow();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        /** Early: before the web server and the start-up jobs, which may call other contexts. */
        @Override
        public int getPhase() {
            return Integer.MIN_VALUE + 1000;
        }
    }
}
