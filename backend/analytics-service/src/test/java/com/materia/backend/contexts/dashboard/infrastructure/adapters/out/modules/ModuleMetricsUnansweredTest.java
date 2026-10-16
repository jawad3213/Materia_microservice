package com.materia.backend.contexts.dashboard.infrastructure.adapters.out.modules;

import com.google.protobuf.Empty;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.FigureUnavailableException;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.proto.common.v1.CountResponse;
import com.materia.proto.common.v1.CountsResponse;
import com.materia.proto.common.v1.StatusRequest;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A service that does not answer (too slow for the call's deadline, or not reachable) gives an unavailable figure,
 * which the dashboard shows without; any other failure goes through as before.
 */
class ModuleMetricsUnansweredTest {

    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
    }

    @Test
    @DisplayName("unanswered: a service slower than the call's deadline gives an unavailable figure")
    void tooSlow_unavailable() {
        ModuleMetricsReadModel readModel = readModel(channel(serving(new SlowAndRefusing())));

        assertThatThrownBy(readModel::ordersByStatus)
                .isInstanceOf(FigureUnavailableException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED")
                .hasCauseInstanceOf(StatusRuntimeException.class);
    }

    @Test
    @DisplayName("unanswered: a service that cannot be reached gives an unavailable figure")
    void unreachable_unavailable() {
        ModuleMetricsReadModel readModel = readModel(channel(InProcessServerBuilder.generateName()));

        assertThatThrownBy(() -> readModel.countReturns("PENDING"))
                .isInstanceOf(FigureUnavailableException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    @DisplayName("unanswered: a refusal is not an unanswered call, and still fails")
    void refused_stillFails() {
        ModuleMetricsReadModel readModel = readModel(channel(serving(new SlowAndRefusing())));

        assertThatThrownBy(() -> readModel.countReturns("PENDING"))
                .isNotInstanceOf(FigureUnavailableException.class)
                .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    /** Answers the order statuses after a second, and refuses the returns. */
    private static final class SlowAndRefusing extends ProcurementMetricsGrpc.ProcurementMetricsImplBase {
        @Override
        public void countOrdersByStatus(Empty request, StreamObserver<CountsResponse> response) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            response.onNext(CountsResponse.getDefaultInstance());
            response.onCompleted();
        }

        @Override
        public void countReturnsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
            response.onError(Status.INTERNAL.withDescription("broken").asRuntimeException());
        }
    }

    /** A server on its own threads, so a slow answer does not hold the caller. */
    private String serving(ProcurementMetricsGrpc.ProcurementMetricsImplBase service) {
        String name = InProcessServerBuilder.generateName();
        try {
            servers.add(InProcessServerBuilder.forName(name).addService(service).build().start());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return name;
    }

    /** Every call gets 200 ms, as the services' channels give each call a short default deadline. */
    private ManagedChannel channel(String name) {
        ManagedChannel channel = InProcessChannelBuilder.forName(name)
                .intercept(new DefaultDeadlineClientInterceptor(Duration.ofMillis(200)))
                .build();
        channels.add(channel);
        return channel;
    }

    private static ModuleMetricsReadModel readModel(ManagedChannel procurement) {
        return new ModuleMetricsReadModel(ProcurementMetricsGrpc.newBlockingStub(procurement),
                FinanceMetricsGrpc.newBlockingStub(procurement), MasterDataMetricsGrpc.newBlockingStub(procurement));
    }
}
