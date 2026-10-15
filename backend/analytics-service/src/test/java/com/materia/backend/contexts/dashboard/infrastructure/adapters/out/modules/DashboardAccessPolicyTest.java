package com.materia.backend.contexts.dashboard.infrastructure.adapters.out.modules;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.fx.FxDocument;
import com.materia.backend.common.domain.fx.FxValuation;
import com.materia.backend.common.domain.fx.ReportingFx;
import com.materia.backend.contexts.dashboard.application.services.DashboardService;
import com.materia.backend.support.fixtures.TestFinanceMetrics;
import com.materia.backend.support.fixtures.TestMasterDataMetrics;
import com.materia.backend.support.fixtures.TestProcurementMetrics;
import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.AuthorizationServerInterceptor;
import com.materia.grpc.auth.Caller;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Every role can open the dashboard: the figures it asks the other services for are the ones its role may read, so
 * none is refused by their RPC policies (GrpcPolicies), which the stand-ins here enforce as the services do.
 */
class DashboardAccessPolicyTest {

    /** The permissions of identity's roles (Role), as their tokens carry them. */
    static Stream<Arguments> roles() {
        return Stream.of(
                Arguments.of("ADMIN", Set.of("user:read", "material:read", "material:stock:read", "supplier:read",
                        "category:read", "requisition:read", "order:read", "receipt:read", "return:read",
                        "invoice:read", "payment:read", "dashboard:read", "setting:read", "log:read")),
                Arguments.of("PURCHASER", Set.of("material:read", "supplier:read", "category:read", "requisition:read",
                        "order:read", "receipt:read", "return:read", "invoice:read", "payment:read", "dashboard:read")),
                Arguments.of("RECEIVER", Set.of("material:read", "material:stock:read", "supplier:read",
                        "category:read", "requisition:read", "order:read", "receipt:read", "return:read",
                        "dashboard:read")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    @DisplayName("policies: each role's dashboard only asks for figures its role may read")
    void everyRoleCanOpenTheDashboard(String role, Set<String> permissions) {
        List<String> authorities = new ArrayList<>(permissions);
        authorities.add("ROLE_" + role);
        Caller caller = new Caller(UUID.randomUUID().toString(), authorities);

        DashboardService service = new DashboardService(new ModuleMetricsReadModel(
                ProcurementMetricsGrpc.newBlockingStub(enforcing(new TestProcurementMetrics(), caller)),
                FinanceMetricsGrpc.newBlockingStub(enforcing(new TestFinanceMetrics(), caller)),
                MasterDataMetricsGrpc.newBlockingStub(enforcing(new TestMasterDataMetrics(), caller))), new NoRates());

        assertDoesNotThrow(() -> service.getOverview(permissions));
    }

    /** A service served behind the servers' authentication and authorization, called with {@code caller}'s token. */
    private static ManagedChannel enforcing(BindableService service, Caller caller) {
        String name = InProcessServerBuilder.generateName();
        String token = "token-of-" + caller.userId();
        try {
            InProcessServerBuilder.forName(name).directExecutor()
                    .addService(ServerInterceptors.intercept(service, new ExceptionTranslatingServerInterceptor(),
                            new AuthorizationServerInterceptor(), new AuthenticationServerInterceptor(
                                    t -> token.equals(t) ? Optional.of(caller) : Optional.empty())))
                    .build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return InProcessChannelBuilder.forName(name).directExecutor()
                .intercept(new CallerTokenClientInterceptor(() -> Optional.of(token))).build();
    }

    private static final class NoRates implements ReportingFx {
        @Override public CurrencyCode reportingCurrency() { return CurrencyCode.MAD; }
        @Override public FxValuation valuationOn(CurrencyCode from, LocalDate date) { return null; }
        @Override public Map<CurrencyCode, FxValuation> currentRates() { return Map.of(); }
        @Override public void refreshDailyRates() { }
        @Override public void valueDocument(FxDocument kind, UUID id) { }
        @Override public int backfill() { return 0; }
    }
}
