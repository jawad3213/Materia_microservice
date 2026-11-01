package com.materia.keycloak.bcrypt;

import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Verifies a password against a BCrypt hash ({@code $2a$}, {@code $2b$}, {@code $2y$}), as identity stored them. No
 * BCrypt hash ever satisfies the realm's policy ({@link #policyCheck}), so Keycloak replaces it with its own algorithm's
 * at the account's first successful sign-in, and this provider is then no longer used for it.
 */
public class BCryptPasswordHashProvider implements PasswordHashProvider {

    public static final String ID = "bcrypt";

    private static final int DEFAULT_COST = 10;

    @Override
    public boolean policyCheck(PasswordPolicy policy, PasswordCredentialModel credential) {
        return false;
    }

    /** Only for a realm whose policy names bcrypt; Materia's does not, so Keycloak never asks. */
    @Override
    public PasswordCredentialModel encodedCredential(String rawPassword, int iterations) {
        int cost = iterations > 0 ? iterations : DEFAULT_COST;
        String hash = BCrypt.hashpw(rawPassword, BCrypt.gensalt(cost));
        return PasswordCredentialModel.createFromValues(ID, new byte[0], cost, hash);
    }

    @Override
    public boolean verify(String rawPassword, PasswordCredentialModel credential) {
        String hash = credential.getPasswordSecretData().getValue();
        if (rawPassword == null || hash == null || !hash.startsWith("$2")) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void close() {
    }
}
