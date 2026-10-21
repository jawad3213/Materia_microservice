package com.materia.backend.support.fixtures;

import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.backend.infrastructure.grpc.JwtTokenVerifier;
import com.materia.backend.infrastructure.grpc.ServiceTokenSource;
import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Identity's stand-in for this application's tests: identity.v1 (UserDirectory) on the in-process server {@value #SERVER_NAME},
 * which {@code app.grpc.identity.target} points at, and Keycloak's for this service's own tokens (the primary
 * {@link ServiceTokenSource}). The directory answers from the accounts tests register ({@link #save}); service
 * tokens are signed with the test key pair. Every call needs a valid token.
 *
 * <p>One server for the JVM, shared by every test context (they all use the same keys and client credentials), so
 * accounts outlive a test's rollback: give each test its own ({@link UserFixtures} makes unique ones). A component
 * of the test sources, so every full application context in the suite registers it.
 */
@Component
@Primary
public class TestIdentity implements ServiceTokenSource {

    public static final String SERVER_NAME = "test-identity";

    private static final TestUserDirectory DIRECTORY = new TestUserDirectory();
    private static volatile JwtTokenProvider jwt;

    static {
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(
                token -> jwt == null ? Optional.empty() : new JwtTokenVerifier(jwt).verify(token), Set.of());
        try {
            InProcessServerBuilder.forName(SERVER_NAME).directExecutor()
                    .addService(ServerInterceptors.intercept(DIRECTORY, new ExceptionTranslatingServerInterceptor(),
                            authentication, new TraceIdServerInterceptor()))
                    .build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final String clientId;

    public TestIdentity(JwtTokenProvider jwt, @Value("${app.service-client.id}") String clientId) {
        TestIdentity.jwt = jwt;
        this.clientId = clientId;
    }

    /** Registers an account with identity; this application then finds it by id or role. */
    public TestAccount save(TestAccount account) {
        return DIRECTORY.save(account);
    }

    /** For tests whose work commits: the account leaves identity with the rest of their data. */
    public void remove(UUID id) {
        DIRECTORY.remove(id);
    }

    /** This service's own token, as Keycloak issues it for its client credentials. */
    @Override
    public IssuedServiceToken issue() {
        return new IssuedServiceToken(jwt.generateServiceToken(clientId), jwt.accessExpirationMs() / 1000);
    }
}
