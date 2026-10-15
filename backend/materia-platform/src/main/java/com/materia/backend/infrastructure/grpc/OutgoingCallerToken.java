package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import com.materia.grpc.auth.GrpcCaller;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.identity.v1.IssueServiceTokenRequest;
import com.materia.proto.identity.v1.IssueServiceTokenResponse;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The token a call to another service carries: the one of the gRPC call being served, else the signed-in user's
 * (kept by the gateway's JWT filter in a {@link BearerTokenAuthentication}), else, for work no user started or a
 * caller authenticated without a token, a service token. Identity issues that one (identity.v1.ServiceTokens) for
 * this service's client credentials ({@code app.service-client.id} and {@code .secret}): no service but Identity
 * holds the signing key.
 *
 * <p>Created by each service's gRPC configuration, with a stub on a channel that does not use this class itself.
 */
public class OutgoingCallerToken implements Supplier<Optional<String>> {

    private final ServiceTokensGrpc.ServiceTokensBlockingStub serviceTokens;
    private final String clientId;
    private final String clientSecret;
    private volatile String serviceToken;
    private volatile long serviceTokenRenewAt;

    public OutgoingCallerToken(ServiceTokensGrpc.ServiceTokensBlockingStub serviceTokens,
                               @Value("${app.service-client.id}") String clientId,
                               @Value("${app.service-client.secret}") String clientSecret) {
        this.serviceTokens = serviceTokens;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public Optional<String> get() {
        Optional<String> served = GrpcCaller.currentToken();
        if (served.isPresent()) return served;
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        if (user instanceof BearerTokenAuthentication bearer && bearer.getToken() != null) {
            return Optional.of(bearer.getToken());
        }
        return Optional.of(serviceToken());
    }

    /** Renewed half way through its life, so a call never leaves with a token about to expire. */
    private String serviceToken() {
        long now = System.currentTimeMillis();
        if (serviceToken == null || now >= serviceTokenRenewAt) {
            synchronized (this) {
                if (serviceToken == null || now >= serviceTokenRenewAt) {
                    IssueServiceTokenResponse issued = GrpcCalls.call(() -> serviceTokens.issueServiceToken(
                            IssueServiceTokenRequest.newBuilder()
                                    .setClientId(clientId)
                                    .setClientSecret(clientSecret)
                                    .build()));
                    serviceToken = issued.getToken();
                    serviceTokenRenewAt = now + issued.getExpiresInSeconds() * 1000 / 2;
                }
            }
        }
        return serviceToken;
    }
}
