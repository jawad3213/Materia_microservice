package com.materia.backend.support.fixtures;

import com.materia.backend.contexts.goodsReceipt.application.services.GoodsReceiptLookupService;
import com.materia.backend.contexts.goodsReceipt.domain.ports.out.GoodsReceiptRepository;
import com.materia.backend.contexts.goodsReceipt.domain.ports.out.OrderedGoods;
import com.materia.backend.contexts.goodsReceipt.domain.ports.out.ReceivedStock;
import com.materia.backend.contexts.goodsReceipt.infrastructure.adapters.out.purchaseOrder.PurchaseOrderGoodsAdapter;
import com.materia.backend.contexts.goodsReceipt.infrastructure.adapters.out.stock.MaterialReceivedStock;
import com.materia.backend.contexts.invoice.domain.ports.out.InvoicedOrders;
import com.materia.backend.contexts.invoice.infrastructure.adapters.out.procurement.ProcurementInvoicedOrders;
import com.materia.backend.contexts.goodsReceipt.infrastructure.adapters.in.grpc.GoodsReceiptLookupGrpcService;
import com.materia.backend.contexts.masterData.application.services.MaterialInventoryService;
import com.materia.backend.contexts.masterData.infrastructure.adapters.in.grpc.MaterialCatalogGrpcService;
import com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.in.grpc.PurchaseOrderLookupGrpcService;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.backend.contexts.masterData.domain.entities.Material;
import com.materia.backend.contexts.masterData.domain.ports.out.MaterialRepository;
import com.materia.backend.contexts.purchaseOrder.application.services.PurchaseOrderLookupService;
import com.materia.backend.contexts.purchaseOrder.application.services.PurchaseOrderReceivingService;
import com.materia.backend.contexts.purchaseOrder.domain.events.PurchaseOrderConfirmedEvent;
import com.materia.backend.contexts.purchaseOrder.domain.events.PurchaseOrderDeliveredEvent;
import com.materia.backend.contexts.purchaseOrder.domain.events.PurchaseOrderSubmittedEvent;
import com.materia.backend.contexts.purchaseOrder.domain.ports.out.PurchaseOrderEventPublisher;
import com.materia.backend.contexts.purchaseOrder.domain.ports.out.PurchaseOrderRepository;
import com.materia.backend.contexts.purchaseRequisition.domain.ports.out.MaterialCatalog;
import com.materia.backend.contexts.purchaseRequisition.domain.valueObjects.MaterialSnapshot;
import com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.out.material.MasterDataMaterialCatalog;
import com.materia.backend.contexts.returnToVendor.domain.ports.out.ReturnSources;
import com.materia.backend.contexts.returnToVendor.infrastructure.adapters.out.procurement.ProcurementReturnSources;

/**
 * Wires a context's outbound port to the real adapter and the real public use case of the context it calls, over
 * that context's (usually mocked) repositories. Tests keep stubbing the repositories they always did while the
 * production code only sees its own port. A call to another service goes through its gRPC contract, in process
 * ({@link InProcessGrpc}), as in the application.
 */
public final class CrossContextWiring {

    private CrossContextWiring() {
    }

    /** The requisition context's view of the materials held by {@code materials}. */
    public static MaterialCatalog materialCatalogOver(MaterialRepository materials) {
        return new MasterDataMaterialCatalog(materialStub(new MaterialInventoryService(materials)));
    }

    /** What a requisition line records of {@code material}. */
    public static MaterialSnapshot snapshotOf(Material material) {
        return MasterDataMaterialCatalog.toSnapshot(
                MaterialCatalogGrpcService.toProto(MaterialInventoryService.toReference(material)));
    }

    /** The goods receipt context's view of the purchase orders held by {@code orders}. */
    public static OrderedGoods orderedGoodsOver(PurchaseOrderRepository orders, PurchaseOrderEventPublisher orderEvents) {
        return new PurchaseOrderGoodsAdapter(new PurchaseOrderLookupService(orders),
                new PurchaseOrderReceivingService(orders, orderEvents));
    }

    /** The goods receipt context's view of the stock of the materials held by {@code materials}. */
    public static ReceivedStock receivedStockOver(MaterialRepository materials) {
        return receivedStockOver(materials, new InlineEvents());
    }

    /**
     * Same, over {@code events}: master data's handler applies the announced lines at once. Register the receipt's
     * stock-levels handler on {@code events} to also see the levels recorded on the lines.
     */
    public static ReceivedStock receivedStockOver(MaterialRepository materials, InlineEvents events) {
        MaterialInventoryService inventory = new MaterialInventoryService(materials);
        events.add(new com.materia.backend.contexts.masterData.infrastructure.adapters.in.events.ReceivedGoodsStockHandler(
                inventory, events));
        return new MaterialReceivedStock(materialStub(inventory), events);
    }

    /** The invoice context's view of the orders in {@code orders} and the receipts in {@code receipts}. */
    public static InvoicedOrders invoicedOrdersOver(PurchaseOrderRepository orders, GoodsReceiptRepository receipts) {
        return new ProcurementInvoicedOrders(
                PurchaseOrderLookupGrpc.newBlockingStub(InProcessGrpc.serving(
                        new PurchaseOrderLookupGrpcService(new PurchaseOrderLookupService(orders)))),
                GoodsReceiptLookupGrpc.newBlockingStub(InProcessGrpc.serving(
                        new GoodsReceiptLookupGrpcService(new GoodsReceiptLookupService(receipts)))));
    }

    /**
     * The return-to-vendor context's view of receipts and orders; goods put back on order reach the materials held by
     * {@code materials} through master data's handler of {@code ReplacementPutOnOrder}, applied at once.
     */
    public static ReturnSources returnSourcesOver(GoodsReceiptRepository receipts, PurchaseOrderRepository orders,
                                                  MaterialRepository materials) {
        InlineEvents events = new InlineEvents().add(
                new com.materia.backend.contexts.masterData.infrastructure.adapters.in.events.StockOnOrderHandler(
                        new MaterialInventoryService(materials)));
        return new ProcurementReturnSources(new GoodsReceiptLookupService(receipts), new PurchaseOrderLookupService(orders),
                new PurchaseOrderReceivingService(orders, NO_ORDER_EVENTS), events);
    }

    /** Master data's catalogue contract, served over {@code inventory}. */
    private static MaterialCatalogGrpc.MaterialCatalogBlockingStub materialStub(MaterialInventoryService inventory) {
        return MaterialCatalogGrpc.newBlockingStub(InProcessGrpc.serving(new MaterialCatalogGrpcService(inventory)));
    }

    private static final PurchaseOrderEventPublisher NO_ORDER_EVENTS = new PurchaseOrderEventPublisher() {
        @Override public void publish(PurchaseOrderSubmittedEvent event) { }
        @Override public void publish(PurchaseOrderConfirmedEvent event) { }
        @Override public void publish(PurchaseOrderDeliveredEvent event) { }
    };
}
