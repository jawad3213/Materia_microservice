package com.materia.backend.contexts.masterData.application.dtos.material;

import java.util.UUID;

/**
 * A material that can be ordered, with what an order needs to size its line (the economic order quantity, may be null)
 * and the supplier it is bought from.
 */
public record OrderableMaterial(MaterialReference material, String categoryName, Integer economicOrderQuantity,
                                UUID supplierId, String supplierCode, String supplierName) {
}
