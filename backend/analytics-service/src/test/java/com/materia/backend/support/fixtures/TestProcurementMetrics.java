package com.materia.backend.support.fixtures;

import com.google.protobuf.Empty;
import com.materia.backend.common.application.metrics.CountedAmount;
import com.materia.backend.common.application.metrics.MonthAmount;
import com.materia.backend.common.application.metrics.RecentDocument;
import com.materia.grpc.convert.MetricsProtos;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.common.v1.AmountResponse;
import com.materia.proto.common.v1.CountResponse;
import com.materia.proto.common.v1.CountedAmountsResponse;
import com.materia.proto.common.v1.CountsResponse;
import com.materia.proto.common.v1.LatestRequest;
import com.materia.proto.common.v1.LatestResponse;
import com.materia.proto.common.v1.MonthAmountsResponse;
import com.materia.proto.common.v1.Period;
import com.materia.proto.common.v1.ReportingCurrencyRequest;
import com.materia.proto.common.v1.StatusRequest;
import com.materia.proto.common.v1.StatusesByMonthRequest;
import com.materia.proto.common.v1.StatusesRequest;
import com.materia.proto.common.v1.StatusesWithRatesRequest;
import com.materia.proto.procurement.v1.CountOrdersExpectedBeforeRequest;
import com.materia.proto.procurement.v1.CountReceiptsRequest;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.ReceiptTotals;
import com.materia.proto.procurement.v1.ReceiptTotalsBySupplierResponse;
import com.materia.proto.procurement.v1.ReceiptTotalsResponse;
import com.materia.proto.procurement.v1.SpendByMaterialResponse;
import io.grpc.stub.StreamObserver;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * procurement.v1.ProcurementMetrics answering what a test set: how procurement computes its figures (which orders are
 * open or late, what they are worth in the reporting currency) is its own tests' business (ProcurementMetricsIT);
 * analytics' tests check what the dashboard makes of them. Every figure is empty or zero until a test sets it.
 */
public class TestProcurementMetrics extends ProcurementMetricsGrpc.ProcurementMetricsImplBase {

    /** Spend on one material: by id, or by code when the order lines name no id. */
    public record MaterialSpend(UUID materialId, String materialCode, long lines, String amount) {
    }

    /** A supplier's receipts, as ReceiptTotals counts them. */
    public record Totals(long receipts, long unitsReceived, long unitsAccepted, long unitsRejected, long datedReceipts,
                         long onTimeReceipts) {
        ReceiptTotals toProto() {
            return ReceiptTotals.newBuilder().setReceipts(receipts).setUnitsReceived(unitsReceived)
                    .setUnitsAccepted(unitsAccepted).setUnitsRejected(unitsRejected).setDatedReceipts(datedReceipts)
                    .setOnTimeReceipts(onTimeReceipts).build();
        }
    }

    private Map<String, Long> requisitionsInStatus = Map.of();
    private Map<String, Long> ordersByCurrency = Map.of();
    private Map<String, Long> ordersValuedAtOwnDate = Map.of();
    private long openOrders;
    private long lateOrders;
    private String openOrdersValue = "0";
    private List<MonthAmount> orderedByMonth = List.of();
    private Map<String, Long> ordersByStatus = Map.of();
    private List<MaterialSpend> spendByMaterial = List.of();
    private List<CountedAmount> spendBySupplier = List.of();
    private long receiptsThisMonth;
    private long receiptsWithRejection;
    private Totals receiptTotals = new Totals(0, 0, 0, 0, 0, 0);
    private Map<UUID, Totals> receiptTotalsBySupplier = Map.of();
    private Map<String, Long> returnsInStatus = Map.of();
    private List<RecentDocument> latestRequisitions = List.of();
    private List<RecentDocument> latestOrders = List.of();
    private List<RecentDocument> latestReceipts = List.of();
    private List<RecentDocument> latestReturns = List.of();

    public TestProcurementMetrics requisitions(Map<String, Long> countByStatus) {
        this.requisitionsInStatus = Map.copyOf(countByStatus);
        return this;
    }

    /** Orders per currency, and those valued at the rate of their own date in the reporting currency. */
    public TestProcurementMetrics orderCurrencies(Map<String, Long> byCurrency, Map<String, Long> valuedAtOwnDate) {
        this.ordersByCurrency = Map.copyOf(byCurrency);
        this.ordersValuedAtOwnDate = Map.copyOf(valuedAtOwnDate);
        return this;
    }

    /** The open orders: how many, how many expected before today, and what they are worth. */
    public TestProcurementMetrics openOrders(long count, long late, String value) {
        this.openOrders = count;
        this.lateOrders = late;
        this.openOrdersValue = value;
        return this;
    }

    /** Committed orders per month of order date, in the reporting currency. */
    public TestProcurementMetrics ordered(List<MonthAmount> months) {
        this.orderedByMonth = List.copyOf(months);
        return this;
    }

    /** Orders per status, in the order given (procurement answers in lifecycle order). */
    public TestProcurementMetrics ordersByStatus(Map<String, Long> countByStatus) {
        this.ordersByStatus = new java.util.LinkedHashMap<>(countByStatus);
        return this;
    }

    public TestProcurementMetrics spend(List<MaterialSpend> byMaterial, List<CountedAmount> bySupplier) {
        this.spendByMaterial = List.copyOf(byMaterial);
        this.spendBySupplier = List.copyOf(bySupplier);
        return this;
    }

