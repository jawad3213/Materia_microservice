package com.materia.backend.support.fixtures;

import com.materia.backend.common.domain.valueObjects.Money;

import java.util.UUID;

/**
 * A material held by master data, as this application's tests set it up: what masterdata.v1.MaterialCatalog tells
 * about it, and the stock master data keeps from this application's events ({@link TestStockKeeper}). Built with
 * {@link MaterialFixtures}, registered with {@link TestMaterialCatalog} (or {@link TestMasterData} in a Spring test).
 */
public final class TestMaterial {

    private final UUID id;
    private final String code;
    private final String name;
    private String unitOfMeasureCode;
    private Money standardPrice;
    private boolean orderable;
    private int currentStock;
    private int stockOnOrder;
    private final UUID supplierId;
    private final String supplierName;

    TestMaterial(UUID id, String code, String name, String unitOfMeasureCode, Money standardPrice, boolean orderable,
                 int currentStock, int stockOnOrder, UUID supplierId, String supplierName) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.unitOfMeasureCode = unitOfMeasureCode;
        this.standardPrice = standardPrice;
        this.orderable = orderable;
        this.currentStock = currentStock;
        this.stockOnOrder = stockOnOrder;
        this.supplierId = supplierId;
        this.supplierName = supplierName;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getUnitOfMeasureCode() { return unitOfMeasureCode; }
    public Money getStandardPrice() { return standardPrice; }
    public boolean isOrderable() { return orderable; }
    public synchronized int getCurrentStock() { return currentStock; }
    public synchronized int getStockOnOrder() { return stockOnOrder; }
    public UUID getSupplierId() { return supplierId; }
    public String getSupplierName() { return supplierName; }

    public void setStandardPrice(Money standardPrice) { this.standardPrice = standardPrice; }
    public void setUnitOfMeasureCode(String unitOfMeasureCode) { this.unitOfMeasureCode = unitOfMeasureCode; }

    /** Obsolete, blocked or inactive: still known, no longer orderable. */
    public void retire() { this.orderable = false; }

    /** Its stock and stock on order, to put back with {@link #restore}. */
    synchronized int[] snapshot() {
        return new int[]{currentStock, stockOnOrder};
    }

    synchronized void restore(int[] snapshot) {
        currentStock = snapshot[0];
        stockOnOrder = snapshot[1];
    }

    synchronized void addStockOnOrder(int quantity) {
        stockOnOrder += quantity;
    }

    synchronized int releaseStockOnOrder(int quantity) {
        int released = Math.min(quantity, stockOnOrder);
        stockOnOrder -= released;
        return released;
    }

    /** Returns the stock before; the accepted quantity enters stock. */
    synchronized int receive(int accepted) {
        int before = currentStock;
        currentStock += accepted;
        return before;
    }
}
