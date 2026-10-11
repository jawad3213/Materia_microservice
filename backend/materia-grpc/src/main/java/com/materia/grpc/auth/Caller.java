package com.materia.grpc.auth;

import java.util.List;

/**
 * The user a call is made for, as their verified token states it.
 *
 * @param userId      the token's subject (a user UUID)
 * @param authorities roles and permissions, e.g. {@code ROLE_ADMIN}, {@code invoice:read}
 */
public record Caller(String userId, List<String> authorities) {

    public Caller {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }

    public boolean has(String authority) {
        return authorities.contains(authority);
    }
}
