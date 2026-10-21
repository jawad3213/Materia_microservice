package com.materia.backend.gateway.security;

import com.nimbusds.jwt.JWTParser;
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

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies the access tokens Keycloak issues and reads them the way Identity's are read (docs/architecture/
 * keycloak-migration.md): the realm roles become {@code ROLE_<name>}, the roles of the API client (the audience,
 * {@code materia-api}) are the permissions, and a service account's token (realm role {@code SERVICE}) has the subject
 * {@code service:<client id>}.
 *
 * <p>The JWK set is fetched on first use, not at start-up, so a service starts while Keycloak is still starting.
 */
public class KeycloakTokens {

    static final String SERVICE_REALM_ROLE = "SERVICE";
    private static final String SERVICE_SUBJECT_PREFIX = "service:";

    private final String issuer;
    private final String audience;
    private final JwtDecoder decoder;

    /**
     * @param issuer    the realm's issuer, as tokens state it ({@code https://<host>/realms/materia})
     * @param jwkSetUri where this service reaches the realm's keys; the issuer's own when blank (Keycloak may be
     *                  reached at another address from inside the network than the one tokens name)
     * @param audience  the API client every accepted token must be meant for
     */
    public KeycloakTokens(String issuer, String jwkSetUri, String audience) {
        this(issuer, audience, NimbusJwtDecoder
                .withJwkSetUri(StringUtils.hasText(jwkSetUri) ? jwkSetUri : issuer + "/protocol/openid-connect/certs")
                .build());
    }

    KeycloakTokens(String issuer, String audience, JwtDecoder decoder) {
        this.issuer = issuer;
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

    /** Whether the token says Keycloak issued it (unverified: decides only which verification applies). */
    public boolean issued(String token) {
        try {
            return issuer.equals(JWTParser.parse(token).getJWTClaimsSet().getIssuer());
        } catch (ParseException e) {
            return false;
        }
    }

    /** @return what the token says, or empty when it is forged, expired, or meant for another audience */
    public Optional<VerifiedToken> verify(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
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
}
