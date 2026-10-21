package com.materia.backend.gateway.security;

import com.materia.backend.gateway.filter.JwtAuthenticationFilter;
import com.materia.backend.support.fixtures.TestJwtKeys;
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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak's tokens are read the way Identity's are (realm roles as ROLE_*, the API client's roles as permissions),
 * and accepted beside them while sign-in moves to Keycloak.
 */
class KeycloakTokensTest {

    private static final String ISSUER = "http://localhost:8180/realms/materia";
    private static final String API = "materia-api";
    private static final KeyPair KEYCLOAK_KEYS = TestJwtKeys.generate();
    private static final KeyPair IDENTITY_KEYS = TestJwtKeys.generate();

    private final KeycloakTokens keycloak = new KeycloakTokens(ISSUER, API,
            NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYCLOAK_KEYS.getPublic()).build());
    private final JwtTokenProvider identity = new JwtTokenProvider(TestJwtKeys.privatePem(IDENTITY_KEYS),
            TestJwtKeys.publicPem(IDENTITY_KEYS), 900_000L);
    private final AccessTokens tokens = new AccessTokens(identity, keycloak);

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

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("keycloak").build(), claims);
        jwt.sign(new RSASSASigner(KEYCLOAK_KEYS.getPrivate()));
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
    void userToken_isReadLikeIdentitys() throws Exception {
        String userId = UUID.randomUUID().toString();
        String token = sign(claims(userId, List.of("PURCHASER", "offline_access"), List.of("order:read", "order:write"))
                .claim("email", "purchaser@materia.com").claim("azp", "materia-frontend").build());

        VerifiedToken verified = keycloak.verify(token).orElseThrow();

        assertThat(verified.subject()).isEqualTo(userId);
        assertThat(verified.email()).isEqualTo("purchaser@materia.com");
        assertThat(verified.service()).isFalse();
        assertThat(verified.authorities()).extracting("authority")
                .contains("ROLE_PURCHASER", "order:read", "order:write");
    }

    @Test
    @DisplayName("keycloak: a service account's token is a service token, named after its client")
    void serviceAccountToken_isAServiceToken() throws Exception {
        String token = sign(claims(UUID.randomUUID().toString(), List.of("SERVICE"), List.of())
                .claim("azp", "procurement").build());

        VerifiedToken verified = keycloak.verify(token).orElseThrow();

        assertThat(verified.service()).isTrue();
        assertThat(verified.subject()).isEqualTo("service:procurement");
        assertThat(verified.authorities()).containsExactly(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    @Test
    @DisplayName("keycloak: a token meant for another audience, expired, or signed by another key is refused")
    void wrongAudienceExpiredOrForged_isRefused() throws Exception {
        String otherAudience = sign(claims(UUID.randomUUID().toString(), List.of("ADMIN"), List.of())
                .audience("account").build());
        String expired = sign(claims(UUID.randomUUID().toString(), List.of("ADMIN"), List.of())
                .issueTime(Date.from(Instant.now().minusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(1800))).build());
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),
                claims(UUID.randomUUID().toString(), List.of("ADMIN"), List.of()).build());
        forged.sign(new RSASSASigner(TestJwtKeys.generate().getPrivate()));

        assertThat(keycloak.verify(otherAudience)).isEmpty();
        assertThat(keycloak.verify(expired)).isEmpty();
        assertThat(keycloak.verify(forged.serialize())).isEmpty();
    }

    @Test
    @DisplayName("keycloak: a token naming another issuer is not verified with Keycloak's keys")
    void otherIssuer_isNotKeycloaks() throws Exception {
        String token = sign(claims(UUID.randomUUID().toString(), List.of("ADMIN"), List.of())
                .issuer("http://evil.example/realms/materia").build());

        assertThat(keycloak.issued(token)).isFalse();
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("security: Identity's tokens are still accepted while Keycloak's are")
    void identityTokens_stillAccepted() {
        UUID userId = UUID.randomUUID();
        String token = identity.generateAccessTokenWithAuthorities(userId, "a@example.com",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        VerifiedToken verified = tokens.verify(token).orElseThrow();

        assertThat(verified.subject()).isEqualTo(userId.toString());
        assertThat(verified.service()).isFalse();
        assertThat(tokens.verify(identity.generateServiceToken("finance")).orElseThrow().service()).isTrue();
    }

    @Test
    @DisplayName("security: a Keycloak user's token signs them in over the REST API; a service account's does not")
    void restApi_acceptsKeycloakUsers_notServiceAccounts() throws Exception {
        UUID userId = UUID.randomUUID();
        String user = sign(claims(userId.toString(), List.of("ADMIN"), List.of("invoice:read")).build());
        String service = sign(claims(UUID.randomUUID().toString(), List.of("SERVICE"), List.of())
                .claim("azp", "finance").build());

        Authentication authentication = authenticate(user);
        assertThat(authentication).isInstanceOf(BearerTokenAuthentication.class);
        assertThat(authentication.getPrincipal()).isEqualTo(userId);
        assertThat(authentication.getAuthorities()).extracting("authority").contains("ROLE_ADMIN", "invoice:read");

        SecurityContextHolder.clearContext();
        assertThat(authenticate(service)).isNull();
    }
}
