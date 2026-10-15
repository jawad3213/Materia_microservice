package com.materia.backend.analytics;

import com.materia.backend.common.audit.application.AuditLogReader;
import com.materia.backend.common.audit.application.AuditLogReader.AuditEntry;
import com.materia.backend.common.audit.application.AuditLogReader.FieldChange;
import com.materia.backend.infrastructure.grpc.ChangeHistoryGrpcService;
import com.materia.backend.support.fixtures.InProcessGrpc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit log page's feed composed from every service's own change history (audit.v1.ChangeHistory): each service
 * answers from its history, the feed merges them newest first.
 */
class ServicesAuditFeedTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 15, 12, 0);

    /** A service's change history, recording the filters it was asked. */
    private static final class History implements AuditLogReader {
        final List<AuditEntry> entries;
        final List<String> asked = new ArrayList<>();

        History(AuditEntry... entries) {
            this.entries = List.of(entries);
        }

        @Override
        public List<AuditEntry> historyOf(String entityType, UUID entityId, int limit) {
            return List.of();
        }

        @Override
        public List<AuditEntry> recent(String entityType, String actorId, int limit) {
            asked.add(entityType + "/" + actorId + "/" + limit);
            return entries.stream().limit(limit).toList();
        }
    }

    private static AuditEntry entry(String type, int minutesAgo, Map<String, FieldChange> changes) {
        return new AuditEntry(UUID.randomUUID(), NOON.minusMinutes(minutesAgo), "user-1", "Ada Admin", type,
                UUID.randomUUID(), null, null, "UPDATE", type + " changed", null, changes);
    }

    private static ServicesAuditFeed feed(History identity, History masterData, History procurement, History finance) {
        return new ServicesAuditFeed(
                InProcessGrpc.serving(new ChangeHistoryGrpcService(identity)),
                InProcessGrpc.serving(new ChangeHistoryGrpcService(masterData)),
                InProcessGrpc.serving(new ChangeHistoryGrpcService(procurement)),
                InProcessGrpc.serving(new ChangeHistoryGrpcService(finance)));
    }

    @Test
    @DisplayName("feed: every service's latest changes, merged newest first and cut at the limit")
    void mergedNewestFirst() {
        History procurement = new History(entry("PurchaseOrder", 1, Map.of()), entry("GoodsReceipt", 30, Map.of()));
        History finance = new History(entry("Invoice", 10, Map.of()));
        History masterData = new History(entry("Material", 20, Map.of()));

        List<AuditEntry> recent = feed(new History(), masterData, procurement, finance).recent(null, null, 3);

        assertThat(recent).extracting(AuditEntry::entityType).containsExactly("PurchaseOrder", "Invoice", "Material");
    }

    @Test
    @DisplayName("feed: the filters reach every service; a change's fields and values come through as they were")
    void filtersAndValues() {
        AuditEntry changed = entry("Invoice", 5, Map.of(
                "status", new FieldChange("SUBMITTED", "VERIFIED"),
                "totalAmount", new FieldChange(new BigDecimal("10.50"), null)));
        History finance = new History(changed);
        History identity = new History();

        AuditEntry read = feed(identity, new History(), new History(), finance).recent("Invoice", "user-1", 50).get(0);

        assertThat(identity.asked).containsExactly("Invoice/user-1/50");
        assertThat(read.id()).isEqualTo(changed.id());
        assertThat(read.occurredAt()).isEqualTo(changed.occurredAt());
        assertThat(read.actorName()).isEqualTo("Ada Admin");
        assertThat(read.changes().get("status")).isEqualTo(new FieldChange("SUBMITTED", "VERIFIED"));
        assertThat(read.changes().get("totalAmount").from()).hasToString("10.5");
        assertThat(read.changes().get("totalAmount").to()).isNull();
    }
}
