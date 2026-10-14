package com.materia.grpc.convert;

import com.materia.backend.common.application.metrics.CountedAmount;
import com.materia.backend.common.application.metrics.MonthAmount;
import com.materia.backend.common.application.metrics.RecentDocument;
import com.materia.backend.common.application.metrics.ReportingRates;
import com.materia.proto.common.v1.AmountResponse;
import com.materia.proto.common.v1.CountResponse;
import com.materia.proto.common.v1.CountedAmountsResponse;
import com.materia.proto.common.v1.CountsResponse;
import com.materia.proto.common.v1.LatestResponse;
import com.materia.proto.common.v1.MonthAmountsResponse;
import com.materia.proto.common.v1.Period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared reporting shapes (common.v1.metrics) to and from the shared kernel's metric types, for the services that
 * report their figures and for analytics, which composes them.
 */
public final class MetricsProtos {

    private MetricsProtos() {
    }

    // ---- Reporting rates and periods ----

    public static com.materia.proto.common.v1.ReportingRates rates(ReportingRates rates) {
        com.materia.proto.common.v1.ReportingRates.Builder proto = com.materia.proto.common.v1.ReportingRates.newBuilder();
        if (rates == null) return proto.build();
        if (rates.reportingCurrency() != null) proto.setReportingCurrency(rates.reportingCurrency());
        if (rates.current() != null) {
            rates.current().forEach((currency, rate) -> {
                if (currency != null && rate != null) proto.putCurrent(currency, rate.toPlainString());
            });
        }
        return proto.build();
    }

    public static ReportingRates rates(com.materia.proto.common.v1.ReportingRates rates) {
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        rates.getCurrentMap().forEach((currency, rate) -> current.put(currency, new BigDecimal(rate)));
        return new ReportingRates(rates.getReportingCurrency().isEmpty() ? null : rates.getReportingCurrency(), current);
    }

    public static Period period(LocalDate from, LocalDate to) {
        Period.Builder period = Period.newBuilder();
        if (from != null) period.setFrom(from.toString());
        if (to != null) period.setTo(to.toString());
        return period.build();
    }

    // ---- Counts and amounts ----

    public static CountResponse count(long count) {
        return CountResponse.newBuilder().setCount(count).build();
    }

    public static CountsResponse counts(Map<String, Long> counts) {
        CountsResponse.Builder proto = CountsResponse.newBuilder();
        if (counts != null) counts.forEach((key, count) -> {
            if (key != null && count != null) proto.putCounts(key, count);
        });
        return proto.build();
    }

    public static Map<String, Long> counts(CountsResponse counts) {
        return new LinkedHashMap<>(counts.getCountsMap());
    }

    public static AmountResponse amount(BigDecimal amount) {
        AmountResponse.Builder proto = AmountResponse.newBuilder();
        if (amount != null) proto.setAmount(amount.toPlainString());
        return proto.build();
    }

    public static MonthAmountsResponse months(List<MonthAmount> months) {
        MonthAmountsResponse.Builder proto = MonthAmountsResponse.newBuilder();
        if (months != null) months.forEach(month -> {
            com.materia.proto.common.v1.MonthAmount.Builder line = com.materia.proto.common.v1.MonthAmount.newBuilder();
            if (month.month() != null) line.setMonth(month.month().toString());
            if (month.amount() != null) line.setAmount(month.amount().toPlainString());
            proto.addMonths(line);
        });
        return proto.build();
    }

    public static List<MonthAmount> months(MonthAmountsResponse months) {
        return months.getMonthsList().stream()
                .map(month -> new MonthAmount(ProtoValues.yearMonth(month.getMonth()), ProtoValues.decimal(month.getAmount())))
                .toList();
    }

    public static CountedAmountsResponse groups(List<CountedAmount> groups) {
        CountedAmountsResponse.Builder proto = CountedAmountsResponse.newBuilder();
        if (groups != null) groups.forEach(group -> {
            com.materia.proto.common.v1.CountedAmount.Builder line =
                    com.materia.proto.common.v1.CountedAmount.newBuilder().setCount(group.count());
            if (group.key() != null) line.setKey(group.key());
            if (group.label() != null) line.setLabel(group.label());
            if (group.amount() != null) line.setAmount(group.amount().toPlainString());
            proto.addGroups(line);
        });
        return proto.build();
    }

    public static List<CountedAmount> groups(CountedAmountsResponse groups) {
        return groups.getGroupsList().stream()
                .map(group -> new CountedAmount(group.getKey().isEmpty() ? null : group.getKey(),
                        group.getLabel().isEmpty() ? null : group.getLabel(), group.getCount(),
                        ProtoValues.decimal(group.getAmount())))
                .toList();
    }

    // ---- Activity ----

    public static LatestResponse latest(List<RecentDocument> documents) {
        LatestResponse.Builder proto = LatestResponse.newBuilder();
        if (documents != null) documents.forEach(document -> {
            com.materia.proto.common.v1.RecentDocument.Builder line = com.materia.proto.common.v1.RecentDocument.newBuilder();
            if (document.id() != null) line.setId(document.id().toString());
            if (document.code() != null) line.setCode(document.code());
            if (document.title() != null) line.setTitle(document.title());
            if (document.status() != null) line.setStatus(document.status());
            if (document.createdAt() != null) line.setCreatedAt(document.createdAt().toString());
            proto.addDocuments(line);
        });
        return proto.build();
    }

    public static List<RecentDocument> latest(LatestResponse documents) {
        return documents.getDocumentsList().stream()
                .map(document -> new RecentDocument(ProtoValues.uuid(document.getId()),
                        document.getCode().isEmpty() ? null : document.getCode(),
                        document.hasTitle() ? document.getTitle() : null,
                        document.hasStatus() ? document.getStatus() : null,
                        document.hasCreatedAt() ? ProtoValues.dateTime(document.getCreatedAt()) : null))
                .toList();
    }
}
