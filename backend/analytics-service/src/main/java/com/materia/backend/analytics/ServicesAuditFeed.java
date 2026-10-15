package com.materia.backend.analytics;

import com.materia.backend.common.audit.application.AuditFeed;
import com.materia.backend.common.audit.application.AuditLogReader.AuditEntry;
import com.materia.backend.infrastructure.grpc.ChangeHistoryProtos;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.audit.v1.ChangeHistoryGrpc;
import com.materia.proto.audit.v1.RecentChangesRequest;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The platform-wide feed of the audit log page ({@code app.audit.feed=services}): each service keeps its own change
 * history (one database per service), so the latest changes are asked of every one of them (audit.v1.ChangeHistory,
 * with the user's token: log:read) and merged, newest first.
 */
@Component
@ConditionalOnProperty(name = "app.audit.feed", havingValue = "services")
public class ServicesAuditFeed implements AuditFeed {

    private final List<ChangeHistoryGrpc.ChangeHistoryBlockingStub> services;

    public ServicesAuditFeed(@Qualifier("identityChannel") ManagedChannel identity,
                             @Qualifier("masterDataChannel") ManagedChannel masterData,
                             @Qualifier("procurementChannel") ManagedChannel procurement,
                             @Qualifier("financeChannel") ManagedChannel finance) {
        this.services = Stream.of(identity, masterData, procurement, finance).map(ChangeHistoryGrpc::newBlockingStub)
                .toList();
    }

    @Override
    public List<AuditEntry> recent(String entityType, String actorId, int limit) {
        RecentChangesRequest.Builder request = RecentChangesRequest.newBuilder().setLimit(limit);
        if (entityType != null && !entityType.isBlank()) request.setEntityType(entityType);
        if (actorId != null && !actorId.isBlank()) request.setActorId(actorId);
        return services.stream()
                .flatMap(service -> GrpcCalls.call(() -> service.recentChanges(request.build())).getChangesList().stream())
                .map(ChangeHistoryProtos::fromProto)
                .sorted(Comparator.comparing(AuditEntry::occurredAt).reversed())
                .limit(limit)
                .toList();
    }
}
