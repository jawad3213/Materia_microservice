package com.materia.grpc;

import com.materia.backend.common.application.exceptions.BusinessException;
import com.materia.backend.common.application.exceptions.NotFoundException;
import com.materia.backend.common.application.exceptions.ValidationException;
import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.auth.Caller;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.auth.GrpcCaller;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.errors.GrpcErrors;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import com.materia.proto.procurement.v1.FindOrderRequest;
import com.materia.proto.procurement.v1.FindOrderResponse;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderView;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The interceptors every service installs, around a real generated contract (PurchaseOrderLookup) over an in-process
 * channel: what a caller sends, what the server lets through, and how failures travel back.
 */
class GrpcPlumbingTest {

    private static final String GOOD_TOKEN = "good-token";

    /** What the handler saw on its last call. */
    private final AtomicInteger handled = new AtomicInteger();
    private final AtomicReference<Caller> seenCaller = new AtomicReference<>();
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenTraceId = new AtomicReference<>();
    private final AtomicReference<Long> seenDeadlineMs = new AtomicReference<>();

    /** The token the client sends; null sends none. */
    private final AtomicReference<String> clientToken = new AtomicReference<>(GOOD_TOKEN);

    private Server server;
    private ManagedChannel channel;
    private PurchaseOrderLookupGrpc.PurchaseOrderLookupBlockingStub orders;

