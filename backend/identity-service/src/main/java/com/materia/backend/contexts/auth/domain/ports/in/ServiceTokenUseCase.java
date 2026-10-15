package com.materia.backend.contexts.auth.domain.ports.in;

/** Issues service tokens to the services that present their client credentials. */
public interface ServiceTokenUseCase {

    /**
     * @return a service token for {@code clientId}
     * @throws com.materia.backend.contexts.auth.domain.exceptions.InvalidCredentialsException when the credentials do
     *         not match a registered service
     */
    IssuedServiceToken issue(String clientId, String clientSecret);

    record IssuedServiceToken(String token, long expiresInSeconds) {
    }
}
