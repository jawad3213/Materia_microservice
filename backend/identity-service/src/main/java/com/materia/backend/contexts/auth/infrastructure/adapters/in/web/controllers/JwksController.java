package com.materia.backend.contexts.auth.infrastructure.adapters.in.web.controllers;

import com.materia.backend.gateway.security.JwtTokenProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the public key access tokens are verified with (RFC 7517), so a service that does not hold the private
 * key can still check them. Public by design: it holds no secret.
 */
@RestController
public class JwksController {

    public static final String PATH = "/.well-known/jwks.json";

    private final JwtTokenProvider jwtTokenProvider;

    public JwksController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping(PATH)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic())
                .body(jwtTokenProvider.publicJwks());
    }
}
