package com.materia.grpc.auth;

import com.materia.proto.audit.v1.ChangeHistoryGrpc;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who may call each RPC of the contracts, part of the contracts like the .proto files. Every server enforces it
 * ({@link AuthorizationServerInterceptor}); an RPC with no policy here is refused, so a new RPC needs one (checked by
 * the contracts' tests).
 *
 * <p>Users reach these RPCs with their own token, which a service forwards while it serves their request; services
 * reach them with their service token for work no user started. The service names are their client ids
 * ({@code app.service-client.id}): procurement, masterdata, finance, analytics.
 */
public final class GrpcPolicies {

    private static final Map<String, RpcPolicy> POLICIES = new HashMap<>();

    static {
        // ---- identity.v1 ----
        // Asking for a service token is how a service gets one.
        put(ServiceTokensGrpc.getIssueServiceTokenMethod(), RpcPolicy.open());
        // Names and roles of accounts: every service shows who did what (change history, attachments, documents) and
        // procurement picks requesters and receivers, for any user and for its own jobs.
        put(UserDirectoryGrpc.getFindUserMethod(), RpcPolicy.authenticated());
        put(UserDirectoryGrpc.getFindUsersWithRoleMethod(), RpcPolicy.authenticated());

        // ---- masterdata.v1 ----
        // The catalogue, as GET /api/v1/masterdata/materials shows it. Procurement reads it for its users (requisition
        // lines, receipt checks) and for its own jobs and seeding, or for master data's (a replenishment it raises).
        RpcPolicy catalogue = RpcPolicy.users("material:read").orServices("procurement", "masterdata");
        put(MaterialCatalogGrpc.getFindMaterialMethod(), catalogue);
        put(MaterialCatalogGrpc.getFindMaterialByCodeMethod(), catalogue);
        put(MaterialCatalogGrpc.getFindOrderableMaterialsMethod(), catalogue);
        // The dashboard's stock alerts, and the categories its spend per category is grouped by.
        put(MasterDataMetricsGrpc.getCountStockAlertsMethod(), RpcPolicy.users("material:read"));
        put(MasterDataMetricsGrpc.getStockAlertsMethod(), RpcPolicy.users("material:read"));
        put(MasterDataMetricsGrpc.getCategoryNamesByIdMethod(), RpcPolicy.users("material:read", "order:read"));
        put(MasterDataMetricsGrpc.getCategoryNamesByCodeMethod(), RpcPolicy.users("material:read", "order:read"));

        // ---- procurement.v1 ----
        // Orders and receipts, as their GET endpoints show them: finance matches invoices against them, for its users
        // and its seeding.
        put(PurchaseOrderLookupGrpc.getFindOrderMethod(), RpcPolicy.users("order:read").orServices("finance"));
        RpcPolicy receipts = RpcPolicy.users("receipt:read").orServices("finance");
        put(GoodsReceiptLookupGrpc.getFindReceiptMethod(), receipts);
        put(GoodsReceiptLookupGrpc.getFindReceiptsForOrderMethod(), receipts);
        put(GoodsReceiptLookupGrpc.getFindValidatedReceiptsMethod(), receipts);
        // Master data's reorders: whether one is under way (its reorder recommendation, material:stock:read), and
        // raising one (its reorder endpoint: material:stock:write or requisition:write; its nightly check).
        put(ReplenishmentGrpc.getHasOpenRequisitionForMethod(),
                RpcPolicy.users("requisition:read", "material:stock:read").orServices("masterdata"));
        RpcPolicy raise = RpcPolicy.users("requisition:write", "material:stock:write").orServices("masterdata");
        put(ReplenishmentGrpc.getRaiseReplenishmentMethod(), raise);
        put(ReplenishmentGrpc.getRaiseGroupedReplenishmentMethod(), raise);
        // The dashboard's figures, each with the permission that reads its documents.
        forMethods(ProcurementMetricsGrpc.getServiceDescriptor(), List.of(
                "CountRequisitionsInStatus", "LatestRequisitions"), RpcPolicy.users("requisition:read"));
        forMethods(ProcurementMetricsGrpc.getServiceDescriptor(), List.of(
                "OrdersByCurrency", "OrdersValuedAtOwnDate", "CountOrders", "CountOrdersExpectedBefore", "SumOrders",
                "OrdersByMonth", "CountOrdersByStatus", "SpendByMaterial", "SpendBySupplier", "LatestOrders"),
                RpcPolicy.users("order:read"));
        forMethods(ProcurementMetricsGrpc.getServiceDescriptor(), List.of(
                "CountReceipts", "ReceiptTotals", "ReceiptTotalsBySupplier", "LatestReceipts"),
                RpcPolicy.users("receipt:read"));
        forMethods(ProcurementMetricsGrpc.getServiceDescriptor(), List.of(
                "CountReturnsInStatus", "LatestReturns"), RpcPolicy.users("return:read"));

        // ---- audit.v1 (served by every service with a change history) ----
        // The platform-wide feed of the audit log page, which asks for log:read over REST.
        put(ChangeHistoryGrpc.getRecentChangesMethod(), RpcPolicy.users("log:read"));

        // ---- finance.v1 ----
        forMethods(FinanceMetricsGrpc.getServiceDescriptor(), List.of(
                "InvoicesByCurrency", "InvoicesValuedAtOwnDate", "OutstandingByDueDate", "InvoicedByMonth",
                "LatestInvoices"), RpcPolicy.users("invoice:read"));
        forMethods(FinanceMetricsGrpc.getServiceDescriptor(), List.of(
                "PaymentsByCurrency", "PaymentsValuedAtOwnDate", "CountPaymentsInStatus", "PaymentsByMonth",
                "LatestPayments"), RpcPolicy.users("payment:read"));
    }

    private GrpcPolicies() {
    }

    /** The policy of a method ({@code package.Service/Method}); empty when it has none (it is then refused). */
    public static Optional<RpcPolicy> of(String fullMethodName) {
        return Optional.ofNullable(POLICIES.get(fullMethodName));
    }

    /** Every policy, by full method name. */
    public static Map<String, RpcPolicy> all() {
        return Map.copyOf(POLICIES);
    }

    private static void put(MethodDescriptor<?, ?> method, RpcPolicy policy) {
        if (POLICIES.putIfAbsent(method.getFullMethodName(), policy) != null) {
            throw new IllegalStateException("Two policies for " + method.getFullMethodName());
        }
    }

    private static void forMethods(ServiceDescriptor service, List<String> bareNames, RpcPolicy policy) {
        for (String name : bareNames) {
            MethodDescriptor<?, ?> method = service.getMethods().stream()
                    .filter(m -> name.equals(m.getBareMethodName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(service.getName() + " has no method " + name));
            put(method, policy);
        }
    }
}
