package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import com.materia.proto.identity.v1.IssueServiceTokenRequest;
import com.materia.proto.identity.v1.IssueServiceTokenResponse;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A call to another service carries the user's own token when there is one; otherwise a service token, which
 * identity issues for this service's client credentials (here a stand-in identity that records the requests).
 */
class OutgoingCallerTokenTest {

    private final AtomicInteger issued = new AtomicInteger();
    private final AtomicReference<IssueServiceTokenRequest> lastRequest = new AtomicReference<>();
    private ManagedChannel channel;
    private OutgoingCallerToken tokens;

    @BeforeEach
    void startIdentity() throws Exception {
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder.forName(name).directExecutor().addService(new ServiceTokensGrpc.ServiceTokensImplBase() {
            @Override
            public void issueServiceToken(IssueServiceTokenRequest request,
                                          StreamObserver<IssueServiceTokenResponse> response) {
                lastRequest.set(request);
                response.onNext(IssueServiceTokenResponse.newBuilder()
                        .setToken("service-token-" + issued.incrementAndGet())
                        .setExpiresInSeconds(900)
                        .build());
                response.onCompleted();
            }
        }).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        tokens = new OutgoingCallerToken(ServiceTokensGrpc.newBlockingStub(channel), "monolith", "s3cret");
    }

    @AfterEach
    void stop() throws InterruptedException {
        SecurityContextHolder.clearContext();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("grpc: a call made for a signed-in user carries that user's own token, and asks identity for nothing")
    void signedInUser_forwardsTheirToken() {
        SecurityContextHolder.getContext().setAuthentication(new BearerTokenAuthentication(UUID.randomUUID(),
                "user-token", List.of(new SimpleGrantedAuthority("ROLE_PURCHASER"))));

        assertThat(tokens.get()).contains("user-token");
        assertThat(issued).hasValue(0);
    }

    @Test
    @DisplayName("grpc: work no user started carries a service token identity issued for this service's credentials")
    void noUser_getsAServiceTokenFromIdentity() {
        assertThat(tokens.get()).contains("service-token-1");

        assertThat(lastRequest.get().getClientId()).isEqualTo("monolith");
        assertThat(lastRequest.get().getClientSecret()).isEqualTo("s3cret");
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
    @DisplayName("grpc: an authentication without a bearer token (a password, a test user) never travels as a token")
    void passwordCredentials_areNeverForwarded() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "password", List.of()));

        assertThat(tokens.get()).contains("service-token-1");
    }
}
