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
 * The gateway in front of a stand-in service that publishes a JWK set and records what reaches it: what is routed,
 * what is refused before the service sees it, and what the service receives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "gateway.rate-limit.max-auth-requests-per-minute=3"})
class GatewayTest {

    private static final KeyPair KEYS = rsa();
    private static final KeyPair OTHER_KEYS = rsa();
    private static final String KEY_ID = "test-key";

    /** Requests the stand-in service received, with the headers that matter. */
    private static final List<Map<String, String>> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVICE = service();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-uri", () -> "http://localhost:" + SERVICE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVICE.stop(0);
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

    private static HttpServer service() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID(KEY_ID).build())
                    .toString();
            server.createContext("/.well-known/jwks.json", exchange -> reply(exchange, 200, jwks));
            server.createContext("/api/", exchange -> {
                RECEIVED.add(Map.of(
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

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String token(KeyPair keys, Date expiry, Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(expiry);
            claims.forEach(set::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID)
                    .type(JOSEObjectType.JWT).build(), set.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String validToken() {
        return token(KEYS, new Date(System.currentTimeMillis() + 60_000), Map.of("roles", "ROLE_ADMIN"));
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
        client.post().uri("/api/v1/auth/refresh").exchange().expectStatus().isOk();

        assertThat(RECEIVED).extracting(r -> r.get("path")).containsExactly("/api/v1/auth/refresh");
    }

    @Test
    @DisplayName("gateway: a token signed with another key is refused (401) and never reaches a service")
    void forgedToken_isRefused() {
        client.get().uri("/api/v1/invoices")
                .header("Authorization", "Bearer " + token(OTHER_KEYS, new Date(System.currentTimeMillis() + 60_000),
                        Map.of("roles", "ROLE_ADMIN")))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.status").isEqualTo(401);

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("gateway: an expired token is refused (401) and never reaches a service")
    void expiredToken_isRefused() {
        client.get().uri("/api/v1/invoices")
                .header("Authorization", "Bearer " + token(KEYS, new Date(System.currentTimeMillis() - 600_000), Map.of()))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("gateway: a service token, valid between services only, is refused from outside")
    void serviceToken_isRefused() {
        client.get().uri("/api/v1/invoices")
                .header("Authorization", "Bearer " + token(KEYS, new Date(System.currentTimeMillis() + 60_000),
                        Map.of("svc", true, "roles", "ROLE_SERVICE")))
                .exchange()
                .expectStatus().isUnauthorized();

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
    @DisplayName("gateway: sign-in is throttled per client address (429), and another address is not")
    void signIn_isThrottledPerClient() {
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/v1/auth/login").header("X-Real-IP", "203.0.113.7")
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/v1/auth/login").header("X-Real-IP", "203.0.113.7")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");
        client.post().uri("/api/v1/auth/login").header("X-Real-IP", "198.51.100.9")
                .exchange().expectStatus().isOk();

        assertThat(RECEIVED).hasSize(4);
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
    @DisplayName("gateway: only the API, its docs and the public key are routed; anything else is 404")
    void unroutedPaths_are404() {
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        client.get().uri("/internal/anything").exchange().expectStatus().isNotFound();

        assertThat(RECEIVED).isEmpty();
    }
}
