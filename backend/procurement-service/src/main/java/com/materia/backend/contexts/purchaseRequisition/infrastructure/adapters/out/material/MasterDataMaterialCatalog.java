package com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.out.material;

import com.materia.backend.contexts.purchaseRequisition.domain.ports.out.MaterialCatalog;
import com.materia.backend.contexts.purchaseRequisition.domain.valueObjects.MaterialSnapshot;
import com.materia.grpc.convert.ProtoValues;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.masterdata.v1.FindMaterialByCodeRequest;
import com.materia.proto.masterdata.v1.FindMaterialRequest;
import com.materia.proto.masterdata.v1.FindMaterialResponse;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.masterdata.v1.MaterialReference;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Reads materials from master data's catalogue (masterdata.v1.MaterialCatalog). */
@Component
public class MasterDataMaterialCatalog implements MaterialCatalog {

    private final MaterialCatalogGrpc.MaterialCatalogBlockingStub materials;

    public MasterDataMaterialCatalog(MaterialCatalogGrpc.MaterialCatalogBlockingStub materials) {
        this.materials = materials;
    }

    @Override
    public Optional<MaterialSnapshot> findById(UUID materialId) {
        if (materialId == null) return Optional.empty();
        return snapshot(GrpcCalls.call(() -> materials.findMaterial(
                FindMaterialRequest.newBuilder().setMaterialId(materialId.toString()).build())));
    }

    @Override
    public Optional<MaterialSnapshot> findByCode(String materialCode) {
        if (materialCode == null) return Optional.empty();
        return snapshot(GrpcCalls.call(() -> materials.findMaterialByCode(
                FindMaterialByCodeRequest.newBuilder().setMaterialCode(materialCode).build())));
    }

    private static Optional<MaterialSnapshot> snapshot(FindMaterialResponse response) {
        return response.hasMaterial() ? Optional.of(toSnapshot(response.getMaterial())) : Optional.empty();
    }

    public static MaterialSnapshot toSnapshot(MaterialReference material) {
        return new MaterialSnapshot(ProtoValues.uuid(material.getId()),
                material.hasCode() ? material.getCode() : null,
                material.hasName() ? material.getName() : null,
                material.hasDescription() ? material.getDescription() : null,
                material.hasUnitOfMeasureCode() ? material.getUnitOfMeasureCode() : null,
                material.hasStandardPrice() ? ProtoValues.money(material.getStandardPrice()) : null,
                material.getOrderable());
    }
}
