package com.materia.backend.infrastructure.grpc;

import com.materia.backend.common.audit.application.AuditLogReader;
import com.materia.proto.audit.v1.ChangeHistoryGrpc;
import com.materia.proto.audit.v1.RecentChangesRequest;
import com.materia.proto.audit.v1.RecentChangesResponse;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/**
 * Serves audit.v1.ChangeHistory from this service's own change history, for the platform-wide feed analytics composes.
 * Every service with a gRPC server serves it (its policy asks for log:read).
 */
@Component
public class ChangeHistoryGrpcService extends ChangeHistoryGrpc.ChangeHistoryImplBase {

    private static final int MAX = 5000;

    private final AuditLogReader history;

    public ChangeHistoryGrpcService(AuditLogReader history) {
        this.history = history;
    }

    @Override
    public void recentChanges(RecentChangesRequest request, StreamObserver<RecentChangesResponse> response) {
        RecentChangesResponse.Builder reply = RecentChangesResponse.newBuilder();
        history.recent(request.hasEntityType() ? request.getEntityType() : null,
                        request.hasActorId() ? request.getActorId() : null,
                        Math.max(1, Math.min(request.getLimit(), MAX)))
                .forEach(entry -> reply.addChanges(ChangeHistoryProtos.toProto(entry)));
        response.onNext(reply.build());
        response.onCompleted();
    }
}