    /** Validated receipts this month, those with rejected goods, and the totals over all, then per supplier. */
    public TestProcurementMetrics receipts(long thisMonth, long withRejection, Totals totals,
                                           Map<UUID, Totals> bySupplier) {
        this.receiptsThisMonth = thisMonth;
        this.receiptsWithRejection = withRejection;
        this.receiptTotals = totals;
        this.receiptTotalsBySupplier = Map.copyOf(bySupplier);
        return this;
    }

    public TestProcurementMetrics returns(Map<String, Long> countByStatus) {
        this.returnsInStatus = Map.copyOf(countByStatus);
        return this;
    }

    public TestProcurementMetrics latest(List<RecentDocument> requisitions, List<RecentDocument> orders,
                                         List<RecentDocument> receipts, List<RecentDocument> returns) {
        this.latestRequisitions = List.copyOf(requisitions);
        this.latestOrders = List.copyOf(orders);
        this.latestReceipts = List.copyOf(receipts);
        this.latestReturns = List.copyOf(returns);
        return this;
    }

    @Override
    public void countRequisitionsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
        reply(MetricsProtos.count(requisitionsInStatus.getOrDefault(request.getStatus(), 0L)), response);
    }

    @Override
    public void latestRequisitions(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(latest(latestRequisitions, request), response);
    }

    @Override
    public void ordersByCurrency(Empty request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(ordersByCurrency), response);
    }

    @Override
    public void ordersValuedAtOwnDate(ReportingCurrencyRequest request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(ordersValuedAtOwnDate), response);
    }

    @Override
    public void countOrders(StatusesRequest request, StreamObserver<CountResponse> response) {
        reply(MetricsProtos.count(openOrders), response);
    }

    @Override
    public void countOrdersExpectedBefore(CountOrdersExpectedBeforeRequest request,
                                          StreamObserver<CountResponse> response) {
        reply(MetricsProtos.count(lateOrders), response);
    }

    @Override
    public void sumOrders(StatusesWithRatesRequest request, StreamObserver<AmountResponse> response) {
        reply(MetricsProtos.amount(new BigDecimal(openOrdersValue)), response);
    }

    @Override
    public void ordersByMonth(StatusesByMonthRequest request, StreamObserver<MonthAmountsResponse> response) {
        reply(MetricsProtos.months(within(orderedByMonth, request.getPeriod())), response);
    }

    @Override
    public void countOrdersByStatus(Empty request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(ordersByStatus), response);
    }

    @Override
    public void spendByMaterial(StatusesWithRatesRequest request, StreamObserver<SpendByMaterialResponse> response) {
        SpendByMaterialResponse.Builder reply = SpendByMaterialResponse.newBuilder();
        spendByMaterial.forEach(spend -> {
            SpendByMaterialResponse.MaterialSpend.Builder proto = SpendByMaterialResponse.MaterialSpend.newBuilder()
                    .setLines(spend.lines()).setAmount(spend.amount());
            if (spend.materialId() != null) proto.setMaterialId(spend.materialId().toString());
            if (spend.materialCode() != null) proto.setMaterialCode(spend.materialCode());
            reply.addSpend(proto);
        });
        reply(reply.build(), response);
    }

    @Override
    public void spendBySupplier(StatusesWithRatesRequest request, StreamObserver<CountedAmountsResponse> response) {
        reply(MetricsProtos.groups(spendBySupplier), response);
    }

    @Override
    public void latestOrders(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(latest(latestOrders, request), response);
    }

    @Override
    public void countReceipts(CountReceiptsRequest request, StreamObserver<CountResponse> response) {
        reply(MetricsProtos.count(request.getWithRejectionOnly() ? receiptsWithRejection : receiptsThisMonth), response);
    }

    @Override
    public void receiptTotals(StatusesRequest request, StreamObserver<ReceiptTotalsResponse> response) {
        reply(ReceiptTotalsResponse.newBuilder().setTotals(receiptTotals.toProto()).build(), response);
    }

    @Override
    public void receiptTotalsBySupplier(StatusesRequest request,
                                        StreamObserver<ReceiptTotalsBySupplierResponse> response) {
        ReceiptTotalsBySupplierResponse.Builder reply = ReceiptTotalsBySupplierResponse.newBuilder();
        receiptTotalsBySupplier.forEach((supplier, totals) -> reply.putBySupplier(supplier.toString(), totals.toProto()));
        reply(reply.build(), response);
    }

    @Override
    public void latestReceipts(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(latest(latestReceipts, request), response);
    }

    @Override
    public void countReturnsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
        reply(MetricsProtos.count(returnsInStatus.getOrDefault(request.getStatus(), 0L)), response);
    }

    @Override
    public void latestReturns(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(latest(latestReturns, request), response);
    }

    private static LatestResponse latest(List<RecentDocument> documents, LatestRequest request) {
        return MetricsProtos.latest(documents.stream().limit(request.getLimit()).toList());
    }

    private static List<MonthAmount> within(List<MonthAmount> months, Period period) {
        YearMonth from = YearMonth.from(ProtoValues.date(period.getFrom()));
        YearMonth to = YearMonth.from(ProtoValues.date(period.getTo()));
        return months.stream().filter(m -> !m.month().isBefore(from) && !m.month().isAfter(to)).toList();
    }

    private static <T> void reply(T message, StreamObserver<T> response) {
        response.onNext(message);
        response.onCompleted();
    }
}
