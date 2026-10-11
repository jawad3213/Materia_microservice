package com.materia.grpc.auth;

import java.util.Optional;

/**
 * Checks an access token (signature with Identity's public key, expiry). Each service provides it from its own token
 * verification, the same one its REST API uses.
 */
@FunctionalInterface
public interface TokenVerifier {

    /** @return the caller, or empty when the token is invalid or expired */
    Optional<Caller> verify(String token);
}
