package com.materia.backend.common.audit.application;

import com.materia.backend.common.audit.application.AuditLogReader.AuditEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** The feed of this service's own change history; the default ({@code app.audit.feed=local}). */
@Component
@ConditionalOnProperty(name = "app.audit.feed", havingValue = "local", matchIfMissing = true)
public class LocalAuditFeed implements AuditFeed {

    private final AuditLogReader reader;

    public LocalAuditFeed(AuditLogReader reader) {
        this.reader = reader;
    }

    @Override
    public List<AuditEntry> recent(String entityType, String actorId, int limit) {
        return reader.recent(entityType, actorId, limit);
    }
}
