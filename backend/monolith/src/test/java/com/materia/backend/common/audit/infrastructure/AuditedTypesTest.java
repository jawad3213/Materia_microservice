package com.materia.backend.common.audit.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The history settings come from the @Audited annotations the contexts put on their own entities. */
class AuditedTypesTest {

    @Test
    @DisplayName("history: each document type is found with the permission its context declared")
    void readPermissions_comeFromTheAnnotations() {
        assertEquals(Optional.of(Optional.of("order:read")), AuditedTypes.readPermissionOf("PurchaseOrder"));
        assertEquals(Optional.of(Optional.of("requisition:read")), AuditedTypes.readPermissionOf("Requisition"));
        assertEquals(Optional.of(Optional.empty()), AuditedTypes.readPermissionOf("Attachment"), "log:read only");
        assertTrue(AuditedTypes.readPermissionOf("PurchaseOrderLine").isEmpty(), "lines are read with their document");
        assertTrue(AuditedTypes.readPermissionOf("Nonsense").isEmpty());
        assertTrue(AuditedTypes.readPermissionOf("Employee").isEmpty(), "identity's records: identity serves them");
        assertTrue(AuditedTypes.readPermissionOf("Material").isEmpty(), "master data's records: master data serves them");
        assertTrue(AuditedTypes.readPermissionOf("Invoice").isEmpty(), "finance's records: finance serves them");
    }

    @Test
    @DisplayName("history: an entity's settings, including a line's document, are read from its annotation")
    void entitySpec_isReadFromTheAnnotation() throws Exception {
        Class<?> line = Class.forName("com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.out.persistence"
                + ".entities.PurchaseOrderLineJpaEntity");
        AuditedEntities.Spec spec = AuditedEntities.of(line).orElseThrow();

        assertEquals("PurchaseOrderLine", spec.type());
        assertEquals("materialCode", spec.codeProperty());
        assertEquals("purchaseOrder", spec.parentProperty());
        assertEquals("PurchaseOrder", spec.parentType());
        assertTrue(AuditedEntities.of(String.class).isEmpty(), "unannotated classes are not recorded");
    }

    @Test
    @DisplayName("history: an attachment's document type follows from its owner type by convention")
    void attachmentOwner_toHistoryType() {
        assertEquals("PurchaseOrder", AuditedEntities.historyTypeOf("PURCHASE_ORDER"));
        assertEquals("ReturnToVendor", AuditedEntities.historyTypeOf("RETURN_TO_VENDOR"));
        assertEquals("Invoice", AuditedEntities.historyTypeOf("INVOICE"));
    }
}
