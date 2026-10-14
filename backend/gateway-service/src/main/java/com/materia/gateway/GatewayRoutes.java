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
 * <p>Today every route leads to the monolith; as services are extracted, each gets its own route by path prefix
 * (identity: /api/v1/auth, /api/v1/employees; master data: /api/v1/masterdata; ...).
 */
@Configuration
public class GatewayRoutes {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder routes, GatewayProperties gateway) {
        return routes.routes()
                .route("api", route -> route.path("/api/**").uri(gateway.backendUri()))
                .route("jwks", route -> route.path("/.well-known/jwks.json").uri(gateway.backendUri()))
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
