package com.materia.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway in front of two stand-in services, procurement and Identity (which publishes the JWK set), that record
 * what reaches them: what is routed where, what is refused before a service sees it, and what a service receives. A
 * stand-in Keycloak publishes the realm's keys, the only ones tokens are verified with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "gateway.rate-limit.max-requests-per-minute=" + GatewayTest.API_BUDGET})
class GatewayTest {

    /** Each client's budget: small, so the throttling test reaches it; the other tests stay well under. */
    static final int API_BUDGET = 60;

    private static final KeyPair KEYCLOAK_KEYS = rsa();
    private static final KeyPair OTHER_KEYS = rsa();
    private static final String KEYCLOAK_KEY_ID = "keycloak-key";

    /** Requests the stand-in services received, with which one and the headers that matter. */
    private static final List<Map<String, String>> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer PROCUREMENT = service("procurement");
    private static final HttpServer IDENTITY = service("identity");
    private static final HttpServer MASTER_DATA = service("masterdata");
    private static final HttpServer FINANCE = service("finance");
    private static final HttpServer ANALYTICS = service("analytics");
    private static final HttpServer KEYCLOAK = keycloak();
    private static final String KEYCLOAK_ISSUER = "http://localhost:" + KEYCLOAK.getAddress().getPort() + "/realms/materia";

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.procurement-uri", () -> "http://localhost:" + PROCUREMENT.getAddress().getPort());
        registry.add("gateway.identity-uri", () -> "http://localhost:" + IDENTITY.getAddress().getPort());
        registry.add("gateway.master-data-uri", () -> "http://localhost:" + MASTER_DATA.getAddress().getPort());
        registry.add("gateway.finance-uri", () -> "http://localhost:" + FINANCE.getAddress().getPort());
        registry.add("gateway.analytics-uri", () -> "http://localhost:" + ANALYTICS.getAddress().getPort());
        registry.add("gateway.keycloak.issuer-uri", () -> KEYCLOAK_ISSUER);
    }

    @AfterAll
    static void stop() {
        PROCUREMENT.stop(0);
        IDENTITY.stop(0);
        MASTER_DATA.stop(0);
        FINANCE.stop(0);
        ANALYTICS.stop(0);
        KEYCLOAK.stop(0);
    }

    @Autowired private WebTestClient client;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer service(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/", exchange -> {
                RECEIVED.add(Map.of(
                        "service", name,
                        "path", exchange.getRequestURI().getPath(),
                        "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                        "trace", String.valueOf(exchange.getRequestHeaders().getFirst("X-Gateway-Trace-Id")),
                        "realIp", String.valueOf(exchange.getRequestHeaders().getFirst("X-Real-IP"))));
                reply(exchange, 200, "{\"ok\":true}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer keycloak() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYCLOAK_KEYS.getPublic())
                    .keyID(KEYCLOAK_KEY_ID).build()).toString();
            server.createContext("/realms/materia/protocol/openid-connect/certs", exchange -> reply(exchange, 200, jwks));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** A user's token for the API, as the realm issues it, signed with the given key and with these realm roles. */
    private static String token(KeyPair keys, Date expiry, List<String> realmRoles) {
        try {
            JWTClaimsSet set = new JWTClaimsSet.Builder()
                    .issuer(KEYCLOAK_ISSUER)
                    .subject(UUID.randomUUID().toString())
                    .audience(List.of("materia-api", "account"))
                    .issueTime(new Date(expiry.getTime() - 120_000))
                    .expirationTime(expiry)
                    .claim("azp", "materia-frontend")
                    .claim("realm_access", Map.of("roles", realmRoles))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEYCLOAK_KEY_ID)
                    .type(JOSEObjectType.JWT).build(), set);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token the realm issued, signed with the given key (Keycloak's own, unless the test forges one). */
    private static String keycloakToken(KeyPair keys, List<String> audience, List<String> realmRoles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(KEYCLOAK_ISSUER)
                    .subject(UUID.randomUUID().toString())
                    .audience(audience)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                    .claim("azp", "materia-frontend")
                    .claim("realm_access", Map.of("roles", realmRoles))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEYCLOAK_KEY_ID)
                    .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String validToken() {
        return token(KEYCLOAK_KEYS, new Date(System.currentTimeMillis() + 60_000), List.of("ADMIN"));
    }

    @Test
    @DisplayName("gateway: a request with a valid token reaches the service with the token and a trace id")
    void validToken_isRouted() {
        String token = validToken();

        String trace = client.get().uri("/api/v1/invoices").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Gateway-Trace-Id");

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0).get("authorization")).isEqualTo("Bearer " + token);
        assertThat(RECEIVED.get(0).get("trace")).isEqualTo(trace).isNotBlank();
    }

    @Test
    @DisplayName("gateway: a request without a token reaches the service, which decides whether the path needs one")
    void noToken_isRouted() {
        client.get().uri("/api/v1/employees").exchange().expectStatus().isOk();

        assertThat(RECEIVED).extracting(r -> r.get("path") + " -> " + r.get("service"))
                .containsExactly("/api/v1/employees -> identity");
    }

    @Test
    @DisplayName("gateway: sign-in, accounts, employees and their history go to Identity, the rest to procurement")
    void identityPaths_goToIdentity() {
        String token = validToken();
        for (String path : List.of("/api/v1/auth/me", "/api/v1/employees", "/api/v1/employees/42",
                "/api/v1/audit/Employee/42", "/api/v1/audit/User/42", "/api/v1/audit/Material/42", "/api/v1/audit",
                "/api/v1/invoices", "/api/v1/authors")) {
            client.get().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
        }

        assertThat(RECEIVED).extracting(r -> r.get("path") + " -> " + r.get("service")).containsExactly(
                "/api/v1/auth/me -> identity",
                "/api/v1/employees -> identity",
                "/api/v1/employees/42 -> identity",
                "/api/v1/audit/Employee/42 -> identity",
                "/api/v1/audit/User/42 -> identity",
                "/api/v1/audit/Material/42 -> masterdata",
                "/api/v1/audit -> procurement",
                "/api/v1/invoices -> finance",
                "/api/v1/authors -> procurement");
    }

    @Test
    @DisplayName("gateway: materials, categories, suppliers and their history go to master data")
    void masterDataPaths_goToMasterData() {
        String token = validToken();
        for (String path : List.of("/api/v1/masterdata/materials", "/api/v1/masterdata/suppliers/42",
                "/api/v1/audit/Material/42", "/api/v1/audit/Category/42", "/api/v1/audit/PurchaseOrder/42")) {
            client.get().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
        }

        assertThat(RECEIVED).extracting(r -> r.get("path") + " -> " + r.get("service")).containsExactly(
                "/api/v1/masterdata/materials -> masterdata",
                "/api/v1/masterdata/suppliers/42 -> masterdata",
                "/api/v1/audit/Material/42 -> masterdata",
                "/api/v1/audit/Category/42 -> masterdata",
                "/api/v1/audit/PurchaseOrder/42 -> procurement");
    }

    @Test
    @DisplayName("gateway: invoices, payments, settings, their history and invoices' attachments go to finance")
    void financePaths_goToFinance() {
        String token = validToken();
        for (String path : List.of("/api/v1/invoices", "/api/v1/payments/42", "/api/v1/settings",
                "/api/v1/audit/Invoice/42", "/api/v1/attachments?ownerType=INVOICE&ownerId=42",
                "/api/v1/attachments?ownerType=PURCHASE_ORDER&ownerId=42", "/api/v1/attachments/42/content?ownerType=INVOICE",
                "/api/v1/attachments/42/content?ownerType=GOODS_RECEIPT", "/api/v1/invoicesx")) {
            client.get().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
        }

        assertThat(RECEIVED).extracting(r -> r.get("path") + " -> " + r.get("service")).containsExactly(
                "/api/v1/invoices -> finance",
                "/api/v1/payments/42 -> finance",
                "/api/v1/settings -> finance",
                "/api/v1/audit/Invoice/42 -> finance",
                "/api/v1/attachments -> finance",
                "/api/v1/attachments -> procurement",
                "/api/v1/attachments/42/content -> finance",
                "/api/v1/attachments/42/content -> procurement",
                "/api/v1/invoicesx -> procurement");
    }

    @Test
    @DisplayName("gateway: the dashboard goes to analytics, the rest to procurement")
    void dashboard_goesToAnalytics() {
        String token = validToken();
        for (String path : List.of("/api/v1/dashboard", "/api/v1/dashboard/overview", "/api/v1/purchase-orders")) {
            client.get().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
        }

        assertThat(RECEIVED).extracting(r -> r.get("path") + " -> " + r.get("service")).containsExactly(
                "/api/v1/dashboard -> analytics",
                "/api/v1/dashboard/overview -> analytics",
                "/api/v1/purchase-orders -> procurement");
    }

    @Test
    @DisplayName("gateway: a token signed with another key is refused (401) and never reaches a service")
    void forgedToken_isRefused() {
        client.get().uri("/api/v1/invoices")
                .header("Authorization", "Bearer " + token(OTHER_KEYS, new Date(System.currentTimeMillis() + 60_000),
                        List.of("ADMIN")))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.status").isEqualTo(401);

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("gateway: an expired token is refused (401) and never reaches a service")
    void expiredToken_isRefused() {
        client.get().uri("/api/v1/invoices")
                .header("Authorization", "Bearer " + token(KEYCLOAK_KEYS, new Date(System.currentTimeMillis() - 600_000), List.of("ADMIN")))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("gateway: a service token, valid between services only, is refused from outside")
    void serviceToken_isRefused() {
        client.get().uri("/api/v1/invoices")
                .header("Authorization", "Bearer " + token(KEYCLOAK_KEYS, new Date(System.currentTimeMillis() + 60_000),
                        List.of("SERVICE")))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("gateway: a Keycloak user's token for the API is routed with the token")
    void keycloakUserToken_isRouted() {
        String token = keycloakToken(KEYCLOAK_KEYS, List.of("materia-api", "account"), List.of("ADMIN"));

        client.get().uri("/api/v1/invoices").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        assertThat(RECEIVED).extracting(r -> r.get("authorization")).containsExactly("Bearer " + token);
    }

    @Test
    @DisplayName("gateway: a token for another audience, of a service account, or not signed by the realm is 401")
    void keycloakTokens_refused() {
        for (String token : List.of(
                keycloakToken(KEYCLOAK_KEYS, List.of("account"), List.of("ADMIN")),
                keycloakToken(KEYCLOAK_KEYS, List.of("materia-api"), List.of("SERVICE")),
                keycloakToken(OTHER_KEYS, List.of("materia-api"), List.of("ADMIN")))) {
            client.get().uri("/api/v1/invoices").header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("gateway: a garbled bearer value is refused (401)")
    void garbledToken_isRefused() {
        client.get().uri("/api/v1/invoices").header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("gateway: the API is throttled per client address (429), and another address is not")
    void api_isThrottledPerClient() {
        String token = validToken();
        for (int i = 0; i < API_BUDGET; i++) {
            client.get().uri("/api/v1/purchase-orders").header("X-Real-IP", "203.0.113.7")
                    .header("Authorization", "Bearer " + token)
                    .exchange().expectStatus().isOk();
        }
        client.get().uri("/api/v1/purchase-orders").header("X-Real-IP", "203.0.113.7")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");
        client.get().uri("/api/v1/purchase-orders").header("X-Real-IP", "198.51.100.9")
                .header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        assertThat(RECEIVED).hasSize(API_BUDGET + 1);
        assertThat(RECEIVED.get(0).get("realIp")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("gateway: a client's own trace id is kept when it looks like one, replaced when it does not")
    void traceId_isKeptOnlyWhenSafe() {
        client.get().uri("/api/v1/invoices").header("X-Gateway-Trace-Id", "trace-abc-12345")
                .exchange().expectHeader().valueEquals("X-Gateway-Trace-Id", "trace-abc-12345");
        client.get().uri("/api/v1/invoices").header("X-Gateway-Trace-Id", "bad value <script>")
                .exchange().expectHeader().value("X-Gateway-Trace-Id", value -> assertThat(value).doesNotContain("bad"));
    }

    @Test
    @DisplayName("gateway: only the API and its docs are routed; anything else is 404")
    void unroutedPaths_are404() {
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        client.get().uri("/internal/anything").exchange().expectStatus().isNotFound();

        assertThat(RECEIVED).isEmpty();
    }
}
