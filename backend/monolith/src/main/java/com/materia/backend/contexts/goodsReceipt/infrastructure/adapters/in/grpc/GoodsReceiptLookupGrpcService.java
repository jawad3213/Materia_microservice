package com.materia.backend.contexts.goodsReceipt.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.goodsReceipt.application.dtos.GoodsReceiptView;
import com.materia.backend.contexts.goodsReceipt.domain.ports.in.GoodsReceiptLookupUseCase;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.procurement.v1.FindReceiptRequest;
import com.materia.proto.procurement.v1.FindReceiptResponse;
import com.materia.proto.procurement.v1.FindReceiptsForOrderRequest;
import com.materia.proto.procurement.v1.FindValidatedReceiptsRequest;
import com.materia.proto.procurement.v1.FindValidatedReceiptsResponse;
import com.materia.proto.procurement.v1.FindReceiptsForOrderResponse;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Serves procurement.v1.GoodsReceiptLookup from {@link GoodsReceiptLookupUseCase}. */
@Component
public class GoodsReceiptLookupGrpcService extends GoodsReceiptLookupGrpc.GoodsReceiptLookupImplBase {

    private final GoodsReceiptLookupUseCase receipts;

    public GoodsReceiptLookupGrpcService(GoodsReceiptLookupUseCase receipts) {
        this.receipts = receipts;
    }

    @Override
    public void findReceipt(FindReceiptRequest request, StreamObserver<FindReceiptResponse> response) {
        FindReceiptResponse.Builder reply = FindReceiptResponse.newBuilder();
        UUID id = ProtoValues.uuid(request.getGoodsReceiptId());
        if (id != null) receipts.findReceipt(id).ifPresent(receipt -> reply.setReceipt(toProto(receipt)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void findReceiptsForOrder(FindReceiptsForOrderRequest request,
                                     StreamObserver<FindReceiptsForOrderResponse> response) {
        FindReceiptsForOrderResponse.Builder reply = FindReceiptsForOrderResponse.newBuilder();
        receipts.findReceiptsForOrder(request.getPurchaseOrderId()).forEach(receipt -> reply.addReceipts(toProto(receipt)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void findValidatedReceipts(FindValidatedReceiptsRequest request,
                                      StreamObserver<FindValidatedReceiptsResponse> response) {
        FindValidatedReceiptsResponse.Builder reply = FindValidatedReceiptsResponse.newBuilder();
        receipts.findValidatedReceipts(request.getLimit()).forEach(receipt -> reply.addReceipts(toProto(receipt)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    static com.materia.proto.procurement.v1.GoodsReceiptView toProto(GoodsReceiptView receipt) {
        com.materia.proto.procurement.v1.GoodsReceiptView.Builder proto =
                com.materia.proto.procurement.v1.GoodsReceiptView.newBuilder()
                        .setValidated(receipt.validated());
        if (receipt.id() != null) proto.setId(receipt.id().toString());
        if (receipt.receiptCode() != null) proto.setReceiptCode(receipt.receiptCode());
        if (receipt.status() != null) proto.setStatus(receipt.status());
        if (receipt.purchaseOrderId() != null) proto.setPurchaseOrderId(receipt.purchaseOrderId());
        if (receipt.purchaseOrderCode() != null) proto.setPurchaseOrderCode(receipt.purchaseOrderCode());
        if (receipt.supplierId() != null) proto.setSupplierId(receipt.supplierId());
        if (receipt.supplierName() != null) proto.setSupplierName(receipt.supplierName());
        if (receipt.receiptDate() != null) proto.setReceiptDate(receipt.receiptDate().toString());
        if (receipt.lines() != null) receipt.lines().forEach(line -> proto.addLines(toProto(line)));
        return proto.build();
    }

    private static com.materia.proto.procurement.v1.GoodsReceiptView.Line toProto(GoodsReceiptView.Line line) {
        com.materia.proto.procurement.v1.GoodsReceiptView.Line.Builder proto =
                com.materia.proto.procurement.v1.GoodsReceiptView.Line.newBuilder();
        if (line.id() != null) proto.setId(line.id().toString());
        if (line.purchaseOrderLineId() != null) proto.setPurchaseOrderLineId(line.purchaseOrderLineId());
        if (line.materialId() != null) proto.setMaterialId(line.materialId().toString());
        if (line.materialCode() != null) proto.setMaterialCode(line.materialCode());
        if (line.materialName() != null) proto.setMaterialName(line.materialName());
        if (line.unitOfMeasure() != null) proto.setUnitOfMeasure(line.unitOfMeasure());
        if (line.unitPrice() != null) proto.setUnitPrice(ProtoValues.money(line.unitPrice()));
        if (line.quantityReceived() != null) proto.setQuantityReceived(line.quantityReceived());
        if (line.quantityAccepted() != null) proto.setQuantityAccepted(line.quantityAccepted());
        if (line.quantityRejected() != null) proto.setQuantityRejected(line.quantityRejected());
        if (line.rejectionReason() != null) proto.setRejectionReason(line.rejectionReason());
        if (line.qualityNotes() != null) proto.setQualityNotes(line.qualityNotes());
        return proto.build();
    }
}
