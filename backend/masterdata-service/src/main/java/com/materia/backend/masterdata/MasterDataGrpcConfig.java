package com.materia.backend.masterdata;

import com.materia.backend.infrastructure.grpc.GrpcChannels;
import com.materia.backend.infrastructure.grpc.GrpcNetworkServer;
import com.materia.backend.infrastructure.grpc.OutgoingCallerToken;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
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
 * Master data's gRPC: it serves masterdata.v1 (MaterialCatalog, MasterDataMetrics) on {@code app.grpc.server.port},
 * and raises replenishment requisitions in procurement (procurement.v1.Replenishment, at
 * {@code app.grpc.procurement.target}). Identity's contracts are wired by the platform (IdentityClientConfig).
 */
@Configuration
public class MasterDataGrpcConfig {

    @Bean
    public GrpcNetworkServer masterDataGrpcNetworkServer(List<BindableService> services, TokenVerifier verifier,
                                                         @Value("${app.grpc.server.port:9092}") int port) {
        return new GrpcNetworkServer("Master data", port, services, verifier, Set.of());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel procurementChannel(OutgoingCallerToken tokens,
                                          @Value("${app.grpc.procurement.target}") String target,
                                          @Value("${app.grpc.procurement.deadline:10s}") Duration deadline) {
        return GrpcChannels.to(target, new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public ReplenishmentGrpc.ReplenishmentBlockingStub replenishmentStub(
            @Qualifier("procurementChannel") ManagedChannel procurementChannel) {
        return ReplenishmentGrpc.newBlockingStub(procurementChannel);
    }
}
