package com.materia.backend.contexts.masterData.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.masterData.application.dtos.material.MaterialReference;
import com.materia.backend.contexts.masterData.application.dtos.material.OrderableMaterial;
import com.materia.backend.contexts.masterData.domain.ports.in.MaterialInventoryUseCase;
import com.materia.backend.contexts.masterData.domain.ports.in.OrderableMaterialsUseCase;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.masterdata.v1.FindMaterialByCodeRequest;
import com.materia.proto.masterdata.v1.FindMaterialRequest;
import com.materia.proto.masterdata.v1.FindMaterialResponse;
import com.materia.proto.masterdata.v1.FindOrderableMaterialsRequest;
import com.materia.proto.masterdata.v1.FindOrderableMaterialsResponse;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Serves masterdata.v1.MaterialCatalog from the reads of {@link MaterialInventoryUseCase} and
 * {@link OrderableMaterialsUseCase}.
 */
@Component
public class MaterialCatalogGrpcService extends MaterialCatalogGrpc.MaterialCatalogImplBase {

    private final MaterialInventoryUseCase materials;
    private final OrderableMaterialsUseCase orderable;

    public MaterialCatalogGrpcService(MaterialInventoryUseCase materials, OrderableMaterialsUseCase orderable) {
        this.materials = materials;
        this.orderable = orderable;
    }

    @Override
    public void findOrderableMaterials(FindOrderableMaterialsRequest request,
                                       StreamObserver<FindOrderableMaterialsResponse> response) {
        FindOrderableMaterialsResponse.Builder reply = FindOrderableMaterialsResponse.newBuilder();
        for (OrderableMaterial found : orderable.findOrderableMaterials()) {
            com.materia.proto.masterdata.v1.OrderableMaterial.Builder proto =
                    com.materia.proto.masterdata.v1.OrderableMaterial.newBuilder()
                            .setMaterial(toProto(found.material()))
                            .setSupplierId(found.supplierId().toString());
            if (found.categoryName() != null) proto.setCategoryName(found.categoryName());
            if (found.economicOrderQuantity() != null) proto.setEconomicOrderQuantity(found.economicOrderQuantity());
            if (found.supplierCode() != null) proto.setSupplierCode(found.supplierCode());
            if (found.supplierName() != null) proto.setSupplierName(found.supplierName());
            reply.addMaterials(proto);
        }
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void findMaterial(FindMaterialRequest request, StreamObserver<FindMaterialResponse> response) {
        UUID id = ProtoValues.uuid(request.getMaterialId());
        reply(id == null ? Optional.empty() : materials.findMaterial(id), response);
    }

    @Override
    public void findMaterialByCode(FindMaterialByCodeRequest request, StreamObserver<FindMaterialResponse> response) {
        reply(materials.findMaterialByCode(request.getMaterialCode()), response);
    }

    private static void reply(Optional<MaterialReference> material, StreamObserver<FindMaterialResponse> response) {
        FindMaterialResponse.Builder reply = FindMaterialResponse.newBuilder();
        material.ifPresent(found -> reply.setMaterial(toProto(found)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    public static com.materia.proto.masterdata.v1.MaterialReference toProto(MaterialReference material) {
        com.materia.proto.masterdata.v1.MaterialReference.Builder proto =
                com.materia.proto.masterdata.v1.MaterialReference.newBuilder()
                        .setOrderable(material.orderable());
        if (material.id() != null) proto.setId(material.id().toString());
        if (material.code() != null) proto.setCode(material.code());
        if (material.name() != null) proto.setName(material.name());
        if (material.description() != null) proto.setDescription(material.description());
        if (material.unitOfMeasureCode() != null) proto.setUnitOfMeasureCode(material.unitOfMeasureCode());
        if (material.standardPrice() != null) proto.setStandardPrice(ProtoValues.money(material.standardPrice()));
        return proto.build();
    }
}
