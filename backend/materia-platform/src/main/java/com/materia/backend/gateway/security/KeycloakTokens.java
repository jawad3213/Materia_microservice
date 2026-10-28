package com.materia.backend.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies the access tokens Keycloak issues and reads them (docs/architecture/keycloak-migration.md): the realm
 * roles become {@code ROLE_<name>}, the roles of the API client (the audience, {@code materia-api}) are the
 * permissions, and a service account's token (realm role {@code SERVICE}) has the subject {@code service:<client id>}.
 *
 * <p>The realm's JWK set is fetched on first use, not at start-up, so a service starts while Keycloak is still
 * starting. A configured public key replaces it (the test suites, which have no Keycloak).
 */
public class KeycloakTokens {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokens.class);
    static final String SERVICE_REALM_ROLE = "SERVICE";
    private static final String SERVICE_SUBJECT_PREFIX = "service:";

    private final String audience;
    private final JwtDecoder decoder;

    /**
     * @param issuer    the realm's issuer, as tokens state it ({@code https://<host>/auth/realms/materia})
     * @param realmUri  where this service reaches the realm; the issuer when blank (Keycloak may be reached at another
     *                  address from inside the network than the one tokens name)
     * @param audience  the API client every accepted token must be meant for
     */
    public KeycloakTokens(String issuer, String realmUri, String audience) {
        this(issuer, realmUri, audience, null);
    }

    /**
     * @param publicKey the realm's key, as PEM ({@code -----BEGIN PUBLIC KEY-----}) or the Base64 of the PEM, to
     *                  verify with instead of the realm's JWK set; blank for the JWK set
     */
    public KeycloakTokens(String issuer, String realmUri, String audience, String publicKey) {
        this(issuer, audience, StringUtils.hasText(publicKey)
                ? NimbusJwtDecoder.withPublicKey(publicKey(publicKey)).build()
                : NimbusJwtDecoder
                        .withJwkSetUri((StringUtils.hasText(realmUri) ? realmUri : issuer) + "/protocol/openid-connect/certs")
                        .build());
    }

    KeycloakTokens(String issuer, String audience, JwtDecoder decoder) {
        this.audience = audience;
        this.decoder = decoder;
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            nimbus.setJwtValidator(validator(issuer, audience));
        }
    }

    /** Expiry, issuer, and the API among the token's audiences. */
    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    /** @return what the token says, or empty when it is forged, expired, or meant for another audience */
    public Optional<VerifiedToken> verify(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            // Why a request gets 401 (expired, another issuer or audience, an unknown key): debug level, since a
            // client can send any number of bad tokens
            log.debug("Refused a token: {}", e.getMessage());
            return Optional.empty();
        }
        List<String> realmRoles = roles(jwt.getClaimAsMap("realm_access"));
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        List<String> permissions = resourceAccess == null ? List.of() : roles(resourceAccess.get(audience));

        List<GrantedAuthority> authorities = new ArrayList<>();
        realmRoles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

        boolean service = realmRoles.contains(SERVICE_REALM_ROLE);
        String subject = service ? SERVICE_SUBJECT_PREFIX + jwt.getClaimAsString("azp") : jwt.getSubject();
        return Optional.of(new VerifiedToken(subject, jwt.getClaimAsString("email"), authorities, service));
    }

    /** The {@code roles} of a {@code realm_access} or {@code resource_access.<client>} claim. */
    private static List<String> roles(Object access) {
        if (!(access instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream().map(String::valueOf).toList();
    }

    /** An X.509 RSA public key, as PEM or as the Base64 of the PEM (one line, for an environment file). */
    static RSAPublicKey publicKey(String configured) {
        String pem = configured.trim();
        if (!pem.startsWith("-----BEGIN")) {
            pem = new String(Base64.getMimeDecoder().decode(pem), StandardCharsets.UTF_8).trim();
        }
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(body)));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("app.keycloak.public-key is not an X.509 RSA public key", e);
        }
    }
}
