package com.materia.backend.infrastructure.grpc;

/**
 * A service token kept and reused, renewed half way through its life, so a call never leaves with a token about to
 * expire and the token source (Keycloak) is asked once per renewal, not once per call.
 */
public class CachedServiceTokens {

    private final ServiceTokenSource source;
    private volatile String token;
    private volatile long renewAt;

    public CachedServiceTokens(ServiceTokenSource source) {
        this.source = source;
    }

    public String token() {
        long now = System.currentTimeMillis();
        if (token == null || now >= renewAt) {
            synchronized (this) {
                if (token == null || now >= renewAt) {
                    ServiceTokenSource.IssuedServiceToken issued = source.issue();
                    token = issued.token();
                    renewAt = now + issued.expiresInSeconds() * 1000 / 2;
                }
            }
        }
        return token;
    }
}
