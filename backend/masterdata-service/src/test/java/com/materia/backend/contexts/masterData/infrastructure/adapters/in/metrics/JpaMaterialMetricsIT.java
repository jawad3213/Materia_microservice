package com.materia.backend.contexts.masterData.infrastructure.adapters.in.metrics;

import com.materia.backend.contexts.masterData.application.dtos.material.StockAlert;
import com.materia.backend.contexts.masterData.domain.ports.in.MaterialMetricsUseCase;
import com.materia.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The figures master data gives the dashboard (masterdata.v1.MasterDataMetrics), on its own tables: which materials are
 * stock alerts, most urgent first, and the category of each material by id or code.
 */
class JpaMaterialMetricsIT extends AbstractIntegrationTest {

    @Autowired private MaterialMetricsUseCase metrics;
    @Autowired private JdbcTemplate jdbc;

    private final UUID bolts = UUID.randomUUID();
    private final UUID paint = UUID.randomUUID();
    private final UUID screws = UUID.randomUUID();

    @BeforeEach
    void catalogue() {
        jdbc.update("delete from material_stock_movements");
        jdbc.update("delete from materials");
        material(bolts, "MAT-1", "Bolts", "Fasteners", 8, 10, null, "ACTIVE");
        material(paint, "MAT-2", "Paint", "Coatings", 0, 5, null, "ACTIVE");
        material(UUID.randomUUID(), "MAT-3", "Glue", null, 1, 4, 2, "ACTIVE");
        material(UUID.randomUUID(), "MAT-4", "Old part", null, 0, 2, null, "OBSOLETE");
        material(screws, "MAT-5", "Screws", "Fasteners", 50, 10, null, "ACTIVE");
    }

    @Test
    @DisplayName("alerts: active materials at or below their reorder point; the obsolete one is not an alert")
    void count() {
        assertEquals(3, metrics.countStockAlerts(false));
        assertEquals(1, metrics.countStockAlerts(true), "MAT-2 is out of stock");
    }

    @Test
    @DisplayName("alerts: out of stock first, then below safety stock, then furthest below the reorder point")
    void mostUrgentFirst() {
        List<StockAlert> alerts = metrics.stockAlerts(10);

        assertEquals(List.of("MAT-2", "MAT-3", "MAT-1"), alerts.stream().map(StockAlert::code).toList());
        assertEquals(List.of("MAT-2"), metrics.stockAlerts(1).stream().map(StockAlert::code).toList());
    }

    @Test
    @DisplayName("categories: by id and by code, absent for a material without one or unknown")
    void categories() {
        Map<UUID, String> byId = metrics.categoryNamesById(List.of(bolts, paint, screws, UUID.randomUUID()));
        assertEquals("Fasteners", byId.get(bolts));
        assertEquals("Coatings", byId.get(paint));
        assertEquals("Fasteners", byId.get(screws));

        Map<String, String> byCode = metrics.categoryNamesByCode(List.of("MAT-1", "MAT-2", "MAT-3", "NOPE"));
        assertEquals("Fasteners", byCode.get("MAT-1"));
        assertEquals("Coatings", byCode.get("MAT-2"));
        assertFalse(byCode.containsKey("NOPE"));
    }

    private void material(UUID id, String code, String name, String category, int stock, int reorder, Integer safety,
                          String status) {
        jdbc.update("insert into materials (id, code, name, category_name, material_type, status, current_stock, "
                        + "available_stock, reorder_point, safety_stock, stock_on_order, unit_of_measure, created_at, version) "
                        + "values (?, ?, ?, ?, 'RAW_MATERIAL', ?, ?, ?, ?, ?, 0, 'PCE', now(), 0)",
                id, code, name, category, status, stock, stock, reorder, safety);
    }
}
