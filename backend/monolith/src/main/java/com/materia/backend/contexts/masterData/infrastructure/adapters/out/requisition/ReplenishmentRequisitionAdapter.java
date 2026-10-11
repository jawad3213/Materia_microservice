package com.materia.backend.contexts.masterData.infrastructure.adapters.out.requisition;

import com.materia.backend.contexts.masterData.domain.entities.Material;
import com.materia.backend.contexts.masterData.domain.ports.out.RequisitionRequests;
import com.materia.backend.contexts.purchaseRequisition.application.dtos.ReplenishmentLineInput;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.in.ReplenishmentUseCase;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.procurement.v1.HasOpenRequisitionForRequest;
import com.materia.proto.procurement.v1.ReplenishmentGrpc;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Asks procurement whether a material already has a requisition in progress (procurement.v1.Replenishment), and
 * raises replenishment requisitions through the requisition context's public use case: a write, which becomes an
 * event before master data leaves the monolith.
 */
@Component
public class ReplenishmentRequisitionAdapter implements RequisitionRequests {

    private final ReplenishmentUseCase replenishment;
    private final ReplenishmentGrpc.ReplenishmentBlockingStub openRequisitions;

    public ReplenishmentRequisitionAdapter(ReplenishmentUseCase replenishment,
                                           ReplenishmentGrpc.ReplenishmentBlockingStub openRequisitions) {
        this.replenishment = replenishment;
        this.openRequisitions = openRequisitions;
    }

    @Override
    public String createRequisitionFromReorder(Material material, int quantity, String reason, boolean isUrgent) {
        return createRequisitionFromReorder(material, quantity, reason, isUrgent, null);
    }

    @Override
    public String createRequisitionFromReorder(Material material, int quantity, String reason, boolean isUrgent,
                                               String requesterId) {
        return replenishment.raiseReplenishment(material.getId(), quantity, reason, isUrgent, requesterId);
    }

    @Override
    public String createGroupedRequisition(String supplierId, List<ReorderLine> lines) {
        return replenishment.raiseGroupedReplenishment(supplierId, lines.stream()
                .map(line -> new ReplenishmentLineInput(line.materialCode(), line.quantity()))
                .toList());
    }

    @Override
    public boolean hasOpenRequisitionFor(UUID materialId, String materialCode) {
        HasOpenRequisitionForRequest.Builder request = HasOpenRequisitionForRequest.newBuilder();
        if (materialId != null) request.setMaterialId(materialId.toString());
        if (materialCode != null) request.setMaterialCode(materialCode);
        return GrpcCalls.call(() -> openRequisitions.hasOpenRequisitionFor(request.build())).getOpen();
    }
}
