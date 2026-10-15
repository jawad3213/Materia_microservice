package com.materia.grpc.auth;

import java.util.Set;

/**
 * Who may call one RPC: users holding any of {@code permissions} (their own token, forwarded by the service that
 * serves their request), and the {@code services} named, with their service token (work no user started: jobs,
 * event handlers, seeding).
 *
 * <p>A read RPC asks for the permission that reads the same data over the REST API, so a call can never show a user
 * more than they could see there; a write RPC, for the permissions of the actions that lead to it.
 */
public record RpcPolicy(Set<String> permissions, Set<String> services, Kind kind) {

    public enum Kind {
        /** Users with one of the permissions, the services named. */
        RESTRICTED,
        /** Any caller with a valid token, user or service. */
        AUTHENTICATED,
        /** Without a token (the authentication interceptor lets it through). */
        OPEN
    }

    public RpcPolicy {
        permissions = Set.copyOf(permissions);
        services = Set.copyOf(services);
    }

    /** Users holding any of these permissions; add services with {@link #orServices}. */
    public static RpcPolicy users(String... anyPermission) {
        return new RpcPolicy(Set.of(anyPermission), Set.of(), Kind.RESTRICTED);
    }

    public RpcPolicy orServices(String... names) {
        return new RpcPolicy(permissions, Set.of(names), kind);
    }

    public static RpcPolicy authenticated() {
        return new RpcPolicy(Set.of(), Set.of(), Kind.AUTHENTICATED);
    }

    public static RpcPolicy open() {
        return new RpcPolicy(Set.of(), Set.of(), Kind.OPEN);
    }

    public boolean allows(Caller caller) {
        return switch (kind) {
            case OPEN -> true;
            case AUTHENTICATED -> caller != null;
            case RESTRICTED -> caller != null && caller.service()
                    .map(services::contains)
                    .orElseGet(() -> permissions.stream().anyMatch(caller::has));
        };
    }
}
