package com.materia.backend.common.audit.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Master data serves the history of its own records (the gateway routes /api/v1/audit/Material, /Supplier and
 * /Category here), with the permission its context declared on them (@Audited).
 */
class AuditedTypesTest {

    @Test
    @DisplayName("history: materials, suppliers and categories are found with their permission; others' records are not")
    void readPermissions_comeFromTheAnnotations() {
        assertEquals(Optional.of(Optional.of("material:read")), AuditedTypes.readPermissionOf("Material"));
        assertEquals(Optional.of(Optional.of("supplier:read")), AuditedTypes.readPermissionOf("Supplier"));
        assertEquals(Optional.of(Optional.of("category:read")), AuditedTypes.readPermissionOf("Category"));
        assertTrue(AuditedTypes.readPermissionOf("Employee").isEmpty(), "identity's records");
        assertTrue(AuditedTypes.readPermissionOf("PurchaseOrder").isEmpty(), "procurement's records");
    }
}
