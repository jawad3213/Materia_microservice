package com.materia.backend.contexts.masterData.infrastructure.adapters.out.requisition;

import com.materia.backend.contexts.masterData.domain.entities.Material;
import com.materia.backend.contexts.masterData.domain.ports.out.RequisitionRequests;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.procurement.v1.HasOpenRequisitionForRequest;
import com.materia.proto.procurement.v1.RaiseGroupedReplenishmentRequest;
import com.materia.proto.procurement.v1.RaiseReplenishmentRequest;
import com.materia.proto.procurement.v1.RaiseReplenishmentResponse;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Asks procurement whether a material already has a requisition in progress, and raises replenishment requisitions,
 * through procurement.v1.Replenishment. Raising is a command: it is never retried, so one reorder never raises two
 * requisitions.
 */
@Component
public class ReplenishmentRequisitionAdapter implements RequisitionRequests {

    private final ReplenishmentGrpc.ReplenishmentBlockingStub replenishment;

    public ReplenishmentRequisitionAdapter(ReplenishmentGrpc.ReplenishmentBlockingStub replenishment) {
        this.replenishment = replenishment;
    }

    @Override
    public String createRequisitionFromReorder(Material material, int quantity, String reason, boolean isUrgent) {
        return createRequisitionFromReorder(material, quantity, reason, isUrgent, null);
    }

    @Override
    public String createRequisitionFromReorder(Material material, int quantity, String reason, boolean isUrgent,
                                               String requesterId) {
        RaiseReplenishmentRequest.Builder request = RaiseReplenishmentRequest.newBuilder()
                .setQuantity(quantity)
                .setUrgent(isUrgent);
        if (material.getId() != null) request.setMaterialId(material.getId().toString());
        if (reason != null) request.setReason(reason);
        if (requesterId != null) request.setRequesterId(requesterId);
        return requisitionId(GrpcCalls.call(() -> replenishment.raiseReplenishment(request.build())));
    }

    @Override
    public String createGroupedRequisition(String supplierId, List<ReorderLine> lines) {
        RaiseGroupedReplenishmentRequest.Builder request = RaiseGroupedReplenishmentRequest.newBuilder();
        if (supplierId != null) request.setSupplierId(supplierId);
        lines.forEach(line -> request.addLines(RaiseGroupedReplenishmentRequest.Line.newBuilder()
                .setMaterialCode(line.materialCode() == null ? "" : line.materialCode())
                .setQuantity(line.quantity())));
        return requisitionId(GrpcCalls.call(() -> replenishment.raiseGroupedReplenishment(request.build())));
    }

    @Override
    public boolean hasOpenRequisitionFor(UUID materialId, String materialCode) {
        HasOpenRequisitionForRequest.Builder request = HasOpenRequisitionForRequest.newBuilder();
        if (materialId != null) request.setMaterialId(materialId.toString());
        if (materialCode != null) request.setMaterialCode(materialCode);
        return GrpcCalls.call(() -> replenishment.hasOpenRequisitionFor(request.build())).getOpen();
    }

    private static String requisitionId(RaiseReplenishmentResponse response) {
        return response.hasRequisitionId() ? response.getRequisitionId() : null;
    }
}
