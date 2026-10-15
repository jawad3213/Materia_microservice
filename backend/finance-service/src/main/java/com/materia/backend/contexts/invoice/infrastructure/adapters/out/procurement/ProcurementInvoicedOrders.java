package com.materia.backend.contexts.invoice.infrastructure.adapters.out.procurement;

import com.materia.backend.contexts.invoice.domain.ports.out.InvoicedOrders;
import com.materia.grpc.convert.ProtoValues;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.procurement.v1.FindOrderRequest;
import com.materia.proto.procurement.v1.FindOrderResponse;
import com.materia.proto.procurement.v1.FindReceiptsForOrderRequest;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptView;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads orders and receipts from procurement (procurement.v1.PurchaseOrderLookup and GoodsReceiptLookup), for the
 * three-way match.
 */
@Component
public class ProcurementInvoicedOrders implements InvoicedOrders {

    private final PurchaseOrderLookupGrpc.PurchaseOrderLookupBlockingStub orders;
    private final GoodsReceiptLookupGrpc.GoodsReceiptLookupBlockingStub receipts;

    public ProcurementInvoicedOrders(PurchaseOrderLookupGrpc.PurchaseOrderLookupBlockingStub orders,
                                     GoodsReceiptLookupGrpc.GoodsReceiptLookupBlockingStub receipts) {
        this.orders = orders;
        this.receipts = receipts;
    }

    @Override
    public Optional<OrderToInvoice> find(UUID purchaseOrderId) {
        if (purchaseOrderId == null) return Optional.empty();
        FindOrderResponse found = GrpcCalls.call(() -> orders.findOrder(
                FindOrderRequest.newBuilder().setPurchaseOrderId(purchaseOrderId.toString()).build()));
        return found.hasOrder() ? Optional.of(toOrderToInvoice(found.getOrder())) : Optional.empty();
    }

    @Override
    public List<ReceivedLine> validatedReceiptLines(String purchaseOrderId) {
        if (purchaseOrderId == null) return List.of();
        return GrpcCalls.call(() -> receipts.findReceiptsForOrder(
                        FindReceiptsForOrderRequest.newBuilder().setPurchaseOrderId(purchaseOrderId).build()))
                .getReceiptsList().stream()
                .filter(GoodsReceiptView::getValidated)
                .flatMap(receipt -> receipt.getLinesList().stream())
                .map(line -> new ReceivedLine(
                        line.hasPurchaseOrderLineId() ? line.getPurchaseOrderLineId() : null,
                        line.hasQuantityReceived() ? line.getQuantityReceived() : null,
                        line.hasQuantityAccepted() ? line.getQuantityAccepted() : null,
                        line.hasQuantityRejected() ? line.getQuantityRejected() : null))
                .toList();
    }

    private static OrderToInvoice toOrderToInvoice(PurchaseOrderView order) {
        return new OrderToInvoice(ProtoValues.uuid(order.getId()), order.hasOrderCode() ? order.getOrderCode() : null,
                order.getGoodsReceived(),
                order.hasSupplierId() ? ProtoValues.uuid(order.getSupplierId()) : null,
                order.hasSupplierName() ? order.getSupplierName() : null,
                order.hasCurrencyCode() ? order.getCurrencyCode() : null,
                order.getLinesList().stream()
                        .map(line -> new OrderedLine(ProtoValues.uuid(line.getId()),
                                line.hasMaterialCode() ? line.getMaterialCode() : null,
                                line.hasMaterialName() ? line.getMaterialName() : null,
                                line.hasUnitOfMeasure() ? line.getUnitOfMeasure() : null,
                                line.hasQuantity() ? line.getQuantity() : null,
                                line.hasUnitPrice() ? ProtoValues.money(line.getUnitPrice()) : null,
                                line.hasTaxRate() ? ProtoValues.decimal(line.getTaxRate()) : null))
                        .toList());
    }
}
