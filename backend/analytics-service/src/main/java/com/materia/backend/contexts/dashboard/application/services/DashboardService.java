package com.materia.backend.contexts.dashboard.application.services;

import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput;
import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.fx.FxValuation;
import com.materia.backend.common.domain.fx.ReportingFx;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.Activity;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.ConvertedCurrency;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.CurrencyConversion;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.AgingBucket;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.Kpi;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.MonthlyTrend;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.ReceiptQuality;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.Slice;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.StockAlert;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.SupplierStats;
import com.materia.backend.contexts.dashboard.domain.ports.in.DashboardUseCase;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.ActivitySource;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.DueGroup;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.FigureUnavailableException;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.Group;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.MonthAmount;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.Rates;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.ReceiptTotals;
import com.materia.backend.contexts.dashboard.domain.ports.out.DashboardReadModel.StockRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Builds the procurement dashboard from aggregates the database computes ({@link DashboardReadModel}), so its cost
 * does not grow with the number of documents. Each section is computed only when the user holds the permission that
 * reads its source (Role.java), so a receiver never sees invoice or payment figures.
 *
 * <p>The aggregates are asked for side by side on {@code executor} (each one is a call to another service), in two
 * waves: at once, the documents per currency and every figure that needs no exchange rate; then, once the rates are
 * known, the amounts in the reporting currency. The executor must run each task as the user who opened the dashboard
 * (CallContextExecutor), so every call carries that user's rights.
 *
 * <p>A figure whose service does not answer ({@link FigureUnavailableException}: still starting, too slow, down) does
 * not fail the dashboard: its section is shown as for a user without access to it (no tile, an empty list) and is
 * named in {@code unavailable}, so the page can say so. Any other failure fails the dashboard, as a bug should.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService implements DashboardUseCase {

    static final int TREND_MONTHS = 12;
    static final int TOP_SUPPLIERS = 5;
    static final int TOP_CATEGORIES = 5;
    static final int MAX_STOCK_ALERTS = 8;
    static final int MAX_ACTIVITY = 10;

    /** Orders that commit spend with a supplier: sent and not cancelled or rejected. */
    private static final Set<String> COMMITTED_ORDERS = Set.of("SUBMITTED", "CONFIRMED",
            "READY_FOR_RECEIPT", "PARTIALLY_RECEIVED", "COMPLETED");
    /** Orders still expected from the supplier. */
    private static final Set<String> OPEN_ORDERS = Set.of("SUBMITTED", "CONFIRMED",
            "READY_FOR_RECEIPT", "PARTIALLY_RECEIVED");
    private static final Set<String> DONE_RECEIPTS = Set.of("COMPLETED", "PARTIAL");
    /** Invoices that still have to be paid. */
    private static final Set<String> PAYABLE_INVOICES = Set.of("SUBMITTED", "VERIFIED");
    /** Invoices that do not count as invoiced: not yet submitted, or withdrawn. */
    private static final Set<String> NOT_INVOICED = Set.of("DRAFT", "CANCELLED");

    private static final Map<String, String> ORDER_LABELS = Map.of(
            "DRAFT", "Draft", "SUBMITTED", "Submitted", "CONFIRMED", "Confirmed",
            "READY_FOR_RECEIPT", "Ready for Receipt", "PARTIALLY_RECEIVED", "Partly Received",
            "COMPLETED", "Completed", "CANCELLED", "Cancelled", "REJECTED", "Rejected");

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DashboardReadModel readModel;
    private final ReportingFx fx;
    private final Clock clock;
    private final Executor executor;

    @Autowired
    public DashboardService(DashboardReadModel readModel, ReportingFx fx,
                            @Qualifier("dashboardExecutor") Executor executor) {
        this(readModel, fx, Clock.systemDefaultZone(), executor);
    }

    /** Asks for one aggregate after the other, on the caller's thread. */
    public DashboardService(DashboardReadModel readModel, ReportingFx fx, Clock clock) {
        this(readModel, fx, clock, Runnable::run);
    }

    public DashboardService(DashboardReadModel readModel, ReportingFx fx, Clock clock, Executor executor) {
        this.readModel = readModel;
        this.fx = fx;
        this.clock = clock;
        this.executor = executor;
    }

    @Override
    public DashboardOutput getOverview(Set<String> permissions) {
        Access access = new Access(permissions == null ? Set.of() : permissions);
        LocalDate today = LocalDate.now(clock);
        Set<String> unavailable = ConcurrentHashMap.newKeySet();

        // ---- First wave: the documents per currency, and every figure that needs no exchange rate ----
        CompletableFuture<Map<String, Long>> currencies = access.orders || access.invoices || access.payments
                ? shown(ask(() -> readModel.documentsByCurrency(access.orders, access.invoices, access.payments)),
                        "conversion", Map.of(), unavailable)
                : done(Map.of());
        CompletableFuture<Kpi> requisitionsKpi = access.requisitions
                ? shown(requisitionsKpi(), "kpi:requisitionsAwaiting", null, unavailable) : done(null);
        CompletableFuture<Kpi> receiptsKpi = access.receipts
                ? shown(receiptsKpi(today), "kpi:receiptsThisMonth", null, unavailable) : done(null);
        CompletableFuture<Kpi> stockKpi = access.materials
                ? shown(stockKpi(), "kpi:stockAlerts", null, unavailable) : done(null);
        CompletableFuture<Kpi> returnsKpi = access.returns
                ? shown(returnsKpi(), "kpi:returnsOpen", null, unavailable) : done(null);
        CompletableFuture<List<Slice>> orderStatus = access.orders
                ? shown(ask(this::orderStatus), "orderStatus", List.of(), unavailable) : done(List.of());
        CompletableFuture<ReceiptQuality> receiptQuality = access.receipts
                ? shown(ask(() -> receiptQuality(readModel.receiptTotals(DONE_RECEIPTS))), "receiptQuality", null,
                        unavailable)
                : done(null);
        CompletableFuture<List<StockAlert>> stockAlerts = access.materials
                ? shown(ask(this::stockAlerts), "stockAlerts", List.of(), unavailable) : done(List.of());
        CompletableFuture<List<Activity>> activity = recentActivity(access, unavailable);

        Map<String, Long> byCurrency = join(currencies);
        String currency = fx.reportingCurrency().getCode();
        boolean foreign = byCurrency.keySet().stream().anyMatch(c -> !c.equalsIgnoreCase(currency));
        // Stored rates only; the exchange-rate service is not called on every dashboard load.
        Map<CurrencyCode, FxValuation> latest = foreign ? fx.currentRates() : Map.of();
        Rates rates = rates(currency, latest);
        int unconverted = (int) byCurrency.entrySet().stream()
                .filter(e -> !rates.current().containsKey(e.getKey().toUpperCase()))
                .mapToLong(Map.Entry::getValue)
                .sum();

        // ---- Second wave: the amounts, in the reporting currency ----
        // The invoices to pay feed both their tile and the aging chart: without them, neither is shown (not a 0).
        CompletableFuture<List<DueGroup>> payable = access.invoices
                ? ask(() -> readModel.outstandingByDueDate("STANDARD", PAYABLE_INVOICES, rates))
                : done(List.of());
        CompletableFuture<Kpi> ordersKpi = access.orders
                ? shown(ordersKpi(today, rates), "kpi:openOrders", null, unavailable) : done(null);
        CompletableFuture<Kpi> invoicesKpi = access.invoices
                ? shown(payable.thenApply(groups -> invoicesKpi(today, currency, groups)), "kpi:invoicesToPay", null,
                        unavailable)
                : done(null);
        CompletableFuture<List<AgingBucket>> aging = access.invoices
                ? shown(payable.thenApply(groups -> invoiceAging(today, groups)), "invoiceAging", List.of(), unavailable)
                : done(List.of());
        CompletableFuture<Kpi> paymentsKpi = access.payments
                ? shown(paymentsKpi(today, rates), "kpi:paidThisMonth", null, unavailable) : done(null);
        CompletableFuture<MonthlyTrend> trend = access.orders || access.invoices || access.payments
                ? shown(monthlyTrend(access, today, rates), "monthlyTrend", null, unavailable)
                : done(null);
        CompletableFuture<List<Slice>> categories = access.orders
                ? shown(ask(() -> spendByCategory(rates)), "spendByCategory", List.of(), unavailable) : done(List.of());
        CompletableFuture<List<SupplierStats>> suppliers = access.orders
                ? shown(topSuppliers(rates), "topSuppliers", List.of(), unavailable) : done(List.of());
        CompletableFuture<CurrencyConversion> conversion = foreign
                ? shown(ask(() -> conversion(access, currency, byCurrency, latest, unconverted)), "conversion", null,
                        unavailable)
                : done(null);

        List<Kpi> kpis = Stream.of(requisitionsKpi, ordersKpi, receiptsKpi, invoicesKpi, paymentsKpi, stockKpi, returnsKpi)
                .map(DashboardService::join)
                .filter(Objects::nonNull)
                .toList();
        DashboardOutput output = new DashboardOutput(
                LocalDateTime.now(clock),
                currency,
                unconverted,
                kpis,
                join(trend),
                join(orderStatus),
                join(categories),
                join(suppliers),
                join(receiptQuality),
                join(aging),
                join(stockAlerts),
                join(activity),
                join(conversion),
                unavailable.stream().sorted().toList());
        if (!unavailable.isEmpty()) {
            log.warn("Dashboard shown without {}: their services did not answer", output.unavailable());
        }
        return output;
    }

    /**
     * A figure, or {@code fallback} when its service did not answer ({@link FigureUnavailableException}), with
     * {@code section} added to {@code unavailable}. Any other failure goes through.
     */
    private static <T> CompletableFuture<T> shown(CompletableFuture<T> figure, String section, T fallback,
                                                  Set<String> unavailable) {
        return figure.exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            if (cause instanceof FigureUnavailableException) {
                unavailable.add(section);
                return fallback;
            }
            throw failure instanceof CompletionException completion ? completion : new CompletionException(cause);
        });
    }

    /** An aggregate, asked for on the executor. */
    private <T> CompletableFuture<T> ask(Supplier<T> aggregate) {
        return CompletableFuture.supplyAsync(aggregate, executor);
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    /** The value of a figure; a failure is thrown as it was raised, as when asked for on the caller's thread. */
    private static <T> T join(CompletableFuture<T> figure) {
        try {
            return figure.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }

    // ============================================================
    // CURRENCY
    // ============================================================

    /** The reporting currency at 1 and the latest stored rate of every other currency that has one. */
    static Rates rates(String reporting, Map<CurrencyCode, FxValuation> latest) {
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        current.put(reporting.toUpperCase(), BigDecimal.ONE);
        latest.forEach((code, valuation) -> current.put(code.getCode(), valuation.rate()));
        return new Rates(reporting.toUpperCase(), current);
    }

    private CurrencyConversion conversion(Access access, String reporting, Map<String, Long> byCurrency,
                                          Map<CurrencyCode, FxValuation> latest, int unconverted) {
        Map<String, Long> ownDate = readModel.documentsValuedAtOwnDate(reporting, access.orders, access.invoices, access.payments);
        List<ConvertedCurrency> currencies = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        byCurrency.entrySet().stream()
                .filter(e -> !e.getKey().equalsIgnoreCase(reporting))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    FxValuation rate = latest.entrySet().stream()
                            .filter(l -> l.getKey().getCode().equalsIgnoreCase(e.getKey()))
                            .map(Map.Entry::getValue).findFirst().orElse(null);
                    if (rate == null) leftOut.add(e.getKey());
                    currencies.add(new ConvertedCurrency(e.getKey(), e.getValue(), ownDate.getOrDefault(e.getKey(), 0L),
                            rate != null ? rate.rate() : null, rate != null ? rate.rateDate() : null,
                            rate != null ? rate.source() : null));
                });
        boolean fallback = latest.values().stream().anyMatch(FxValuation::isFallback);

        List<String> converted = currencies.stream().map(ConvertedCurrency::currency).filter(c -> !leftOut.contains(c)).toList();
        StringBuilder note = new StringBuilder("All amounts in ").append(reporting).append('.');
        if (!converted.isEmpty()) {
            note.append(' ').append(String.join(", ", converted)).append(" converted to ").append(reporting)
                    .append(" at the rate of each document's date; documents not yet committed use the latest rate.");
        }
        if (fallback) {
            note.append(" The exchange-rate service was unavailable: built-in fallback rates are in use.");
        }
        if (unconverted > 0) {
            note.append(' ').append(unconverted).append(" document(s) in ").append(String.join(", ", leftOut))
                    .append(" left out: no exchange rate available.");
        }
        return new CurrencyConversion(reporting, currencies, fallback, note.toString());
    }

    // ============================================================
    // KPIs
    // ============================================================

    private CompletableFuture<Kpi> requisitionsKpi() {
        CompletableFuture<Long> awaiting = ask(() -> readModel.countRequisitions("SUBMITTED"));
        CompletableFuture<Long> approved = ask(() -> readModel.countRequisitions("APPROVED"));
        return awaiting.thenCombine(approved, (waiting, ready) -> new Kpi("requisitionsAwaiting", "Awaiting Approval",
                BigDecimal.valueOf(waiting), "requisitions", ready + " approved, not yet ordered",
                "/requisitions/approvals", "amber"));
    }

    private CompletableFuture<Kpi> ordersKpi(LocalDate today, Rates rates) {
        CompletableFuture<Long> open = ask(() -> readModel.countOrders(OPEN_ORDERS));
        CompletableFuture<Long> late = ask(() -> readModel.countOrdersExpectedBefore(OPEN_ORDERS, today));
        CompletableFuture<BigDecimal> value = ask(() -> readModel.sumOrders(OPEN_ORDERS, rates));
        return CompletableFuture.allOf(open, late, value).thenApply(all -> new Kpi("openOrders", "Open Orders",
                scale(value.join()), rates.reportingCurrency(), open.join() + " order(s), " + late.join() + " late",
                "/purchase-orders", late.join() > 0 ? "red" : "brand"));
    }

    private CompletableFuture<Kpi> receiptsKpi(LocalDate today) {
        YearMonth thisMonth = YearMonth.from(today);
        LocalDate from = thisMonth.atDay(1);
        LocalDate to = thisMonth.atEndOfMonth();
        CompletableFuture<Long> received = ask(() -> readModel.countReceipts(DONE_RECEIPTS, from, to, false));
        CompletableFuture<Long> rejected = ask(() -> readModel.countReceipts(DONE_RECEIPTS, from, to, true));
        return received.thenCombine(rejected, (all, withRejection) -> new Kpi("receiptsThisMonth", "Receipts This Month",
                BigDecimal.valueOf(all), "receipts", withRejection + " with rejected goods", "/goods-receipts",
                withRejection > 0 ? "orange" : "green"));
    }

    private static Kpi invoicesKpi(LocalDate today, String currency, List<DueGroup> payable) {
        BigDecimal toPay = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        long overdue = 0;
        for (DueGroup group : payable) {
            boolean late = group.dueDate() != null && group.dueDate().isBefore(today);
            if (late) overdue += group.invoices();
            toPay = toPay.add(group.outstanding());
            if (late) overdueAmount = overdueAmount.add(group.outstanding());
        }
        return new Kpi("invoicesToPay", "Invoices to Pay", scale(toPay), currency,
                overdue + " overdue: " + scale(overdueAmount) + " " + currency, "/invoices", overdue == 0 ? "blue" : "red");
    }

    private CompletableFuture<Kpi> paymentsKpi(LocalDate today, Rates rates) {
        YearMonth thisMonth = YearMonth.from(today);
        CompletableFuture<BigDecimal> paid = ask(() -> readModel.paymentsByMonth("COMPLETED", rates, thisMonth.atDay(1),
                thisMonth.atEndOfMonth()).stream().map(MonthAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        CompletableFuture<Long> toExecute = ask(() -> readModel.countPayments("PENDING"));
        return paid.thenCombine(toExecute, (amount, pending) -> new Kpi("paidThisMonth", "Paid This Month", scale(amount),
                rates.reportingCurrency(), pending + " payment(s) prepared, to execute", "/payments", "green"));
    }

    private CompletableFuture<Kpi> stockKpi() {
        CompletableFuture<Long> alerts = ask(() -> readModel.countStockAlerts(false));
        CompletableFuture<Long> out = ask(() -> readModel.countStockAlerts(true));
        return alerts.thenCombine(out, (all, empty) -> new Kpi("stockAlerts", "Stock Alerts", BigDecimal.valueOf(all),
                "materials", empty + " out of stock", "/materials", all > 0 ? "orange" : "green"));
    }

    private CompletableFuture<Kpi> returnsKpi() {
        CompletableFuture<Long> withSupplier = ask(() -> readModel.countReturns("PENDING"));
        CompletableFuture<Long> drafts = ask(() -> readModel.countReturns("DRAFT"));
        return withSupplier.thenCombine(drafts, (pending, draft) -> new Kpi("returnsOpen", "Returns with Suppliers",
                BigDecimal.valueOf(pending), "returns", draft + " draft(s) to ship", "/returns",
                pending > 0 ? "amber" : "green"));
    }

    // ============================================================
    // CHARTS
    // ============================================================

    /** Ordered (committed orders), invoiced (net of credit notes) and paid (executed payments) per month. */
    private CompletableFuture<MonthlyTrend> monthlyTrend(Access access, LocalDate today, Rates rates) {
        YearMonth last = YearMonth.from(today);
        YearMonth first = last.minusMonths(TREND_MONTHS - 1L);
        List<YearMonth> months = Stream.iterate(first, m -> m.plusMonths(1)).limit(TREND_MONTHS).toList();
        LocalDate from = first.atDay(1);
        LocalDate to = last.atEndOfMonth();

        CompletableFuture<List<BigDecimal>> ordered = access.orders
                ? ask(() -> perMonth(months, readModel.ordersByMonth(COMMITTED_ORDERS, rates, from, to)))
                : done(List.of());
        CompletableFuture<List<BigDecimal>> invoiced = access.invoices
                ? ask(() -> perMonth(months, readModel.invoicedByMonth(NOT_INVOICED, rates, from, to)))
                : done(List.of());
        CompletableFuture<List<BigDecimal>> paid = access.payments
                ? ask(() -> perMonth(months, readModel.paymentsByMonth("COMPLETED", rates, from, to)))
                : done(List.of());
        return CompletableFuture.allOf(ordered, invoiced, paid).thenApply(all -> new MonthlyTrend(
                months.stream().map(YearMonth::toString).toList(), ordered.join(), invoiced.join(), paid.join()));
    }

    /** Number of orders in each status, in lifecycle order, statuses with no order left out. */
    private List<Slice> orderStatus() {
        return readModel.ordersByStatus().entrySet().stream()
                .map(e -> new Slice(e.getKey(), ORDER_LABELS.getOrDefault(e.getKey(), e.getKey()), e.getValue(), null))
                .toList();
    }

    /** Committed spend per material category (top five, the rest grouped as "Other"). */
    private List<Slice> spendByCategory(Rates rates) {
        List<Group> ranked = readModel.spendByCategory(COMMITTED_ORDERS, rates).stream()
                .sorted(Comparator.comparing(Group::amount).reversed())
                .toList();
        List<Slice> slices = new ArrayList<>();
        BigDecimal other = BigDecimal.ZERO;
        long otherLines = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Group g = ranked.get(i);
            if (i < TOP_CATEGORIES) {
                slices.add(new Slice(g.key(), g.label(), g.count(), scale(g.amount())));
            } else {
                other = other.add(g.amount());
                otherLines += g.count();
            }
        }
        if (otherLines > 0) {
            slices.add(new Slice("OTHER", "Other", otherLines, scale(other)));
        }
        return slices;
    }

    /**
     * The suppliers with the most committed spend, with the quality and punctuality of their deliveries (asked for at
     * the same time as the spend, so even when there is none).
     */
    private CompletableFuture<List<SupplierStats>> topSuppliers(Rates rates) {
        CompletableFuture<List<Group>> spend = ask(() -> readModel.spendBySupplier(COMMITTED_ORDERS, rates));
        CompletableFuture<Map<UUID, ReceiptTotals>> receipts = ask(() -> readModel.receiptTotalsBySupplier(DONE_RECEIPTS));
        return spend.thenCombine(receipts, DashboardService::topSuppliers);
    }

    private static List<SupplierStats> topSuppliers(List<Group> spend, Map<UUID, ReceiptTotals> receipts) {
        List<Group> top = spend.stream()
                .sorted(Comparator.comparing(Group::amount).reversed())
                .limit(TOP_SUPPLIERS)
                .toList();
        return top.stream()
                .map(g -> {
                    ReceiptQuality quality = receiptQuality(receipts.getOrDefault(UUID.fromString(g.key()), ReceiptTotals.NONE));
                    return new SupplierStats(g.key(), g.label(), scale(g.amount()), g.count(),
                            quality.acceptanceRate(), quality.onTimeRate());
                })
                .toList();
    }

    /** Units accepted against units received, and receipts made by the expected delivery date. */
    private static ReceiptQuality receiptQuality(ReceiptTotals t) {
        return new ReceiptQuality(t.receipts(), t.unitsReceived(), t.unitsAccepted(), t.unitsRejected(),
                rate(t.unitsAccepted(), t.unitsReceived()), t.onTimeReceipts(), rate(t.onTimeReceipts(), t.datedReceipts()));
    }

    /** Outstanding amounts of unpaid standard invoices by days past due, in the reporting currency. */
    private static List<AgingBucket> invoiceAging(LocalDate today, List<DueGroup> payable) {
        String[][] buckets = {{"NOT_DUE", "Not due"}, {"1_30", "1-30 days"}, {"31_60", "31-60 days"}, {"60_PLUS", "Over 60 days"}};
        long[] counts = new long[4];
        BigDecimal[] amounts = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (DueGroup group : payable) {
            if (group.outstanding().signum() <= 0) continue;
            long late = group.dueDate() == null ? 0 : ChronoUnit.DAYS.between(group.dueDate(), today);
            int bucket = late <= 0 ? 0 : late <= 30 ? 1 : late <= 60 ? 2 : 3;
            counts[bucket] += group.invoices();
            amounts[bucket] = amounts[bucket].add(group.outstanding());
        }
        List<AgingBucket> result = new ArrayList<>();
        for (int b = 0; b < buckets.length; b++) {
            result.add(new AgingBucket(buckets[b][0], buckets[b][1], counts[b], scale(amounts[b])));
        }
        return result;
    }

    /** Active materials at or below their reorder level, the most urgent first. */
    private List<StockAlert> stockAlerts() {
        return readModel.stockAlerts(MAX_STOCK_ALERTS).stream()
                .map(m -> new StockAlert(
                        m.id() != null ? m.id().toString() : null,
                        m.code(),
                        m.name(),
                        stockStatus(m),
                        m.currentStock(),
                        m.reorderPoint(),
                        m.safetyStock(),
                        m.stockOnOrder(),
                        m.unit()))
                .toList();
    }

    /** Same rule as Material#getStockStatus, for a row that is known to be an alert. */
    private static String stockStatus(StockRow m) {
        if (m.currentStock() <= 0) return "OUT_OF_STOCK";
        if (m.safetyStock() != null && m.currentStock() <= m.safetyStock()) return "CRITICAL";
        return "REORDER_NEEDED";
    }

    /**
     * The latest documents created in the modules the user can read; a module whose service does not answer is left
     * out, the others are shown.
     */
    private CompletableFuture<List<Activity>> recentActivity(Access access, Set<String> unavailable) {
        List<CompletableFuture<List<Activity>>> modules = new ArrayList<>();
        if (access.requisitions) modules.add(activity(ActivitySource.REQUISITION, "REQUISITION", "/requisitions/view/"));
        if (access.orders) modules.add(activity(ActivitySource.PURCHASE_ORDER, "PURCHASE_ORDER", "/purchase-orders/"));
        if (access.receipts) modules.add(activity(ActivitySource.GOODS_RECEIPT, "GOODS_RECEIPT", "/goods-receipts/"));
        if (access.invoices) modules.add(activity(ActivitySource.INVOICE, "INVOICE", "/invoices/"));
        if (access.payments) modules.add(activity(ActivitySource.PAYMENT, "PAYMENT", "/payments/"));
        if (access.returns) modules.add(activity(ActivitySource.RETURN, "RETURN", "/returns/"));
        modules.replaceAll(module -> shown(module, "recentActivity", List.of(), unavailable));
        return CompletableFuture.allOf(modules.toArray(CompletableFuture[]::new)).thenApply(all -> modules.stream()
                .flatMap(module -> module.join().stream())
                .sorted(Comparator.comparing(Activity::date).reversed())
                .limit(MAX_ACTIVITY)
                .toList());
    }

    private CompletableFuture<List<Activity>> activity(ActivitySource source, String type, String link) {
        return ask(() -> readModel.latest(source, MAX_ACTIVITY).stream()
                .map(row -> new Activity(type, row.code() != null ? row.code() : "—", row.title(), row.status(),
                        row.createdAt(), link + row.id()))
                .toList());
    }

    // ============================================================
    // HELPERS
    // ============================================================

    /** One amount per month of the trend, zero where the database returned nothing. */
    private static List<BigDecimal> perMonth(List<YearMonth> months, List<MonthAmount> amounts) {
        Map<YearMonth, BigDecimal> byMonth = new LinkedHashMap<>();
        months.forEach(m -> byMonth.put(m, BigDecimal.ZERO));
        amounts.forEach(a -> byMonth.computeIfPresent(a.month(), (m, v) -> v.add(a.amount())));
        return byMonth.values().stream().map(DashboardService::scale).toList();
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static Double rate(long part, long whole) {
        if (whole <= 0) return null;
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** What the user may read, from their permissions. */
    private static final class Access {
        final boolean requisitions;
        final boolean orders;
        final boolean receipts;
        final boolean invoices;
        final boolean payments;
        final boolean returns;
        final boolean materials;

        Access(Set<String> permissions) {
            this.requisitions = permissions.contains("requisition:read");
            this.orders = permissions.contains("order:read");
            this.receipts = permissions.contains("receipt:read");
            this.invoices = permissions.contains("invoice:read");
            this.payments = permissions.contains("payment:read");
            this.returns = permissions.contains("return:read");
            this.materials = permissions.contains("material:read");
        }
    }
}
