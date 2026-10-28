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
 * credentials ({@link KeycloakServiceTokens}), reused until half way through its life ({@link CachedServiceTokens}).
 *
 * <p>Created by each service's gRPC configuration ({@link IdentityClientConfig}).
 */
public class OutgoingCallerToken implements Supplier<Optional<String>> {

    private final CachedServiceTokens serviceTokens;

    public OutgoingCallerToken(ServiceTokenSource serviceTokens) {
        this.serviceTokens = new CachedServiceTokens(serviceTokens);
    }

    @Override
    public Optional<String> get() {
        Optional<String> served = GrpcCaller.currentToken();
        if (served.isPresent()) return served;
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        if (user instanceof BearerTokenAuthentication bearer && bearer.getToken() != null) {
            return Optional.of(bearer.getToken());
        }
        return Optional.of(serviceTokens.token());
    }
}
