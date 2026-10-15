package com.materia.backend.contexts.auth.application.services;

import com.materia.backend.contexts.auth.domain.exceptions.InvalidCredentialsException;
import com.materia.backend.contexts.auth.domain.ports.in.ServiceTokenUseCase;
import com.materia.backend.contexts.auth.domain.ports.out.ServiceClients;
import com.materia.backend.contexts.auth.domain.ports.out.TokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Identity is the only signer: a service that needs a token for its own work (no user behind it) exchanges its
 * client credentials for one here, rather than holding the signing key itself.
 */
@Service
public class ServiceTokenService implements ServiceTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenService.class);

    private final ServiceClients clients;
    private final TokenIssuer tokens;

    public ServiceTokenService(ServiceClients clients, TokenIssuer tokens) {
        this.clients = clients;
        this.tokens = tokens;
    }

    @Override
    public IssuedServiceToken issue(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || !clients.matches(clientId, clientSecret)) {
            log.warn("[AUTH-SERVICE] Refused a service token to client [{}]: unknown client or wrong secret", clientId);
            throw new InvalidCredentialsException("Unknown service client or wrong secret");
        }
        return new IssuedServiceToken(tokens.generateServiceToken(clientId), tokens.tokenLifetimeSeconds());
    }
}
