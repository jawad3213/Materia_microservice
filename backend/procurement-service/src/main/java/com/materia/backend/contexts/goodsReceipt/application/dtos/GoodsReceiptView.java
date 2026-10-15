package com.materia.backend.contexts.goodsReceipt.application.dtos;

import com.materia.backend.common.domain.valueObjects.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt as the other contexts (invoicing, returns) need to see it.
 *
 * @param status    the receipt status code, e.g. COMPLETED
 * @param validated   whether the receipt was validated (completed or partial), so its quantities count
 * @param receiptDate the day the goods arrived; may be null on a draft
 */
public record GoodsReceiptView(UUID id, String receiptCode, String status, boolean validated, String purchaseOrderId,
                               String purchaseOrderCode, String supplierId, String supplierName, LocalDate receiptDate,
                               List<Line> lines) {

    public record Line(UUID id, String purchaseOrderLineId, UUID materialId, String materialCode, String materialName,
                       String unitOfMeasure, Money unitPrice, Integer quantityReceived, Integer quantityAccepted,
                       Integer quantityRejected, String rejectionReason, String qualityNotes) {
    }
}
