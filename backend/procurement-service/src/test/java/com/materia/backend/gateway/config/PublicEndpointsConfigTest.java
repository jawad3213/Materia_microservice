package com.materia.backend.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default list of public endpoints is what production and staging get when APP_SECURITY_PUBLIC_ENDPOINTS is not
 * set, so it must stay limited to signing in, API docs and health checks.
 */
class PublicEndpointsConfigTest {

    private static List<String> defaultPublicEndpoints() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = PublicEndpointsConfigTest.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        String value = properties.getProperty("app.security.public-endpoints");
        String fallback = value.substring(value.indexOf(':') + 1, value.lastIndexOf('}'));
        return Arrays.stream(fallback.split(",")).map(String::trim).toList();
    }

    @Test
    @DisplayName("security: by default only the API docs and health checks are reachable without a token")
    void defaultPublicEndpoints_areDocsAndHealthOnly() throws Exception {
        List<String> open = defaultPublicEndpoints();

        // Sign-in is Keycloak's: no route of the old one stays open
        assertFalse(open.stream().anyMatch(p -> p.startsWith("/api/v1/auth")), "no sign-in route is served here any more");
        assertTrue(open.contains("/actuator/health/**"), "the deployment health check must stay reachable");
        for (String closed : List.of("/api/v1/auth/**", "/api/v1/masterdata/**", "/api/v1/kafka/**", "/actuator/**")) {
            assertFalse(open.contains(closed), closed + " must require a session");
        }
        assertFalse(open.stream().anyMatch(p -> p.startsWith("/api/v1/masterdata")), "master data must require a session");
    }
}
