package com.materia.backend.contexts.invoice.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.invoice.domain.ports.in.InvoiceMetricsUseCase;
import com.materia.backend.contexts.payment.domain.ports.in.PaymentMetricsUseCase;
import com.materia.grpc.convert.MetricsProtos;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.common.v1.CountResponse;
import com.materia.proto.common.v1.CountsResponse;
import com.materia.proto.common.v1.LatestRequest;
import com.materia.proto.common.v1.LatestResponse;
import com.materia.proto.common.v1.MonthAmountsResponse;
import com.materia.proto.common.v1.ReportingCurrencyRequest;
import com.materia.proto.common.v1.StatusRequest;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.finance.v1.InvoicedByMonthRequest;
import com.materia.proto.finance.v1.OutstandingByDueDateRequest;
import com.materia.proto.finance.v1.OutstandingByDueDateResponse;
import com.materia.proto.finance.v1.PaymentsByMonthRequest;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;

/**
 * Serves finance.v1.FinanceMetrics: the reporting use cases of invoices and payments, the contexts finance owns. Each
 * figure is computed on finance's own data.
 */
@Component
public class FinanceMetricsGrpcService extends FinanceMetricsGrpc.FinanceMetricsImplBase {

    private final InvoiceMetricsUseCase invoices;
    private final PaymentMetricsUseCase payments;

    public FinanceMetricsGrpcService(InvoiceMetricsUseCase invoices, PaymentMetricsUseCase payments) {
        this.invoices = invoices;
        this.payments = payments;
    }

    private static <T> void reply(StreamObserver<T> response, T value) {
        response.onNext(value);
        response.onCompleted();
    }

    // ---- Invoices ----

    @Override
    public void invoicesByCurrency(Empty request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(invoices.documentsByCurrency()));
    }

    @Override
    public void invoicesValuedAtOwnDate(ReportingCurrencyRequest request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(invoices.valuedAtOwnDate(request.getReportingCurrency())));
    }

    @Override
    public void outstandingByDueDate(OutstandingByDueDateRequest request,
                                     StreamObserver<OutstandingByDueDateResponse> response) {
        OutstandingByDueDateResponse.Builder reply = OutstandingByDueDateResponse.newBuilder();
        invoices.outstandingByDueDate(request.getType(), new LinkedHashSet<>(request.getStatusesList()),
                        MetricsProtos.rates(request.getRates()))
                .forEach(due -> {
                    OutstandingByDueDateResponse.Group.Builder group =
                            OutstandingByDueDateResponse.Group.newBuilder().setInvoices(due.invoices());
                    if (due.dueDate() != null) group.setDueDate(due.dueDate().toString());
                    if (due.currency() != null) group.setCurrency(due.currency());
                    if (due.outstanding() != null) group.setOutstanding(due.outstanding().toPlainString());
                    reply.addGroups(group);
                });
        reply(response, reply.build());
    }

    @Override
    public void invoicedByMonth(InvoicedByMonthRequest request, StreamObserver<MonthAmountsResponse> response) {
        reply(response, MetricsProtos.months(invoices.invoicedByMonth(new LinkedHashSet<>(request.getExcludedStatusesList()),
                MetricsProtos.rates(request.getRates()), ProtoValues.date(request.getPeriod().getFrom()),
                ProtoValues.date(request.getPeriod().getTo()))));
    }

    @Override
    public void latestInvoices(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(response, MetricsProtos.latest(invoices.latest(request.getLimit())));
    }

    // ---- Payments ----

    @Override
    public void paymentsByCurrency(Empty request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(payments.documentsByCurrency()));
    }

    @Override
    public void paymentsValuedAtOwnDate(ReportingCurrencyRequest request, StreamObserver<CountsResponse> response) {
        reply(response, MetricsProtos.counts(payments.valuedAtOwnDate(request.getReportingCurrency())));
    }

    @Override
    public void countPaymentsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
        reply(response, MetricsProtos.count(payments.countInStatus(request.getStatus())));
    }

    @Override
    public void paymentsByMonth(PaymentsByMonthRequest request, StreamObserver<MonthAmountsResponse> response) {
        reply(response, MetricsProtos.months(payments.byMonth(request.getStatus(), MetricsProtos.rates(request.getRates()),
                ProtoValues.date(request.getPeriod().getFrom()), ProtoValues.date(request.getPeriod().getTo()))));
    }

    @Override
    public void latestPayments(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(response, MetricsProtos.latest(payments.latest(request.getLimit())));
    }
}
