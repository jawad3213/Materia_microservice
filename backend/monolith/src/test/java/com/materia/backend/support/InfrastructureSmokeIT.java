package com.materia.backend.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    /** db/migration holds V1__init.sql to V14__procurement_shared_kernel_tables.sql; raise this with each new one. */
    private static final int EXPECTED_MIGRATIONS = 14;

    /** The schemas the tables live in: one per service, and public for the legacy shared kernel. */
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
            Map.entry("app_settings", "finance"));

    /**
     * The shared kernel's tables: procurement's own copy (V14, one database per service), and the legacy ones every
     * service shared in public, which the monolith's migrations still create on a new database.
     */
    private static final List<String> SHARED_KERNEL = List.of("global_code_sequences", "outbox_messages",
            "processed_events", "audit_log", "exchange_rates", "attachments");

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
    @DisplayName("schema: all 34 tables exist: the 22 of the contexts, the shared kernel's 6 in public, and procurement's own copy of them")
    void allEntityTablesExist() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema in " + SCHEMAS + " and table_type = 'BASE TABLE' "
                        + "and table_name <> 'flyway_schema_history'",
                Integer.class);

        assertEquals(SCHEMA_OF.size() + 2 * SHARED_KERNEL.size(), tables);
    }

    @Test
    @DisplayName("schema: every table lives in the schema of the service that owns it; procurement has its own shared kernel")
    void everyTableLivesInItsServicesSchema() {
        Set<String> actual = new TreeSet<>(jdbc.queryForList("select table_schema || '.' || table_name "
                        + "from information_schema.tables where table_schema in " + SCHEMAS + " and table_type = 'BASE TABLE' "
                        + "and table_name <> 'flyway_schema_history'", String.class));

        Set<String> expected = new TreeSet<>();
        SCHEMA_OF.forEach((table, schema) -> expected.add(schema + "." + table));
        SHARED_KERNEL.forEach(table -> {
            expected.add("procurement." + table);
            expected.add("public." + table);
        });
        assertEquals(expected, actual);
    }

    @Test
    // Outside the test's transaction: a refused statement would abort it, and the next ones with it.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("schema: the search_path reaches procurement's tables and its shared kernel, and no other service's")
    void searchPathReachesProcurementOnly() {
        String searchPath = jdbc.queryForObject("show search_path", String.class);

        assertEquals("procurement", searchPath);
        SCHEMA_OF.forEach((table, schema) -> {
            if (schema.equals("procurement")) {
                assertNotNull(jdbc.queryForObject("select count(*) from " + table, Long.class), table);
            } else {
                assertThrows(BadSqlGrammarException.class,
                        () -> jdbc.queryForObject("select count(*) from " + table, Long.class), table);
            }
        });
        for (String table : SHARED_KERNEL) {
            assertEquals("procurement", jdbc.queryForObject("select table_schema from information_schema.tables "
                    + "where table_name = ? and table_schema = current_schema()", String.class, table), table);
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
