package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A call to another service carries the user's own token when there is one; otherwise a service token, from the
 * service's token source (Keycloak in production; here a stand-in that counts what it issued).
 */
class OutgoingCallerTokenTest {

    private final AtomicInteger issued = new AtomicInteger();
    private final OutgoingCallerToken tokens = new OutgoingCallerToken(
            () -> new ServiceTokenSource.IssuedServiceToken("service-token-" + issued.incrementAndGet(), 900));

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("grpc: a call made for a signed-in user carries that user's own token, and asks for no service token")
    void signedInUser_forwardsTheirToken() {
        SecurityContextHolder.getContext().setAuthentication(new BearerTokenAuthentication(UUID.randomUUID(),
                "user-token", List.of(new SimpleGrantedAuthority("ROLE_PURCHASER"))));

        assertThat(tokens.get()).contains("user-token");
        assertThat(issued).hasValue(0);
    }

    @Test
    @DisplayName("grpc: work no user started carries the service's own token")
    void noUser_getsAServiceToken() {
        assertThat(tokens.get()).contains("service-token-1");
    }

    @Test
    @DisplayName("grpc: the service token is reused until half its life is over, not requested for every call")
    void serviceToken_isReused() {
        tokens.get();
        tokens.get();
        tokens.get();

        assertThat(issued).hasValue(1);
    }

    @Test
    @DisplayName("grpc: a token already half way through its life is renewed before the next call")
    void serviceToken_isRenewedHalfWay() {
        OutgoingCallerToken shortLived = new OutgoingCallerToken(
                () -> new ServiceTokenSource.IssuedServiceToken("service-token-" + issued.incrementAndGet(), 0));

        shortLived.get();
        shortLived.get();

        assertThat(issued).hasValue(2);
    }

    @Test
    @DisplayName("grpc: an authentication without a bearer token (a password, a test user) never travels as a token")
    void passwordCredentials_areNeverForwarded() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "password", List.of()));

        assertThat(tokens.get()).contains("service-token-1");
    }
}
