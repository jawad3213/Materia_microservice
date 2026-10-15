package com.materia.backend.contexts.masterData.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.masterData.domain.ports.in.MaterialMetricsUseCase;
import com.materia.grpc.convert.ProtoValues;
import com.materia.proto.masterdata.v1.CategoryNamesByCodeRequest;
import com.materia.proto.masterdata.v1.CategoryNamesByIdRequest;
import com.materia.proto.masterdata.v1.CategoryNamesResponse;
import com.materia.proto.masterdata.v1.CountStockAlertsRequest;
import com.materia.proto.masterdata.v1.CountStockAlertsResponse;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.StockAlertsRequest;
import com.materia.proto.masterdata.v1.StockAlertsResponse;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Serves masterdata.v1.MasterDataMetrics from {@link MaterialMetricsUseCase}. */
@Component
public class MasterDataMetricsGrpcService extends MasterDataMetricsGrpc.MasterDataMetricsImplBase {

    private final MaterialMetricsUseCase materials;

    public MasterDataMetricsGrpcService(MaterialMetricsUseCase materials) {
        this.materials = materials;
    }

    @Override
    public void countStockAlerts(CountStockAlertsRequest request, StreamObserver<CountStockAlertsResponse> response) {
        response.onNext(CountStockAlertsResponse.newBuilder()
                .setCount(materials.countStockAlerts(request.getOutOfStockOnly())).build());
        response.onCompleted();
    }

    @Override
    public void stockAlerts(StockAlertsRequest request, StreamObserver<StockAlertsResponse> response) {
        StockAlertsResponse.Builder reply = StockAlertsResponse.newBuilder();
        materials.stockAlerts(request.getLimit()).forEach(alert -> {
            StockAlertsResponse.StockAlert.Builder line = StockAlertsResponse.StockAlert.newBuilder()
                    .setCurrentStock(alert.currentStock())
                    .setStockOnOrder(alert.stockOnOrder());
            if (alert.id() != null) line.setId(alert.id().toString());
            if (alert.code() != null) line.setCode(alert.code());
            if (alert.name() != null) line.setName(alert.name());
            if (alert.reorderPoint() != null) line.setReorderPoint(alert.reorderPoint());
            if (alert.safetyStock() != null) line.setSafetyStock(alert.safetyStock());
            if (alert.unit() != null) line.setUnit(alert.unit());
            reply.addAlerts(line);
        });
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void categoryNamesById(CategoryNamesByIdRequest request, StreamObserver<CategoryNamesResponse> response) {
        CategoryNamesResponse.Builder reply = CategoryNamesResponse.newBuilder();
        materials.categoryNamesById(request.getMaterialIdsList().stream().map(ProtoValues::uuid)
                        .filter(Objects::nonNull).toList())
                .forEach((UUID id, String name) -> {
                    if (id != null && name != null) reply.putCategoryNames(id.toString(), name);
                });
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void categoryNamesByCode(CategoryNamesByCodeRequest request, StreamObserver<CategoryNamesResponse> response) {
        CategoryNamesResponse.Builder reply = CategoryNamesResponse.newBuilder();
        materials.categoryNamesByCode(request.getMaterialCodesList()).forEach((code, name) -> {
            if (code != null && name != null) reply.putCategoryNames(code, name);
        });
        response.onNext(reply.build());
        response.onCompleted();
    }
}
