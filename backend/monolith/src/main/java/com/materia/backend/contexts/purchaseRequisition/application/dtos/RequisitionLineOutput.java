package com.materia.backend.contexts.purchaseRequisition.application.dtos;

import com.materia.backend.common.domain.valueObjects.Money;
import com.materia.backend.contexts.purchaseRequisition.domain.entities.RequisitionLine;

import java.time.LocalDate;
import java.util.UUID;

/** A requisition line as the requisition's own web layer shows it: a copy of the line, not the domain entity. */
public record RequisitionLineOutput(
        UUID id,
        Integer lineNumber,
        String materialCode,
        UUID materialId,
        String materialName,
        String materialDescription,
        String unitOfMeasure,
        Money standardPrice,
        Money unitPrice,
        String currencyCode,
        Integer quantity,
        Integer quantityReceived,
        Integer quantityRejected,
        LocalDate requiredDate,
        Money lineTotal,
        String currencyCodeLine,
        UUID supplierId,
        String supplierName,
        String supplierCode,
        String notes,
        String deliveryTerms,
        String storageLocation,
        String batchNumber,
        LocalDate expiryDate) {

    public static RequisitionLineOutput from(RequisitionLine line) {
        return new RequisitionLineOutput(
                line.getId(),
                line.getLineNumber(),
                line.getMaterialCode(),
                line.getMaterialId(),
                line.getMaterialName(),
                line.getMaterialDescription(),
                line.getUnitOfMeasure(),
                line.getStandardPrice(),
                line.getUnitPrice(),
                line.getCurrencyCode(),
                line.getQuantity(),
                line.getQuantityReceived(),
                line.getQuantityRejected(),
                line.getRequiredDate(),
                line.getLineTotal(),
                line.getCurrencyCodeLine(),
                line.getSupplierId(),
                line.getSupplierName(),
                line.getSupplierCode(),
                line.getNotes(),
                line.getDeliveryTerms(),
                line.getStorageLocation(),
                line.getBatchNumber(),
                line.getExpiryDate());
    }

    /** What other contexts may know about the line. */
    public RequisitionLineSummary toSummary() {
        return new RequisitionLineSummary(id, materialCode, quantity, supplierId);
    }
}
