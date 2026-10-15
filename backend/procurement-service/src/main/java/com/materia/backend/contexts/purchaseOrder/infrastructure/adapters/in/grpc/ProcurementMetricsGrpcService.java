package com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.goodsReceipt.application.dtos.ReceiptTotals;
import com.materia.backend.contexts.goodsReceipt.domain.ports.in.GoodsReceiptMetricsUseCase;
import com.materia.backend.contexts.purchaseOrder.domain.ports.in.PurchaseOrderMetricsUseCase;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.RequisitionMetricsUseCase;
import com.materia.backend.contexts.returnToVendor.domain.ports.in.ReturnMetricsUseCase;
import com.materia.grpc.convert.MetricsProtos;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.common.v1.AmountResponse;
import com.materia.proto.common.v1.CountResponse;
import com.materia.proto.common.v1.CountedAmountsResponse;
import com.materia.proto.common.v1.CountsResponse;
import com.materia.proto.common.v1.LatestRequest;
import com.materia.proto.common.v1.LatestResponse;
import com.materia.proto.common.v1.MonthAmountsResponse;
import com.materia.proto.common.v1.ReportingCurrencyRequest;
import com.materia.proto.common.v1.StatusRequest;
import com.materia.proto.common.v1.StatusesByMonthRequest;
import com.materia.proto.common.v1.StatusesRequest;
import com.materia.proto.common.v1.StatusesWithRatesRequest;
import com.materia.proto.procurement.v1.CountOrdersExpectedBeforeRequest;
import com.materia.proto.procurement.v1.CountReceiptsRequest;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import com.materia.proto.procurement.v1.ReceiptTotalsBySupplierResponse;
import com.materia.proto.procurement.v1.ReceiptTotalsResponse;
import com.materia.proto.procurement.v1.SpendByMaterialResponse;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Serves procurement.v1.ProcurementMetrics: the reporting use cases of requisitions, orders, receipts and returns,
 * the four contexts procurement owns. Each figure is computed on procurement's own data.
 */
@Component
public class ProcurementMetricsGrpcService extends ProcurementMetricsGrpc.ProcurementMetricsImplBase {

    private final RequisitionMetricsUseCase requisitions;
    private final PurchaseOrderMetricsUseCase orders;
    private final GoodsReceiptMetricsUseCase receipts;
    private final ReturnMetricsUseCase returns;

    public ProcurementMetricsGrpcService(RequisitionMetricsUseCase requisitions, PurchaseOrderMetricsUseCase orders,
                                         GoodsReceiptMetricsUseCase receipts, ReturnMetricsUseCase returns) {
        this.requisitions = requisitions;
        this.orders = orders;
        this.receipts = receipts;
        this.returns = returns;
    }

    private static <T> void reply(StreamObserver<T> response, T value) {
        response.onNext(value);
        response.onCompleted();
    }

    private static Set<String> statuses(List<String> statuses) {
        return new LinkedHashSet<>(statuses);
    }

    // ---- Requisitions ----

