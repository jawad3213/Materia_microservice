package com.materia.backend.infrastructure.grpc;

/**
 * Where a service gets a token of its own, for work no user started (scheduled jobs, event handlers, start-up): in
 * production Keycloak, for the service's client credentials ({@link KeycloakServiceTokens}).
 */
@FunctionalInterface
public interface ServiceTokenSource {

    /** A new token; asked again once half its life is over ({@link OutgoingCallerToken}). */
    IssuedServiceToken issue();

    /**
     * @param token            the access token
     * @param expiresInSeconds how long it stays valid
     */
    record IssuedServiceToken(String token, long expiresInSeconds) {
    }
}
