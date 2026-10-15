package com.materia.backend.infrastructure.grpc;

import com.materia.backend.common.domain.ports.out.UserDisplayNames;
import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.BindableService;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Every call between the services goes through its gRPC contract.
 *
 * <ul>
 *   <li>The contexts still in this application serve their contracts ({@code adapters/in/grpc}) on an in-process
 *   server and call each other through stubs on an in-process channel. Server and channel run calls on the caller's
 *   thread, so a call joins the caller's transaction as the direct call it replaced.</li>
 *   <li>Identity is a service of its own: its contracts (UserDirectory, ServiceTokens) are reached over the network
 *   at {@code app.grpc.identity.target}, with a short deadline.</li>
 * </ul>
 *
 * <p>Every channel installs the interceptors every service uses (caller token, trace id, deadline); the one to
 * identity's ServiceTokens has no caller token, since asking for a service token must not need one.
 */
@Configuration
public class GrpcConfig {

    private static final String IN_PROCESS_PREFIX = "in-process:";

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

    // ---- Identity (another service) ----

    /**
     * {@code host:port} of identity's gRPC server (e.g. {@code identity:9091}), or {@code in-process:<name>} for an
     * in-process server of that name (tests).
     */
    private static ManagedChannel channelTo(String target, ClientInterceptor... interceptors) {
        if (target.startsWith(IN_PROCESS_PREFIX)) {
            return InProcessChannelBuilder.forName(target.substring(IN_PROCESS_PREFIX.length()))
                    .directExecutor().intercept(interceptors).build();
        }
        return NettyChannelBuilder.forTarget(target).usePlaintext().intercept(interceptors).build();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel identityServiceTokenChannel(@Value("${app.grpc.identity.target}") String target,
                                                      @Value("${app.grpc.identity.deadline:5s}") Duration deadline) {
        return channelTo(target, new TraceIdClientInterceptor(), new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public OutgoingCallerToken outgoingCallerToken(
            @Qualifier("identityServiceTokenChannel") ManagedChannel identityServiceTokenChannel,
            @Value("${app.service-client.id}") String clientId,
            @Value("${app.service-client.secret}") String clientSecret) {
        return new OutgoingCallerToken(ServiceTokensGrpc.newBlockingStub(identityServiceTokenChannel), clientId,
                clientSecret);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel identityChannel(OutgoingCallerToken tokens,
                                          @Value("${app.grpc.identity.target}") String target,
                                          @Value("${app.grpc.identity.deadline:5s}") Duration deadline) {
        return channelTo(target, new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public UserDirectoryGrpc.UserDirectoryBlockingStub userDirectoryStub(
            @Qualifier("identityChannel") ManagedChannel identityChannel) {
        return UserDirectoryGrpc.newBlockingStub(identityChannel);
    }

    /** Names in the change history and on attachments, from identity's directory. */
    @Bean
    public UserDisplayNames userDisplayNames(UserDirectoryGrpc.UserDirectoryBlockingStub userDirectoryStub) {
        return new GrpcUserDisplayNames(userDirectoryStub);
    }

    // ---- The contexts still in this application ----

    @Bean(destroyMethod = "shutdownNow")
    @Primary
    public ManagedChannel grpcChannel(OutgoingCallerToken tokens,
                                      @Value("${app.grpc.client.deadline:30s}") Duration deadline) {
        return InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .intercept(new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                        new DefaultDeadlineClientInterceptor(deadline))
                .build();
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
