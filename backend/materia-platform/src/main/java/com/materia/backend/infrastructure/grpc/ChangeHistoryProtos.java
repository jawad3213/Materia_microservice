package com.materia.backend.infrastructure.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.materia.backend.common.audit.application.AuditLogReader.AuditEntry;
import com.materia.backend.common.audit.application.AuditLogReader.FieldChange;
import com.materia.proto.audit.v1.Change;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A change of the history to and from audit.v1 (field values travel as JSON: a text, a number, a date...). */
public final class ChangeHistoryProtos {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private ChangeHistoryProtos() {
    }

    public static Change toProto(AuditEntry entry) {
        Change.Builder proto = Change.newBuilder()
                .setId(entry.id().toString())
                .setOccurredAt(entry.occurredAt().toString())
                .setActorId(entry.actorId())
                .setEntityType(entry.entityType())
                .setEntityId(entry.entityId().toString())
                .setAction(entry.action());
        if (entry.actorName() != null) proto.setActorName(entry.actorName());
        if (entry.parentType() != null) proto.setParentType(entry.parentType());
        if (entry.parentId() != null) proto.setParentId(entry.parentId().toString());
        if (entry.summary() != null) proto.setSummary(entry.summary());
        if (entry.reason() != null) proto.setReason(entry.reason());
        if (entry.changes() != null) {
            entry.changes().forEach((field, change) -> {
                com.materia.proto.audit.v1.FieldChange.Builder value = com.materia.proto.audit.v1.FieldChange.newBuilder();
                if (change.from() != null) value.setFromJson(json(change.from()));
                if (change.to() != null) value.setToJson(json(change.to()));
                proto.putChanges(field, value.build());
            });
        }
        return proto.build();
    }

    public static AuditEntry fromProto(Change change) {
        Map<String, FieldChange> changes = new LinkedHashMap<>();
        change.getChangesMap().forEach((field, value) -> changes.put(field, new FieldChange(
                value.hasFromJson() ? value(value.getFromJson()) : null,
                value.hasToJson() ? value(value.getToJson()) : null)));
        return new AuditEntry(
                UUID.fromString(change.getId()),
                LocalDateTime.parse(change.getOccurredAt()),
                change.getActorId(),
                change.hasActorName() ? change.getActorName() : null,
                change.getEntityType(),
                UUID.fromString(change.getEntityId()),
                change.hasParentType() ? change.getParentType() : null,
                change.hasParentId() ? UUID.fromString(change.getParentId()) : null,
                change.getAction(),
                change.hasSummary() ? change.getSummary() : null,
                change.hasReason() ? change.getReason() : null,
                changes);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A changed value cannot be written as JSON", e);
        }
    }

    private static Object value(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A changed value is not JSON: " + json, e);
        }
    }
}
