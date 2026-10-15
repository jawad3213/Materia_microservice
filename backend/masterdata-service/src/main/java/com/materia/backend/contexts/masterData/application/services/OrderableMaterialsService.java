package com.materia.backend.contexts.masterData.application.services;

import com.materia.backend.contexts.masterData.application.dtos.material.OrderableMaterial;
import com.materia.backend.contexts.masterData.domain.entities.Material;
import com.materia.backend.contexts.masterData.domain.entities.Supplier;
import com.materia.backend.contexts.masterData.domain.enums.MaterialStatus;
import com.materia.backend.contexts.masterData.domain.ports.in.OrderableMaterialsUseCase;
import com.materia.backend.contexts.masterData.domain.ports.out.MaterialRepository;
import com.materia.backend.contexts.masterData.domain.ports.out.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderableMaterialsService implements OrderableMaterialsUseCase {

    private final MaterialRepository materials;
    private final SupplierRepository suppliers;

    public OrderableMaterialsService(MaterialRepository materials, SupplierRepository suppliers) {
        this.materials = materials;
        this.suppliers = suppliers;
    }

    @Override
    public List<OrderableMaterial> findOrderableMaterials() {
        Map<UUID, Optional<Supplier>> supplierById = new HashMap<>();
        return materials.findByStatus(MaterialStatus.ACTIVE).stream()
                .filter(Material::isOrderable)
                .map(material -> {
                    UUID supplierId = uuid(material.getSupplierId());
                    if (supplierId == null) return null;
                    Optional<Supplier> supplier = supplierById.computeIfAbsent(supplierId, suppliers::findById);
                    return new OrderableMaterial(MaterialInventoryService.toReference(material),
                            material.getCategoryName(), material.getEconomicOrderQuantity(), supplierId,
                            supplier.map(Supplier::getCode).orElse(null),
                            supplier.map(Supplier::getName).orElse(material.getSupplierName()));
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(orderable -> String.valueOf(orderable.material().code())))
                .toList();
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
