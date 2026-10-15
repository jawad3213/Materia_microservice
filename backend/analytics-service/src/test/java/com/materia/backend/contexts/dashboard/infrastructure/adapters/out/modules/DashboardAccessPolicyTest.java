package com.materia.backend.contexts.dashboard.infrastructure.adapters.out.modules;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.fx.FxDocument;
import com.materia.backend.common.domain.fx.FxValuation;
import com.materia.backend.common.domain.fx.ReportingFx;
import com.materia.backend.contexts.dashboard.application.services.DashboardService;
import com.materia.backend.infrastructure.grpc.CallContextExecutor;
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
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Every role can open the dashboard: the figures it asks the other services for are the ones its role may read, so
 * none is refused by their RPC policies (GrpcPolicies), which the stand-ins here enforce as the services do.
 *
 * <p>The dashboard asks for its figures side by side, as in production (CallContextExecutor on other threads): each
 * call must still leave with the signed-in user's token. A call that lost the user would leave with a service token
 * (OutgoingCallerToken's fallback), which the stand-ins refuse. How many calls each role's dashboard makes is pinned
 * too, so a change that adds calls is a visible decision.
 */
class DashboardAccessPolicyTest {

    private static final String SERVICE_TOKEN = "token-of-the-analytics-service";

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        threads.shutdownNow();
    }

    /** The permissions of identity's roles (Role), as their tokens carry them, and the calls their dashboard makes. */
    static Stream<Arguments> roles() {
        return Stream.of(
                Arguments.of("ADMIN", Set.of("user:read", "material:read", "material:stock:read", "supplier:read",
                        "category:read", "requisition:read", "order:read", "receipt:read", "return:read",
                        "invoice:read", "payment:read", "dashboard:read", "setting:read", "log:read"), ADMIN_CALLS),
                Arguments.of("PURCHASER", Set.of("material:read", "supplier:read", "category:read", "requisition:read",
                        "order:read", "receipt:read", "return:read", "invoice:read", "payment:read", "dashboard:read"),
                        PURCHASER_CALLS),
                Arguments.of("RECEIVER", Set.of("material:read", "material:stock:read", "supplier:read",
                        "category:read", "requisition:read", "order:read", "receipt:read", "return:read",
                        "dashboard:read"), RECEIVER_CALLS));
    }

    // With every currency the reporting one and no spend, as the stand-ins have it. Foreign currencies add one call per
    // kind of document (valued at their own date); spend adds the two category lookups (master data).
    private static final int ADMIN_CALLS = 32;
    private static final int PURCHASER_CALLS = 32;
    private static final int RECEIVER_CALLS = 23;

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    @DisplayName("policies: each role's dashboard only asks for figures its role may read, each call as the user")
    void everyRoleCanOpenTheDashboard(String role, Set<String> permissions, int expectedCalls) {
        List<String> authorities = new ArrayList<>(permissions);
        authorities.add("ROLE_" + role);
        Caller caller = new Caller(UUID.randomUUID().toString(), authorities);
        String token = "token-of-" + caller.userId();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(caller.userId(), token));
        AtomicInteger calls = new AtomicInteger();

        DashboardService service = new DashboardService(new ModuleMetricsReadModel(
                ProcurementMetricsGrpc.newBlockingStub(enforcing(new TestProcurementMetrics(), caller, token, calls)),
                FinanceMetricsGrpc.newBlockingStub(enforcing(new TestFinanceMetrics(), caller, token, calls)),
                MasterDataMetricsGrpc.newBlockingStub(enforcing(new TestMasterDataMetrics(), caller, token, calls))),
                new NoRates(), Clock.systemDefaultZone(), new CallContextExecutor(threads));

        assertDoesNotThrow(() -> service.getOverview(permissions));
        assertThat(calls.get()).as("gRPC calls of the %s dashboard", role).isEqualTo(expectedCalls);
    }

    /**
     * A service served behind the servers' authentication and authorization, which admit {@code caller}'s token only.
     * The client sends the signed-in user's token, else the service's own, as OutgoingCallerToken does.
     */
    private static ManagedChannel enforcing(BindableService service, Caller caller, String token, AtomicInteger calls) {
        String name = InProcessServerBuilder.generateName();
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
                .intercept(new CallerTokenClientInterceptor(DashboardAccessPolicyTest::tokenOfTheSignedInUser),
                        counting(calls))
                .build();
    }

    private static Optional<String> tokenOfTheSignedInUser() {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        return Optional.of(user != null ? (String) user.getCredentials() : SERVICE_TOKEN);
    }

    private static ClientInterceptor counting(AtomicInteger calls) {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions options, Channel next) {
                calls.incrementAndGet();
                return next.newCall(method, options);
            }
        };
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
