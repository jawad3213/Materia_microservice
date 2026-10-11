package com.materia.backend.architecture;

import com.materia.backend.contexts.auth.domain.ports.in.UserDirectoryUseCase;
import com.materia.backend.contexts.goodsReceipt.domain.ports.in.GoodsReceiptLookupUseCase;
import com.materia.backend.contexts.goodsReceipt.domain.ports.in.GoodsReceiptMetricsUseCase;
import com.materia.backend.contexts.invoice.domain.ports.in.InvoiceMetricsUseCase;
import com.materia.backend.contexts.masterData.domain.ports.in.MaterialInventoryUseCase;
import com.materia.backend.contexts.masterData.domain.ports.in.MaterialMetricsUseCase;
import com.materia.backend.contexts.payment.domain.ports.in.PaymentMetricsUseCase;
import com.materia.backend.contexts.purchaseOrder.domain.ports.in.PurchaseOrderLookupUseCase;
import com.materia.backend.contexts.purchaseOrder.domain.ports.in.PurchaseOrderMetricsUseCase;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.ReplenishmentUseCase;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.RequisitionMetricsUseCase;
import com.materia.backend.contexts.returnToVendor.domain.ports.in.ReturnMetricsUseCase;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each gRPC contract (materia-grpc) mirrors the public use cases another service calls, method for method. A method
 * added to one of these use cases needs its RPC, or an explicit reason to stay off the contract; an RPC needs the
 * method it serves.
 */
class GrpcContractsTest {

    /** A use case, which RPC serves each of its methods, and the methods deliberately left off the contract. */
    private record Mirror(Class<?> useCase, Map<String, String> rpcByMethod, Set<String> notExposed) {
        Mirror(Class<?> useCase, Map<String, String> rpcByMethod) {
            this(useCase, rpcByMethod, Set.of());
        }
    }

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of(UserDirectoryGrpc.getServiceDescriptor(), List.of(
                        new Mirror(UserDirectoryUseCase.class,
                                Map.of("findUser", "FindUser", "findUsersWithRole", "FindUsersWithRole"),
                                // No other service looks accounts up by email.
                                Set.of("findUserByEmail")))),
                Arguments.of(MaterialCatalogGrpc.getServiceDescriptor(), List.of(
                        new Mirror(MaterialInventoryUseCase.class,
                                Map.of("findMaterial", "FindMaterial", "findMaterialByCode", "FindMaterialByCode"),
                                // Stock changes travel as events (StockOnOrderChanged, GoodsReceiptLineReceived).
                                Set.of("addStockOnOrder", "releaseStockOnOrder", "receiveGoods")))),
                Arguments.of(PurchaseOrderLookupGrpc.getServiceDescriptor(), List.of(
                        new Mirror(PurchaseOrderLookupUseCase.class, Map.of("findOrder", "FindOrder")))),
                Arguments.of(GoodsReceiptLookupGrpc.getServiceDescriptor(), List.of(
                        new Mirror(GoodsReceiptLookupUseCase.class,
                                Map.of("findReceipt", "FindReceipt", "findReceiptsForOrder", "FindReceiptsForOrder")))),
                Arguments.of(ReplenishmentGrpc.getServiceDescriptor(), List.of(
                        new Mirror(ReplenishmentUseCase.class,
                                Map.of("hasOpenRequisitionFor", "HasOpenRequisitionFor"),
                                // Raising a requisition is a write: it becomes an event before master data leaves.
                                Set.of("raiseReplenishment", "raiseGroupedReplenishment")))),
                Arguments.of(ProcurementMetricsGrpc.getServiceDescriptor(), List.of(
                        new Mirror(RequisitionMetricsUseCase.class,
                                Map.of("countInStatus", "CountRequisitionsInStatus", "latest", "LatestRequisitions")),
                        new Mirror(PurchaseOrderMetricsUseCase.class, Map.of(
                                "documentsByCurrency", "OrdersByCurrency",
                                "valuedAtOwnDate", "OrdersValuedAtOwnDate",
                                "count", "CountOrders",
                                "countExpectedBefore", "CountOrdersExpectedBefore",
                                "sum", "SumOrders",
                                "byMonth", "OrdersByMonth",
                                "countByStatus", "CountOrdersByStatus",
                                "spendByMaterial", "SpendByMaterial",
                                "spendBySupplier", "SpendBySupplier",
                                "latest", "LatestOrders")),
                        new Mirror(GoodsReceiptMetricsUseCase.class, Map.of(
                                "count", "CountReceipts",
                                "totals", "ReceiptTotals",
                                "totalsBySupplier", "ReceiptTotalsBySupplier",
                                "latest", "LatestReceipts")),
                        new Mirror(ReturnMetricsUseCase.class,
                                Map.of("countInStatus", "CountReturnsInStatus", "latest", "LatestReturns")))),
                Arguments.of(FinanceMetricsGrpc.getServiceDescriptor(), List.of(
                        new Mirror(InvoiceMetricsUseCase.class, Map.of(
                                "documentsByCurrency", "InvoicesByCurrency",
                                "valuedAtOwnDate", "InvoicesValuedAtOwnDate",
                                "outstandingByDueDate", "OutstandingByDueDate",
                                "invoicedByMonth", "InvoicedByMonth",
                                "latest", "LatestInvoices")),
                        new Mirror(PaymentMetricsUseCase.class, Map.of(
                                "documentsByCurrency", "PaymentsByCurrency",
                                "valuedAtOwnDate", "PaymentsValuedAtOwnDate",
                                "countInStatus", "CountPaymentsInStatus",
                                "byMonth", "PaymentsByMonth",
                                "latest", "LatestPayments")))),
                Arguments.of(MasterDataMetricsGrpc.getServiceDescriptor(), List.of(
                        new Mirror(MaterialMetricsUseCase.class, Map.of(
                                "countStockAlerts", "CountStockAlerts",
                                "stockAlerts", "StockAlerts",
                                "categoryNamesById", "CategoryNamesById",
                                "categoryNamesByCode", "CategoryNamesByCode")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    @DisplayName("grpc: every method of the mirrored use cases has its RPC (or a reason not to), and every RPC a method")
    void contractMirrorsItsUseCases(ServiceDescriptor service, List<Mirror> mirrors) {
        Set<String> rpcs = service.getMethods().stream()
                .map(MethodDescriptor::getBareMethodName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> served = new HashSet<>();

        for (Mirror mirror : mirrors) {
            Set<String> methods = Arrays.stream(mirror.useCase().getMethods())
                    .filter(m -> !Modifier.isStatic(m.getModifiers()) && !m.isDefault())
                    .map(Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> unmapped = new TreeSet<>(methods);
            unmapped.removeAll(mirror.rpcByMethod().keySet());
            unmapped.removeAll(mirror.notExposed());

            assertThat(unmapped)
                    .as("%s methods with no RPC in %s: add the RPC to the .proto, or list the method as not exposed",
                            mirror.useCase().getSimpleName(), service.getName())
                    .isEmpty();
            assertThat(methods)
                    .as("%s no longer has these methods", mirror.useCase().getSimpleName())
                    .containsAll(mirror.rpcByMethod().keySet())
                    .containsAll(mirror.notExposed());
            served.addAll(mirror.rpcByMethod().values());
        }

        assertThat(served).as("RPCs %s maps that %s does not define", served, service.getName())
                .isSubsetOf(rpcs);
        assertThat(rpcs).as("RPCs of %s that serve no use case method", service.getName())
                .isSubsetOf(served);
    }
}
