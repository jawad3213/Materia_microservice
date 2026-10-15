package com.materia.backend.support.fixtures;

import com.materia.backend.contexts.invoice.domain.ports.out.InvoicedOrders;
import com.materia.backend.contexts.invoice.infrastructure.adapters.out.procurement.ProcurementInvoicedOrders;
import com.materia.backend.support.fixtures.ProcurementFixtures.TestOrder;
import com.materia.backend.support.fixtures.ProcurementFixtures.TestReceipt;
import com.materia.backend.support.fixtures.ProcurementFixtures.TestReceiptLine;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.procurement.v1.FindOrderRequest;
import com.materia.proto.procurement.v1.FindOrderResponse;
import com.materia.proto.procurement.v1.FindReceiptRequest;
import com.materia.proto.procurement.v1.FindReceiptResponse;
import com.materia.proto.procurement.v1.FindReceiptsForOrderRequest;
import com.materia.proto.procurement.v1.FindReceiptsForOrderResponse;
import com.materia.proto.procurement.v1.FindValidatedReceiptsRequest;
import com.materia.proto.procurement.v1.FindValidatedReceiptsResponse;
import com.materia.proto.procurement.v1.GoodsReceiptLookupGrpc;
import com.materia.proto.procurement.v1.GoodsReceiptView;
import com.materia.proto.procurement.v1.PurchaseOrderLookupGrpc;
import com.materia.proto.procurement.v1.PurchaseOrderView;
import io.grpc.BindableService;
import io.grpc.stub.StreamObserver;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * procurement.v1 (PurchaseOrderLookup, GoodsReceiptLookup) over the orders and receipts a test registers, answering as
 * procurement does: unknown or malformed ids find nothing; an order's receipts are all of them, whatever their status.
 */
public class TestProcurementLookups {

    private final Map<UUID, TestOrder> orders = new ConcurrentHashMap<>();
    private final List<TestReceipt> receipts = new CopyOnWriteArrayList<>();

    public TestOrder save(TestOrder order) {
        orders.put(order.getId(), order);
        return order;
    }

    public TestReceipt save(TestReceipt receipt) {
        receipts.add(receipt);
        return receipt;
    }

    /** Forgets the order's receipts, so a test can register others. */
    public void clearReceipts(TestOrder order) {
        receipts.removeIf(receipt -> receipt.getOrder().getId().equals(order.getId()));
    }

    /** The two contracts, to serve in process. */
    public BindableService[] services() {
        return new BindableService[]{new Orders(), new Receipts()};
    }

    /** The invoice context's view of these orders and receipts, through the contracts (unit tests). */
    public InvoicedOrders invoicedOrders() {
        BindableService[] services = services();
        return new ProcurementInvoicedOrders(
                PurchaseOrderLookupGrpc.newBlockingStub(InProcessGrpc.serving(services[0])),
                GoodsReceiptLookupGrpc.newBlockingStub(InProcessGrpc.serving(services[1])));
    }

    private final class Orders extends PurchaseOrderLookupGrpc.PurchaseOrderLookupImplBase {
        @Override
        public void findOrder(FindOrderRequest request, StreamObserver<FindOrderResponse> response) {
            FindOrderResponse.Builder reply = FindOrderResponse.newBuilder();
            UUID id = ProtoValues.uuid(request.getPurchaseOrderId());
            TestOrder order = id == null ? null : orders.get(id);
            if (order != null) reply.setOrder(toProto(order));
            response.onNext(reply.build());
            response.onCompleted();
        }
    }

    private final class Receipts extends GoodsReceiptLookupGrpc.GoodsReceiptLookupImplBase {
        @Override
        public void findReceipt(FindReceiptRequest request, StreamObserver<FindReceiptResponse> response) {
            FindReceiptResponse.Builder reply = FindReceiptResponse.newBuilder();
            receipts.stream().filter(receipt -> receipt.getId().toString().equals(request.getGoodsReceiptId()))
                    .findFirst().ifPresent(receipt -> reply.setReceipt(toProto(receipt)));
            response.onNext(reply.build());
            response.onCompleted();
        }

