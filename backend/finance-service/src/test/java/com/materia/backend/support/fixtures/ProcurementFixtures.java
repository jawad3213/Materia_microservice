package com.materia.backend.support.fixtures;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.valueObjects.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Procurement's orders and receipts, as finance's tests set them up: what procurement.v1 (PurchaseOrderLookup,
 * GoodsReceiptLookup) tells about them. Register them with {@link TestProcurementLookups} (or {@link TestProcurement}
 * in a Spring test).
 */
public final class ProcurementFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    private ProcurementFixtures() {
    }

    /** A confirmed order in MAD from Acme Supplies, without lines. */
    public static OrderBuilder anOrder() {
        return new OrderBuilder();
    }

    /** A draft receipt of {@code order}, receiving nothing yet. */
    public static ReceiptBuilder aReceipt(TestOrder order) {
        return new ReceiptBuilder(order);
    }

    /** An order line. */
    public static final class TestOrderLine {
        private final UUID id = UUID.randomUUID();
        private final UUID materialId = UUID.randomUUID();
        private final String materialCode = "MAT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        private final String materialName = "Material " + materialCode;
        private final String unitOfMeasure = "PCE";
        private final int quantity;
        private Money unitPrice;
        private BigDecimal taxRate;

        TestOrderLine(int quantity, Money unitPrice) {
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        public UUID getId() { return id; }
        public UUID getMaterialId() { return materialId; }
        public String getMaterialCode() { return materialCode; }
        public String getMaterialName() { return materialName; }
        public String getUnitOfMeasure() { return unitOfMeasure; }
        public int getQuantity() { return quantity; }
        public Money getUnitPrice() { return unitPrice; }
        public BigDecimal getTaxRate() { return taxRate; }
        public void setUnitPrice(Money unitPrice) { this.unitPrice = unitPrice; }
        public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    }

    /** A purchase order. */
    public static final class TestOrder {
        private final UUID id = UUID.randomUUID();
        private final String orderCode = String.format("PO-2026-T%04d", SEQUENCE.getAndIncrement() % 10000);
        private String status;
        private final UUID supplierId;
        private final String supplierCode = "SUP-2026-0001";
        private final String supplierName = "Acme Supplies";
        private String currencyCode;
        private final List<TestOrderLine> lines = new ArrayList<>();

        TestOrder(String status, String currencyCode, UUID supplierId) {
            this.status = status;
            this.currencyCode = currencyCode;
            this.supplierId = supplierId;
        }

        public UUID getId() { return id; }
        public String getOrderCode() { return orderCode; }
        public String getStatus() { return status; }
        public UUID getSupplierId() { return supplierId; }
        public String getSupplierCode() { return supplierCode; }
        public String getSupplierName() { return supplierName; }
        public String getCurrencyCode() { return currencyCode; }
        public List<TestOrderLine> getLines() { return lines; }
        public void setStatus(String status) { this.status = status; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

        /** Goods can be received against it now (procurement's PurchaseOrderLookupService). */
        public boolean isReceivable() {
            return "READY_FOR_RECEIPT".equals(status) || "PARTIALLY_RECEIVED".equals(status);
        }

        /** Goods were received on it (procurement's PurchaseOrderLookupService). */
        public boolean isGoodsReceived() {
            return "PARTIALLY_RECEIVED".equals(status) || "COMPLETED".equals(status);
        }
    }

    /** A receipt line: what arrived on one order line. */
    public record TestReceiptLine(UUID id, TestOrderLine orderLine, int received, int rejected, String reason) {
        public int accepted() { return received - rejected; }
    }

    /** A goods receipt; it counts (validated) once completed or partial. */
    public static final class TestReceipt {
        private final UUID id = UUID.randomUUID();
        private final String receiptCode = String.format("GR-2026-T%04d", SEQUENCE.getAndIncrement() % 10000);
        private final TestOrder order;
        private final String status;
        private final List<TestReceiptLine> lines;

        TestReceipt(TestOrder order, String status, List<TestReceiptLine> lines) {
            this.order = order;
            this.status = status;
            this.lines = lines;
        }

        public UUID getId() { return id; }
        public String getReceiptCode() { return receiptCode; }
        public TestOrder getOrder() { return order; }
        public String getStatus() { return status; }
        public List<TestReceiptLine> getLines() { return lines; }

        public boolean isValidated() {
            return "COMPLETED".equals(status) || "PARTIAL".equals(status);
        }
    }

    public static final class OrderBuilder {
        private final List<int[]> quantities = new ArrayList<>();
        private final List<String> prices = new ArrayList<>();
        private String status = "CONFIRMED";
        private String currency = CurrencyCode.MAD.name();
        private UUID supplierId = UUID.randomUUID();

        public OrderBuilder supplier(UUID supplierId) {
            this.supplierId = supplierId;
            return this;
        }

        public OrderBuilder withLine(int quantity, String unitPrice) {
            quantities.add(new int[]{quantity});
            prices.add(unitPrice);
            return this;
        }

        /** e.g. "PARTIALLY_RECEIVED" */
        public OrderBuilder inStatus(String status) {
            this.status = status;
            return this;
        }

        public OrderBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public TestOrder build() {
            TestOrder order = new TestOrder(status, currency, supplierId);
            for (int i = 0; i < quantities.size(); i++) {
                order.lines.add(new TestOrderLine(quantities.get(i)[0],
                        Money.of(prices.get(i), CurrencyCode.valueOf(currency))));
            }
            return order;
        }
    }

    public static final class ReceiptBuilder {
        private final TestOrder order;
        private final List<TestReceiptLine> lines = new ArrayList<>();
        private String status = "DRAFT";

        ReceiptBuilder(TestOrder order) {
            this.order = order;
        }

        /** Receives {@code received} units on the order's line {@code lineIndex}, {@code rejected} of them refused. */
        public ReceiptBuilder receiving(int lineIndex, int received, int rejected, String reason) {
            lines.add(new TestReceiptLine(UUID.randomUUID(), order.getLines().get(lineIndex), received, rejected, reason));
            return this;
        }

        /** e.g. "COMPLETED", "PARTIAL" */
        public ReceiptBuilder inStatus(String status) {
            this.status = status;
            return this;
        }

        public TestReceipt build() {
            return new TestReceipt(order, status, List.copyOf(lines));
        }
    }
}
