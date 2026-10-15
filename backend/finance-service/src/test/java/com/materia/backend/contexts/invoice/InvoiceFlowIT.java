package com.materia.backend.contexts.invoice;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.valueObjects.Money;
import com.materia.backend.support.fixtures.TestAccount;
import com.materia.backend.support.fixtures.TestIdentity;
import com.materia.backend.contexts.invoice.application.dtos.CreateInvoiceInput;
import com.materia.backend.contexts.invoice.application.dtos.InvoiceLineInput;
import com.materia.backend.contexts.invoice.application.dtos.InvoiceLineOutput;
import com.materia.backend.contexts.invoice.application.dtos.InvoiceOutput;
import com.materia.backend.contexts.invoice.domain.enums.InvoiceStatus;
import com.materia.backend.contexts.invoice.domain.enums.InvoiceType;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceRuleViolationException;
import com.materia.backend.contexts.invoice.domain.exceptions.InvoiceSupplierMismatchException;
import com.materia.backend.contexts.invoice.domain.ports.in.InvoiceUseCase;
import com.materia.backend.support.AbstractIntegrationTest;
import com.materia.backend.support.fixtures.ProcurementFixtures.TestOrder;
import com.materia.backend.support.fixtures.TestProcurement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.materia.backend.support.fixtures.UserFixtures.aPurchaser;
import static com.materia.backend.support.fixtures.ProcurementFixtures.aReceipt;
import static com.materia.backend.support.fixtures.ProcurementFixtures.anOrder;
import static org.junit.jupiter.api.Assertions.*;

/** Invoicing against the real database: an invoice is matched to its order and receipts, then verified and paid. */
class InvoiceFlowIT extends AbstractIntegrationTest {

    @Autowired private TestProcurement procurement;
    @Autowired private InvoiceUseCase invoices;
    @Autowired private TestIdentity users;
    @PersistenceContext private EntityManager em;

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    /**
     * Procurement's order of 10 units at 5.00 MAD with 20% VAT per line, of which {@code received} were accepted on a
     * validated receipt.
     */
    private TestOrder orderWithReceipt(int received) {
        boolean all = received >= 10;
        TestOrder order = procurement.save(anOrder().withLine(10, "5.00")
                .inStatus(all ? "COMPLETED" : "PARTIALLY_RECEIVED").build());
        order.getLines().get(0).setTaxRate(new BigDecimal("20"));
        procurement.save(aReceipt(order).receiving(0, received, 0, null).inStatus(all ? "COMPLETED" : "PARTIAL").build());
        return order;
    }

    private CreateInvoiceInput invoiceFor(TestOrder order, InvoiceType type, int quantity, String unitPrice) {
        InvoiceLineInput line = new InvoiceLineInput();
        line.setPurchaseOrderLineId(order.getLines().get(0).getId().toString());
        line.setMaterialCode("CLIENT-SENT-CODE");
        line.setMaterialName("Client-sent name");
        line.setQuantityInvoiced(quantity);
        line.setUnitPrice(Money.of(unitPrice, CurrencyCode.MAD));
        // VAT as the order sets it (its lines take the default 20% rate).
        line.setTaxRate(new BigDecimal("20"));
        CreateInvoiceInput input = new CreateInvoiceInput();
        input.setPurchaseOrderId(order.getId().toString());
        input.setSupplierId(order.getSupplierId().toString());
        input.setSupplierName("Whatever the client sent");
        input.setInvoiceType(type);
        input.setExternalReference("FA-" + UUID.randomUUID().toString().substring(0, 8));
        input.setInvoiceDate(LocalDate.now());
        input.setCurrencyCode("MAD");
        input.setLines(new ArrayList<>(List.of(line)));
        input.setUserId("buyer-1");
        return input;
    }

