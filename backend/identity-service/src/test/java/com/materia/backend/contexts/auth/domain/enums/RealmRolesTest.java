package com.materia.backend.contexts.auth.domain.enums;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak puts the roles and permissions in the tokens (keycloak/materia-realm.json): each realm role grants the
 * permissions of the role of the same name here, the API client holds every permission, and the dashboard's role
 * labels stay identity's. A permission added to {@link Role} needs its line in the realm too, and the reverse.
 */
class RealmRolesTest {

    private static JsonNode realm() throws IOException {
        // The module's directory when Maven runs the tests; the repository root is two levels up
        File file = new File("../../keycloak/materia-realm.json");
        assertThat(file).as("keycloak/materia-realm.json").exists();
        return new ObjectMapper().readTree(file);
    }

    @Test
    @DisplayName("realm: each role grants exactly the permissions Role gives it")
    void realmRoles_matchRole() throws IOException {
        JsonNode realmRoles = realm().path("roles").path("realm");
        for (Role role : Role.values()) {
            JsonNode found = null;
            for (JsonNode candidate : realmRoles) {
                if (candidate.path("name").asText().equals(role.getCode())) found = candidate;
            }
            assertThat(found).as("realm role %s", role.getCode()).isNotNull();
            Set<String> granted = new TreeSet<>();
            found.path("composites").path("client").path("materia-api").forEach(p -> granted.add(p.asText()));
            assertThat(granted).as("permissions of %s", role.getCode()).isEqualTo(new TreeSet<>(role.getPermissions()));
        }
    }

    @Test
    @DisplayName("realm: the API client holds every permission of every role, and no other")
    void apiClient_holdsEveryPermission() throws IOException {
        Set<String> declared = new TreeSet<>();
        realm().path("roles").path("client").path("materia-api").forEach(p -> declared.add(p.path("name").asText()));
        Set<String> used = new HashSet<>();
        for (Role role : Role.values()) used.addAll(role.getPermissions());

        assertThat(declared).isEqualTo(new TreeSet<>(used));
    }
}
