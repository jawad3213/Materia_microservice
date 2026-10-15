package com.materia.backend.common.audit.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity serves the history of its own records (the gateway routes /api/v1/audit/User and /Employee here), with
 * the permission its contexts declared on them (@Audited).
 */
class AuditedTypesTest {

    @Test
    @DisplayName("history: accounts and employees are found with user:read; other services' records are not")
    void readPermissions_comeFromTheAnnotations() {
        assertEquals(Optional.of(Optional.of("user:read")), AuditedTypes.readPermissionOf("User"));
        assertEquals(Optional.of(Optional.of("user:read")), AuditedTypes.readPermissionOf("Employee"));
        assertTrue(AuditedTypes.readPermissionOf("PurchaseOrder").isEmpty(), "procurement's records");
    }
}
