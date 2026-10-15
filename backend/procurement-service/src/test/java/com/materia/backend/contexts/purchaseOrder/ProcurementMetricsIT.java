package com.materia.backend.contexts.purchaseOrder;

import com.materia.backend.common.application.metrics.CountedAmount;
import com.materia.backend.common.application.metrics.MonthAmount;
import com.materia.backend.common.application.metrics.ReportingRates;
import com.materia.backend.contexts.goodsReceipt.application.dtos.ReceiptTotals;
import com.materia.backend.contexts.goodsReceipt.domain.ports.in.GoodsReceiptMetricsUseCase;
import com.materia.backend.contexts.purchaseOrder.application.dtos.MaterialSpend;
import com.materia.backend.contexts.purchaseOrder.domain.ports.in.PurchaseOrderMetricsUseCase;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.RequisitionMetricsUseCase;
import com.materia.backend.contexts.returnToVendor.domain.ports.in.ReturnMetricsUseCase;
import com.materia.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The figures procurement gives the dashboard (procurement.v1.ProcurementMetrics), on its own tables: one scenario of
 * requisitions, orders, receipts and returns. The dashboard's test (analytics) composes the same figures.
 *
 * <p>The test runs in a transaction that is rolled back. It first empties the document tables (inside that
 * transaction), so documents other tests committed to the shared database cannot change the totals.
 */
