package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.backend.support.fixtures.TestJwtKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A call to another service carries the user's own token when there is one, this application's otherwise. */
class OutgoingCallerTokenTest {

    private static final KeyPair KEYS = TestJwtKeys.generate();

    private final JwtTokenProvider jwt = new JwtTokenProvider(TestJwtKeys.privatePem(KEYS), TestJwtKeys.publicPem(KEYS),
            900_000L);
    private final OutgoingCallerToken tokens = new OutgoingCallerToken(jwt);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("grpc: a call made for a signed-in user carries that user's own token")
    void signedInUser_forwardsTheirToken() {
        String userToken = jwt.generateAccessToken(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(new BearerTokenAuthentication(UUID.randomUUID(), userToken,
                List.of(new SimpleGrantedAuthority("ROLE_PURCHASER"))));

        assertThat(tokens.get()).contains(userToken);
    }

    @Test
    @DisplayName("grpc: work no user started (jobs, events, start-up) carries a service token, reused between calls")
    void noUser_usesTheServiceToken() {
        String token = tokens.get().orElseThrow();

        assertThat(jwt.isServiceToken(token)).isTrue();
        assertThat(jwt.getSubjectFromAccessToken(token)).isEqualTo("service:monolith");
        assertThat(jwt.getAuthoritiesFromAccessToken(token)).extracting("authority")
                .containsExactly(JwtTokenProvider.SERVICE_ROLE);
        assertThat(tokens.get()).contains(token);
    }

    @Test
    @DisplayName("grpc: an authentication without a bearer token (a password, a test user) never travels as a token")
    void passwordCredentials_areNeverForwarded() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "password", List.of()));

        String token = tokens.get().orElseThrow();

        assertThat(token).isNotEqualTo("password");
        assertThat(jwt.isServiceToken(token)).isTrue();
    }
}
