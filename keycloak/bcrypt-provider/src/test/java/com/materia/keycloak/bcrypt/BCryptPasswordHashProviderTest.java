package com.materia.keycloak.bcrypt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identity's hashes (Spring's BCryptPasswordEncoder) verify in Keycloak, and are always replaced after sign-in. */
class BCryptPasswordHashProviderTest {

    private final BCryptPasswordHashProvider provider = new BCryptPasswordHashProvider();

    private static PasswordCredentialModel imported(String hash) {
        return PasswordCredentialModel.createFromValues(BCryptPasswordHashProvider.ID, new byte[0], 10, hash);
    }

    @Test
    @DisplayName("bcrypt: the password identity hashed verifies; another does not")
    void identitysHash_verifies() {
        PasswordCredentialModel credential = imported(new BCryptPasswordEncoder().encode("Materia.Dev1!"));

        assertTrue(provider.verify("Materia.Dev1!", credential));
        assertFalse(provider.verify("Materia.Dev2!", credential));
        assertFalse(provider.verify(null, credential));
    }

    @Test
    @DisplayName("bcrypt: a value that is not a BCrypt hash never verifies")
    void notABcryptHash_neverVerifies() {
        assertFalse(provider.verify("anything", imported("plain-text")));
        assertFalse(provider.verify("anything", imported("$2a$10$truncated")));
    }

    @Test
    @DisplayName("bcrypt: never the realm's policy, so Keycloak re-hashes at the first successful sign-in")
    void policyCheck_alwaysAsksForARehash() {
        assertFalse(provider.policyCheck(null, imported(new BCryptPasswordEncoder().encode("x"))));
    }

    @Test
    @DisplayName("bcrypt: what it encodes, it verifies")
    void encode_roundTrips() {
        assertTrue(provider.verify("S3cret!pass", provider.encodedCredential("S3cret!pass", 4)));
    }
}