    @Override
    public void countRequisitionsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
        reply(response, MetricsProtos.count(requisitions.countInStatus(request.getStatus())));
    }

    @Override
    public void latestRequisitions(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(response, MetricsProtos.latest(requisitions.latest(request.getLimit())));
    }

    // ---- Purchase orders ----

    @Override
    public void ordersByCurrency(Empty request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(orders.documentsByCurrency()));
    }

    @Override
    public void ordersValuedAtOwnDate(ReportingCurrencyRequest request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(orders.valuedAtOwnDate(request.getReportingCurrency())));
    }

    @Override
    public void countOrders(StatusesRequest request, StreamObserver<CountResponse> response) {
        reply(response, MetricsProtos.count(orders.count(statuses(request.getStatusesList()))));
    }

    @Override
    public void countOrdersExpectedBefore(CountOrdersExpectedBeforeRequest request, StreamObserver<CountResponse> response) {
        reply(response, MetricsProtos.count(orders.countExpectedBefore(statuses(request.getStatusesList()),
                ProtoValues.date(request.getDate()))));
    }

    @Override
    public void sumOrders(StatusesWithRatesRequest request, StreamObserver<AmountResponse> response) {
        reply(response, MetricsProtos.amount(orders.sum(statuses(request.getStatusesList()),
                MetricsProtos.rates(request.getRates()))));
    }

    @Override
    public void ordersByMonth(StatusesByMonthRequest request, StreamObserver<MonthAmountsResponse> response) {
        reply(response, MetricsProtos.months(orders.byMonth(statuses(request.getStatusesList()),
                MetricsProtos.rates(request.getRates()), ProtoValues.date(request.getPeriod().getFrom()),
                ProtoValues.date(request.getPeriod().getTo()))));
    }

    @Override
    public void countOrdersByStatus(Empty request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(orders.countByStatus()));
    }

    @Override
    public void spendByMaterial(StatusesWithRatesRequest request, StreamObserver<SpendByMaterialResponse> response) {
        SpendByMaterialResponse.Builder reply = SpendByMaterialResponse.newBuilder();
        orders.spendByMaterial(statuses(request.getStatusesList()), MetricsProtos.rates(request.getRates()))
                .forEach(spend -> {
                    SpendByMaterialResponse.MaterialSpend.Builder line =
                            SpendByMaterialResponse.MaterialSpend.newBuilder().setLines(spend.lines());
                    if (spend.materialId() != null) line.setMaterialId(spend.materialId().toString());
                    if (spend.materialCode() != null) line.setMaterialCode(spend.materialCode());
                    if (spend.amount() != null) line.setAmount(spend.amount().toPlainString());
                    reply.addSpend(line);
                });
        reply(response, reply.build());
    }

    @Override
    public void spendBySupplier(StatusesWithRatesRequest request, StreamObserver<CountedAmountsResponse> response) {
        reply(response, MetricsProtos.groups(orders.spendBySupplier(statuses(request.getStatusesList()),
                MetricsProtos.rates(request.getRates()))));
    }

    @Override
    public void latestOrders(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(response, MetricsProtos.latest(orders.latest(request.getLimit())));
    }

    // ---- Goods receipts ----

    @Override
    public void countReceipts(CountReceiptsRequest request, StreamObserver<CountResponse> response) {
        reply(response, MetricsProtos.count(receipts.count(statuses(request.getStatusesList()),
                request.hasFrom() ? ProtoValues.date(request.getFrom()) : null,
                request.hasTo() ? ProtoValues.date(request.getTo()) : null,
                request.getWithRejectionOnly())));
    }

    @Override
    public void receiptTotals(StatusesRequest request, StreamObserver<ReceiptTotalsResponse> response) {
        reply(response, ReceiptTotalsResponse.newBuilder()
                .setTotals(toProto(receipts.totals(statuses(request.getStatusesList())))).build());
    }

    @Override
    public void receiptTotalsBySupplier(StatusesRequest request,
                                        StreamObserver<ReceiptTotalsBySupplierResponse> response) {
        ReceiptTotalsBySupplierResponse.Builder reply = ReceiptTotalsBySupplierResponse.newBuilder();
        receipts.totalsBySupplier(statuses(request.getStatusesList())).forEach((supplier, totals) -> {
            if (supplier != null) reply.putBySupplier(supplier.toString(), toProto(totals));
        });
        reply(response, reply.build());
    }

    @Override
    public void latestReceipts(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(response, MetricsProtos.latest(receipts.latest(request.getLimit())));
    }

    private static com.materia.proto.procurement.v1.ReceiptTotals toProto(ReceiptTotals totals) {
        ReceiptTotals t = totals == null ? ReceiptTotals.NONE : totals;
        return com.materia.proto.procurement.v1.ReceiptTotals.newBuilder()
                .setReceipts(t.receipts())
                .setUnitsReceived(t.unitsReceived())
                .setUnitsAccepted(t.unitsAccepted())
                .setUnitsRejected(t.unitsRejected())
                .setDatedReceipts(t.datedReceipts())
                .setOnTimeReceipts(t.onTimeReceipts())
                .build();
    }

    // ---- Returns to vendor ----

    @Override
    public void countReturnsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
        reply(response, MetricsProtos.count(returns.countInStatus(request.getStatus())));
    }

    @Override
    public void latestReturns(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(response, MetricsProtos.latest(returns.latest(request.getLimit())));
    }
}
