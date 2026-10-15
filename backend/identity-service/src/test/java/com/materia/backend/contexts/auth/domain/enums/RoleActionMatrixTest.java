package com.materia.backend.contexts.auth.domain.enums;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [T015] The shared action matrix (who may do what to purchase orders and goods receipts) names exactly the roles of
 * {@link Role}, with their permissions. The matrix lives with the monolith's tests, which check its rules; the frontend
 * reads the same file (SC-002a).
 */
class RoleActionMatrixTest {

    /** From this module's directory, where the tests run. */
    private static final Path MATRIX = Path.of("..", "monolith", "src", "test", "resources", "contracts",
            "purchase-order-actions.json");

    private static JsonNode roles;

    @BeforeAll
    static void load() throws IOException {
        roles = new ObjectMapper().readTree(MATRIX.toFile()).path("roles");
    }

    @Test
    @DisplayName("action matrix: lists exactly the roles defined by Role")
    void roles_matchRoleEnum() {
        Set<String> expected = Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> listed = new HashSet<>();
        roles.fieldNames().forEachRemaining(listed::add);

        assertEquals(expected, listed);
    }

    @Test
    @DisplayName("action matrix: each role's order and receipt permissions match Role.java")
    void rolePermissions_matchRoleEnum() {
        for (Role role : Role.values()) {
            Set<String> fromEnum = role.getPermissions().stream()
                    .filter(p -> p.startsWith("order:") || p.startsWith("receipt:"))
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> listed = new TreeSet<>();
            roles.path(role.name()).path("permissions").forEach(p -> listed.add(p.asText()));

            assertEquals(fromEnum, listed, "permissions for " + role);
        }
    }
}
