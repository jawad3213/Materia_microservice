package com.materia.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a trace id ({@code X-Gateway-Trace-Id}), passed to the service and returned to the client, so
 * the gateway's, the services' and the client's view of one request carry the same id. A caller's own id is kept
 * when it looks like one; anything else is replaced, so a client cannot inject text into the logs.
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_HEADER = "X-Gateway-Trace-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String received = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        String traceId = received != null && SAFE_ID.matcher(received).matches() ? received : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        return chain.filter(exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(TRACE_HEADER, traceId)))
                .build());
    }

    /** First: even a refused request is answered with its trace id. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
