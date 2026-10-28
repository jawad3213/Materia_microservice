package com.materia.backend.contexts.auth.infrastructure.adapters.out.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.enums.Role;
import com.materia.backend.contexts.auth.domain.exceptions.EmailAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Identity's accounts in Keycloak, through its admin API, as identity's own client. */
class KeycloakIdentityProviderTest {

    private static final String REALM = "http://keycloak:8080/auth/realms/materia";
    private static final String ADMIN = "http://keycloak:8080/auth/admin/realms/materia";

    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer keycloak = MockRestServiceServer.bindTo(http).ignoreExpectOrder(true).build();
    private final KeycloakIdentityProvider provider =
            new KeycloakIdentityProvider(http, new ObjectMapper(), REALM, "identity", "s3cret");

    /** Asked once per test: the admin token is reused for every call of the test (verified where a test verifies). */
    @BeforeEach
    void identitysOwnToken() {
        keycloak.expect(once(), requestTo(REALM + "/protocol/openid-connect/token"))
                .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"expires_in\":60}",
                        MediaType.APPLICATION_JSON));
    }

    private ResponseActions admin(HttpMethod verb, String path) {
        return keycloak.expect(requestTo(ADMIN + path)).andExpect(method(verb))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"));
    }

    private static User user() {
        return User.builder().email("ada@materia.test").firstName("Ada").lastName("Lovelace").role(Role.RECEIVER)
                .enabled(true).build();
    }

    @Test
    @DisplayName("keycloak: the admin API is the realm's address with /admin before /realms")
    void adminUri() {
        assertThat(KeycloakIdentityProvider.adminUri(REALM)).isEqualTo(ADMIN);
        assertThat(KeycloakIdentityProvider.adminUri("https://app.example.com/auth/realms/materia"))
                .isEqualTo("https://app.example.com/auth/admin/realms/materia");
    }

    @Test
    @DisplayName("keycloak: a new account is created with its e-mail as username, a temporary password, then its role")
    void createAccount_thenRole() {
        UUID id = UUID.randomUUID();
        admin(HttpMethod.POST, "/users")
                .andExpect(jsonPath("$.username").value("ada@materia.test"))
                .andExpect(jsonPath("$.credentials[0].value").value("Tmp!pass1"))
                .andExpect(jsonPath("$.credentials[0].temporary").value(true))
                .andExpect(jsonPath("$.requiredActions[0]").value("UPDATE_PASSWORD"))
                .andRespond(withCreatedEntity(URI.create(ADMIN + "/users/" + id)));
        // The roles it may be given: readable with manage-users, unlike the realm's own role list
        admin(HttpMethod.GET, "/users/" + id + "/role-mappings/realm/available")
                .andRespond(withSuccess("[{\"id\":\"r0\",\"name\":\"ADMIN\"},{\"id\":\"r1\",\"name\":\"RECEIVER\"}]",
                        MediaType.APPLICATION_JSON));
        admin(HttpMethod.POST, "/users/" + id + "/role-mappings/realm")
                .andExpect(jsonPath("$[0].name").value("RECEIVER"))
                .andRespond(withNoContent());

        assertThat(provider.createAccount(user(), "Tmp!pass1", true)).isEqualTo(id);
        keycloak.verify();
    }

    @Test
    @DisplayName("keycloak: an e-mail Keycloak already has is the same conflict as in the directory")
    void createAccount_conflict() {
        admin(HttpMethod.POST, "/users").andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> provider.createAccount(user(), "Tmp!pass1", true))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    @DisplayName("keycloak: accounts from before Keycloak are imported under their ids with their BCrypt hashes")
    void importAccounts_keepIdsAndHashes() {
        User existing = user();
        existing.setId(UUID.randomUUID());
        existing.setPasswordHash("$2a$12$abcdefghijklmnopqrstuuJ5W1ZDpwzF0hO8xX8a7Wn9cEPOZQx2a");
        existing.setMustChangePassword(true);
        admin(HttpMethod.POST, "/partialImport")
                .andExpect(jsonPath("$.ifResourceExists").value("SKIP"))
                .andExpect(jsonPath("$.users[0].id").value(existing.getId().toString()))
                .andExpect(jsonPath("$.users[0].realmRoles[0]").value("RECEIVER"))
                .andExpect(jsonPath("$.users[0].credentials[0].secretData")
                        .value("{\"value\":\"" + existing.getPasswordHash() + "\",\"salt\":\"\",\"additionalParameters\":{}}"))
                .andExpect(jsonPath("$.users[0].requiredActions[0]").value("UPDATE_PASSWORD"))
                .andRespond(withSuccess("{\"added\":1,\"skipped\":0}", MediaType.APPLICATION_JSON));

        assertThat(provider.importAccounts(List.of(existing))).isEqualTo(1);
        keycloak.verify();
        assertThat(KeycloakIdentityProvider.cost(existing.getPasswordHash())).isEqualTo(12);
    }

    @Test
    @DisplayName("keycloak: disabling an account ends its sessions; an account Keycloak lacks is no error")
    void disableAccount() {
        UUID id = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        admin(HttpMethod.PUT, "/users/" + id).andExpect(content().json("{\"enabled\":false}"))
                .andRespond(withNoContent());
        admin(HttpMethod.POST, "/users/" + id + "/logout").andRespond(withNoContent());
        admin(HttpMethod.PUT, "/users/" + unknown).andRespond(withStatus(HttpStatus.NOT_FOUND));

        provider.disableAccount(id);
        provider.disableAccount(unknown);
        keycloak.verify();
    }
}
