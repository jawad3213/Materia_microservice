package com.materia.backend.gateway.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/**
 * A user signed in with a bearer token, which this authentication keeps so the calls made on the user's behalf to
 * other services carry the same token.
 */
public class BearerTokenAuthentication extends UsernamePasswordAuthenticationToken {

    private final String token;

    public BearerTokenAuthentication(UUID userId, String token, Collection<? extends GrantedAuthority> authorities) {
        super(userId, null, authorities);
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}
