package com.materia.backend.infrastructure.seed;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.valueObjects.Money;
import com.materia.backend.common.infrastructure.seed.DevSeed;
import com.materia.grpc.convert.ProtoValues;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.masterdata.v1.FindOrderableMaterialsRequest;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.masterdata.v1.MaterialReference;
import com.materia.proto.masterdata.v1.OrderableMaterial;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The demo catalogue, for the seeders of this application: master data seeds it, so it is read from master data's
 * catalogue (masterdata.v1.MaterialCatalog). Master data may still be starting, or still seeding, when this
 * application seeds, so the read waits for a catalogue for a while; without one the seeders run without materials and
 * say so.
 */
@Component
@Profile(DevSeed.PROFILE)
public class DevCatalog {

    private static final Logger log = LoggerFactory.getLogger(DevCatalog.class);
    private static final int ATTEMPTS = 60;
    private static final long PAUSE_MS = 2_000;

    /** What a seeder needs of an orderable material. */
    public record SeedMaterial(UUID id, String code, String name, String unitOfMeasureCode, Money standardPrice,
                               String categoryName, Integer economicOrderQuantity, UUID supplierId,
                               String supplierCode, String supplierName) {

        /** Priced in {@code currency} (the seeded documents are in MAD). */
        public boolean pricedIn(CurrencyCode currency) {
            return standardPrice != null && standardPrice.getCurrency() == currency;
        }
    }

    private final MaterialCatalogGrpc.MaterialCatalogBlockingStub catalog;
    private volatile List<SeedMaterial> orderable;

    public DevCatalog(MaterialCatalogGrpc.MaterialCatalogBlockingStub catalog) {
        this.catalog = catalog;
    }

    /** The orderable materials with a supplier, by code; read once. */
    public List<SeedMaterial> orderableMaterials() {
        List<SeedMaterial> found = orderable;
        if (found == null) {
            found = read();
            if (!found.isEmpty()) orderable = found;
        }
        return found;
    }

    /** The orderable material with this code. */
    public SeedMaterial material(String code) {
        return orderableMaterials().stream()
                .filter(material -> code.equals(material.code()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Seeded material " + code + " not found"));
    }

    private List<SeedMaterial> read() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                List<SeedMaterial> materials = GrpcCalls.call(() -> catalog.findOrderableMaterials(
                                FindOrderableMaterialsRequest.getDefaultInstance()))
                        .getMaterialsList().stream()
                        .map(DevCatalog::toSeedMaterial)
                        .toList();
                if (!materials.isEmpty()) return materials;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.UNAVAILABLE) throw e;
            }
            if (attempt == ATTEMPTS) break;
            sleep();
        }
        log.warn("[DEV-SEED] Master data has no orderable material (or is unreachable): seeding without materials");
        return List.of();
    }

    private static SeedMaterial toSeedMaterial(OrderableMaterial orderable) {
        MaterialReference material = orderable.getMaterial();
        return new SeedMaterial(
                UUID.fromString(material.getId()),
                material.hasCode() ? material.getCode() : null,
                material.hasName() ? material.getName() : null,
                material.hasUnitOfMeasureCode() ? material.getUnitOfMeasureCode() : null,
                material.hasStandardPrice() ? ProtoValues.money(material.getStandardPrice()) : null,
                orderable.hasCategoryName() ? orderable.getCategoryName() : null,
                orderable.hasEconomicOrderQuantity() ? orderable.getEconomicOrderQuantity() : null,
                UUID.fromString(orderable.getSupplierId()),
                orderable.hasSupplierCode() ? orderable.getSupplierCode() : null,
                orderable.hasSupplierName() ? orderable.getSupplierName() : null);
    }

    private static void sleep() {
        try {
            Thread.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
