package com.materia.backend.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * V13__schema_per_service.sql moves live tables, so it is checked on a database that already holds data: migrated to
 * V12, filled, then migrated to V13. Its undo script is checked the same way. Each test gets its own database in the
 * suite's PostgreSQL container, so the application's schema is never touched.
 */
@ExtendWith(DockerRequiredInCi.class)
class SchemaPerServiceMigrationIT {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID SETTING = UUID.randomUUID();

    private String url;

    @BeforeEach
    void createDatabase() throws SQLException {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect(AbstractIntegrationTest.POSTGRES.getJdbcUrl());
             Statement sql = admin.createStatement()) {
            sql.execute("create database " + name);
        }
        String base = AbstractIntegrationTest.POSTGRES.getJdbcUrl();
        url = base.substring(0, base.lastIndexOf('/') + 1) + name;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, AbstractIntegrationTest.POSTGRES.getUsername(),
                AbstractIntegrationTest.POSTGRES.getPassword());
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(url, AbstractIntegrationTest.POSTGRES.getUsername(), AbstractIntegrationTest.POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .target(target)
                .load()
                .migrate();
    }

    /** One row in a table of identity, master data and finance, written while they are still in public. */
    private void fillBeforeV13() throws SQLException {
        try (Connection db = connect(url); Statement sql = db.createStatement()) {
            sql.execute("insert into public.users (id, email, password_hash, role) values ('" + USER
                    + "', 'migrated@example.com', 'hash', 'ADMIN')");
            sql.execute("insert into public.categories (id, code, name, status, created_at) values ('" + CATEGORY
                    + "', 'CAT-MIGRATED', 'Migrated', 'ACTIVE', now())");
            sql.execute("insert into public.app_settings (id, setting_key, setting_value, created_at) values ('"
                    + SETTING + "', 'migration.probe', '42', now())");
        }
    }

    private String scalar(String query) throws SQLException {
        try (Connection db = connect(url); Statement sql = db.createStatement(); ResultSet row = sql.executeQuery(query)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private int foreignKeysAcrossSchemas() throws SQLException {
        return Integer.parseInt(scalar("select count(*) from pg_constraint c "
                + "join pg_class src on src.oid = c.conrelid join pg_class dst on dst.oid = c.confrelid "
                + "where c.contype = 'f' and src.relnamespace <> dst.relnamespace"));
    }

    private int foreignKeys() throws SQLException {
        return Integer.parseInt(scalar("select count(*) from pg_constraint where contype = 'f'"));
    }

    @Test
    @DisplayName("migration: V13 moves tables that hold data to their service's schema, rows and constraints included")
    void v13_movesLiveTablesWithTheirRows() throws SQLException {
        migrate("12");
        fillBeforeV13();
        int foreignKeysBefore = foreignKeys();

        migrate("13");

        assertEquals("migrated@example.com", scalar("select email from identity.users where id = '" + USER + "'"));
        assertEquals("CAT-MIGRATED", scalar("select code from masterdata.categories where id = '" + CATEGORY + "'"));
        assertEquals("42", scalar("select setting_value from finance.app_settings where id = '" + SETTING + "'"));
        assertEquals("0", scalar("select count(*) from information_schema.tables where table_schema = 'public' "
                + "and table_name in ('users', 'categories', 'app_settings', 'purchase_orders', 'invoices')"));
        assertEquals(foreignKeysBefore, foreignKeys(), "every foreign key survives the move");
        assertEquals(0, foreignKeysAcrossSchemas(), "no foreign key crosses two services");
    }

    @Test
    @DisplayName("migration: the unique and check constraints still hold after V13")
    void v13_keepsConstraints() throws SQLException {
        migrate("12");
        fillBeforeV13();
        migrate("13");

        boolean refused;
        try (Connection db = connect(url); Statement sql = db.createStatement()) {
            sql.execute("insert into identity.users (id, email, password_hash, role) values ('" + UUID.randomUUID()
                    + "', 'migrated@example.com', 'hash', 'ADMIN')");
            refused = false;
        } catch (SQLException duplicate) {
            refused = true;
        }
        assertEquals(true, refused, "the unique e-mail constraint moved with identity.users");
    }

    @Test
    @DisplayName("migration: the V13 undo script puts every table back in public, and V13 applies again afterwards")
    void undoScript_restoresPublicAndAllowsV13Again() throws SQLException, IOException {
        migrate("12");
        fillBeforeV13();
        migrate("13");

        try (Connection db = connect(url); Statement sql = db.createStatement()) {
            db.setAutoCommit(false);
            sql.execute(rollbackScript());
            db.commit();
        }

        assertEquals("migrated@example.com", scalar("select email from public.users where id = '" + USER + "'"));
        assertEquals("0", scalar("select count(*) from information_schema.schemata where schema_name in "
                + "('identity', 'masterdata', 'procurement', 'finance', 'analytics')"));
        assertEquals("0", scalar("select count(*) from public.flyway_schema_history where version = '13'"));

        migrate("13");
        assertEquals("migrated@example.com", scalar("select email from identity.users where id = '" + USER + "'"));
        assertFalse(scalar("select count(*) from public.flyway_schema_history where version = '13'").equals("0"));
    }

    private static String rollbackScript() throws IOException {
        try (InputStream in = SchemaPerServiceMigrationIT.class
                .getResourceAsStream("/db/rollback/V13__undo_schema_per_service.sql")) {
            if (in == null) throw new IOException("db/rollback/V13__undo_schema_per_service.sql is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
