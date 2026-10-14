package com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.purchaseRequisition.application.dtos.ReplenishmentLineInput;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.ReplenishmentUseCase;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.procurement.v1.HasOpenRequisitionForRequest;
import com.materia.proto.procurement.v1.HasOpenRequisitionForResponse;
import com.materia.proto.procurement.v1.RaiseGroupedReplenishmentRequest;
import com.materia.proto.procurement.v1.RaiseReplenishmentRequest;
import com.materia.proto.procurement.v1.RaiseReplenishmentResponse;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** Serves procurement.v1.Replenishment from {@link ReplenishmentUseCase}. */
@Component
public class ReplenishmentGrpcService extends ReplenishmentGrpc.ReplenishmentImplBase {

    private final ReplenishmentUseCase replenishment;

    public ReplenishmentGrpcService(ReplenishmentUseCase replenishment) {
        this.replenishment = replenishment;
    }

    @Override
    public void hasOpenRequisitionFor(HasOpenRequisitionForRequest request,
                                      StreamObserver<HasOpenRequisitionForResponse> response) {
        boolean open = replenishment.hasOpenRequisitionFor(
                request.hasMaterialId() ? ProtoValues.uuid(request.getMaterialId()) : null,
                request.hasMaterialCode() ? request.getMaterialCode() : null);
        response.onNext(HasOpenRequisitionForResponse.newBuilder().setOpen(open).build());
        response.onCompleted();
    }

    @Override
    public void raiseReplenishment(RaiseReplenishmentRequest request, StreamObserver<RaiseReplenishmentResponse> response) {
        reply(response, replenishment.raiseReplenishment(ProtoValues.uuid(request.getMaterialId()), request.getQuantity(),
                request.hasReason() ? request.getReason() : null, request.getUrgent(),
                request.hasRequesterId() ? request.getRequesterId() : null));
    }

    @Override
    public void raiseGroupedReplenishment(RaiseGroupedReplenishmentRequest request,
                                          StreamObserver<RaiseReplenishmentResponse> response) {
        reply(response, replenishment.raiseGroupedReplenishment(request.hasSupplierId() ? request.getSupplierId() : null,
                request.getLinesList().stream()
                        .map(line -> new ReplenishmentLineInput(line.getMaterialCode(), line.getQuantity()))
                        .toList()));
    }

    private static void reply(StreamObserver<RaiseReplenishmentResponse> response, String requisitionId) {
        RaiseReplenishmentResponse.Builder reply = RaiseReplenishmentResponse.newBuilder();
        if (requisitionId != null) reply.setRequisitionId(requisitionId);
        response.onNext(reply.build());
        response.onCompleted();
    }
}
