package com.materia.backend.contexts.auth.infrastructure.bootstrap;

import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.enums.Role;
import com.materia.backend.contexts.auth.domain.ports.out.IdentityProvider;
import com.materia.backend.contexts.auth.domain.ports.out.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The accounts from before Keycloak move there once, with their password hashes, which identity then forgets. */
@ExtendWith(MockitoExtension.class)
class KeycloakAccountImportTest {

    @Mock private UserRepository users;
    @Mock private IdentityProvider identityProvider;

    private static User account(String hash) {
        return User.builder().id(UUID.randomUUID()).email(UUID.randomUUID() + "@materia.test").role(Role.RECEIVER)
                .passwordHash(hash).build();
    }

    @Test
    @DisplayName("import: accounts still holding a hash are imported with it, then the hash is forgotten here")
    void pendingAccounts_areImportedAndForgotten() {
        User pending = account("$2a$10$abcdefghijklmnopqrstuuJ5W1ZDpwzF0hO8xX8a7Wn9cEPOZQx2a");
        User done = account(null);
        when(users.findAll()).thenReturn(List.of(pending, done));
        when(identityProvider.importAccounts(anyList())).thenReturn(1);

        assertThat(new KeycloakAccountImport(users, identityProvider).importPending()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> imported = ArgumentCaptor.forClass(List.class);
        verify(identityProvider).importAccounts(imported.capture());
        assertThat(imported.getValue()).containsExactly(pending);
        verify(users).save(pending);
        assertThat(pending.getPasswordHash()).isNull();
    }

    @Test
    @DisplayName("import: nothing to import, nothing asked of Keycloak")
    void nothingPending_asksNothing() {
        when(users.findAll()).thenReturn(List.of(account(null)));

        assertThat(new KeycloakAccountImport(users, identityProvider).importPending()).isZero();

        verifyNoInteractions(identityProvider);
    }

    @Test
    @DisplayName("import: Keycloak refusing keeps the hashes for the next start, and identity starts anyway")
    void keycloakRefusing_keepsTheHashes() {
        User pending = account("$2a$10$hash");
        when(users.findAll()).thenReturn(List.of(pending));
        when(identityProvider.importAccounts(anyList())).thenThrow(new IllegalStateException("403 Forbidden"));

        assertThat(new KeycloakAccountImport(users, identityProvider).importPending()).isZero();

        assertThat(pending.getPasswordHash()).isEqualTo("$2a$10$hash");
        verify(users, never()).save(any());
    }
}
