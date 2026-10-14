package com.materia.backend.contexts.auth.domain.ports.out;

/** The services allowed to ask for a service token, and their secrets. */
public interface ServiceClients {

    /** Whether {@code clientSecret} is the secret of the registered service {@code clientId}. */
    boolean matches(String clientId, String clientSecret);
}
