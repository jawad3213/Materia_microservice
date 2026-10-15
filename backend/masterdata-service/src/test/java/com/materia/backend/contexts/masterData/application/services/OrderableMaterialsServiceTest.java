package com.materia.backend.contexts.masterData.application.services;

import com.materia.backend.contexts.masterData.application.dtos.material.OrderableMaterial;
import com.materia.backend.contexts.masterData.domain.entities.Material;
import com.materia.backend.contexts.masterData.domain.entities.Supplier;
import com.materia.backend.contexts.masterData.domain.enums.MaterialStatus;
import com.materia.backend.contexts.masterData.domain.ports.out.MaterialRepository;
import com.materia.backend.contexts.masterData.domain.ports.out.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.materia.backend.support.fixtures.CatalogueFixtures.aSupplier;
import static com.materia.backend.support.fixtures.MaterialFixtures.aMaterial;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The catalogue procurement orders from (masterdata.v1.MaterialCatalog FindOrderableMaterials). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderableMaterialsServiceTest {

    @Mock private MaterialRepository materials;
    @Mock private SupplierRepository suppliers;

    private OrderableMaterialsService service;
    private Supplier acme;

    @BeforeEach
    void setUp() {
        service = new OrderableMaterialsService(materials, suppliers);
        acme = aSupplier().code("SUP-2026-0001").name("Acme").build();
        when(suppliers.findById(any())).thenReturn(Optional.empty());
        when(suppliers.findById(acme.getId())).thenReturn(Optional.of(acme));
    }

    private static Material supplied(String code, String supplierId) {
        Material material = aMaterial().code(code).supplier(supplierId).build();
        material.setSupplierName("Name on the material");
        material.setCategoryName("Fasteners");
        material.setEconomicOrderQuantity(40);
        return material;
    }

    @Test
    @DisplayName("orderable: active materials with a supplier, by code, with the supplier's code and name")
    void orderableMaterials_withTheirSupplier() {
        Material bolts = supplied("MAT-0002", acme.getId().toString());
        Material anchors = supplied("MAT-0001", acme.getId().toString());
        when(materials.findByStatus(MaterialStatus.ACTIVE)).thenReturn(List.of(bolts, anchors));

        List<OrderableMaterial> found = service.findOrderableMaterials();

        assertEquals(List.of("MAT-0001", "MAT-0002"), found.stream().map(m -> m.material().code()).toList());
        OrderableMaterial first = found.get(0);
        assertEquals(anchors.getId(), first.material().id());
        assertEquals(acme.getId(), first.supplierId());
        assertEquals("SUP-2026-0001", first.supplierCode());
        assertEquals("Acme", first.supplierName());
        assertEquals("Fasteners", first.categoryName());
        assertEquals(40, first.economicOrderQuantity());
        verify(suppliers, times(1)).findById(acme.getId());
    }

    @Test
    @DisplayName("orderable: a material without a supplier, or naming one by a malformed id, is left out")
    void withoutSupplier_isLeftOut() {
        when(materials.findByStatus(MaterialStatus.ACTIVE)).thenReturn(List.of(
                supplied("MAT-0011", null), supplied("MAT-0012", " "), supplied("MAT-0013", "not-a-uuid")));

        assertTrue(service.findOrderableMaterials().isEmpty());
    }

    @Test
    @DisplayName("orderable: a supplier no longer known keeps the name the material carries, without a code")
    void unknownSupplier_keepsTheMaterialsName() {
        when(materials.findByStatus(MaterialStatus.ACTIVE)).thenReturn(List.of(
                supplied("MAT-0011", UUID.randomUUID().toString())));

        OrderableMaterial found = service.findOrderableMaterials().get(0);

        assertNull(found.supplierCode());
        assertEquals("Name on the material", found.supplierName());
    }

    @Test
    @DisplayName("orderable: an active material that is not orderable (obsolete) is left out")
    void notOrderable_isLeftOut() {
        Material obsolete = supplied("MAT-0011", acme.getId().toString());
        obsolete.setStatus(MaterialStatus.OBSOLETE);
        when(materials.findByStatus(MaterialStatus.ACTIVE)).thenReturn(List.of(obsolete));

        assertTrue(service.findOrderableMaterials().isEmpty());
    }
}
