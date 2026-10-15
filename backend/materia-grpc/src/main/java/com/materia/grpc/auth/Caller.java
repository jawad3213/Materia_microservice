package com.materia.grpc.auth;

import java.util.List;
import java.util.Optional;

/**
 * The user a call is made for, as their verified token states it, or the service that made it for work no user
 * started (a service token: subject {@code service:<name>}).
 *
 * @param userId      the token's subject (a user UUID, or {@code service:<name>})
 * @param authorities roles and permissions, e.g. {@code ROLE_ADMIN}, {@code invoice:read}
 */
public record Caller(String userId, List<String> authorities) {

    private static final String SERVICE_PREFIX = "service:";

    public Caller {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }

    public boolean has(String authority) {
        return authorities.contains(authority);
    }

    /** The calling service's name, when the token is a service token; empty for a user. */
    public Optional<String> service() {
        return userId != null && userId.startsWith(SERVICE_PREFIX)
                ? Optional.of(userId.substring(SERVICE_PREFIX.length()))
                : Optional.empty();
    }
}
