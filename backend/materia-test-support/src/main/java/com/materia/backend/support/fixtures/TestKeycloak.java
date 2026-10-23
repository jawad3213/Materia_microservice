package com.materia.backend.support.fixtures;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keycloak's stand-in for the suites: access tokens as the materia realm issues them (issuer, audience materia-api,
 * the realm role, the API client's roles as permissions), signed with the suite's key, whose public half the services
 * verify with ({@code app.keycloak.public-key}).
 */
public final class TestKeycloak {

    /** The issuer the suites' services expect ({@code app.keycloak.issuer-uri} of each application-test.properties). */
    public static final String ISSUER = "http://keycloak.test/auth/realms/materia";
    private static final String API = "materia-api";
    private static final long LIFETIME_SECONDS = 900;

    private final PrivateKey key;

    /** @param privateKey PKCS#8, as PEM or the Base64 of the PEM ({@code test.keycloak.private-key}) */
    public TestKeycloak(String privateKey) {
        this.key = privateKey(privateKey);
    }

    public TestKeycloak(PrivateKey key) {
        this.key = key;
    }

    /** A user's token: their id, their realm role, and the permissions it grants. */
    public String userToken(UUID userId, String role, Collection<String> permissions) {
        return sign(claims(userId.toString(), "materia-frontend", List.of(role), permissions));
    }

    /** A service's own token (client credentials): the realm role SERVICE. */
    public String serviceToken(String clientId) {
        return sign(claims(UUID.randomUUID().toString(), clientId, List.of("SERVICE"), List.of()));
    }

    public long lifetimeSeconds() {
        return LIFETIME_SECONDS;
    }

    private static JWTClaimsSet claims(String subject, String clientId, List<String> realmRoles,
                                       Collection<String> permissions) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of(API, "account"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(LIFETIME_SECONDS)))
                .jwtID(UUID.randomUUID().toString())
                .claim("azp", clientId)
                .claim("realm_access", Map.of("roles", realmRoles))
                .claim("resource_access", Map.of(API, Map.of("roles", List.copyOf(permissions))))
                .build();
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-realm-key").build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivateKey privateKey(String configured) {
        String pem = configured.trim();
        if (!pem.startsWith("-----BEGIN")) {
            pem = new String(Base64.getMimeDecoder().decode(pem), StandardCharsets.UTF_8).trim();
        }
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Not a PKCS#8 RSA private key", e);
        }
    }
}
