package com.materia.backend.support.fixtures;

import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.masterdata.v1.FindMaterialByCodeRequest;
import com.materia.proto.masterdata.v1.FindMaterialRequest;
import com.materia.proto.masterdata.v1.FindMaterialResponse;
import com.materia.proto.masterdata.v1.FindOrderableMaterialsRequest;
import com.materia.proto.masterdata.v1.FindOrderableMaterialsResponse;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.masterdata.v1.MaterialReference;
import com.materia.proto.masterdata.v1.OrderableMaterial;
import io.grpc.stub.StreamObserver;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * masterdata.v1.MaterialCatalog over the materials a test registers, answering as master data does: unknown, blank
 * and malformed ids or codes find nothing.
 */
public class TestMaterialCatalog extends MaterialCatalogGrpc.MaterialCatalogImplBase {

    private final Map<UUID, TestMaterial> materials = new ConcurrentHashMap<>();
    private volatile TestMaterial anyLookup;
    private final java.util.concurrent.atomic.AtomicInteger movements = new java.util.concurrent.atomic.AtomicInteger();

    public TestMaterial save(TestMaterial material) {
        materials.put(material.getId(), material);
        return material;
    }

    public void remove(UUID id) {
        materials.remove(id);
    }

    /** How many stock movements master data applied (TestStockKeeper): none when nothing touched stock. */
    public int movements() {
        return movements.get();
    }

    void recordMovement() {
        movements.incrementAndGet();
    }

    /**
     * Every id or code no registered material has finds {@code material} (null: finds nothing), for tests whose
     * documents name materials of their own.
     */
    public void everyLookupFinds(TestMaterial material) {
        this.anyLookup = material;
    }

    /** By id when one is given, else by code (master data's lookup for stock movements). */
    public Optional<TestMaterial> find(UUID id, String code) {
        if (id != null) return Optional.ofNullable(materials.get(id)).or(() -> Optional.ofNullable(anyLookup));
        return byCode(code);
    }

    public Optional<TestMaterial> byCode(String code) {
        if (code == null) return Optional.empty();
        return materials.values().stream().filter(material -> code.equals(material.getCode())).findFirst()
                .or(() -> Optional.ofNullable(anyLookup));
    }

    @Override
    public void findMaterial(FindMaterialRequest request, StreamObserver<FindMaterialResponse> response) {
        UUID id = ProtoValues.uuid(request.getMaterialId());
        reply(id == null ? Optional.empty() : find(id, null), response);
    }

    @Override
    public void findMaterialByCode(FindMaterialByCodeRequest request, StreamObserver<FindMaterialResponse> response) {
        reply(byCode(request.getMaterialCode()), response);
    }

    @Override
    public void findOrderableMaterials(FindOrderableMaterialsRequest request,
                                       StreamObserver<FindOrderableMaterialsResponse> response) {
        FindOrderableMaterialsResponse.Builder reply = FindOrderableMaterialsResponse.newBuilder();
        materials.values().stream()
                .filter(TestMaterial::isOrderable)
                .sorted(Comparator.comparing(TestMaterial::getCode))
                .forEach(material -> {
                    OrderableMaterial.Builder orderable = OrderableMaterial.newBuilder()
                            .setMaterial(toProto(material))
                            .setSupplierId(material.getSupplierId().toString());
                    if (material.getSupplierName() != null) orderable.setSupplierName(material.getSupplierName());
                    reply.addMaterials(orderable);
                });
        response.onNext(reply.build());
        response.onCompleted();
    }

    private static void reply(Optional<TestMaterial> material, StreamObserver<FindMaterialResponse> response) {
        FindMaterialResponse.Builder reply = FindMaterialResponse.newBuilder();
        material.ifPresent(found -> reply.setMaterial(toProto(found)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    public static MaterialReference toProto(TestMaterial material) {
        MaterialReference.Builder proto = MaterialReference.newBuilder()
                .setId(material.getId().toString())
                .setOrderable(material.isOrderable());
        if (material.getCode() != null) proto.setCode(material.getCode());
        if (material.getName() != null) proto.setName(material.getName());
        if (material.getUnitOfMeasureCode() != null) proto.setUnitOfMeasureCode(material.getUnitOfMeasureCode());
        if (material.getStandardPrice() != null) proto.setStandardPrice(ProtoValues.money(material.getStandardPrice()));
        return proto.build();
    }
}
