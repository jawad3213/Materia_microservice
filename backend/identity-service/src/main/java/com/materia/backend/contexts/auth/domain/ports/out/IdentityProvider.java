package com.materia.backend.contexts.auth.domain.ports.out;

import com.materia.backend.contexts.auth.domain.entities.User;

import java.util.List;
import java.util.UUID;

/**
 * Where accounts sign in and keep their passwords: Keycloak (docs/architecture/keycloak-migration.md). Identity keeps
 * the directory (names, role, status) under the same id as the account there, which is the subject of the user's
 * tokens.
 */
public interface IdentityProvider {

    /**
     * Creates the account, with the user's role and a password; the provider chooses its id.
     *
     * @param temporary true: the user must choose a new password at their first sign-in
     * @return the account's id, which the user's directory entry must take
     * @throws com.materia.backend.contexts.auth.domain.exceptions.EmailAlreadyExistsException when an account with
     *                                                                                          this e-mail exists there
     */
    UUID createAccount(User user, String password, boolean temporary);

    /**
     * Imports accounts under their directory ids, with the password hashes identity stored (BCrypt), so their
     * passwords keep working; an account already there is left alone.
     *
     * @return how many were added
     */
    int importAccounts(List<User> users);

    /** Deletes an account just created, when its directory entry could not be saved; an unknown id is ignored. */
    void deleteAccount(UUID userId);

    void renameAccount(UUID userId, String firstName, String lastName);

    /** Disables the account and ends its sessions; an unknown id is ignored. */
    void disableAccount(UUID userId);
}