class ProcurementMetricsIT extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final Set<String> COMMITTED = Set.of("SUBMITTED", "CONFIRMED", "READY_FOR_RECEIPT",
            "PARTIALLY_RECEIVED", "COMPLETED");
    private static final Set<String> OPEN = Set.of("SUBMITTED", "CONFIRMED", "READY_FOR_RECEIPT", "PARTIALLY_RECEIVED");
    private static final Set<String> DONE_RECEIPTS = Set.of("COMPLETED", "PARTIAL");
    private static final ReportingRates RATES = new ReportingRates("MAD", Map.of("EUR", new BigDecimal("10.80")));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RequisitionMetricsUseCase requisitions;
    @Autowired private PurchaseOrderMetricsUseCase orders;
    @Autowired private GoodsReceiptMetricsUseCase receipts;
    @Autowired private ReturnMetricsUseCase returns;

    private UUID eurOrder;
    private final UUID acme = UUID.randomUUID();
    private final UUID globex = UUID.randomUUID();
    private final UUID bolts = UUID.randomUUID();
    private int sequence;

    @BeforeEach
    void scenario() {
        emptyDocumentTables();

        requisition("SUBMITTED", 1);
        requisition("SUBMITTED", 2);
        requisition("APPROVED", 3);

        UUID confirmed = order(acme, "Acme", "CONFIRMED", "1000.00", "MAD", TODAY.minusDays(3), TODAY.minusDays(1), bolts, "MAT-1");
        order(acme, "Acme", "COMPLETED", "500.00", "MAD", TODAY.minusMonths(2), TODAY.minusMonths(1), bolts, "MAT-1");
        UUID ready = order(globex, "Globex", "READY_FOR_RECEIPT", "300.00", "MAD", TODAY.minusDays(10), TODAY.plusDays(5), null, "MAT-2");
        order(globex, "Globex", "CANCELLED", "999.00", "MAD", TODAY.minusDays(9), null, null, "MAT-2");
        eurOrder = order(globex, "Globex", "CONFIRMED", "80.00", "EUR", TODAY.minusDays(2), null, null, "MAT-2");
        // Confirmed, so it carries the rate of its own date (11.00), which differs from the latest rate (10.80).
        valued(eurOrder, "11.00", "MAD");

        receipt(confirmed, acme, "COMPLETED", 10, 8, TODAY.minusDays(1), TODAY);
        receipt(confirmed, acme, "PARTIAL", 10, 10, TODAY.minusMonths(1), TODAY.minusMonths(1));
        receipt(ready, globex, "DRAFT", 5, 5, null, null);

        returnToVendor("PENDING");
        returnToVendor("DRAFT");
    }

    // ---- Tests ----

    @Test
    @DisplayName("requisitions and returns: counted by status")
    void requisitionsAndReturns() {
        assertEquals(2, requisitions.countInStatus("SUBMITTED"));
        assertEquals(1, requisitions.countInStatus("APPROVED"));
        assertEquals(1, returns.countInStatus("PENDING"));
        assertEquals(1, returns.countInStatus("DRAFT"));
    }

    @Test
    @DisplayName("open orders: counted, late ones too, and valued in MAD; a committed order uses the rate of its own date")
    void openOrders() {
        assertEquals(3, orders.count(OPEN));
        assertEquals(1, orders.countExpectedBefore(OPEN, TODAY), "expected yesterday");
        assertEquals(0, new BigDecimal("2180.00").compareTo(orders.sum(OPEN, RATES)), "1300 MAD + 80 EUR x 11.00");
        assertEquals(Map.of("MAD", 4L, "EUR", 1L), orders.documentsByCurrency());
        assertEquals(Map.of("EUR", 1L), orders.valuedAtOwnDate("MAD"));
    }

    @Test
    @DisplayName("open orders: without its own copy, or with one made for another reporting currency, the latest rate is used")
    void openOrders_latestRateWithoutCopy() {
        jdbc.update("update purchase_orders set fx_rate_to_reporting = null, fx_reporting_currency = null where id = ?", eurOrder);
        assertEquals(0, new BigDecimal("2164.00").compareTo(orders.sum(OPEN, RATES)), "80 x 10.80");

        valued(eurOrder, "1.08", "USD");
        assertEquals(0, new BigDecimal("2164.00").compareTo(orders.sum(OPEN, RATES)), "a USD copy is ignored");
        assertFalse(orders.valuedAtOwnDate("MAD").containsKey("EUR"));
    }

    @Test
    @DisplayName("open orders: a currency with no rate at all is left out of the value")
    void openOrders_noRateLeftOut() {
        jdbc.update("update purchase_orders set fx_rate_to_reporting = null, fx_reporting_currency = null where id = ?", eurOrder);

        assertEquals(0, new BigDecimal("1300.00").compareTo(orders.sum(OPEN, new ReportingRates("MAD", Map.of()))));
    }

    @Test
    @DisplayName("orders: committed per month (cancelled excluded, EUR converted), by status, by supplier and by material")
    void ordersByMonthStatusSupplierMaterial() {
        Map<YearMonth, BigDecimal> months = orders.byMonth(COMMITTED, RATES, LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31))
                .stream().collect(Collectors.toMap(MonthAmount::month, MonthAmount::amount, BigDecimal::add));
        assertEquals(0, new BigDecimal("2180.00").compareTo(months.get(YearMonth.of(2026, 10))));
        assertEquals(0, new BigDecimal("500.00").compareTo(months.get(YearMonth.of(2026, 8))));

        assertEquals(Map.of("CONFIRMED", 2L, "READY_FOR_RECEIPT", 1L, "COMPLETED", 1L, "CANCELLED", 1L), orders.countByStatus());
        assertEquals(List.of("CONFIRMED", "READY_FOR_RECEIPT", "COMPLETED", "CANCELLED"),
                List.copyOf(orders.countByStatus().keySet()), "lifecycle order");

        Map<String, CountedAmount> suppliers = orders.spendBySupplier(COMMITTED, RATES).stream()
                .collect(Collectors.toMap(CountedAmount::key, Function.identity()));
        assertEquals(2, suppliers.get(acme.toString()).count());
        assertEquals(0, new BigDecimal("1500.00").compareTo(suppliers.get(acme.toString()).amount()));
        assertEquals(0, new BigDecimal("1180.00").compareTo(suppliers.get(globex.toString()).amount()), "300 MAD + 80 EUR x 11.00");

        List<MaterialSpend> materials = orders.spendByMaterial(COMMITTED, RATES);
        MaterialSpend byId = materials.stream().filter(m -> bolts.equals(m.materialId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1500.00").compareTo(byId.amount()));
        MaterialSpend byCode = materials.stream().filter(m -> m.materialId() == null && "MAT-2".equals(m.materialCode()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1180.00").compareTo(byCode.amount()), "matched by code when a line has no id");
    }

    @Test
    @DisplayName("receipts: validated ones counted this month, totals with acceptance and punctuality, by supplier")
    void receiptsTotals() {
        LocalDate from = TODAY.withDayOfMonth(1);
        LocalDate to = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        assertEquals(1, receipts.count(DONE_RECEIPTS, from, to, false));
        assertEquals(1, receipts.count(DONE_RECEIPTS, from, to, true), "8 of 10 accepted");

        ReceiptTotals totals = receipts.totals(DONE_RECEIPTS);
        assertEquals(2, totals.receipts(), "draft receipts are not counted");
        assertEquals(20, totals.unitsReceived());
        assertEquals(18, totals.unitsAccepted());
        assertEquals(2, totals.unitsRejected());
        assertEquals(2, totals.datedReceipts());
        assertEquals(1, totals.onTimeReceipts(), "today's receipt came after the expected day");

        Map<UUID, ReceiptTotals> bySupplier = receipts.totalsBySupplier(DONE_RECEIPTS);
        assertEquals(totals, bySupplier.get(acme));
        assertFalse(bySupplier.containsKey(globex), "Globex has no validated receipt");
    }

    // ---- Scenario ----

    /** Removes every procurement document inside the test's transaction; the rollback brings them back. */
    private void emptyDocumentTables() {
        for (String sql : List.of(
                "delete from return_to_vendor_lines", "delete from return_to_vendor",
                "delete from goods_receipt_lines", "delete from goods_receipts",
                "update purchase_requisitions set purchase_order_id = null",
                "delete from purchase_order_lines", "delete from purchase_orders",
                "delete from purchase_requisition_lines", "delete from purchase_requisitions")) {
            jdbc.update(sql);
        }
    }

    private void valued(UUID order, String rate, String reporting) {
        jdbc.update("update purchase_orders set fx_rate_to_reporting = ?, fx_reporting_currency = ?, fx_rate_date = ?, "
                + "fx_rate_source = 'LIVE' where id = ?", new BigDecimal(rate), reporting, Date.valueOf(TODAY.minusDays(2)), order);
    }

    private String code(String prefix) {
        return prefix + "-2026-" + String.format("%04d", 9000 + ++sequence);
    }

    private void requisition(String status, int hoursAgo) {
        jdbc.update("insert into purchase_requisitions (id, requisition_code, title, status, requester_id, requester_name, "
                        + "created_at, version) values (?, ?, ?, ?, 'requester-1', 'Rita', ?, 0)",
                UUID.randomUUID(), code("REQ"), "Request " + hoursAgo, status,
                Timestamp.valueOf(TODAY.atTime(9, 0).minusHours(hoursAgo)));
    }

    private UUID order(UUID supplier, String name, String status, String total, String currency, LocalDate ordered,
                       LocalDate expected, UUID material, String materialCode) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into purchase_orders (id, order_code, status, delivery_status, supplier_id, supplier_name, "
                        + "order_date, expected_delivery_date, currency_code, total_amount, grand_total, created_at, version) "
                        + "values (?, ?, ?, 'NOT_SHIPPED', ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, code("PO"), status, supplier, name, Date.valueOf(ordered), expected != null ? Date.valueOf(expected) : null,
                currency, new BigDecimal(total), new BigDecimal(total), Timestamp.valueOf(ordered.atStartOfDay()));
        jdbc.update("insert into purchase_order_lines (id, purchase_order_id, line_number, material_id, material_code, "
                        + "quantity, unit_price, line_total, currency_code, created_at, version) "
                        + "values (?, ?, 1, ?, ?, 1, ?, ?, ?, now(), 0)",
                UUID.randomUUID(), id, material, materialCode, new BigDecimal(total), new BigDecimal(total), currency);
        return id;
    }

    private void receipt(UUID order, UUID supplier, String status, int received, int accepted, LocalDate expected,
                         LocalDate receivedOn) {
        jdbc.update("insert into goods_receipts (id, receipt_code, purchase_order_id, status, received_by, received_by_name, "
                        + "supplier_id, total_quantity_received, total_quantity_accepted, total_quantity_rejected, "
                        + "expected_delivery_date, receipt_date, has_discrepancy, created_at, version) "
                        + "values (?, ?, ?, ?, 'receiver-1', 'Rita', ?, ?, ?, ?, ?, ?, false, ?, 0)",
                UUID.randomUUID(), code("GR"), order, status, supplier, received, accepted, received - accepted,
                expected != null ? Date.valueOf(expected) : null, receivedOn != null ? Date.valueOf(receivedOn) : null,
                Timestamp.valueOf((receivedOn != null ? receivedOn : TODAY.minusDays(20)).atStartOfDay()));
    }

    private void returnToVendor(String status) {
        jdbc.update("insert into return_to_vendor (id, return_code, status, supplier_id, supplier_name, created_at, version) "
                        + "values (?, ?, ?, ?, 'Acme', ?, 0)",
                UUID.randomUUID(), code("RTN"), status, acme, Timestamp.valueOf(TODAY.minusDays(20).atStartOfDay()));
    }
}
