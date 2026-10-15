package com.materia.backend.contexts.invoice.infrastructure.adapters.in.metrics;

import com.materia.backend.common.application.metrics.MonthAmount;
import com.materia.backend.common.application.metrics.ReportingRates;
import com.materia.backend.contexts.invoice.application.dtos.OutstandingByDueDate;
import com.materia.backend.contexts.invoice.domain.ports.in.InvoiceMetricsUseCase;
import com.materia.backend.contexts.payment.domain.ports.in.PaymentMetricsUseCase;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The figures finance gives the dashboard (finance.v1.FinanceMetrics), on its own tables: what is left to pay and when
 * it is due, what was invoiced and paid each month, and how many payments wait to be executed.
 */
class FinanceMetricsIT extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final ReportingRates MAD = new ReportingRates("MAD", Map.of());

    @Autowired private InvoiceMetricsUseCase invoices;
    @Autowired private PaymentMetricsUseCase payments;
    @Autowired private JdbcTemplate jdbc;

    private final UUID acme = UUID.randomUUID();
    private int sequence;

    @BeforeEach
    void documents() {
        for (String sql : List.of("delete from payment_lines", "delete from payments", "delete from invoice_lines",
                "delete from invoices")) {
            jdbc.update(sql);
        }
        invoice("STANDARD", "VERIFIED", "400.00", null, TODAY.minusDays(40), TODAY.minusDays(30));
        invoice("STANDARD", "SUBMITTED", "200.00", "50.00", TODAY.plusDays(10), TODAY.minusDays(5));
        invoice("CREDIT_NOTE", "VERIFIED", "50.00", null, null, TODAY.minusDays(1));
        invoice("STANDARD", "PAID", "100.00", "100.00", TODAY.minusDays(70), TODAY.minusMonths(3));

        payment("COMPLETED", "250.00", TODAY.minusDays(2));
        payment("COMPLETED", "100.00", TODAY.minusMonths(1));
        payment("PENDING", "75.00", null);
    }

    @Test
    @DisplayName("outstanding: payable standard invoices by due date, less what was paid; credit notes and paid ones excluded")
    void outstanding() {
        List<OutstandingByDueDate> groups = invoices.outstandingByDueDate("STANDARD", Set.of("SUBMITTED", "VERIFIED"), MAD)
                .stream().sorted(Comparator.comparing(OutstandingByDueDate::dueDate)).toList();

        assertEquals(2, groups.size());
        assertEquals(TODAY.minusDays(40), groups.get(0).dueDate());
        assertEquals(0, new BigDecimal("400.00").compareTo(groups.get(0).outstanding()));
        assertEquals(TODAY.plusDays(10), groups.get(1).dueDate());
        assertEquals(0, new BigDecimal("150.00").compareTo(groups.get(1).outstanding()), "200 less the 50 paid");
    }

    @Test
    @DisplayName("invoiced: per month of invoice date, net of credit notes; a paid invoice still counts as invoiced")
    void invoicedByMonth() {
        Map<YearMonth, BigDecimal> months = byMonth(invoices.invoicedByMonth(Set.of("DRAFT", "CANCELLED"), MAD,
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31)));

        assertEquals(0, new BigDecimal("150.00").compareTo(months.get(YearMonth.of(2026, 10))), "200 - 50 credited");
        assertEquals(0, new BigDecimal("400.00").compareTo(months.get(YearMonth.of(2026, 9))));
        assertEquals(0, new BigDecimal("100.00").compareTo(months.get(YearMonth.of(2026, 7))));
    }

    @Test
    @DisplayName("paid: executed payments per month of confirmation; one payment prepared, to execute")
    void paidByMonth() {
        Map<YearMonth, BigDecimal> months = byMonth(payments.byMonth("COMPLETED", MAD,
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31)));

        assertEquals(0, new BigDecimal("250.00").compareTo(months.get(YearMonth.of(2026, 10))));
        assertEquals(0, new BigDecimal("100.00").compareTo(months.get(YearMonth.of(2026, 9))));
        assertEquals(1, payments.countInStatus("PENDING"));
        assertEquals(Map.of("MAD", 4L), invoices.documentsByCurrency());
        assertEquals(Map.of("MAD", 3L), payments.documentsByCurrency());
    }

    private static Map<YearMonth, BigDecimal> byMonth(List<MonthAmount> months) {
        return months.stream().collect(Collectors.toMap(MonthAmount::month, MonthAmount::amount, BigDecimal::add));
    }

    private String code(String prefix) {
        return prefix + "-2026-" + String.format("%04d", 9000 + ++sequence);
    }

    private void invoice(String type, String status, String total, String paid, LocalDate due, LocalDate date) {
        jdbc.update("insert into invoices (id, invoice_code, invoice_type, status, supplier_id, supplier_name, invoice_date, "
                        + "due_date, total_amount, total_tax_amount, total_amount_with_tax, paid_amount, currency_code, "
                        + "is_verified, has_discrepancy, created_at, version) "
                        + "values (?, ?, ?, ?, ?, 'Acme', ?, ?, ?, 0, ?, ?, 'MAD', false, false, ?, 0)",
                UUID.randomUUID(), code("INV"), type, status, acme, Date.valueOf(date), due != null ? Date.valueOf(due) : null,
                new BigDecimal(total), new BigDecimal(total), paid != null ? new BigDecimal(paid) : null,
                Timestamp.valueOf(date.atStartOfDay()));
    }

    private void payment(String status, String total, LocalDate confirmed) {
        jdbc.update("insert into payments (id, payment_code, status, supplier_id, supplier_name, total_amount, currency_code, "
                        + "confirmed_date, created_at, version) values (?, ?, ?, ?, 'Acme', ?, 'MAD', ?, ?, 0)",
                UUID.randomUUID(), code("PAY"), status, acme, new BigDecimal(total),
                confirmed != null ? Timestamp.valueOf(confirmed.atTime(12, 0)) : null,
                Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)));
    }
}
