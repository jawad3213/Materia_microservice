package com.materia.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the gateway sends requests and how it checks them ({@code gateway.*}).
 *
 * @param backendUri the service the REST API is routed to; today the monolith, later one entry per service
 * @param jwksUri    Identity's published token key (JWK set)
 * @param rateLimit  per-client request budgets
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String backendUri, String jwksUri, RateLimit rateLimit) {

    public GatewayProperties {
        if (backendUri == null || backendUri.isBlank()) {
            throw new IllegalStateException("gateway.backend-uri is not set");
        }
        if (jwksUri == null || jwksUri.isBlank()) jwksUri = backendUri + "/.well-known/jwks.json";
        if (rateLimit == null) rateLimit = new RateLimit(true, 120, 10, 60);
    }

    /**
     * @param enabled                     off in tests that are not about it
     * @param maxRequestsPerMinute        a client's budget for the API
     * @param maxAuthRequestsPerMinute    a client's budget for sign-in and password reset, kept small against guessing
     * @param windowSeconds               length of the counting window
     */
    public record RateLimit(boolean enabled, int maxRequestsPerMinute, int maxAuthRequestsPerMinute, int windowSeconds) {
    }
}
