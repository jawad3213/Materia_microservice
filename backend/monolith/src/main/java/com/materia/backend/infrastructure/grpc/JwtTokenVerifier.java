package com.materia.backend.infrastructure.grpc;

import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.grpc.auth.Caller;
import com.materia.grpc.auth.TokenVerifier;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Verifies the token of an incoming gRPC call the way the REST API does: RS256 signature and expiry, no database. */
@Component
public class JwtTokenVerifier implements TokenVerifier {

    private final JwtTokenProvider jwt;

    public JwtTokenVerifier(JwtTokenProvider jwt) {
        this.jwt = jwt;
    }

    @Override
    public Optional<Caller> verify(String token) {
        if (!jwt.validateAccessToken(token)) return Optional.empty();
        return Optional.of(new Caller(jwt.getSubjectFromAccessToken(token),
                jwt.getAuthoritiesFromAccessToken(token).stream().map(GrantedAuthority::getAuthority).toList()));
    }
}
