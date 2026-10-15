package com.materia.backend.common.audit.application;

import com.materia.backend.common.audit.application.AuditLogReader.AuditEntry;

import java.util.List;

/**
 * The platform-wide feed of the audit log page: the latest changes, optionally of one type of record or by one user,
 * newest first. A service reads its own change history ({@link LocalAuditFeed}); analytics composes every service's
 * (each keeps its own history: one database per service).
 */
public interface AuditFeed {

    List<AuditEntry> recent(String entityType, String actorId, int limit);
}
