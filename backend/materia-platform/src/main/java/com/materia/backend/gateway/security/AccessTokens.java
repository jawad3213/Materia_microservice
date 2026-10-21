package com.materia.backend.gateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Verifies an access token, the REST API's and gRPC's alike. While authentication moves to Keycloak
 * (docs/architecture/keycloak-migration.md) both issuers are accepted: a token whose issuer is the Keycloak realm is
 * verified with Keycloak's keys, any other with Identity's. Keycloak is off unless {@code app.keycloak.issuer-uri} is
 * set.
 */
@Component
public class AccessTokens {

    private final JwtTokenProvider identity;
    private final KeycloakTokens keycloak;

    @Autowired
    public AccessTokens(JwtTokenProvider identity,
                        @Value("${app.keycloak.issuer-uri:}") String keycloakIssuer,
                        @Value("${app.keycloak.jwk-set-uri:}") String keycloakJwkSetUri,
                        @Value("${app.keycloak.audience:materia-api}") String keycloakAudience) {
        this(identity, StringUtils.hasText(keycloakIssuer)
                ? new KeycloakTokens(keycloakIssuer, keycloakJwkSetUri, keycloakAudience)
                : null);
    }

    /** @param keycloak null to accept Identity's tokens only */
    public AccessTokens(JwtTokenProvider identity, KeycloakTokens keycloak) {
        this.identity = identity;
        this.keycloak = keycloak;
    }

    /** @return what the token says, or empty when it is invalid or expired */
    public Optional<VerifiedToken> verify(String token) {
        if (keycloak != null && keycloak.issued(token)) {
            return keycloak.verify(token);
        }
        if (!identity.validateAccessToken(token)) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedToken(identity.getSubjectFromAccessToken(token),
                identity.getEmailFromAccessToken(token), identity.getAuthoritiesFromAccessToken(token),
                identity.isServiceToken(token)));
    }
}
