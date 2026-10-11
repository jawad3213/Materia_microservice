package com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.ReplenishmentUseCase;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.procurement.v1.HasOpenRequisitionForRequest;
import com.materia.proto.procurement.v1.HasOpenRequisitionForResponse;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** Serves procurement.v1.Replenishment from the query of {@link ReplenishmentUseCase}. */
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
}
