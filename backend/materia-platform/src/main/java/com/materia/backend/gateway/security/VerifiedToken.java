package com.materia.backend.gateway.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.List;

/**
 * What a verified access token says, whoever issued it (Identity or Keycloak).
 *
 * @param subject     a user UUID, or {@code service:<name>} for a service token
 * @param email       the user's e-mail, when the token carries it
 * @param authorities roles ({@code ROLE_ADMIN}) and permissions ({@code invoice:read})
 * @param service     a service token: valid between services, never on the REST API
 */
public record VerifiedToken(String subject, String email, List<GrantedAuthority> authorities, boolean service) {

    public VerifiedToken {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }
}
