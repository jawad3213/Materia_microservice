package com.materia.backend.contexts.auth.application.services;

import com.materia.backend.contexts.auth.domain.exceptions.InvalidCredentialsException;
import com.materia.backend.contexts.auth.domain.ports.in.ServiceTokenUseCase;
import com.materia.backend.contexts.auth.infrastructure.adapters.out.clients.ConfiguredServiceClients;
import com.materia.backend.contexts.auth.infrastructure.adapters.out.token.JwtTokenIssuer;
import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.backend.support.fixtures.TestJwtKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Identity signs service tokens only for the registered services that present their own secret. */
class ServiceTokenServiceTest {

    private static final KeyPair KEYS = TestJwtKeys.generate();

    private final JwtTokenProvider jwt = new JwtTokenProvider(TestJwtKeys.privatePem(KEYS), TestJwtKeys.publicPem(KEYS),
            900_000L);
    private final ServiceTokenService service = new ServiceTokenService(
            new ConfiguredServiceClients(new MockEnvironment()
                    .withProperty("app.service-clients.procurement", "s3cret")
                    .withProperty("app.service-clients.unconfigured", "")),
            new JwtTokenIssuer(jwt));

    @Test
    @DisplayName("service tokens: a registered service with its secret gets a service token for itself")
    void registeredService_getsItsToken() {
        ServiceTokenUseCase.IssuedServiceToken issued = service.issue("procurement", "s3cret");

        assertThat(jwt.validateAccessToken(issued.token())).isTrue();
        assertThat(jwt.isServiceToken(issued.token())).isTrue();
        assertThat(jwt.getSubjectFromAccessToken(issued.token())).isEqualTo("service:procurement");
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    @DisplayName("service tokens: a wrong secret, an unknown client or a client without a secret gets nothing")
    void wrongCredentials_areRefused() {
        assertThatThrownBy(() -> service.issue("procurement", "guess")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.issue("intruder", "s3cret")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.issue("unconfigured", "")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.issue(null, "s3cret")).isInstanceOf(InvalidCredentialsException.class);
    }
}