    private final PurchaseOrderLookupGrpc.PurchaseOrderLookupImplBase service =
            new PurchaseOrderLookupGrpc.PurchaseOrderLookupImplBase() {
                @Override
                public void findOrder(FindOrderRequest request, StreamObserver<FindOrderResponse> response) {
                    handled.incrementAndGet();
                    seenCaller.set(GrpcCaller.current().orElse(null));
                    seenToken.set(GrpcCaller.currentToken().orElse(null));
                    seenTraceId.set(MDC.get(TraceIdServerInterceptor.MDC_KEY));
                    seenDeadlineMs.set(Context.current().getDeadline() == null ? null
                            : Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                    switch (request.getPurchaseOrderId()) {
                        case "missing" -> throw new NotFoundException("Purchase order missing not found", "PO_NOT_FOUND");
                        case "invalid" -> throw new ValidationException("The purchase order id is not a UUID");
                        case "closed" -> throw new BusinessException("The order is closed", "PO_CLOSED");
                        case "forbidden" -> throw new AccessDeniedException("invoice:read required");
                        case "boom" -> throw new IllegalStateException("select * from users where password = 'x'");
                        default -> {
                            response.onNext(FindOrderResponse.newBuilder()
                                    .setOrder(PurchaseOrderView.newBuilder()
                                            .setId(request.getPurchaseOrderId())
                                            .setOrderCode("PO-2026-00001")
                                            .setStatus("SENT"))
                                    .build());
                            response.onCompleted();
                        }
                    }
                }
            };

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(token -> switch (token) {
            case GOOD_TOKEN -> Optional.of(new Caller("user-1", List.of("ROLE_ADMIN", "invoice:read")));
            case "explodes" -> throw new IllegalStateException("verifier failure");
            default -> Optional.empty();
        });
        // The last interceptor runs first: trace id, then authentication, then error translation around the handler.
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(service, new ExceptionTranslatingServerInterceptor(),
                        authentication, new TraceIdServerInterceptor()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name)
                .intercept(new CallerTokenClientInterceptor(() -> Optional.ofNullable(clientToken.get())),
                        new TraceIdClientInterceptor(),
                        new DefaultDeadlineClientInterceptor(Duration.ofSeconds(2)))
                .build();
        orders = PurchaseOrderLookupGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() throws Exception {
        MDC.clear();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private FindOrderResponse find(String id) {
        return orders.findOrder(FindOrderRequest.newBuilder().setPurchaseOrderId(id).build());
    }

    private StatusRuntimeException failure(String id) {
        return catchThrowableOfType(StatusRuntimeException.class, () -> find(id));
    }

    @Test
    @DisplayName("grpc: a call with a valid token reaches the handler, which sees the caller and their token")
    void validToken_reachesTheHandlerWithTheCaller() {
        FindOrderResponse response = find("8b0e5c1e-0000-4000-8000-000000000001");

        assertThat(response.getOrder().getOrderCode()).isEqualTo("PO-2026-00001");
        assertThat(seenCaller.get().userId()).isEqualTo("user-1");
        assertThat(seenCaller.get().has("invoice:read")).isTrue();
        assertThat(seenToken.get()).isEqualTo(GOOD_TOKEN);
    }

    @Test
    @DisplayName("grpc: a call without a token is refused (UNAUTHENTICATED) before any handler runs")
    void noToken_isRefusedBeforeTheHandler() {
        clientToken.set(null);

        assertThat(failure("any").getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(handled).hasValue(0);
    }

    @Test
    @DisplayName("grpc: an invalid token, or a verifier that fails, is refused (UNAUTHENTICATED)")
    void invalidToken_isRefused() {
        clientToken.set("forged");
        assertThat(failure("any").getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

        clientToken.set("explodes");
        assertThat(failure("any").getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(handled).hasValue(0);
    }

    @Test
    @DisplayName("grpc: the caller's trace id reaches the logging context of the service it calls")
    void traceId_travelsWithTheCall() {
        MDC.put(TraceIdServerInterceptor.MDC_KEY, "trace-123");

        find("8b0e5c1e-0000-4000-8000-000000000001");

        assertThat(seenTraceId.get()).isEqualTo("trace-123");
    }

    @Test
    @DisplayName("grpc: a call without a trace id gets a new one on the server")
    void missingTraceId_getsANewOne() {
        find("8b0e5c1e-0000-4000-8000-000000000001");

        assertThat(seenTraceId.get()).isNotBlank();
    }

    @Test
    @DisplayName("grpc: a call gets the default deadline, and keeps a deadline the caller set")
    void deadline_defaultsButKeepsTheCallersOwn() {
        find("8b0e5c1e-0000-4000-8000-000000000001");
        assertThat(seenDeadlineMs.get()).isNotNull().isLessThanOrEqualTo(2_000L);

        orders.withDeadlineAfter(30, TimeUnit.SECONDS)
                .findOrder(FindOrderRequest.newBuilder().setPurchaseOrderId("8b0e5c1e").build());
        assertThat(seenDeadlineMs.get()).isGreaterThan(2_000L);
    }

    @Test
    @DisplayName("grpc: NotFoundException travels as NOT_FOUND and comes back as the same exception and code")
    void notFound_roundTrips() {
        StatusRuntimeException failure = failure("missing");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        RuntimeException back = GrpcErrors.toException(failure);
        assertThat(back).isInstanceOf(NotFoundException.class).hasMessage("Purchase order missing not found");
        assertThat(((NotFoundException) back).getErrorCode()).isEqualTo("PO_NOT_FOUND");
    }

    @Test
    @DisplayName("grpc: ValidationException travels as INVALID_ARGUMENT and comes back as a ValidationException")
    void validation_roundTrips() {
        StatusRuntimeException failure = failure("invalid");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(GrpcErrors.toException(failure)).isInstanceOf(ValidationException.class)
                .hasMessage("The purchase order id is not a UUID");
    }

    @Test
    @DisplayName("grpc: a business rule failure travels as FAILED_PRECONDITION with its error code")
    void businessRule_roundTripsWithItsCode() {
        StatusRuntimeException failure = failure("closed");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        RuntimeException back = GrpcErrors.toException(failure);
        assertThat(back).isExactlyInstanceOf(BusinessException.class).hasMessage("The order is closed");
        assertThat(((BusinessException) back).getErrorCode()).isEqualTo("PO_CLOSED");
    }

    @Test
    @DisplayName("grpc: a missing permission travels as PERMISSION_DENIED and comes back as AccessDeniedException")
    void accessDenied_roundTrips() {
        StatusRuntimeException failure = failure("forbidden");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(GrpcErrors.toException(failure)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("grpc: an unexpected failure is INTERNAL with a generic message, never its details")
    void unexpectedFailure_revealsNothing() {
        StatusRuntimeException failure = failure("boom");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(failure.getStatus().getDescription()).isEqualTo("Internal error");
        assertThat(failure.getMessage()).doesNotContain("password").doesNotContain("select");
    }

    @Test
    @DisplayName("grpc: a transport failure stays a StatusRuntimeException, for retries to recognise")
    void transportFailure_isNotTurnedIntoABusinessException() {
        StatusRuntimeException unavailable = Status.UNAVAILABLE.withDescription("connection refused").asRuntimeException();

        assertThat(GrpcErrors.toException(unavailable)).isSameAs(unavailable);
    }
}
