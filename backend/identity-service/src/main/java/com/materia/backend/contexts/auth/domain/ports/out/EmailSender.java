package com.materia.backend.contexts.auth.domain.ports.out;

/**
 * 🔹 EMAIL SENDER PORT (OUTPUT PORT)
 * 
 * Defines the contract for dispatching transactional account emails (password resets are Keycloak's own).
 * Pure domain interface — decoupled from Spring Mail or SMTP implementations.
 */
public interface EmailSender {

    /**
     * Sends the temporary password generated when an account is provisioned
     * on behalf of a user (employee onboarding), together with the sign-in link.
     *
     * @param toEmail           Recipient email address
     * @param temporaryPassword Generated single-use temporary password
     */
    void sendTemporaryPasswordEmail(String toEmail, String temporaryPassword);
}
