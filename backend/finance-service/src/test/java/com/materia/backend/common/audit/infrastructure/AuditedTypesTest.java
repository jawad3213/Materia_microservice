package com.materia.backend.common.audit.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finance serves the history of its own records (the gateway routes /api/v1/audit/Invoice, /Payment and /Setting
 * here), with the permission its contexts declared on them (@Audited).
 */
class AuditedTypesTest {

    @Test
    @DisplayName("history: invoices, payments and settings are found with their permission; others' records are not")
    void readPermissions_comeFromTheAnnotations() {
        assertEquals(Optional.of(Optional.of("invoice:read")), AuditedTypes.readPermissionOf("Invoice"));
        assertEquals(Optional.of(Optional.of("payment:read")), AuditedTypes.readPermissionOf("Payment"));
        assertEquals(Optional.of(Optional.of("setting:read")), AuditedTypes.readPermissionOf("Setting"));
        assertTrue(AuditedTypes.readPermissionOf("InvoiceLine").isEmpty(), "lines are read with their document");
        assertTrue(AuditedTypes.readPermissionOf("Employee").isEmpty(), "identity's records");
        assertTrue(AuditedTypes.readPermissionOf("PurchaseOrder").isEmpty(), "the monolith's records");
    }
}
