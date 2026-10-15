package com.materia.backend.contexts.goodsReceipt.infrastructure.adapters.out.stock;

import com.materia.backend.contexts.goodsReceipt.domain.events.GoodsReceiptLineReceivedEvent;
import com.materia.backend.contexts.goodsReceipt.domain.ports.out.ReceivedStock;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.masterdata.v1.FindMaterialByCodeRequest;
import com.materia.proto.masterdata.v1.FindMaterialRequest;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Checks materials through master data's catalogue (masterdata.v1.MaterialCatalog), and announces received lines as
 * {@link GoodsReceiptLineReceivedEvent}s, which master data applies to its stock.
 */
@Component
public class MaterialReceivedStock implements ReceivedStock {

    private final MaterialCatalogGrpc.MaterialCatalogBlockingStub materials;
    private final ApplicationEventPublisher events;

    public MaterialReceivedStock(MaterialCatalogGrpc.MaterialCatalogBlockingStub materials,
                                 ApplicationEventPublisher events) {
        this.materials = materials;
        this.events = events;
    }

    @Override
    public boolean knows(UUID materialId, String materialCode) {
        if (materialId != null) {
            return GrpcCalls.call(() -> materials.findMaterial(
                    FindMaterialRequest.newBuilder().setMaterialId(materialId.toString()).build())).hasMaterial();
        }
        return materialCode != null && GrpcCalls.call(() -> materials.findMaterialByCode(
                FindMaterialByCodeRequest.newBuilder().setMaterialCode(materialCode).build())).hasMaterial();
    }

    @Override
    public void received(UUID goodsReceiptId, UUID goodsReceiptLineId, UUID materialId, String materialCode,
                         int acceptedQuantity, int receivedQuantity, String reason, String userId) {
        events.publishEvent(new GoodsReceiptLineReceivedEvent(goodsReceiptId, goodsReceiptLineId, materialId,
                materialCode, acceptedQuantity, receivedQuantity, reason, userId));
    }
}
