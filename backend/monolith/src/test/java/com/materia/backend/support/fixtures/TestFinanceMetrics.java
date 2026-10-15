package com.materia.backend.support.fixtures;

import com.google.protobuf.Empty;
import com.materia.backend.common.application.metrics.MonthAmount;
import com.materia.backend.common.application.metrics.RecentDocument;
import com.materia.grpc.convert.MetricsProtos;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.common.v1.CountResponse;
import com.materia.proto.common.v1.CountsResponse;
import com.materia.proto.common.v1.LatestRequest;
import com.materia.proto.common.v1.LatestResponse;
import com.materia.proto.common.v1.MonthAmountsResponse;
import com.materia.proto.common.v1.Period;
import com.materia.proto.common.v1.ReportingCurrencyRequest;
import com.materia.proto.common.v1.StatusRequest;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.finance.v1.InvoicedByMonthRequest;
import com.materia.proto.finance.v1.OutstandingByDueDateRequest;
import com.materia.proto.finance.v1.OutstandingByDueDateResponse;
import com.materia.proto.finance.v1.PaymentsByMonthRequest;
import io.grpc.stub.StreamObserver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * finance.v1.FinanceMetrics answering what a test set: how finance computes its figures (what is outstanding, what
 * counts as invoiced or paid) is its own tests' business; this application's tests check what the dashboard makes of
 * them. Every figure is empty or zero until a test sets it; {@link #reset} empties them again.
 */
public class TestFinanceMetrics extends FinanceMetricsGrpc.FinanceMetricsImplBase {

    /** What is left to pay on standard invoices due on {@code dueDate} (null: no due date), in {@code currency}. */
    public record DueGroup(LocalDate dueDate, String currency, long invoices, String outstanding) {
    }

    private volatile List<DueGroup> outstanding = List.of();
    private volatile List<MonthAmount> invoiced = List.of();
    private volatile List<MonthAmount> paid = List.of();
    private volatile long pendingPayments;
    private volatile List<RecentDocument> latestInvoices = List.of();
    private volatile List<RecentDocument> latestPayments = List.of();
    private volatile Map<String, Long> invoicesByCurrency = Map.of();
    private volatile Map<String, Long> paymentsByCurrency = Map.of();

    public void reset() {
        outstanding = List.of();
        invoiced = List.of();
        paid = List.of();
        pendingPayments = 0;
        latestInvoices = List.of();
        latestPayments = List.of();
        invoicesByCurrency = Map.of();
        paymentsByCurrency = Map.of();
    }

    /** Payable standard invoices, grouped by due date and currency. */
    public TestFinanceMetrics outstanding(List<DueGroup> groups) {
        this.outstanding = List.copyOf(groups);
        return this;
    }

    /** Invoiced per month, net of credit notes, in the reporting currency. */
    public TestFinanceMetrics invoiced(List<MonthAmount> months) {
        this.invoiced = List.copyOf(months);
        return this;
    }

    /** Executed (completed) payments per month, in the reporting currency. */
    public TestFinanceMetrics paid(List<MonthAmount> months) {
        this.paid = List.copyOf(months);
        return this;
    }

    public TestFinanceMetrics pendingPayments(long count) {
        this.pendingPayments = count;
        return this;
    }

    public TestFinanceMetrics latest(List<RecentDocument> invoices, List<RecentDocument> payments) {
        this.latestInvoices = List.copyOf(invoices);
        this.latestPayments = List.copyOf(payments);
        return this;
    }

    /** Documents per currency (every one valued at the reporting currency of its own date: none in another). */
    public TestFinanceMetrics documents(Map<String, Long> invoices, Map<String, Long> payments) {
        this.invoicesByCurrency = Map.copyOf(invoices);
        this.paymentsByCurrency = Map.copyOf(payments);
        return this;
    }

    public static MonthAmount month(String month, String amount) {
        return new MonthAmount(YearMonth.parse(month), new BigDecimal(amount));
    }

    @Override
    public void invoicesByCurrency(Empty request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(invoicesByCurrency), response);
    }

    @Override
    public void invoicesValuedAtOwnDate(ReportingCurrencyRequest request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(Map.of()), response);
    }

    @Override
    public void outstandingByDueDate(OutstandingByDueDateRequest request,
                                     StreamObserver<OutstandingByDueDateResponse> response) {
        OutstandingByDueDateResponse.Builder reply = OutstandingByDueDateResponse.newBuilder();
        if ("STANDARD".equals(request.getType())) {
            outstanding.forEach(group -> {
                OutstandingByDueDateResponse.Group.Builder proto = OutstandingByDueDateResponse.Group.newBuilder()
                        .setCurrency(group.currency())
                        .setInvoices(group.invoices())
                        .setOutstanding(group.outstanding());
                if (group.dueDate() != null) proto.setDueDate(group.dueDate().toString());
                reply.addGroups(proto);
            });
        }
        reply(reply.build(), response);
    }

    @Override
    public void invoicedByMonth(InvoicedByMonthRequest request, StreamObserver<MonthAmountsResponse> response) {
        reply(MetricsProtos.months(within(invoiced, request.getPeriod())), response);
    }

    @Override
    public void latestInvoices(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(MetricsProtos.latest(latestInvoices.stream().limit(request.getLimit()).toList()), response);
    }

    @Override
    public void latestPayments(LatestRequest request, StreamObserver<LatestResponse> response) {
        reply(MetricsProtos.latest(latestPayments.stream().limit(request.getLimit()).toList()), response);
    }

    @Override
    public void paymentsByCurrency(Empty request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(paymentsByCurrency), response);
    }

    @Override
    public void paymentsValuedAtOwnDate(ReportingCurrencyRequest request, StreamObserver<CountsResponse> response) {
        reply(MetricsProtos.counts(Map.of()), response);
    }

    @Override
    public void countPaymentsInStatus(StatusRequest request, StreamObserver<CountResponse> response) {
        reply(MetricsProtos.count("PENDING".equals(request.getStatus()) ? pendingPayments : 0), response);
    }

    @Override
    public void paymentsByMonth(PaymentsByMonthRequest request, StreamObserver<MonthAmountsResponse> response) {
        List<MonthAmount> months = "COMPLETED".equals(request.getStatus()) ? paid : List.of();
        reply(MetricsProtos.months(within(months, request.getPeriod())), response);
    }

    /** The months of {@code period} (dates, as the dashboard asks: from the first day to the last). */
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
