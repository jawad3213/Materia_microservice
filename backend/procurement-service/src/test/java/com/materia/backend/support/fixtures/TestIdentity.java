package com.materia.backend.support.fixtures;

import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.backend.infrastructure.grpc.JwtTokenVerifier;
import com.materia.grpc.auth.AuthenticationServerInterceptor;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import com.materia.proto.identity.v1.IssueServiceTokenRequest;
import com.materia.proto.identity.v1.IssueServiceTokenResponse;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Identity's stand-in for this application's tests: identity.v1 (UserDirectory, ServiceTokens) on the in-process
 * server {@value #SERVER_NAME}, which {@code app.grpc.identity.target} points at. The directory answers from the
 * accounts tests register ({@link #save}); service tokens are signed with the test key pair, as identity would.
 * Every call needs a valid token, except asking for a service token.
 *
 * <p>One server for the JVM, shared by every test context (they all use the same keys and client credentials), so
 * accounts outlive a test's rollback: give each test its own ({@link UserFixtures} makes unique ones). A component
 * of the test sources, so every full application context in the suite registers it.
 */
@Component
public class TestIdentity {

    public static final String SERVER_NAME = "test-identity";

    private static final TestUserDirectory DIRECTORY = new TestUserDirectory();
    private static volatile JwtTokenProvider jwt;
    private static volatile String clientId;
    private static volatile String clientSecret;

    static {
        String issue = ServiceTokensGrpc.getIssueServiceTokenMethod().getFullMethodName();
        AuthenticationServerInterceptor authentication = new AuthenticationServerInterceptor(
                token -> jwt == null ? Optional.empty() : new JwtTokenVerifier(jwt).verify(token), Set.of(issue));
        InProcessServerBuilder server = InProcessServerBuilder.forName(SERVER_NAME).directExecutor();
        for (BindableService service : new BindableService[]{DIRECTORY, new ServiceTokens()}) {
            server.addService(ServerInterceptors.intercept(service, new ExceptionTranslatingServerInterceptor(),
                    authentication, new TraceIdServerInterceptor()));
        }
        try {
            server.build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public TestIdentity(JwtTokenProvider jwt,
                        @Value("${app.service-client.id}") String clientId,
                        @Value("${app.service-client.secret}") String clientSecret) {
        TestIdentity.jwt = jwt;
        TestIdentity.clientId = clientId;
        TestIdentity.clientSecret = clientSecret;
    }

    /** Registers an account with identity; this application then finds it by id or role. */
    public TestAccount save(TestAccount account) {
        return DIRECTORY.save(account);
    }

    /** For tests whose work commits: the account leaves identity with the rest of their data. */
    public void remove(UUID id) {
        DIRECTORY.remove(id);
    }

    private static final class ServiceTokens extends ServiceTokensGrpc.ServiceTokensImplBase {
        @Override
        public void issueServiceToken(IssueServiceTokenRequest request,
                                      StreamObserver<IssueServiceTokenResponse> response) {
            if (jwt == null || !request.getClientId().equals(clientId)
                    || !request.getClientSecret().equals(clientSecret)) {
                response.onError(Status.UNAUTHENTICATED.withDescription("Unknown service client or wrong secret")
                        .asRuntimeException());
                return;
            }
            response.onNext(IssueServiceTokenResponse.newBuilder()
                    .setToken(jwt.generateServiceToken(request.getClientId()))
                    .setExpiresInSeconds(jwt.accessExpirationMs() / 1000)
                    .build());
            response.onCompleted();
        }
    }
}
