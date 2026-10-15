package com.materia.backend.contexts.masterData.domain.ports.in;

import com.materia.backend.contexts.masterData.application.dtos.material.OrderableMaterial;

import java.util.List;

/** The catalogue procurement orders from: orderable materials and their suppliers. */
public interface OrderableMaterialsUseCase {

    /** Every orderable material that has a supplier, with that supplier's code and name, by material code. */
    List<OrderableMaterial> findOrderableMaterials();
}
