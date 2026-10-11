package com.materia.backend.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.materia.backend.support.fixtures.TestJwtKeys;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Access tokens are signed with RS256 and verified with the public key alone, as another service will verify them. */
class JwtTokenProviderTest {

    private static final KeyPair KEYS = TestJwtKeys.generate();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JwtTokenProvider signing(KeyPair keys) {
        return new JwtTokenProvider(TestJwtKeys.base64(TestJwtKeys.privatePem(keys)),
                TestJwtKeys.base64(TestJwtKeys.publicPem(keys)), 900_000L);
    }

    private static JsonNode header(String token) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))));
    }

    @Test
    @DisplayName("jwt: an access token is signed with RS256 under the key id the JWK set publishes")
    void accessToken_isRs256WithThePublishedKeyId() throws Exception {
        JwtTokenProvider provider = signing(KEYS);
        UUID userId = UUID.randomUUID();

        String token = provider.generateAccessTokenWithAuthorities(userId, "a@example.com",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((List<Map<String, Object>>) provider.publicJwks().get("keys")).get(0);
        assertThat(header(token).path("alg").asText()).isEqualTo("RS256");
        assertThat(header(token).path("kid").asText()).isEqualTo(jwk.get("kid"));
        assertThat(provider.validateAccessToken(token)).isTrue();
        assertThat(provider.getUserIdFromAccessToken(token)).isEqualTo(userId);
        assertThat(provider.getEmailFromAccessToken(token)).isEqualTo("a@example.com");
        assertThat(provider.getAuthoritiesFromAccessToken(token)).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("jwt: the JWK set holds only the public key, and its modulus and exponent are that key's")
    void publicJwks_holdsThePublicKeyOnly() {
        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((List<Map<String, Object>>) signing(KEYS).publicJwks().get("keys")).get(0);
        RSAPublicKey publicKey = (RSAPublicKey) KEYS.getPublic();

        assertThat(jwk).containsOnlyKeys("kty", "use", "alg", "kid", "n", "e");
        assertThat(new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("n"))))
                .isEqualTo(publicKey.getModulus());
        assertThat(new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e"))))
                .isEqualTo(publicKey.getPublicExponent());
    }

    @Test
    @DisplayName("jwt: a service holding only the public key verifies tokens but cannot issue any")
    void verifyOnlyProvider_verifiesButCannotSign() {
        String token = signing(KEYS).generateAccessToken(UUID.randomUUID());
        JwtTokenProvider verifyOnly = new JwtTokenProvider("", TestJwtKeys.publicPem(KEYS), 900_000L);

        assertThat(verifyOnly.validateAccessToken(token)).isTrue();
        assertThatThrownBy(() -> verifyOnly.generateAccessToken(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only verifies");
    }

    @Test
    @DisplayName("jwt: keys are accepted as plain PEM as well as Base64 of the PEM")
    void keys_acceptPlainPem() {
        JwtTokenProvider provider = new JwtTokenProvider(TestJwtKeys.privatePem(KEYS), TestJwtKeys.publicPem(KEYS),
                900_000L);

        assertThat(provider.validateAccessToken(provider.generateAccessToken(UUID.randomUUID()))).isTrue();
    }

    @Test
    @DisplayName("jwt: a token signed with another key pair is rejected")
    void tokenOfAnotherKeyPair_isRejected() {
        String foreign = signing(TestJwtKeys.generate()).generateAccessToken(UUID.randomUUID());

        assertThat(signing(KEYS).validateAccessToken(foreign)).isFalse();
    }

    @Test
    @DisplayName("jwt: an HMAC token keyed with the public key is rejected (no algorithm confusion)")
    void hmacTokenKeyedWithThePublicKey_isRejected() {
        String forged = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", "ROLE_ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(KEYS.getPublic().getEncoded()), Jwts.SIG.HS256)
                .compact();

        assertThat(signing(KEYS).validateAccessToken(forged)).isFalse();
    }

    @Test
    @DisplayName("jwt: a private and a public key from different pairs stop the application from starting")
    void mismatchedKeyPair_failsAtStartup() {
        KeyPair other = TestJwtKeys.generate();

        assertThatThrownBy(() -> new JwtTokenProvider(TestJwtKeys.privatePem(KEYS), TestJwtKeys.publicPem(other),
                900_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the same key pair");
    }

    @Test
    @DisplayName("jwt: a missing public key stops the application from starting")
    void missingPublicKey_failsAtStartup() {
        assertThatThrownBy(() -> new JwtTokenProvider(TestJwtKeys.privatePem(KEYS), " ", 900_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.public-key is not set");
    }
}
