package com.materia.backend.infrastructure.grpc;

import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Procurement's gRPC (this application holds procurement alone): every call between the services goes through its
 * gRPC contract, over the network.
 *
 * <ul>
 *   <li>Procurement's contracts (procurement.v1) are served to the other services on {@code app.grpc.server.port}:
 *   master data raises replenishment requisitions here, finance reads orders and receipts, analytics procurement's
 *   figures. Each call must be allowed by its RPC's policy (GrpcPolicies).</li>
 *   <li>Master data's catalogue (masterdata.v1.MaterialCatalog) is reached at {@code app.grpc.masterdata.target},
 *   with a short deadline. Identity's contracts are wired by the platform ({@link IdentityClientConfig}).</li>
 * </ul>
 *
 * <p>Every channel installs the interceptors every service uses (caller token, trace id, deadline).
 */
@Configuration
public class GrpcConfig {

    @Bean
    public GrpcNetworkServer grpcNetworkServer(List<BindableService> services, TokenVerifier verifier,
                                               @Value("${app.grpc.server.port:9090}") int port) {
        return new GrpcNetworkServer("Procurement", port, services, verifier, Set.of());
    }

    // ---- Master data (another service) ----

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel masterDataChannel(OutgoingCallerToken tokens,
                                            @Value("${app.grpc.masterdata.target}") String target,
                                            @Value("${app.grpc.masterdata.deadline:5s}") Duration deadline) {
        return GrpcChannels.to(target, new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public MaterialCatalogGrpc.MaterialCatalogBlockingStub materialCatalogStub(
            @Qualifier("masterDataChannel") ManagedChannel masterDataChannel) {
        return MaterialCatalogGrpc.newBlockingStub(masterDataChannel);
    }
}