        @Override
        public void findReceiptsForOrder(FindReceiptsForOrderRequest request,
                                         StreamObserver<FindReceiptsForOrderResponse> response) {
            FindReceiptsForOrderResponse.Builder reply = FindReceiptsForOrderResponse.newBuilder();
            receipts.stream()
                    .filter(receipt -> receipt.getOrder().getId().toString().equals(request.getPurchaseOrderId()))
                    .forEach(receipt -> reply.addReceipts(toProto(receipt)));
            response.onNext(reply.build());
            response.onCompleted();
        }

        @Override
        public void findValidatedReceipts(FindValidatedReceiptsRequest request,
                                          StreamObserver<FindValidatedReceiptsResponse> response) {
            FindValidatedReceiptsResponse.Builder reply = FindValidatedReceiptsResponse.newBuilder();
            receipts.stream().filter(TestReceipt::isValidated)
                    .sorted(Comparator.comparing(TestReceipt::getReceiptCode))
                    .limit(request.getLimit())
                    .forEach(receipt -> reply.addReceipts(toProto(receipt)));
            response.onNext(reply.build());
            response.onCompleted();
        }
    }

    private static PurchaseOrderView toProto(TestOrder order) {
        PurchaseOrderView.Builder proto = PurchaseOrderView.newBuilder()
                .setId(order.getId().toString())
                .setOrderCode(order.getOrderCode())
                .setStatus(order.getStatus())
                .setReceivable(order.isReceivable())
                .setGoodsReceived(order.isGoodsReceived())
                .setSupplierId(order.getSupplierId().toString())
                .setSupplierCode(order.getSupplierCode())
                .setSupplierName(order.getSupplierName())
                .setCurrencyCode(order.getCurrencyCode());
        int number = 1;
        for (ProcurementFixtures.TestOrderLine line : order.getLines()) {
            PurchaseOrderView.Line.Builder proto2 = PurchaseOrderView.Line.newBuilder()
                    .setId(line.getId().toString())
                    .setLineNumber(number++)
                    .setMaterialId(line.getMaterialId().toString())
                    .setMaterialCode(line.getMaterialCode())
                    .setMaterialName(line.getMaterialName())
                    .setUnitOfMeasure(line.getUnitOfMeasure())
                    .setQuantity(line.getQuantity());
            if (line.getUnitPrice() != null) proto2.setUnitPrice(ProtoValues.money(line.getUnitPrice()));
            if (line.getTaxRate() != null) proto2.setTaxRate(line.getTaxRate().toPlainString());
            proto.addLines(proto2);
        }
        return proto.build();
    }

    private static GoodsReceiptView toProto(TestReceipt receipt) {
        TestOrder order = receipt.getOrder();
        GoodsReceiptView.Builder proto = GoodsReceiptView.newBuilder()
                .setId(receipt.getId().toString())
                .setReceiptCode(receipt.getReceiptCode())
                .setStatus(receipt.getStatus())
                .setValidated(receipt.isValidated())
                .setPurchaseOrderId(order.getId().toString())
                .setPurchaseOrderCode(order.getOrderCode())
                .setSupplierId(order.getSupplierId().toString())
                .setSupplierName(order.getSupplierName());
        for (TestReceiptLine line : receipt.getLines()) {
            GoodsReceiptView.Line.Builder proto2 = GoodsReceiptView.Line.newBuilder()
                    .setId(line.id().toString())
                    .setPurchaseOrderLineId(line.orderLine().getId().toString())
                    .setMaterialId(line.orderLine().getMaterialId().toString())
                    .setMaterialCode(line.orderLine().getMaterialCode())
                    .setMaterialName(line.orderLine().getMaterialName())
                    .setUnitOfMeasure(line.orderLine().getUnitOfMeasure())
                    .setQuantityReceived(line.received())
                    .setQuantityAccepted(line.accepted())
                    .setQuantityRejected(line.rejected());
            if (line.orderLine().getUnitPrice() != null) {
                proto2.setUnitPrice(ProtoValues.money(line.orderLine().getUnitPrice()));
            }
            if (line.reason() != null) proto2.setRejectionReason(line.reason());
            proto.addLines(proto2);
        }
        return proto.build();
    }
}
