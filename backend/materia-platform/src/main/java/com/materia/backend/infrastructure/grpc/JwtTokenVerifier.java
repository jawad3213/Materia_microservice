package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.AccessTokens;
import com.materia.grpc.auth.Caller;
import com.materia.grpc.auth.TokenVerifier;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Verifies the token of an incoming gRPC call the way the REST API does: RS256 signature and expiry, no database. */
@Component
public class JwtTokenVerifier implements TokenVerifier {

    private final AccessTokens tokens;

    public JwtTokenVerifier(AccessTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public Optional<Caller> verify(String token) {
        return tokens.verify(token).map(verified -> new Caller(verified.subject(),
                verified.authorities().stream().map(GrantedAuthority::getAuthority).toList()));
    }
}
