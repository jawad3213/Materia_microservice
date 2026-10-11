package com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.purchaseOrder.application.dtos.PurchaseOrderView;
import com.materia.backend.contexts.purchaseOrder.domain.ports.in.PurchaseOrderLookupUseCase;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.procurement.v1.FindOrderRequest;
import com.materia.proto.procurement.v1.FindOrderResponse;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Serves procurement.v1.PurchaseOrderLookup from {@link PurchaseOrderLookupUseCase}. */
@Component
public class PurchaseOrderLookupGrpcService extends PurchaseOrderLookupGrpc.PurchaseOrderLookupImplBase {

    private final PurchaseOrderLookupUseCase orders;

    public PurchaseOrderLookupGrpcService(PurchaseOrderLookupUseCase orders) {
        this.orders = orders;
    }

    @Override
    public void findOrder(FindOrderRequest request, StreamObserver<FindOrderResponse> response) {
        FindOrderResponse.Builder reply = FindOrderResponse.newBuilder();
        UUID id = ProtoValues.uuid(request.getPurchaseOrderId());
        if (id != null) orders.findOrder(id).ifPresent(order -> reply.setOrder(toProto(order)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    static com.materia.proto.procurement.v1.PurchaseOrderView toProto(PurchaseOrderView order) {
        com.materia.proto.procurement.v1.PurchaseOrderView.Builder proto =
                com.materia.proto.procurement.v1.PurchaseOrderView.newBuilder()
                        .setReceivable(order.receivable())
                        .setGoodsReceived(order.goodsReceived());
        if (order.id() != null) proto.setId(order.id().toString());
        if (order.orderCode() != null) proto.setOrderCode(order.orderCode());
        if (order.status() != null) proto.setStatus(order.status());
        if (order.supplierId() != null) proto.setSupplierId(order.supplierId().toString());
        if (order.supplierCode() != null) proto.setSupplierCode(order.supplierCode());
        if (order.supplierName() != null) proto.setSupplierName(order.supplierName());
        if (order.currencyCode() != null) proto.setCurrencyCode(order.currencyCode());
        if (order.assignedTo() != null) proto.setAssignedTo(order.assignedTo());
        if (order.assignedToName() != null) proto.setAssignedToName(order.assignedToName());
        if (order.expectedDeliveryDate() != null) proto.setExpectedDeliveryDate(order.expectedDeliveryDate().toString());
        if (order.requisitionId() != null) proto.setRequisitionId(order.requisitionId().toString());
        if (order.lines() != null) order.lines().forEach(line -> proto.addLines(toProto(line)));
        return proto.build();
    }

    private static com.materia.proto.procurement.v1.PurchaseOrderView.Line toProto(PurchaseOrderView.Line line) {
        com.materia.proto.procurement.v1.PurchaseOrderView.Line.Builder proto =
                com.materia.proto.procurement.v1.PurchaseOrderView.Line.newBuilder();
        if (line.id() != null) proto.setId(line.id().toString());
        if (line.lineNumber() != null) proto.setLineNumber(line.lineNumber());
        if (line.materialId() != null) proto.setMaterialId(line.materialId().toString());
        if (line.materialCode() != null) proto.setMaterialCode(line.materialCode());
        if (line.materialName() != null) proto.setMaterialName(line.materialName());
        if (line.unitOfMeasure() != null) proto.setUnitOfMeasure(line.unitOfMeasure());
        if (line.unitPrice() != null) proto.setUnitPrice(ProtoValues.money(line.unitPrice()));
        if (line.quantity() != null) proto.setQuantity(line.quantity());
        if (line.requisitionLineId() != null) proto.setRequisitionLineId(line.requisitionLineId().toString());
        if (line.taxRate() != null) proto.setTaxRate(line.taxRate().toPlainString());
        return proto.build();
    }
}
