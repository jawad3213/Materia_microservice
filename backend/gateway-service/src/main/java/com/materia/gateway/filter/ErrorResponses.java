package com.materia.gateway.filter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** The JSON error body the services answer with, so a client handles a refusal from the gateway the same way. */
final class ErrorResponses {

    private ErrorResponses() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"message\":\"" + message + "\",\"path\":\""
                + exchange.getRequest().getPath().value().replace("\"", "") + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
