package com.materia.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * What the gateway exposes. Everything else (actuator, internal endpoints) answers 404 here: only the REST API, its
 * documentation and Identity's public key are reachable from outside.
 *
 * <p>An extracted service gets its own routes by path prefix, checked before the monolith's catch-all: Identity
 * answers /api/v1/auth, /api/v1/employees, the history of its own records (User, Employee) and its public key; master
 * data /api/v1/masterdata and the history of its records (Material, Supplier, Category); finance /api/v1/invoices,
 * /api/v1/payments, /api/v1/settings, the history of its records (Invoice, Payment, Setting) and the attachments of
 * invoices (listed and uploaded with {@code ownerType=INVOICE}; read and deleted by id in the monolith, whose
 * attachment table and files finance shares); analytics /api/v1/dashboard; the monolith the rest of the API and its
 * docs.
 */
@Configuration
public class GatewayRoutes {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder routes, GatewayProperties gateway) {
        return routes.routes()
                .route("identity", route -> route.order(-1)
                        .path("/api/v1/auth", "/api/v1/auth/**", "/api/v1/employees", "/api/v1/employees/**",
                                "/api/v1/audit/User/**", "/api/v1/audit/Employee/**")
                        .uri(gateway.identityUri()))
                .route("jwks", route -> route.order(-1).path("/.well-known/jwks.json").uri(gateway.identityUri()))
                .route("masterdata", route -> route.order(-1)
                        .path("/api/v1/masterdata/**", "/api/v1/audit/Material/**", "/api/v1/audit/Supplier/**",
                                "/api/v1/audit/Category/**")
                        .uri(gateway.masterDataUri()))
                .route("finance", route -> route.order(-1)
                        .path("/api/v1/invoices", "/api/v1/invoices/**", "/api/v1/payments", "/api/v1/payments/**",
                                "/api/v1/settings", "/api/v1/settings/**", "/api/v1/audit/Invoice/**",
                                "/api/v1/audit/Payment/**", "/api/v1/audit/Setting/**")
                        .uri(gateway.financeUri()))
                .route("finance-attachments", route -> route.order(-1)
                        .path("/api/v1/attachments").and().query("ownerType", "INVOICE")
                        .uri(gateway.financeUri()))
                .route("analytics", route -> route.order(-1)
                        .path("/api/v1/dashboard", "/api/v1/dashboard/**")
                        .uri(gateway.analyticsUri()))
                .route("api", route -> route.path("/api/**").uri(gateway.backendUri()))
                .route("api-docs", route -> route.path("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .uri(gateway.backendUri()))
                .build();
    }

    /** Verifies RS256 tokens with Identity's JWK set, fetched on first use and refreshed when the key changes. */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(GatewayProperties gateway) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(gateway.jwksUri()).build();
    }
}
