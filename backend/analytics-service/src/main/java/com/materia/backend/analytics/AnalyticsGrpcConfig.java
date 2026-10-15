package com.materia.backend.analytics;

import com.materia.backend.infrastructure.grpc.GrpcChannels;
import com.materia.backend.infrastructure.grpc.OutgoingCallerToken;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Analytics' gRPC: it serves nothing, and reads the reporting figures of procurement (procurement.v1, today in the
 * monolith at {@code app.grpc.monolith.target}), master data ({@code app.grpc.masterdata.target}) and finance
 * ({@code app.grpc.finance.target}). Identity's contracts are wired by the platform (IdentityClientConfig).
 */
@Configuration
public class AnalyticsGrpcConfig {

    private static ManagedChannel channel(OutgoingCallerToken tokens, String target, Duration deadline) {
        return GrpcChannels.to(target, new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel monolithChannel(OutgoingCallerToken tokens, @Value("${app.grpc.monolith.target}") String target,
                                          @Value("${app.grpc.monolith.deadline:5s}") Duration deadline) {
        return channel(tokens, target, deadline);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel masterDataChannel(OutgoingCallerToken tokens,
                                            @Value("${app.grpc.masterdata.target}") String target,
                                            @Value("${app.grpc.masterdata.deadline:5s}") Duration deadline) {
        return channel(tokens, target, deadline);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel financeChannel(OutgoingCallerToken tokens, @Value("${app.grpc.finance.target}") String target,
                                         @Value("${app.grpc.finance.deadline:5s}") Duration deadline) {
        return channel(tokens, target, deadline);
    }

    @Bean
    public ProcurementMetricsGrpc.ProcurementMetricsBlockingStub procurementMetricsStub(
            @Qualifier("monolithChannel") ManagedChannel channel) {
        return ProcurementMetricsGrpc.newBlockingStub(channel);
    }

    @Bean
    public MasterDataMetricsGrpc.MasterDataMetricsBlockingStub masterDataMetricsStub(
            @Qualifier("masterDataChannel") ManagedChannel channel) {
        return MasterDataMetricsGrpc.newBlockingStub(channel);
    }

    @Bean
    public FinanceMetricsGrpc.FinanceMetricsBlockingStub financeMetricsStub(
            @Qualifier("financeChannel") ManagedChannel channel) {
        return FinanceMetricsGrpc.newBlockingStub(channel);
    }
}
