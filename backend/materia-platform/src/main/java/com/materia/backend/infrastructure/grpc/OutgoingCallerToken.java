package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import com.materia.grpc.auth.GrpcCaller;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The token a call to another service carries: the one of the gRPC call being served, else the signed-in user's
 * (kept by the gateway's JWT filter in a {@link BearerTokenAuthentication}), else, for work no user started or a
 * caller authenticated without a token, a service token: Keycloak issues that one for this service's client
 * credentials ({@link KeycloakServiceTokens}).
 *
 * <p>Created by each service's gRPC configuration ({@link IdentityClientConfig}).
 */
public class OutgoingCallerToken implements Supplier<Optional<String>> {

    private final ServiceTokenSource serviceTokens;
    private volatile String serviceToken;
    private volatile long serviceTokenRenewAt;

    public OutgoingCallerToken(ServiceTokenSource serviceTokens) {
        this.serviceTokens = serviceTokens;
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
                    ServiceTokenSource.IssuedServiceToken issued = serviceTokens.issue();
                    serviceToken = issued.token();
                    serviceTokenRenewAt = now + issued.expiresInSeconds() * 1000 / 2;
                }
            }
        }
        return serviceToken;
    }
}
