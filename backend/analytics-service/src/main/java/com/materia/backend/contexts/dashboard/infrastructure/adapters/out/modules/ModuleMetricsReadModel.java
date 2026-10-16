package com.materia.backend.contexts.dashboard.infrastructure.adapters.out.modules;

import com.materia.backend.common.application.metrics.CountedAmount;
import com.materia.backend.common.application.metrics.RecentDocument;
import com.materia.backend.common.application.metrics.ReportingRates;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel;
import com.materia.grpc.convert.MetricsProtos;
import com.materia.grpc.convert.ProtoValues;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.common.v1.LatestRequest;
import com.materia.proto.common.v1.ReportingCurrencyRequest;
import com.materia.proto.common.v1.StatusRequest;
import com.materia.proto.common.v1.StatusesByMonthRequest;
import com.materia.proto.common.v1.StatusesRequest;
import com.materia.proto.common.v1.StatusesWithRatesRequest;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.finance.v1.InvoicedByMonthRequest;
import com.materia.proto.finance.v1.OutstandingByDueDateRequest;
import com.materia.proto.finance.v1.PaymentsByMonthRequest;
import com.materia.proto.masterdata.v1.CategoryNamesByCodeRequest;
import com.materia.proto.masterdata.v1.CategoryNamesByIdRequest;
import com.materia.proto.masterdata.v1.CountStockAlertsRequest;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.StockAlertsRequest;
import com.materia.proto.procurement.v1.CountOrdersExpectedBeforeRequest;
import com.materia.proto.procurement.v1.CountReceiptsRequest;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.SpendByMaterialResponse;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The dashboard's figures, composed from each service's reporting contract (API composition): procurement.v1,
 * finance.v1 and masterdata.v1 metrics. No other service's table or class is read here.
 */
@Component
public class ModuleMetricsReadModel implements DashboardReadModel {

