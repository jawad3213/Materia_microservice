package com.materia.backend.support.fixtures;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.valueObjects.Money;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Materials for this application's tests. Master data holds the materials, so these are what its catalogue tells
 * about them ({@link TestMaterial}); register one with {@link TestMaterialCatalog#save} or {@link TestMasterData#save}.
 */
public final class MaterialFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    private MaterialFixtures() {
    }

    /** An active material priced at 5.00 MAD, with no stock. */
    public static Builder aMaterial() {
        return new Builder();
    }

    public static String uniqueCode() {
        return String.format("MAT-T%05d", SEQUENCE.getAndIncrement() % 100000);
    }

    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private String code = uniqueCode();
        private String name = "Test material";
        private String unitOfMeasure = "PCE";
        private String price = "5.00";
        private CurrencyCode currency = CurrencyCode.MAD;
        private boolean orderable = true;
        private int currentStock;
        private int stockOnOrder;
        private UUID supplierId = UUID.randomUUID();
        private String supplierName = "Acme Supplies";

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder code(String code) { this.code = code; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder unitOfMeasure(String unitOfMeasure) { this.unitOfMeasure = unitOfMeasure; return this; }
        public Builder price(String price) { this.price = price; return this; }
        public Builder currency(CurrencyCode currency) { this.currency = currency; return this; }
        public Builder notOrderable() { this.orderable = false; return this; }
        public Builder stock(int current) { this.currentStock = current; return this; }
        public Builder onOrder(int onOrder) { this.stockOnOrder = onOrder; return this; }
        public Builder supplier(UUID supplierId, String supplierName) {
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            return this;
        }

        public TestMaterial build() {
            return new TestMaterial(id, code, name, unitOfMeasure, Money.of(price, currency), orderable, currentStock,
                    stockOnOrder, supplierId, supplierName);
        }
    }
}
