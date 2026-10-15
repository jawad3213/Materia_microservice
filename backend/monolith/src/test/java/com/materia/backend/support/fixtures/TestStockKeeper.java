package com.materia.backend.support.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEvent;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEventHandler;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Master data's side of the stock events, for this application's tests: it keeps the stock of the materials in a
 * {@link TestMaterialCatalog} the way master data does (its StockOnOrderHandler and ReceivedGoodsStockHandler), and
 * answers a received line with {@code ReceiptStockApplied}, handed to {@code replies} as it would arrive from Kafka.
 * <ul>
 *   <li>{@code StockOnOrderChanged} (purchaseOrder) and {@code ReplacementPutOnOrder} (returnToVendor) move the stock
 *   on order; the material is looked up by id, then by code.</li>
 *   <li>{@code GoodsReceiptLineReceived} (goodsReceipt): the accepted quantity enters stock and everything received
 *   leaves the stock on order; the material is looked up by id when given, else by code.</li>
 * </ul>
 */
public class TestStockKeeper implements IntegrationEventHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TestMaterialCatalog materials;
    private final Consumer<IntegrationEvent> replies;

    public TestStockKeeper(TestMaterialCatalog materials, Consumer<IntegrationEvent> replies) {
        this.materials = materials;
        this.replies = replies;
    }

    /** Over {@code materials}, answering on {@code events} (unit tests). */
    public static TestStockKeeper on(InlineEvents events, TestMaterialCatalog materials) {
        TestStockKeeper keeper = new TestStockKeeper(materials, events::deliver);
        events.add(keeper);
        return keeper;
    }

    @Override
    public String consumerName() {
        return "test.master-data-stock";
    }

    @Override
    public boolean handles(IntegrationEvent event) {
        return event.is("purchaseOrder", "StockOnOrderChanged") || event.is("returnToVendor", "ReplacementPutOnOrder")
                || event.is("goodsReceipt", "GoodsReceiptLineReceived");
    }

    /** The material an event moves, as master data looks it up. */
    public Optional<TestMaterial> materialOf(IntegrationEvent event) {
        UUID materialId = uuid(event.payload(), "materialId");
        String materialCode = text(event.payload(), "materialCode");
        if (event.is("goodsReceipt", "GoodsReceiptLineReceived")
                && event.payload().path("quantityAccepted").asInt() > 0) {
            return materials.find(materialId, materialCode);
        }
        return materials.find(materialId, null).or(() -> materials.byCode(materialCode));
    }

    @Override
    public void handle(IntegrationEvent event) {
        JsonNode payload = event.payload();
        UUID materialId = uuid(payload, "materialId");
        String materialCode = text(payload, "materialCode");
        if (event.is("goodsReceipt", "GoodsReceiptLineReceived")) {
            received(payload, materialId, materialCode);
            return;
        }
        int change = event.is("returnToVendor", "ReplacementPutOnOrder")
                ? payload.path("quantity").asInt() : payload.path("quantityChange").asInt();
        Optional<TestMaterial> material = materials.find(materialId, null).or(() -> materials.byCode(materialCode));
        if (change > 0) {
            material.ifPresent(found -> {
                found.addStockOnOrder(change);
                materials.recordMovement();
            });
        } else if (change < 0) {
            material.ifPresent(found -> {
                if (found.releaseStockOnOrder(-change) > 0) materials.recordMovement();
            });
        }
    }

    private void received(JsonNode payload, UUID materialId, String materialCode) {
        int accepted = payload.path("quantityAccepted").asInt();
        int received = payload.path("quantityReceived").asInt();
        if (accepted <= 0) {
            if (received > 0) {
                materials.find(materialId, null).or(() -> materials.byCode(materialCode)).ifPresent(found -> {
                    if (found.releaseStockOnOrder(received) > 0) materials.recordMovement();
                });
            }
            return;
        }
        materials.find(materialId, materialCode).ifPresent(found -> {
            int before = found.receive(accepted);
            found.releaseStockOnOrder(received);
            materials.recordMovement();
            ObjectNode applied = JSON.createObjectNode()
                    .put("goodsReceiptId", text(payload, "goodsReceiptId"))
                    .put("goodsReceiptLineId", text(payload, "goodsReceiptLineId"))
                    .put("stockBefore", before)
                    .put("stockAfter", found.getCurrentStock());
            replies.accept(new IntegrationEvent(UUID.randomUUID(), "ReceiptStockApplied", "masterData",
                    found.getId().toString(), LocalDateTime.now().toString(), applied));
        });
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static UUID uuid(JsonNode payload, String field) {
        String value = text(payload, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
