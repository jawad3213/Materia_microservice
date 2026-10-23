package com.materia.backend.gateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Verifies an access token, the REST API's and gRPC's alike: Keycloak issues them all, users' and services' own
 * (docs/architecture/keycloak-migration.md).
 */
@Component
public class AccessTokens {

    private final KeycloakTokens keycloak;

    /**
     * @param issuer    the realm's issuer, as tokens state it (the address a browser reaches Keycloak at)
     * @param realmUri  where this service reaches the realm, when not at the issuer (inside docker compose)
     * @param publicKey the realm's key instead of its JWK set (the test suites); blank in a deployment
     */
    @Autowired
    public AccessTokens(@Value("${app.keycloak.issuer-uri}") String issuer,
                        @Value("${app.keycloak.realm-uri:}") String realmUri,
                        @Value("${app.keycloak.audience:materia-api}") String audience,
                        @Value("${app.keycloak.public-key:}") String publicKey) {
        this(new KeycloakTokens(issuer, realmUri, audience, publicKey));
    }

    public AccessTokens(KeycloakTokens keycloak) {
        this.keycloak = keycloak;
    }

    /** @return what the token says, or empty when it is forged, expired, or meant for another audience */
    public Optional<VerifiedToken> verify(String token) {
        return keycloak.verify(token);
    }
}
