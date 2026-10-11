package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.grpc.auth.GrpcCaller;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The token a call to another service carries: the one of the gRPC call being served, else the signed-in user's
 * (kept by the gateway's JWT filter in a {@link BearerTokenAuthentication}), else, for work no user started or a
 * caller authenticated without a token, this application's service token.
 */
@Component
public class OutgoingCallerToken implements Supplier<Optional<String>> {

    static final String SERVICE_NAME = "monolith";

    private final JwtTokenProvider jwt;
    private volatile String serviceToken;
    private volatile long serviceTokenRenewAt;

    public OutgoingCallerToken(JwtTokenProvider jwt) {
        this.jwt = jwt;
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
                    serviceToken = jwt.generateServiceToken(SERVICE_NAME);
                    serviceTokenRenewAt = now + jwt.accessExpirationMs() / 2;
                }
            }
        }
        return serviceToken;
    }
}
