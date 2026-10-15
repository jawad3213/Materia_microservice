package com.materia.backend.contexts.returnToVendor.domain.events;

import com.materia.backend.common.domain.DomainEvent;

import java.util.UUID;

/**
 * Returned goods the supplier will replace: their quantity is on order again. The master data context adds it to the
 * material's stock on order.
 *
 * <p>Keyed by the material, so the movements of one material are applied in the order they were made.
 */
public class ReplacementPutOnOrderEvent extends DomainEvent {

    private final String materialCode;
    private final int quantity;
    private final String userId;

    public ReplacementPutOnOrderEvent(UUID materialId, String materialCode, int quantity, String userId) {
        super(materialId);
        this.materialCode = materialCode;
        this.quantity = quantity;
        this.userId = userId;
    }

    public UUID getMaterialId() {
        return (UUID) getAggregateId();
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getUserId() {
        return userId;
    }
}
