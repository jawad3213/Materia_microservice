package com.materia.backend.support.fixtures;

import com.materia.proto.masterdata.v1.CategoryNamesByCodeRequest;
import com.materia.proto.masterdata.v1.CategoryNamesByIdRequest;
import com.materia.proto.masterdata.v1.CategoryNamesResponse;
import com.materia.proto.masterdata.v1.CountStockAlertsRequest;
import com.materia.proto.masterdata.v1.CountStockAlertsResponse;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.StockAlertsRequest;
import com.materia.proto.masterdata.v1.StockAlertsResponse;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;

/**
 * masterdata.v1.MasterDataMetrics answering what a test set ({@link #answer}): which materials are stock alerts and
 * the category of each material. How master data picks them is its own tests' business; analytics' tests
 * check what the dashboard makes of the answers. Nothing is an alert until a test says so.
 */
public class TestMasterDataMetrics extends MasterDataMetricsGrpc.MasterDataMetricsImplBase {

    /** A stock alert, most urgent first in {@link #answer}; {@code safetyStock} may be null. */
    public record Alert(String id, String code, String name, int currentStock, int reorderPoint, Integer safetyStock) {
    }

    private volatile List<Alert> alerts = List.of();
    private volatile Map<String, String> categoryById = Map.of();
    private volatile Map<String, String> categoryByCode = Map.of();

    /**
     * @param alerts         the stock alerts, most urgent first (the out-of-stock count is the ones with no stock)
     * @param categoryById   material id -> category name
     * @param categoryByCode material code -> category name
     */
    public void answer(List<Alert> alerts, Map<String, String> categoryById, Map<String, String> categoryByCode) {
        this.alerts = List.copyOf(alerts);
        this.categoryById = Map.copyOf(categoryById);
        this.categoryByCode = Map.copyOf(categoryByCode);
    }

    @Override
    public void countStockAlerts(CountStockAlertsRequest request, StreamObserver<CountStockAlertsResponse> response) {
        long count = alerts.stream().filter(alert -> !request.getOutOfStockOnly() || alert.currentStock() <= 0).count();
        response.onNext(CountStockAlertsResponse.newBuilder().setCount(count).build());
        response.onCompleted();
    }

    @Override
    public void stockAlerts(StockAlertsRequest request, StreamObserver<StockAlertsResponse> response) {
        StockAlertsResponse.Builder reply = StockAlertsResponse.newBuilder();
        alerts.stream().limit(request.getLimit()).forEach(alert -> {
            StockAlertsResponse.StockAlert.Builder proto = StockAlertsResponse.StockAlert.newBuilder()
                    .setId(alert.id())
                    .setCode(alert.code())
                    .setName(alert.name())
                    .setCurrentStock(alert.currentStock())
                    .setReorderPoint(alert.reorderPoint())
                    .setUnit("PCE");
            if (alert.safetyStock() != null) proto.setSafetyStock(alert.safetyStock());
            reply.addAlerts(proto);
        });
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void categoryNamesById(CategoryNamesByIdRequest request, StreamObserver<CategoryNamesResponse> response) {
        reply(request.getMaterialIdsList(), categoryById, response);
    }

    @Override
    public void categoryNamesByCode(CategoryNamesByCodeRequest request, StreamObserver<CategoryNamesResponse> response) {
        reply(request.getMaterialCodesList(), categoryByCode, response);
    }

    private static void reply(List<String> keys, Map<String, String> names, StreamObserver<CategoryNamesResponse> response) {
        CategoryNamesResponse.Builder reply = CategoryNamesResponse.newBuilder();
        keys.stream().filter(names::containsKey).forEach(key -> reply.putCategoryNames(key, names.get(key)));
        response.onNext(reply.build());
        response.onCompleted();
    }
}
