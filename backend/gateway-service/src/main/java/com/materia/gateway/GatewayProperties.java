package com.materia.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the gateway sends requests and how it checks them ({@code gateway.*}).
 *
 * @param procurementUri procurement: the REST API no other service answers
 * @param identityUri    Identity (accounts, employees); procurement when not set
 * @param masterDataUri  Master data (materials, categories, suppliers); procurement when not set
 * @param financeUri     Finance (invoices, payments, settings); procurement when not set
 * @param analyticsUri   Analytics (the dashboard); procurement when not set
 * @param keycloak       the realm whose tokens the gateway accepts
 * @param rateLimit      per-client request budgets
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String procurementUri, String identityUri, String masterDataUri, String financeUri,
                                String analyticsUri, Keycloak keycloak, RateLimit rateLimit) {

    public GatewayProperties {
        if (procurementUri == null || procurementUri.isBlank()) {
            throw new IllegalStateException("gateway.procurement-uri is not set");
        }
        if (identityUri == null || identityUri.isBlank()) identityUri = procurementUri;
        if (masterDataUri == null || masterDataUri.isBlank()) masterDataUri = procurementUri;
        if (financeUri == null || financeUri.isBlank()) financeUri = procurementUri;
        if (analyticsUri == null || analyticsUri.isBlank()) analyticsUri = procurementUri;
        if (keycloak == null) keycloak = new Keycloak(null, null, null);
        if (rateLimit == null) rateLimit = new RateLimit(true, 120, 60);
    }

    /**
     * @param issuerUri the realm's issuer, as tokens state it (required)
     * @param realmUri  where the gateway reaches the realm; the issuer when not set
     * @param audience  the API client every Keycloak token must be meant for
     */
    public record Keycloak(String issuerUri, String realmUri, String audience) {

        public Keycloak {
            if (issuerUri == null || issuerUri.isBlank()) {
                throw new IllegalStateException("gateway.keycloak.issuer-uri is not set");
            }
            if (realmUri == null || realmUri.isBlank()) realmUri = issuerUri;
            if (audience == null || audience.isBlank()) audience = "materia-api";
        }

        /** The realm's keys. */
        public String jwkSetUri() {
            return realmUri + "/protocol/openid-connect/certs";
        }
    }

    /**
     * @param enabled                     off in tests that are not about it
     * @param maxRequestsPerMinute        a client's budget for the API
     * @param windowSeconds               length of the counting window
     */
    public record RateLimit(boolean enabled, int maxRequestsPerMinute, int windowSeconds) {
    }
}
