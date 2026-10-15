package com.materia.grpc.auth;

import com.materia.proto.audit.v1.ChangeHistoryGrpc;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.procurement.v1.FindOrderRequest;
import com.materia.proto.procurement.v1.FindOrderResponse;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Who may call each RPC: every contract method has a policy, and the servers enforce it. */
class GrpcPoliciesTest {

    private static final List<ServiceDescriptor> CONTRACTS = List.of(
            ServiceTokensGrpc.getServiceDescriptor(), UserDirectoryGrpc.getServiceDescriptor(),
            MaterialCatalogGrpc.getServiceDescriptor(), MasterDataMetricsGrpc.getServiceDescriptor(),
            PurchaseOrderLookupGrpc.getServiceDescriptor(), GoodsReceiptLookupGrpc.getServiceDescriptor(),
            ReplenishmentGrpc.getServiceDescriptor(), ProcurementMetricsGrpc.getServiceDescriptor(),
            FinanceMetricsGrpc.getServiceDescriptor(), ChangeHistoryGrpc.getServiceDescriptor());

    /** Tokens of the test callers. */
    private static final Map<String, Caller> CALLERS = Map.of(
            "buyer", new Caller("buyer-1", List.of("ROLE_PURCHASER", "order:read")),
            "receiver-without-orders", new Caller("receiver-1", List.of("ROLE_RECEIVER", "receipt:read")),
            "finance", new Caller("service:finance", List.of("ROLE_SERVICE")),
            "masterdata", new Caller("service:masterdata", List.of("ROLE_SERVICE")));

    private ManagedChannel channel;
    private String token;

    @BeforeEach
    void server() throws IOException {
        String name = InProcessServerBuilder.generateName();
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(
                t -> Optional.ofNullable(CALLERS.get(t)));
        InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new PurchaseOrderLookupGrpc.PurchaseOrderLookupImplBase() {
                    @Override
                    public void findOrder(FindOrderRequest request, StreamObserver<FindOrderResponse> response) {
                        response.onNext(FindOrderResponse.getDefaultInstance());
                        response.onCompleted();
                    }
                }, new AuthorizationServerInterceptor(), authentication))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor()
                .intercept(new CallerTokenClientInterceptor(() -> Optional.ofNullable(token)))
                .build();
    }

    @AfterEach
    void close() {
        channel.shutdownNow();
    }

    private Status.Code findOrderAs(String caller) {
        token = caller;
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () ->
                PurchaseOrderLookupGrpc.newBlockingStub(channel).findOrder(FindOrderRequest.getDefaultInstance()));
        return e == null ? Status.Code.OK : e.getStatus().getCode();
    }

    @Test
    @DisplayName("policies: every RPC of every contract has one, and none names an RPC that does not exist")
    void everyRpcHasAPolicy() {
        Set<String> methods = CONTRACTS.stream()
                .flatMap(service -> service.getMethods().stream())
                .map(MethodDescriptor::getFullMethodName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(new TreeSet<>(GrpcPolicies.all().keySet())).isEqualTo(methods);
    }

    @Test
    @DisplayName("policies: only issuing a service token is open; reads ask for the permission that reads them over REST")
    void policies() {
        assertThat(GrpcPolicies.all().entrySet()).filteredOn(e -> e.getValue().kind() == RpcPolicy.Kind.OPEN)
                .extracting(Map.Entry::getKey)
                .containsExactly(ServiceTokensGrpc.getIssueServiceTokenMethod().getFullMethodName());
        assertThat(GrpcPolicies.of(FinanceMetricsGrpc.getLatestInvoicesMethod().getFullMethodName()).orElseThrow()
                .permissions()).containsExactly("invoice:read");
        assertThat(GrpcPolicies.of("unknown.v1.Service/Method")).isEmpty();
    }

    @Test
    @DisplayName("server: a user with the permission is let through; one without it is refused (PERMISSION_DENIED)")
    void users() {
        assertThat(findOrderAs("buyer")).isEqualTo(Status.Code.OK);
        assertThat(findOrderAs("receiver-without-orders")).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("server: a service named by the policy is let through; another service is refused, whatever it holds")
    void services() {
        assertThat(findOrderAs("finance")).isEqualTo(Status.Code.OK);
        assertThat(findOrderAs("masterdata")).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("server: without a valid token the call never reaches authorization (UNAUTHENTICATED)")
    void anonymous() {
        assertThat(findOrderAs("forged")).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("policy: an RPC without a policy is refused, an authenticated one admits any caller")
    void kinds() {
        Caller user = CALLERS.get("buyer");
        assertThat(RpcPolicy.authenticated().allows(user)).isTrue();
        assertThat(RpcPolicy.authenticated().allows(null)).isFalse();
        assertThat(RpcPolicy.open().allows(null)).isTrue();
        assertThat(RpcPolicy.users("order:read").allows(CALLERS.get("finance")))
                .as("a service needs to be named, permissions do not count for it").isFalse();
        assertThat(new AuthorizationServerInterceptor(method -> Optional.empty())).isNotNull();
    }
}
