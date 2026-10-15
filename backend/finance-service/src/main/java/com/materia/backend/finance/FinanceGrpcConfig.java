package com.materia.backend.finance;

import com.materia.backend.infrastructure.grpc.GrpcChannels;
import com.materia.backend.infrastructure.grpc.GrpcNetworkServer;
import com.materia.backend.infrastructure.grpc.OutgoingCallerToken;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.auth.TokenVerifier;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
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
 * Finance's gRPC: it serves finance.v1 (FinanceMetrics) on {@code app.grpc.server.port}, and reads procurement's
 * orders and receipts for the three-way match (procurement.v1 PurchaseOrderLookup, GoodsReceiptLookup, at {@code app.grpc.procurement.target}). Identity's contracts are wired by the platform (IdentityClientConfig).
 */
@Configuration
public class FinanceGrpcConfig {

    @Bean
    public GrpcNetworkServer financeGrpcNetworkServer(List<BindableService> services, TokenVerifier verifier,
                                                      @Value("${app.grpc.server.port:9093}") int port) {
        return new GrpcNetworkServer("Finance", port, services, verifier, Set.of());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel procurementChannel(OutgoingCallerToken tokens,
                                          @Value("${app.grpc.procurement.target}") String target,
                                          @Value("${app.grpc.procurement.deadline:5s}") Duration deadline) {
        return GrpcChannels.to(target, new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public PurchaseOrderLookupGrpc.PurchaseOrderLookupBlockingStub purchaseOrderLookupStub(
            @Qualifier("procurementChannel") ManagedChannel procurementChannel) {
        return PurchaseOrderLookupGrpc.newBlockingStub(procurementChannel);
    }

    @Bean
    public GoodsReceiptLookupGrpc.GoodsReceiptLookupBlockingStub goodsReceiptLookupStub(
            @Qualifier("procurementChannel") ManagedChannel procurementChannel) {
        return GoodsReceiptLookupGrpc.newBlockingStub(procurementChannel);
    }
}