    private static final String UNCATEGORIZED = "Uncategorized";
    /** A service that does not answer in time or at all: its figures are unavailable, the dashboard still shows. */
    private static final Set<Status.Code> NOT_ANSWERED =
            Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED);

    private final ProcurementMetricsGrpc.ProcurementMetricsBlockingStub procurement;
    private final FinanceMetricsGrpc.FinanceMetricsBlockingStub finance;
    private final MasterDataMetricsGrpc.MasterDataMetricsBlockingStub masterData;

    public ModuleMetricsReadModel(ProcurementMetricsGrpc.ProcurementMetricsBlockingStub procurement,
                                  FinanceMetricsGrpc.FinanceMetricsBlockingStub finance,
                                  MasterDataMetricsGrpc.MasterDataMetricsBlockingStub masterData) {
        this.procurement = procurement;
        this.finance = finance;
        this.masterData = masterData;
    }

    /** A call to another service; one that went unanswered is a {@link FigureUnavailableException}. */
    private static <T> T answered(Supplier<T> call) {
        try {
            return GrpcCalls.call(call);
        } catch (StatusRuntimeException failure) {
            if (NOT_ANSWERED.contains(failure.getStatus().getCode())) {
                throw new FigureUnavailableException("No answer: " + failure.getStatus().getCode(), failure);
            }
            throw failure;
        }
    }

    private static com.materia.proto.common.v1.ReportingRates rates(Rates rates) {
        return MetricsProtos.rates(new ReportingRates(rates.reportingCurrency(), rates.current()));
    }

    private static List<MonthAmount> months(com.materia.proto.common.v1.MonthAmountsResponse months) {
        return MetricsProtos.months(months).stream().map(m -> new MonthAmount(m.month(), m.amount())).toList();
    }

    private static StatusesRequest statuses(Set<String> statuses) {
        return StatusesRequest.newBuilder().addAllStatuses(statuses).build();
    }

    private static StatusesWithRatesRequest statuses(Set<String> statuses, Rates rates) {
        return StatusesWithRatesRequest.newBuilder().addAllStatuses(statuses).setRates(rates(rates)).build();
    }

    private static StatusRequest status(String status) {
        return StatusRequest.newBuilder().setStatus(status == null ? "" : status).build();
    }

    // ---- Currency ----

    @Override
    public Map<String, Long> documentsByCurrency(boolean withOrders, boolean withInvoices, boolean withPayments) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Empty all = Empty.getDefaultInstance();
        if (withOrders) merge(counts, answered(() -> procurement.ordersByCurrency(all)));
        if (withInvoices) merge(counts, answered(() -> finance.invoicesByCurrency(all)));
        if (withPayments) merge(counts, answered(() -> finance.paymentsByCurrency(all)));
        return counts;
    }

    @Override
    public Map<String, Long> documentsValuedAtOwnDate(String reportingCurrency, boolean withOrders, boolean withInvoices,
                                                      boolean withPayments) {
        Map<String, Long> counts = new LinkedHashMap<>();
        ReportingCurrencyRequest request = ReportingCurrencyRequest.newBuilder()
                .setReportingCurrency(reportingCurrency == null ? "" : reportingCurrency).build();
        if (withOrders) merge(counts, answered(() -> procurement.ordersValuedAtOwnDate(request)));
        if (withInvoices) merge(counts, answered(() -> finance.invoicesValuedAtOwnDate(request)));
        if (withPayments) merge(counts, answered(() -> finance.paymentsValuedAtOwnDate(request)));
        return counts;
    }

    private static void merge(Map<String, Long> counts, com.materia.proto.common.v1.CountsResponse more) {
        MetricsProtos.counts(more).forEach((c, n) -> counts.merge(c, n, Long::sum));
    }

    // ---- Requisitions ----

    @Override
    public long countRequisitions(String status) {
        return answered(() -> procurement.countRequisitionsInStatus(status(status))).getCount();
    }

    // ---- Purchase orders ----

    @Override
    public long countOrders(Set<String> statuses) {
        return answered(() -> procurement.countOrders(statuses(statuses))).getCount();
    }

    @Override
    public long countOrdersExpectedBefore(Set<String> statuses, LocalDate date) {
        CountOrdersExpectedBeforeRequest.Builder request = CountOrdersExpectedBeforeRequest.newBuilder()
                .addAllStatuses(statuses);
        if (date != null) request.setDate(date.toString());
        return answered(() -> procurement.countOrdersExpectedBefore(request.build())).getCount();
    }

    @Override
    public BigDecimal sumOrders(Set<String> statuses, Rates rates) {
        return ProtoValues.decimal(answered(() -> procurement.sumOrders(statuses(statuses, rates))).getAmount());
    }

    @Override
    public List<MonthAmount> ordersByMonth(Set<String> statuses, Rates rates, LocalDate from, LocalDate to) {
        return months(answered(() -> procurement.ordersByMonth(StatusesByMonthRequest.newBuilder()
                .addAllStatuses(statuses).setRates(rates(rates)).setPeriod(MetricsProtos.period(from, to)).build())));
    }

    @Override
    public Map<String, Long> ordersByStatus() {
        return MetricsProtos.counts(answered(() -> procurement.countOrdersByStatus(Empty.getDefaultInstance())));
    }

    /**
     * Order-line value per material category: procurement gives the spend per material, master data each material's
     * category (by id, or by code for a line without one). A line whose material is unknown or has no category counts
     * as "Uncategorized".
     */
    @Override
    public List<Group> spendByCategory(Set<String> statuses, Rates rates) {
        List<SpendByMaterialResponse.MaterialSpend> spend =
                answered(() -> procurement.spendByMaterial(statuses(statuses, rates))).getSpendList();
        if (spend.isEmpty()) return List.of();
        Map<String, String> byId = answered(() -> masterData.categoryNamesById(CategoryNamesByIdRequest.newBuilder()
                .addAllMaterialIds(spend.stream().filter(SpendByMaterialResponse.MaterialSpend::hasMaterialId)
                        .map(SpendByMaterialResponse.MaterialSpend::getMaterialId).distinct().toList())
                .build())).getCategoryNamesMap();
        Map<String, String> byCode = answered(() -> masterData.categoryNamesByCode(CategoryNamesByCodeRequest.newBuilder()
                .addAllMaterialCodes(spend.stream().filter(s -> !s.hasMaterialId() && s.hasMaterialCode())
                        .map(SpendByMaterialResponse.MaterialSpend::getMaterialCode).filter(Objects::nonNull)
                        .distinct().toList())
                .build())).getCategoryNamesMap();
        Map<String, long[]> lines = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (SpendByMaterialResponse.MaterialSpend s : spend) {
            String category = s.hasMaterialId() ? byId.get(s.getMaterialId())
                    : s.hasMaterialCode() ? byCode.get(s.getMaterialCode()) : null;
            if (category == null || category.isBlank()) category = UNCATEGORIZED;
            lines.computeIfAbsent(category, k -> new long[1])[0] += s.getLines();
            amounts.merge(category, ProtoValues.decimalOrZero(s.getAmount()), BigDecimal::add);
        }
        return amounts.entrySet().stream()
                .map(e -> new Group(e.getKey(), e.getKey(), lines.get(e.getKey())[0], e.getValue()))
                .toList();
    }

    @Override
    public List<Group> spendBySupplier(Set<String> statuses, Rates rates) {
        return MetricsProtos.groups(answered(() -> procurement.spendBySupplier(statuses(statuses, rates)))).stream()
                .map(ModuleMetricsReadModel::group).toList();
    }

    private static Group group(CountedAmount c) {
        return new Group(c.key(), c.label(), c.count(), c.amount());
    }

    // ---- Goods receipts ----

    @Override
    public long countReceipts(Set<String> statuses, LocalDate from, LocalDate to, boolean withRejectionOnly) {
        CountReceiptsRequest.Builder request = CountReceiptsRequest.newBuilder()
                .addAllStatuses(statuses).setWithRejectionOnly(withRejectionOnly);
        if (from != null) request.setFrom(from.toString());
        if (to != null) request.setTo(to.toString());
        return answered(() -> procurement.countReceipts(request.build())).getCount();
    }

    @Override
    public ReceiptTotals receiptTotals(Set<String> statuses) {
        return totals(answered(() -> procurement.receiptTotals(statuses(statuses))).getTotals());
    }

    @Override
    public Map<UUID, ReceiptTotals> receiptTotalsBySupplier(Set<String> statuses) {
        Map<UUID, ReceiptTotals> result = new LinkedHashMap<>();
        answered(() -> procurement.receiptTotalsBySupplier(statuses(statuses))).getBySupplierMap()
                .forEach((supplier, t) -> result.put(ProtoValues.uuid(supplier), totals(t)));
        return result;
    }

    private static ReceiptTotals totals(com.materia.proto.procurement.v1.ReceiptTotals t) {
        return new ReceiptTotals(t.getReceipts(), t.getUnitsReceived(), t.getUnitsAccepted(), t.getUnitsRejected(),
                t.getDatedReceipts(), t.getOnTimeReceipts());
    }

    // ---- Invoices ----

    @Override
    public List<DueGroup> outstandingByDueDate(String type, Set<String> statuses, Rates rates) {
        return answered(() -> finance.outstandingByDueDate(OutstandingByDueDateRequest.newBuilder()
                        .setType(type == null ? "" : type).addAllStatuses(statuses).setRates(rates(rates)).build()))
                .getGroupsList().stream()
                .map(o -> new DueGroup(o.hasDueDate() ? ProtoValues.date(o.getDueDate()) : null,
                        o.getCurrency().isEmpty() ? null : o.getCurrency(), o.getInvoices(),
                        ProtoValues.decimal(o.getOutstanding())))
                .toList();
    }

    @Override
    public List<MonthAmount> invoicedByMonth(Set<String> excluded, Rates rates, LocalDate from, LocalDate to) {
        return months(answered(() -> finance.invoicedByMonth(InvoicedByMonthRequest.newBuilder()
                .addAllExcludedStatuses(excluded).setRates(rates(rates)).setPeriod(MetricsProtos.period(from, to))
                .build())));
    }

    // ---- Payments ----

    @Override
    public long countPayments(String status) {
        return answered(() -> finance.countPaymentsInStatus(status(status))).getCount();
    }

    @Override
    public List<MonthAmount> paymentsByMonth(String status, Rates rates, LocalDate from, LocalDate to) {
        return months(answered(() -> finance.paymentsByMonth(PaymentsByMonthRequest.newBuilder()
                .setStatus(status == null ? "" : status).setRates(rates(rates))
                .setPeriod(MetricsProtos.period(from, to)).build())));
    }

    // ---- Stock ----

    @Override
    public long countStockAlerts(boolean outOfStockOnly) {
        return answered(() -> masterData.countStockAlerts(
                CountStockAlertsRequest.newBuilder().setOutOfStockOnly(outOfStockOnly).build())).getCount();
    }

    @Override
    public List<StockRow> stockAlerts(int limit) {
        return answered(() -> masterData.stockAlerts(StockAlertsRequest.newBuilder().setLimit(limit).build()))
                .getAlertsList().stream()
                .map(a -> new StockRow(ProtoValues.uuid(a.getId()), a.getCode().isEmpty() ? null : a.getCode(),
                        a.getName().isEmpty() ? null : a.getName(), a.getCurrentStock(),
                        a.hasReorderPoint() ? a.getReorderPoint() : null,
                        a.hasSafetyStock() ? a.getSafetyStock() : null,
                        a.getStockOnOrder(), a.hasUnit() ? a.getUnit() : null))
                .toList();
    }

    // ---- Returns ----

    @Override
    public long countReturns(String status) {
        return answered(() -> procurement.countReturnsInStatus(status(status))).getCount();
    }

    // ---- Activity ----

    @Override
    public List<ActivityRow> latest(ActivitySource source, int limit) {
        LatestRequest request = LatestRequest.newBuilder().setLimit(limit).build();
        List<RecentDocument> documents = MetricsProtos.latest(answered(() -> switch (source) {
            case REQUISITION -> procurement.latestRequisitions(request);
            case PURCHASE_ORDER -> procurement.latestOrders(request);
            case GOODS_RECEIPT -> procurement.latestReceipts(request);
            case INVOICE -> finance.latestInvoices(request);
            case PAYMENT -> finance.latestPayments(request);
            case RETURN -> procurement.latestReturns(request);
        }));
        return documents.stream()
                .map(d -> new ActivityRow(d.id(), d.code(), d.title(), d.status(), d.createdAt()))
                .toList();
    }
}
