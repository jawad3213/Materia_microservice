package com.materia.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the gateway sends requests and how it checks them ({@code gateway.*}).
 *
 * @param procurementUri procurement: the REST API no other service answers
 * @param identityUri    Identity (sign-in, accounts, employees, the token key); procurement when not set
 * @param masterDataUri  Master data (materials, categories, suppliers); procurement when not set
 * @param financeUri     Finance (invoices, payments, settings); procurement when not set
 * @param analyticsUri   Analytics (the dashboard); procurement when not set
 * @param jwksUri        Identity's published token key (JWK set); Identity's own when not set
 * @param rateLimit      per-client request budgets
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String procurementUri, String identityUri, String masterDataUri, String financeUri,
                                String analyticsUri, String jwksUri, RateLimit rateLimit) {

    public GatewayProperties {
        if (procurementUri == null || procurementUri.isBlank()) {
            throw new IllegalStateException("gateway.procurement-uri is not set");
        }
        if (identityUri == null || identityUri.isBlank()) identityUri = procurementUri;
        if (masterDataUri == null || masterDataUri.isBlank()) masterDataUri = procurementUri;
        if (financeUri == null || financeUri.isBlank()) financeUri = procurementUri;
        if (analyticsUri == null || analyticsUri.isBlank()) analyticsUri = procurementUri;
        if (jwksUri == null || jwksUri.isBlank()) jwksUri = identityUri + "/.well-known/jwks.json";
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