    @Test
    @DisplayName("flow: an invoice matching the receipt is created with its own code and totals, then submitted, verified and paid")
    void matchingInvoice_isVerifiedAndPaid() {
        TestOrder order = orderWithReceipt(6);

        InvoiceOutput created = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 6, "5.00"));
        flushAndClear();

        assertEquals(InvoiceStatus.DRAFT, created.getStatus());
        assertTrue(created.getInvoiceCode().matches("INV-\\d{4}-\\d+"), created.getInvoiceCode());
        assertEquals(0, new BigDecimal("30.00").compareTo(created.getTotalAmount().getAmount()));
        assertEquals(0, new BigDecimal("36.00").compareTo(created.getTotalAmountWithTax().getAmount()), "30.00 + 20% VAT");
        assertFalse(created.isHasDiscrepancy());
        InvoiceLineOutput line = created.getLines().get(0);
        assertEquals(10, line.getQuantityOrdered());
        assertEquals(6, line.getQuantityReceived());
        assertEquals(order.getLines().get(0).getMaterialCode(), line.getMaterialCode(), "material details come from the order");
        assertEquals(order.getOrderCode(), created.getPurchaseOrderCode());
        assertEquals("Acme Supplies", created.getSupplierName());

        // Each action is its own request (and transaction) in the application.
        String verifier = users.save(aPurchaser().firstName("Ada").lastName("Admin").build()).getId().toString();
        invoices.submit(created.getId(), "buyer-1");
        flushAndClear();
        invoices.verify(created.getId(), verifier);
        flushAndClear();
        InvoiceOutput paid = invoices.pay(created.getId(), verifier, 36.00);
        flushAndClear();

        InvoiceOutput reloaded = invoices.getById(paid.getId());
        assertEquals(InvoiceStatus.PAID, reloaded.getStatus());
        assertEquals("Ada Admin", reloaded.getVerifiedByName(), "named from the verifier's account");
        assertEquals("Ada Admin", reloaded.getPaidByName(), "named from the payer's account");
        assertEquals(0, new BigDecimal("36.00").compareTo(reloaded.getPaidAmount().getAmount()));
    }

    @Test
    @DisplayName("payments: partial payments accumulate, the invoice is paid once the total is reached, and overpaying is refused")
    void partialPayments_accumulate() {
        TestOrder order = orderWithReceipt(6);
        InvoiceOutput invoice = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 6, "5.00"));
        flushAndClear();
        invoices.submit(invoice.getId(), "buyer-1");
        flushAndClear();
        invoices.verify(invoice.getId(), "admin-1");
        flushAndClear();

        InvoiceOutput partly = invoices.pay(invoice.getId(), "admin-1", 20.00);
        flushAndClear();
        assertEquals(InvoiceStatus.VERIFIED, partly.getStatus(), "16.00 of 36.00 still due");
        assertThrows(RuntimeException.class, () -> invoices.pay(invoice.getId(), "admin-1", 16.01));
        flushAndClear();
        assertThrows(RuntimeException.class, () -> invoices.cancel(invoice.getId(), "admin-1", "Changed our mind"));
        flushAndClear();

        InvoiceOutput settled = invoices.pay(invoice.getId(), "admin-1", 16.00);
        flushAndClear();

        InvoiceOutput reloaded = invoices.getById(settled.getId());
        assertEquals(InvoiceStatus.PAID, reloaded.getStatus());
        assertEquals(0, new BigDecimal("36.00").compareTo(reloaded.getPaidAmount().getAmount()));
        assertNotNull(reloaded.getPaymentDate());
        assertEquals("admin-1", reloaded.getPaidByName(), "an id without an account is shown as itself");
    }

    @Test
    @DisplayName("flow: two invoices get different codes")
    void codes_areUnique() {
        TestOrder order = orderWithReceipt(10);

        String first = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 4, "5.00")).getInvoiceCode();
        String second = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 4, "5.00")).getInvoiceCode();
        flushAndClear();

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("match: billing beyond what was received and not yet billed, or at another price, is flagged")
    void overBilling_isFlagged() {
        TestOrder order = orderWithReceipt(6);
        invoices.create(invoiceFor(order, InvoiceType.STANDARD, 4, "5.00"));
        flushAndClear();

        InvoiceOutput second = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 4, "5.50"));

        assertTrue(second.isHasDiscrepancy());
        InvoiceLineOutput line = second.getLines().get(0);
        assertTrue(line.isHasQuantityDiscrepancy());
        assertEquals(2, line.getQuantityDiscrepancy(), "6 received, 4 already billed: 2 billable, 4 billed");
        assertTrue(line.isHasPriceDiscrepancy(), "5.50 against 5.00 is +10%, beyond the 2% tolerance");
        assertTrue(second.getDiscrepancySummary().contains("Unit price"), second.getDiscrepancySummary());
    }

    @Test
    @DisplayName("match: a cancelled invoice no longer counts as billed")
    void cancelledInvoice_freesTheQuantity() {
        TestOrder order = orderWithReceipt(6);
        InvoiceOutput first = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 6, "5.00"));
        invoices.cancel(first.getId(), "buyer-1", "Wrong supplier reference");
        flushAndClear();

        InvoiceOutput replacement = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 6, "5.00"));

        assertFalse(replacement.isHasDiscrepancy());
    }

    @Test
    @DisplayName("rules: an invoice for another supplier is refused; a draft can be deleted, a submitted one cannot")
    void supplierMismatchAndDeletion() {
        TestOrder order = orderWithReceipt(6);
        CreateInvoiceInput otherSupplier = invoiceFor(order, InvoiceType.STANDARD, 1, "5.00");
        otherSupplier.setSupplierId(UUID.randomUUID().toString());
        assertThrows(InvoiceSupplierMismatchException.class, () -> invoices.create(otherSupplier));

        InvoiceOutput draft = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 1, "5.00"));
        InvoiceOutput submitted = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 1, "5.00"));
        invoices.submit(submitted.getId(), "buyer-1");
        flushAndClear();

        invoices.delete(draft.getId());
        assertThrows(RuntimeException.class, () -> invoices.delete(submitted.getId()));
        flushAndClear();
        assertEquals(1, invoices.getByPurchaseOrderId(order.getId().toString()).size());
    }

    @Test
    @DisplayName("rules: paying a submitted invoice or verifying a draft is refused")
    void outOfOrderTransitions_areRefused() {
        TestOrder order = orderWithReceipt(6);
        InvoiceOutput draft = invoices.create(invoiceFor(order, InvoiceType.STANDARD, 6, "5.00"));

        assertThrows(RuntimeException.class, () -> invoices.verify(draft.getId(), "admin-1"));
        flushAndClear();
        invoices.submit(draft.getId(), "buyer-1");
        flushAndClear();
        assertThrows(RuntimeException.class, () -> invoices.pay(draft.getId(), "admin-1", 32.0));
        assertThrows(InvoiceRuleViolationException.class, () -> invoices.submit(draft.getId(), "buyer-1"));
    }
}
