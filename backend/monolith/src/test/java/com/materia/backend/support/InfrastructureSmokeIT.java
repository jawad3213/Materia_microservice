package com.materia.backend.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the integration infrastructure works before any story builds on it (T031).
 *
 * <p>If this class passes, the container started, every production migration applied to an empty
 * database, and {@code ddl-auto=validate} accepted the result for every mapped entity. Validation
 * runs during context startup, so reaching any test method here is itself that proof: a fresh
 * install builds a schema the application accepts.
 */
class InfrastructureSmokeIT extends AbstractIntegrationTest {

    /** db/migration holds V1__init.sql to V13__schema_per_service.sql; raise this with each new migration. */
    private static final int EXPECTED_MIGRATIONS = 13;

    /** The schemas the application's tables live in: one per service, and public for the shared kernel. */
    private static final String SCHEMAS = "('identity', 'masterdata', 'procurement', 'finance', 'analytics', 'public')";

    /** Where each table lives (V13__schema_per_service.sql). */
    private static final Map<String, String> SCHEMA_OF = Map.ofEntries(
            Map.entry("users", "identity"),
            Map.entry("refresh_tokens", "identity"),
            Map.entry("password_reset_tokens", "identity"),
            Map.entry("employees", "identity"),
            Map.entry("categories", "masterdata"),
            Map.entry("materials", "masterdata"),
            Map.entry("suppliers", "masterdata"),
            Map.entry("supplier_payment_terms", "masterdata"),
            Map.entry("material_stock_movements", "masterdata"),
            Map.entry("purchase_requisitions", "procurement"),
            Map.entry("purchase_requisition_lines", "procurement"),
            Map.entry("purchase_orders", "procurement"),
            Map.entry("purchase_order_lines", "procurement"),
            Map.entry("goods_receipts", "procurement"),
            Map.entry("goods_receipt_lines", "procurement"),
            Map.entry("return_to_vendor", "procurement"),
            Map.entry("return_to_vendor_lines", "procurement"),
            Map.entry("invoices", "finance"),
            Map.entry("invoice_lines", "finance"),
            Map.entry("payments", "finance"),
            Map.entry("payment_lines", "finance"),
            Map.entry("app_settings", "finance"),
            Map.entry("global_code_sequences", "public"),
            Map.entry("outbox_messages", "public"),
            Map.entry("processed_events", "public"),
            Map.entry("audit_log", "public"),
            Map.entry("exchange_rates", "public"),
            Map.entry("attachments", "public"));

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("schema: every test migration applied successfully, in order")
    void allMigrationsApplied() {
        MigrationInfo[] applied = flyway.info().applied();

        assertEquals(EXPECTED_MIGRATIONS, applied.length,
                () -> "Applied: " + Arrays.stream(applied).map(MigrationInfo::getScript).toList());
        assertTrue(Arrays.stream(applied).allMatch(m -> m.getState().isApplied()),
                "A migration was recorded but did not apply cleanly");
    }

    @Test
    @DisplayName("schema: all 28 tables exist (outbox, inbox, audit log, exchange rates, attachments and settings included), including out-of-scope ones that validation requires")
    void allEntityTablesExist() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema in " + SCHEMAS + " and table_type = 'BASE TABLE' "
                        + "and table_name <> 'flyway_schema_history'",
                Integer.class);

        assertEquals(28, tables);
    }

    @Test
    @DisplayName("schema: every table lives in the schema of the service that owns it, the shared kernel's in public")
    void everyTableLivesInItsServicesSchema() {
        Map<String, String> actual = new TreeMap<>();
        jdbc.query("select table_name, table_schema from information_schema.tables "
                        + "where table_schema in " + SCHEMAS + " and table_type = 'BASE TABLE' "
                        + "and table_name <> 'flyway_schema_history'",
                row -> {
                    actual.put(row.getString("table_name"), row.getString("table_schema"));
                });

        assertEquals(new TreeMap<>(SCHEMA_OF), actual);
    }

    @Test
    @DisplayName("schema: the application finds every table through its search_path, without naming a schema")
    void searchPathReachesEveryServiceSchema() {
        String searchPath = jdbc.queryForObject("show search_path", String.class);

        assertEquals("public, identity, masterdata, procurement, finance, analytics", searchPath);
        for (String table : SCHEMA_OF.keySet()) {
            assertNotNull(jdbc.queryForObject("select count(*) from " + table, Long.class), table);
        }
    }

    @Test
    @DisplayName("schema: the drift fix restored the columns the original migrations omitted (FINDING-010)")
    void driftFixColumnsExist() {
        List<String> missing = List.of(
                        "users.must_change_password",
                        "refresh_tokens.created_by",
                        "refresh_tokens.updated_by")
                .stream()
                .filter(qualified -> {
                    String[] parts = qualified.split("\\.");
                    Integer found = jdbc.queryForObject(
                            "select count(*) from information_schema.columns "
                                    + "where table_schema in " + SCHEMAS + " and table_name = ? and column_name = ?",
                            Integer.class, parts[0], parts[1]);
                    return found == null || found == 0;
                })
                .toList();

        assertTrue(missing.isEmpty(), () -> "Columns still missing: " + missing);
    }

    @Test
    @DisplayName("schema: no migration seeds an account (the first administrator comes from AdminAccountBootstrap)")
    void noAccountIsSeeded() {
        Integer seeded = jdbc.queryForObject(
                "select count(*) from identity.users where email in "
                        + "('admin@materia.com','purchaser@materia.com','receiver@materia.com')",
                Integer.class);

        assertEquals(0, seeded);
    }
}
