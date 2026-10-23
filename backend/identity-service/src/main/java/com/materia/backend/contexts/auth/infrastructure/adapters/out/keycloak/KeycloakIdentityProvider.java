package com.materia.backend.contexts.auth.infrastructure.adapters.out.keycloak;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.exceptions.EmailAlreadyExistsException;
import com.materia.backend.contexts.auth.domain.ports.out.IdentityProvider;
import com.materia.backend.infrastructure.grpc.KeycloakServiceTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keycloak's admin API, as identity's own client ({@code app.service-client.id}, a service account of the realm with
 * realm-management's manage-users and, for {@link #importAccounts}, manage-realm).
 *
 * <p>A new account is created by Keycloak, which chooses its id; the import (partial import, the one admin call that
 * keeps the ids it is given) is for the accounts identity had before Keycloak, whose ids every service refers to.
 */
@Component
public class KeycloakIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvider.class);
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private final RestClient http;
    private final KeycloakServiceTokens tokens;
    private final String adminUri;
    private final ObjectMapper json;

    public KeycloakIdentityProvider(RestClient.Builder http, ObjectMapper json,
                                    @Value("${app.keycloak.realm-uri:${app.keycloak.issuer-uri:}}") String realmUri,
                                    @Value("${app.service-client.id}") String clientId,
                                    @Value("${app.service-client.secret}") String clientSecret) {
        this.http = http.build();
        this.json = json;
        this.tokens = new KeycloakServiceTokens(http, realmUri, clientId, clientSecret);
        this.adminUri = adminUri(realmUri);
    }

    /** {@code http://host/auth/realms/materia} -> {@code http://host/auth/admin/realms/materia}. */
    static String adminUri(String realmUri) {
        if (!StringUtils.hasText(realmUri)) return null;
        int realms = realmUri.lastIndexOf("/realms/");
        if (realms < 0) throw new IllegalStateException("Not a Keycloak realm address: " + realmUri);
        return realmUri.substring(0, realms) + "/admin" + realmUri.substring(realms);
    }

    @Override
    public UUID createAccount(User user, String password, boolean temporary) {
        Map<String, Object> account = account(user);
        account.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", temporary)));
        if (temporary) account.put("requiredActions", List.of(UPDATE_PASSWORD));
        ResponseEntity<Void> created;
        try {
            created = http.post().uri(admin("/users"))
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(account)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                throw new EmailAlreadyExistsException("A user account already exists with email: " + user.getEmail());
            }
            throw failure("create the account of " + user.getEmail(), e);
        }
        URI location = created.getHeaders().getLocation();
        if (location == null) throw new IllegalStateException("Keycloak created an account without saying its id");
        String path = location.getPath();
        UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        assignRole(id, user);
        return id;
    }

    /**
     * Not part of the creation: Keycloak's create-user ignores roles. The role is found among those the user may be
     * given, which manage-users can read (the realm's own role list needs view-realm).
     */
    private void assignRole(UUID id, User user) {
        String code = user.getRole().getCode();
        try {
            List<?> available = http.get().uri(admin("/users/" + id + "/role-mappings/realm/available"))
                    .headers(this::authorize)
                    .retrieve()
                    .body(List.class);
            Object role = available == null ? null : available.stream()
                    .filter(r -> r instanceof Map<?, ?> m && code.equals(m.get("name")))
                    .findFirst()
                    .orElse(null);
            if (role == null) {
                deleteAccount(id);
                throw new IllegalStateException("Keycloak has no realm role " + code + " to give " + user.getEmail());
            }
            http.post().uri(admin("/users/" + id + "/role-mappings/realm"))
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            deleteAccount(id);
            throw failure("give " + user.getEmail() + " the role " + user.getRole().getCode(), e);
        }
    }

    @Override
    public int importAccounts(List<User> users) {
        if (users.isEmpty()) return 0;
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (User user : users) {
            Map<String, Object> account = account(user);
            account.put("id", user.getId().toString());
            account.put("realmRoles", List.of(user.getRole().getCode()));
            if (StringUtils.hasText(user.getPasswordHash())) {
                account.put("credentials", List.of(bcrypt(user.getPasswordHash())));
            }
            if (user.isMustChangePassword()) account.put("requiredActions", List.of(UPDATE_PASSWORD));
            accounts.add(account);
        }
        try {
            Map<?, ?> result = http.post().uri(admin("/partialImport"))
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ifResourceExists", "SKIP", "users", accounts))
                    .retrieve()
                    .body(Map.class);
            return result != null && result.get("added") instanceof Number added ? added.intValue() : 0;
        } catch (RestClientResponseException e) {
            throw failure("import " + users.size() + " accounts", e);
        }
    }

    @Override
    public void deleteAccount(UUID userId) {
        try {
            http.delete().uri(admin("/users/" + userId)).headers(this::authorize).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                log.error("[KEYCLOAK] Could not delete the account {}: {}", userId, e.getStatusCode());
            }
        }
    }

    @Override
    public void renameAccount(UUID userId, String firstName, String lastName) {
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("firstName", firstName == null ? "" : firstName);
        names.put("lastName", lastName == null ? "" : lastName);
        update(userId, names, "rename");
    }

    @Override
    public void disableAccount(UUID userId) {
        if (!update(userId, Map.of("enabled", false), "disable")) return;
        try {
            http.post().uri(admin("/users/" + userId + "/logout")).headers(this::authorize).retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw failure("end the sessions of " + userId, e);
        }
    }

    /** @return false when Keycloak has no such account */
    private boolean update(UUID userId, Map<String, Object> changes, String what) {
        try {
            http.put().uri(admin("/users/" + userId))
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(changes)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                log.warn("[KEYCLOAK] No account {} to {}", userId, what);
                return false;
            }
            throw failure(what + " the account " + userId, e);
        }
    }

    /** The account as Keycloak represents it: the e-mail is the username. */
    private static Map<String, Object> account(User user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("username", user.getEmail());
        account.put("email", user.getEmail());
        account.put("emailVerified", true);
        account.put("firstName", user.getFirstName() == null ? "" : user.getFirstName());
        account.put("lastName", user.getLastName() == null ? "" : user.getLastName());
        account.put("enabled", user.isEnabled());
        return account;
    }

    /** A stored BCrypt hash as a Keycloak credential, verified by the realm's bcrypt provider (keycloak/). */
    private Map<String, Object> bcrypt(String hash) {
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("value", hash);
        secret.put("salt", "");
        secret.put("additionalParameters", Map.of());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hashIterations", cost(hash));
        data.put("algorithm", "bcrypt");
        data.put("additionalParameters", Map.of());
        try {
            return Map.of(
                    "type", "password",
                    "secretData", json.writeValueAsString(secret),
                    "credentialData", json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code $2a$10$...} -> 10. */
    static int cost(String hash) {
        try {
            return Integer.parseInt(hash.substring(4, 6));
        } catch (RuntimeException e) {
            return 10;
        }
    }

    private String admin(String path) {
        if (adminUri == null) throw new IllegalStateException("app.keycloak.issuer-uri is not set: no account store");
        return adminUri + path;
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(tokens.issue().token());
    }

    private static IllegalStateException failure(String what, RestClientResponseException e) {
        return new IllegalStateException("Keycloak could not " + what + ": " + e.getStatusCode() + " "
                + e.getResponseBodyAsString(), e);
    }
}
