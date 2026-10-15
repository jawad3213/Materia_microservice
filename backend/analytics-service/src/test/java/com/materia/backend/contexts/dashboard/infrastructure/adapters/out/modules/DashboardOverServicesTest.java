package com.materia.backend.contexts.dashboard.infrastructure.adapters.out.modules;

import com.materia.backend.common.application.metrics.CountedAmount;
import com.materia.backend.common.application.metrics.RecentDocument;
import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.fx.FxDocument;
import com.materia.backend.common.domain.fx.FxValuation;
import com.materia.backend.common.domain.fx.ReportingFx;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput;
import com.materia.backend.contexts.dashboard.application.dtos.DashboardOutput.Kpi;
import com.materia.backend.contexts.dashboard.application.services.DashboardService;
import com.materia.backend.support.fixtures.InProcessGrpc;
import com.materia.backend.support.fixtures.TestFinanceMetrics;
import com.materia.backend.support.fixtures.TestMasterDataMetrics;
import com.materia.backend.support.fixtures.TestProcurementMetrics;
import com.materia.backend.support.fixtures.TestProcurementMetrics.MaterialSpend;
import com.materia.backend.support.fixtures.TestProcurementMetrics.Totals;
import com.materia.proto.finance.v1.FinanceMetricsGrpc;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.procurement.v1.ProcurementMetricsGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.materia.backend.support.fixtures.TestFinanceMetrics.month;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard composed from the other services' figures, read through their contracts (procurement.v1, finance.v1,
 * masterdata.v1) as in production: one scenario of every document type, checked figure by figure. How each service
 * computes its figures is its own tests' business (ProcurementMetricsIT, FinanceMetricsIT, JpaMaterialMetricsIT, on
 * the same scenario); this test checks what the dashboard makes of them.
 */
class DashboardOverServicesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final Set<String> ADMIN = Set.of("requisition:read", "order:read", "receipt:read", "invoice:read",
            "payment:read", "return:read", "material:read", "dashboard:read");
    private static final Set<String> RECEIVER = Set.of("requisition:read", "order:read", "receipt:read", "return:read",
            "material:read", "dashboard:read");

    private final TestProcurementMetrics procurement = new TestProcurementMetrics();
    private final TestFinanceMetrics finance = new TestFinanceMetrics();
    private final TestMasterDataMetrics masterData = new TestMasterDataMetrics();
    private final StubFx fx = new StubFx();
    private DashboardService service;

    private final UUID acme = UUID.randomUUID();
    private final UUID globex = UUID.randomUUID();
    private final UUID bolts = UUID.randomUUID();
    private int sequence;

    @BeforeEach
    void scenario() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new DashboardService(new ModuleMetricsReadModel(
                ProcurementMetricsGrpc.newBlockingStub(InProcessGrpc.serving(procurement)),
                FinanceMetricsGrpc.newBlockingStub(InProcessGrpc.serving(finance)),
                MasterDataMetricsGrpc.newBlockingStub(InProcessGrpc.serving(masterData))), fx, clock);
        fx.rates.put(CurrencyCode.EUR, new FxValuation(CurrencyCode.EUR, CurrencyCode.MAD, bd("10.80"), TODAY, FxValuation.LIVE));

        // Procurement: requisitions (2 submitted, 1 approved); orders from Acme (1000.00 confirmed, expected yesterday;
        // 500.00 completed in August) and Globex (300.00 ready for receipt, 999.00 cancelled, 80.00 EUR confirmed and
        // valued at the 11.00 of its own date); two validated receipts from Acme (10 received, 8 accepted, today and
        // late; 10 of 10 last month, on time); returns (1 with the supplier, 1 draft).
        procurement
                .requisitions(Map.of("SUBMITTED", 2L, "APPROVED", 1L))
                .orderCurrencies(Map.of("MAD", 4L, "EUR", 1L), Map.of("EUR", 1L))
                .openOrders(3, 1, "2180.00")
                .ordered(List.of(month("2026-08", "500.00"), month("2026-10", "2180.00")))
                .ordersByStatus(lifecycle("CONFIRMED", 2L, "READY_FOR_RECEIPT", 1L, "COMPLETED", 1L, "CANCELLED", 1L))
                .spend(List.of(new MaterialSpend(bolts, "MAT-1", 2, "1500.00"), new MaterialSpend(null, "MAT-2", 3, "1180.00")),
                        List.of(new CountedAmount(acme.toString(), "Acme", 2, bd("1500.00")),
                                new CountedAmount(globex.toString(), "Globex", 2, bd("1180.00"))))
                .receipts(1, 1, new Totals(2, 20, 18, 2, 2, 1), Map.of(acme, new Totals(2, 20, 18, 2, 2, 1)))
                .returns(Map.of("PENDING", 1L, "DRAFT", 1L))
                .latest(List.of(document("REQ", TODAY.atTime(8, 0)), document("REQ", TODAY.atTime(7, 0)),
                                document("REQ", TODAY.atTime(6, 0))),
                        List.of(document("PO", TODAY.minusDays(2).atStartOfDay()), document("PO", TODAY.minusDays(3).atStartOfDay()),
                                document("PO", TODAY.minusDays(9).atStartOfDay()), document("PO", TODAY.minusDays(10).atStartOfDay()),
                                document("PO", TODAY.minusMonths(2).atStartOfDay())),
                        List.of(document("GR", TODAY.atStartOfDay()), document("GR", TODAY.minusDays(20).atStartOfDay()),
                                document("GR", TODAY.minusMonths(1).atStartOfDay())),
                        List.of(document("RTN", TODAY.minusDays(20).atStartOfDay()), document("RTN", TODAY.minusDays(20).atStartOfDay())));

        // Master data: MAT-1 Bolts and MAT-5 Screws are fasteners, MAT-2 Paint a coating; MAT-2, MAT-3 Glue and MAT-1
        // are below their reorder point, most urgent first.
        UUID paint = UUID.randomUUID();
        masterData.answer(
                List.of(new TestMasterDataMetrics.Alert(paint.toString(), "MAT-2", "Paint", 0, 5, null),
                        new TestMasterDataMetrics.Alert(UUID.randomUUID().toString(), "MAT-3", "Glue", 1, 4, 2),
                        new TestMasterDataMetrics.Alert(bolts.toString(), "MAT-1", "Bolts", 8, 10, null)),
                Map.of(bolts.toString(), "Fasteners", paint.toString(), "Coatings"),
                Map.of("MAT-1", "Fasteners", "MAT-2", "Coatings", "MAT-5", "Fasteners"));

        // Finance: a verified invoice of 400.00 due 40 days ago, a submitted one of 200.00 (50.00 paid) due in 10 days,
        // a credit note of 50.00 this month, an invoice of 100.00 paid in July; payments of 250.00 this month and 100.00
        // last month, executed, and one of 75.00 to execute.
        finance
                .outstanding(List.of(
                        new TestFinanceMetrics.DueGroup(TODAY.minusDays(40), "MAD", 1, "400.00"),
                        new TestFinanceMetrics.DueGroup(TODAY.plusDays(10), "MAD", 1, "150.00")))
                .invoiced(List.of(month("2026-07", "100.00"), month("2026-09", "400.00"), month("2026-10", "150.00")))
                .paid(List.of(month("2026-09", "100.00"), month("2026-10", "250.00")))
                .pendingPayments(1)
                .latest(List.of(document("INV", TODAY.minusDays(1).atStartOfDay()), document("INV", TODAY.minusDays(5).atStartOfDay())),
                        List.of(document("PAY", LocalDateTime.of(2026, 1, 1, 0, 0))))
                .documents(Map.of("MAD", 4L), Map.of("MAD", 3L));
    }

    // ---- Tests ----

    @Test
    @DisplayName("currency: other currencies are converted; a committed document uses the rate of its own date")
    void currency_convertedAtTheDocumentRate() {
        DashboardOutput out = service.getOverview(ADMIN);

        assertEquals("MAD", out.currency());
        assertEquals(0, out.otherCurrencyDocuments(), "nothing left out");
        assertEquals(0, bd("2180.00").compareTo(byKey(out).get("openOrders").value()), "1300 MAD + 80 EUR x 11.00");
        DashboardOutput.ConvertedCurrency euro = out.conversion().currencies().get(0);
        assertEquals("EUR", euro.currency());
        assertEquals(1, euro.documents());
        assertEquals(1, euro.valuedAtDocumentDate());
        assertEquals(0, bd("10.80").compareTo(euro.latestRate()));
        assertFalse(out.conversion().fallbackRates());
    }

    @Test
    @DisplayName("currency: without its own copy, the latest rate values the document, and the dashboard says so")
    void currency_latestRateWithoutCopy() {
        procurement.orderCurrencies(Map.of("MAD", 4L, "EUR", 1L), Map.of()).openOrders(3, 1, "2164.00");

        DashboardOutput out = service.getOverview(ADMIN);

        assertEquals(0, bd("2164.00").compareTo(byKey(out).get("openOrders").value()), "80 x 10.80");
        assertEquals(0, out.conversion().currencies().get(0).valuedAtDocumentDate());
    }

    @Test
    @DisplayName("currency: a currency with no rate at all is left out, counted and explained")
    void currency_noRateLeftOut() {
        procurement.orderCurrencies(Map.of("MAD", 4L, "EUR", 1L), Map.of()).openOrders(3, 1, "1300.00");
        fx.rates.clear();

        DashboardOutput out = service.getOverview(ADMIN);

        assertEquals(1, out.otherCurrencyDocuments());
        assertEquals(0, bd("1300.00").compareTo(byKey(out).get("openOrders").value()));
        assertTrue(out.conversion().note().contains("1 document(s) in EUR left out"), out.conversion().note());
    }

    @Test
    @DisplayName("KPIs: approvals, open and late orders, receipts, invoices to pay and overdue, payments, stock and returns")
    void kpis() {
        Map<String, Kpi> kpis = byKey(service.getOverview(ADMIN));

        assertEquals(7, kpis.size());
        assertEquals(0, bd("2").compareTo(kpis.get("requisitionsAwaiting").value()));
        assertEquals("1 approved, not yet ordered", kpis.get("requisitionsAwaiting").hint());
        assertEquals("3 order(s), 1 late", kpis.get("openOrders").hint());
        assertEquals("red", kpis.get("openOrders").tone());
        assertEquals(0, bd("1").compareTo(kpis.get("receiptsThisMonth").value()));
        assertEquals(0, bd("550.00").compareTo(kpis.get("invoicesToPay").value()));
        assertTrue(kpis.get("invoicesToPay").hint().startsWith("1 overdue: 400.00"), kpis.get("invoicesToPay").hint());
        assertEquals(0, bd("250.00").compareTo(kpis.get("paidThisMonth").value()));
        assertEquals("1 payment(s) prepared, to execute", kpis.get("paidThisMonth").hint());
        assertEquals(0, bd("3").compareTo(kpis.get("stockAlerts").value()));
        assertEquals("1 out of stock", kpis.get("stockAlerts").hint());
        assertEquals(0, bd("1").compareTo(kpis.get("returnsOpen").value()));
    }

    @Test
    @DisplayName("trend: twelve months of what was ordered, invoiced and paid")
    void monthlyTrend() {
        DashboardOutput.MonthlyTrend trend = service.getOverview(ADMIN).monthlyTrend();

        assertEquals(12, trend.months().size());
        assertEquals("2025-11", trend.months().get(0));
        assertEquals("2026-10", trend.months().get(11));
        assertEquals(0, bd("2180.00").compareTo(trend.ordered().get(11)));
        assertEquals(0, bd("500.00").compareTo(trend.ordered().get(9)));
        assertEquals(0, bd("150.00").compareTo(trend.invoiced().get(11)));
        assertEquals(0, bd("400.00").compareTo(trend.invoiced().get(10)));
        assertEquals(0, bd("100.00").compareTo(trend.invoiced().get(8)));
        assertEquals(0, bd("250.00").compareTo(trend.paid().get(11)));
        assertEquals(0, bd("100.00").compareTo(trend.paid().get(10)));
    }

    @Test
    @DisplayName("orders: status distribution, top suppliers with quality, and spend by material category")
    void ordersSections() {
        DashboardOutput out = service.getOverview(ADMIN);

        Map<String, Long> status = out.orderStatus().stream().collect(Collectors.toMap(DashboardOutput.Slice::key, DashboardOutput.Slice::count));
        assertEquals(Map.of("CONFIRMED", 2L, "READY_FOR_RECEIPT", 1L, "COMPLETED", 1L, "CANCELLED", 1L), status);
        assertEquals(List.of("CONFIRMED", "READY_FOR_RECEIPT", "COMPLETED", "CANCELLED"),
                out.orderStatus().stream().map(DashboardOutput.Slice::key).toList(), "lifecycle order");

        assertEquals("Acme", out.topSuppliers().get(0).supplierName());
        assertEquals(0, bd("1500.00").compareTo(out.topSuppliers().get(0).spend()));
        assertEquals(2, out.topSuppliers().get(0).orders());
        assertEquals(90.0, out.topSuppliers().get(0).acceptanceRate());
        assertEquals(50.0, out.topSuppliers().get(0).onTimeRate());
        assertEquals("Globex", out.topSuppliers().get(1).supplierName());
        assertNull(out.topSuppliers().get(1).acceptanceRate(), "Globex has no completed receipt");

        assertEquals("Fasteners", out.spendByCategory().get(0).label());
        assertEquals(0, bd("1500.00").compareTo(out.spendByCategory().get(0).amount()));
        assertEquals("Coatings", out.spendByCategory().get(1).label(), "matched by material code when the line has no material id");
        assertEquals(0, bd("1180.00").compareTo(out.spendByCategory().get(1).amount()));
    }

    @Test
    @DisplayName("receipts and invoices: acceptance and on-time rates, and outstanding amounts by days overdue")
    void qualityAndAging() {
        DashboardOutput out = service.getOverview(ADMIN);

        DashboardOutput.ReceiptQuality q = out.receiptQuality();
        assertEquals(2, q.receipts());
        assertEquals(20, q.unitsReceived());
        assertEquals(2, q.unitsRejected());
        assertEquals(90.0, q.acceptanceRate());
        assertEquals(50.0, q.onTimeRate());

        Map<String, BigDecimal> aging = out.invoiceAging().stream()
                .collect(Collectors.toMap(DashboardOutput.AgingBucket::key, DashboardOutput.AgingBucket::amount));
        assertEquals(0, bd("150.00").compareTo(aging.get("NOT_DUE")));
        assertEquals(0, bd("400.00").compareTo(aging.get("31_60")));
        assertEquals(0, BigDecimal.ZERO.compareTo(aging.get("60_PLUS")));
    }

    @Test
    @DisplayName("stock and activity: active alerts most urgent first, and the latest documents across services")
    void stockAndActivity() {
        DashboardOutput out = service.getOverview(ADMIN);

        assertEquals(List.of("MAT-2", "MAT-3", "MAT-1"), out.stockAlerts().stream().map(DashboardOutput.StockAlert::code).toList());
        assertEquals(List.of("OUT_OF_STOCK", "CRITICAL", "REORDER_NEEDED"),
                out.stockAlerts().stream().map(DashboardOutput.StockAlert::status).toList());
        assertEquals(10, out.recentActivity().size());
        assertEquals("REQUISITION", out.recentActivity().get(0).type());
        assertTrue(out.recentActivity().get(0).link().startsWith("/requisitions/view/"));
        assertTrue(out.recentActivity().stream().anyMatch(a -> a.type().equals("INVOICE")));
        for (int i = 1; i < out.recentActivity().size(); i++) {
            assertFalse(out.recentActivity().get(i).date().isAfter(out.recentActivity().get(i - 1).date()), "newest first");
        }
    }

    @Test
    @DisplayName("access: a receiver sees no invoice or payment figures")
    void receiver_seesNoFinance() {
        DashboardOutput out = service.getOverview(RECEIVER);

        Set<String> keys = byKey(out).keySet();
        assertFalse(keys.contains("invoicesToPay"));
        assertFalse(keys.contains("paidThisMonth"));
        assertTrue(out.invoiceAging().isEmpty());
        assertTrue(out.monthlyTrend().invoiced().isEmpty());
        assertTrue(out.monthlyTrend().paid().isEmpty());
        assertFalse(out.monthlyTrend().ordered().isEmpty());
        assertTrue(out.recentActivity().stream().noneMatch(a -> a.type().equals("INVOICE") || a.type().equals("PAYMENT")));
    }

    // ---- Scenario ----

    /** Fixed latest rates; no history or exchange-rate service involved. */
    private static final class StubFx implements ReportingFx {
        final Map<CurrencyCode, FxValuation> rates = new java.util.EnumMap<>(CurrencyCode.class);

        @Override public CurrencyCode reportingCurrency() { return CurrencyCode.MAD; }
        @Override public FxValuation valuationOn(CurrencyCode from, LocalDate date) { return rates.get(from); }
        @Override public Map<CurrencyCode, FxValuation> currentRates() { return Map.copyOf(rates); }
        @Override public void refreshDailyRates() { }
        @Override public void valueDocument(FxDocument kind, UUID id) { }
        @Override public int backfill() { return 0; }
    }

    private RecentDocument document(String prefix, LocalDateTime created) {
        return new RecentDocument(UUID.randomUUID(), prefix + "-2026-" + String.format("%04d", 9000 + ++sequence),
                null, "SUBMITTED", created);
    }

    /** Counts per status in the order given, as procurement answers them (lifecycle order). */
    private static Map<String, Long> lifecycle(Object... statusAndCount) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < statusAndCount.length; i += 2) counts.put((String) statusAndCount[i], (Long) statusAndCount[i + 1]);
        return counts;
    }

    private static Map<String, Kpi> byKey(DashboardOutput out) {
        return out.kpis().stream().collect(Collectors.toMap(Kpi::key, k -> k));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
