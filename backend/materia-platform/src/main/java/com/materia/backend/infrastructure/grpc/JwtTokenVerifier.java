package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.AccessTokens;
import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.grpc.auth.Caller;
import com.materia.grpc.auth.TokenVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Verifies the token of an incoming gRPC call the way the REST API does: RS256 signature and expiry, no database. */
@Component
public class JwtTokenVerifier implements TokenVerifier {

    private final AccessTokens tokens;

    @Autowired
    public JwtTokenVerifier(AccessTokens tokens) {
        this.tokens = tokens;
    }

    /** Identity's tokens only. */
    public JwtTokenVerifier(JwtTokenProvider jwt) {
        this(new AccessTokens(jwt, null));
    }

    @Override
    public Optional<Caller> verify(String token) {
        return tokens.verify(token).map(verified -> new Caller(verified.subject(),
                verified.authorities().stream().map(GrantedAuthority::getAuthority).toList()));
    }
}
