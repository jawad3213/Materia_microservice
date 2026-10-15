package com.materia.backend.contexts.invoice.infrastructure.seed;

import com.materia.backend.common.domain.valueObjects.Money;
import com.materia.backend.common.infrastructure.seed.DevSeed;
import com.materia.grpc.convert.ProtoValues;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.procurement.v1.FindValidatedReceiptsRequest;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptView;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The demo goods receipts the invoices bill, for the invoice seeder: procurement seeds them, so they are read from
 * its receipts (procurement.v1.GoodsReceiptLookup). Procurement may still be starting, or still seeding, when this
 * service seeds, so the read waits for validated receipts for a while; without them no invoice is seeded.
 */
@Component
@Profile(DevSeed.PROFILE)
public class DevReceipts {

    private static final Logger log = LoggerFactory.getLogger(DevReceipts.class);
    private static final int ATTEMPTS = 150;
    private static final long PAUSE_MS = 2_000;

    /** What the invoice seeder needs of a validated receipt. */
    public record SeedReceipt(UUID id, String receiptCode, String purchaseOrderId, String purchaseOrderCode,
                              String supplierId, String supplierName, LocalDate receiptDate, List<Line> lines) {

        public record Line(UUID id, String purchaseOrderLineId, String materialCode, String materialName,
                           String unitOfMeasure, Money unitPrice, Integer quantityReceived, Integer quantityAccepted,
                           Integer quantityRejected) {
        }
    }

    private final GoodsReceiptLookupGrpc.GoodsReceiptLookupBlockingStub receipts;

    public DevReceipts(GoodsReceiptLookupGrpc.GoodsReceiptLookupBlockingStub receipts) {
        this.receipts = receipts;
    }

    /** At most {@code limit} validated receipts, by receipt code. */
    public List<SeedReceipt> validatedReceipts(int limit) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                List<SeedReceipt> found = GrpcCalls.call(() -> receipts.findValidatedReceipts(
                                FindValidatedReceiptsRequest.newBuilder().setLimit(limit).build()))
                        .getReceiptsList().stream()
                        .map(DevReceipts::toSeedReceipt)
                        .toList();
                if (!found.isEmpty()) return found;
            } catch (StatusRuntimeException e) {
                // Still starting: not reachable yet, or too slow to answer (identity's service token included)
                Status.Code code = e.getStatus().getCode();
                if (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED) throw e;
            }
            if (attempt == ATTEMPTS) break;
            sleep();
        }
        log.warn("[DEV-SEED] Procurement has no validated receipt (or is unreachable): no invoice to seed");
        return List.of();
    }

    private static SeedReceipt toSeedReceipt(GoodsReceiptView receipt) {
        return new SeedReceipt(
                UUID.fromString(receipt.getId()),
                receipt.hasReceiptCode() ? receipt.getReceiptCode() : null,
                receipt.hasPurchaseOrderId() ? receipt.getPurchaseOrderId() : null,
                receipt.hasPurchaseOrderCode() ? receipt.getPurchaseOrderCode() : null,
                receipt.hasSupplierId() ? receipt.getSupplierId() : null,
                receipt.hasSupplierName() ? receipt.getSupplierName() : null,
                receipt.hasReceiptDate() ? LocalDate.parse(receipt.getReceiptDate()) : null,
                receipt.getLinesList().stream().map(line -> new SeedReceipt.Line(
                        UUID.fromString(line.getId()),
                        line.hasPurchaseOrderLineId() ? line.getPurchaseOrderLineId() : null,
                        line.hasMaterialCode() ? line.getMaterialCode() : null,
                        line.hasMaterialName() ? line.getMaterialName() : null,
                        line.hasUnitOfMeasure() ? line.getUnitOfMeasure() : null,
                        line.hasUnitPrice() ? ProtoValues.money(line.getUnitPrice()) : null,
                        line.hasQuantityReceived() ? line.getQuantityReceived() : null,
                        line.hasQuantityAccepted() ? line.getQuantityAccepted() : null,
                        line.hasQuantityRejected() ? line.getQuantityRejected() : null)).toList());
    }

    private static void sleep() {
        try {
            Thread.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
