package com.materia.backend.gateway.security;

import com.materia.backend.gateway.filter.JwtAuthenticationFilter;
import com.materia.backend.support.fixtures.TestJwtKeys;
import com.materia.backend.support.fixtures.TestKeycloak;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keycloak's tokens: realm roles as ROLE_*, the API client's roles as permissions, a service account's token as a
 * service token; refused when forged, expired, from another issuer or for another audience.
 */
class KeycloakTokensTest {

    private static final String ISSUER = TestKeycloak.ISSUER;
    private static final String API = "materia-api";
    private static final KeyPair REALM_KEYS = TestJwtKeys.generate();

    /** Verified with the realm's public key, as the suites configure it (app.keycloak.public-key). */
    private final AccessTokens tokens = new AccessTokens(ISSUER, "", API,
            TestJwtKeys.base64(TestJwtKeys.publicPem(REALM_KEYS)));
    private final TestKeycloak keycloak = new TestKeycloak(REALM_KEYS.getPrivate());

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static JWTClaimsSet.Builder claims(String subject, List<String> realmRoles, List<String> permissions) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of(API, "account"))
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("realm_access", Map.of("roles", realmRoles))
                .claim("resource_access", Map.of(API, Map.of("roles", permissions)));
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("realm").build(), claims);
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private Authentication authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/invoices");
        request.addHeader("Authorization", "Bearer " + token);
        new JwtAuthenticationFilter(tokens).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("keycloak: a user's realm role becomes ROLE_*, the API client's roles are the permissions")
    void userToken_rolesAndPermissions() throws Exception {
        String userId = UUID.randomUUID().toString();
        String token = sign(claims(userId, List.of("PURCHASER", "offline_access"), List.of("order:read", "order:write"))
                .claim("email", "purchaser@materia.com").claim("azp", "materia-frontend").build(), REALM_KEYS);

        VerifiedToken verified = tokens.verify(token).orElseThrow();

        assertThat(verified.subject()).isEqualTo(userId);
        assertThat(verified.email()).isEqualTo("purchaser@materia.com");
        assertThat(verified.service()).isFalse();
        assertThat(verified.authorities()).extracting("authority")
                .contains("ROLE_PURCHASER", "order:read", "order:write");
    }

    @Test
    @DisplayName("keycloak: a service account's token is a service token, named after its client")
    void serviceAccountToken_isAServiceToken() {
        VerifiedToken verified = tokens.verify(keycloak.serviceToken("procurement")).orElseThrow();

        assertThat(verified.service()).isTrue();
        assertThat(verified.subject()).isEqualTo("service:procurement");
        assertThat(verified.authorities()).containsExactly(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    @Test
    @DisplayName("keycloak: a token for another audience, expired, from another issuer or signed by another key is refused")
    void wrongAudienceExpiredOtherIssuerOrForged_isRefused() throws Exception {
        String subject = UUID.randomUUID().toString();
        String otherAudience = sign(claims(subject, List.of("ADMIN"), List.of()).audience("account").build(), REALM_KEYS);
        String expired = sign(claims(subject, List.of("ADMIN"), List.of())
                .issueTime(Date.from(Instant.now().minusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(1800))).build(), REALM_KEYS);
        String otherIssuer = sign(claims(subject, List.of("ADMIN"), List.of())
                .issuer("http://evil.example/auth/realms/materia").build(), REALM_KEYS);
        String forged = sign(claims(subject, List.of("ADMIN"), List.of()).build(), TestJwtKeys.generate());

        assertThat(tokens.verify(otherAudience)).isEmpty();
        assertThat(tokens.verify(expired)).isEmpty();
        assertThat(tokens.verify(otherIssuer)).isEmpty();
        assertThat(tokens.verify(forged)).isEmpty();
        assertThat(tokens.verify("not-a-jwt")).isEmpty();
    }

    @Test
    @DisplayName("keycloak: the pinned key is read as PEM or as the Base64 of the PEM, and refused when it is no key")
    void publicKey_pemOrBase64() {
        String pem = TestJwtKeys.publicPem(REALM_KEYS);

        assertThat(KeycloakTokens.publicKey(pem)).isEqualTo(REALM_KEYS.getPublic());
        assertThat(KeycloakTokens.publicKey(TestJwtKeys.base64(pem))).isEqualTo(REALM_KEYS.getPublic());
        assertThatThrownBy(() -> KeycloakTokens.publicKey("bm90IGEga2V5")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("security: a user's token signs them in over the REST API and is kept for their calls; a service's does not")
    void restApi_acceptsUsers_notServiceAccounts() throws Exception {
        UUID userId = UUID.randomUUID();
        String user = keycloak.userToken(userId, "ADMIN", List.of("invoice:read"));

        Authentication authentication = authenticate(user);
        assertThat(authentication).isInstanceOf(BearerTokenAuthentication.class);
        assertThat(authentication.getPrincipal()).isEqualTo(userId);
        assertThat(((BearerTokenAuthentication) authentication).getToken()).isEqualTo(user);
        assertThat(authentication.getAuthorities()).extracting("authority").contains("ROLE_ADMIN", "invoice:read");

        SecurityContextHolder.clearContext();
        assertThat(authenticate(keycloak.serviceToken("finance"))).isNull();
    }
}
