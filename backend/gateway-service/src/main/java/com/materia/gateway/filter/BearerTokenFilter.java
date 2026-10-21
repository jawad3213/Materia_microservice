package com.materia.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * Refuses a request whose bearer token is forged, expired or a service token (401) before it reaches any service.
 * A request without a token goes on: whether its path needs one is the service's decision (sign-in, password
 * reset and the public key do not). The token itself is forwarded untouched, and each service verifies it again.
 */
@Component
public class BearerTokenFilter implements GlobalFilter, Ordered {

    private static final String BEARER = "Bearer ";

    private final ReactiveJwtDecoder decoder;

    public BearerTokenFilter(ReactiveJwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return chain.filter(exchange);
        }
        String token = header.substring(BEARER.length()).trim();
        return decoder.decode(token)
                .flatMap(jwt -> isServiceToken(jwt)
                        ? ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "Service tokens are not accepted here")
                        : chain.filter(exchange))
                .onErrorResume(JwtException.class,
                        e -> ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token"));
    }

    /**
     * Service tokens travel between services only: Identity's (JwtTokenProvider.generateServiceToken), and a Keycloak
     * service account's (realm role SERVICE, KeycloakTokens).
     */
    static boolean isServiceToken(Jwt jwt) {
        if (Boolean.TRUE.equals(jwt.getClaim("svc"))) return true;
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        return realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles
                && roles.contains("SERVICE");
    }

    /** After the trace id and the rate limit, so a flood of bad tokens is throttled before any signature check. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
