package com.materia.gateway.filter;

import com.materia.gateway.GatewayProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A per-client budget of requests per window: 120 a minute for the API. Same rules as procurement's limiter, which
 * stays as a second line. Password guessing is Keycloak's to stop: nginx limits its sign-in endpoints per address.
 *
 * <p>The client is the address nginx saw ({@code X-Real-IP}, which it overwrites), else the last
 * {@code X-Forwarded-For} hop, written by the nearest proxy, else the connection's address. The first
 * {@code X-Forwarded-For} entry is the client's own claim and would let it escape the limit.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final GatewayProperties.RateLimit limits;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(GatewayProperties gateway) {
        this.limits = gateway.rateLimit();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!limits.enabled()) return chain.filter(exchange);
        String path = exchange.getRequest().getPath().value();
        if (isPublic(path)) return chain.filter(exchange);

        int limit = limits.maxRequestsPerMinute();
        String key = "api:" + clientOf(exchange.getRequest());
        int used = consume(key);
        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(limit));
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        if (used > limit) {
            exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(limits.windowSeconds()));
            return ErrorResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Maximum " + limit + " requests per minute.");
        }
        return chain.filter(exchange);
    }

    /** The API docs: not counted, like in procurement. Sign-in is Keycloak's, limited by nginx in front of it. */
    private static boolean isPublic(String path) {
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    static String clientOf(ServerHttpRequest request) {
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) return last;
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    /** @return the requests counted in the current window, this one included */
    private int consume(String key) {
        long now = System.currentTimeMillis();
        long windowMs = limits.windowSeconds() * 1000L;
        Window window = windows.compute(key, (k, current) ->
                current == null || now - current.start >= windowMs ? new Window(now, 1) : current.increment());
        if (windows.size() > 100_000) windows.entrySet().removeIf(e -> now - e.getValue().start >= windowMs);
        return window.count;
    }

    private record Window(long start, int count) {
        Window increment() {
            return new Window(start, count + 1);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
