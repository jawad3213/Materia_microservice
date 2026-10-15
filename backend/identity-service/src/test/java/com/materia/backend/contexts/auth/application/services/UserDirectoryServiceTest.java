package com.materia.backend.contexts.auth.application.services;

import com.materia.backend.contexts.auth.application.dtos.UserSummary;
import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.enums.Role;
import com.materia.backend.contexts.auth.domain.enums.UserStatus;
import com.materia.backend.contexts.auth.domain.ports.out.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The directory the other services ask identity about its users (identity.v1.UserDirectory). */
class UserDirectoryServiceTest {

    private static final UUID ID = UUID.fromString("7d1f6c1e-2b3a-4c5d-8e9f-0a1b2c3d4e5f");

    private final UserRepository users = mock(UserRepository.class);
    private final UserDirectoryService directory = new UserDirectoryService(users);

    @Test
    @DisplayName("findUser: a user is found by id, the id trimmed")
    void findUser_byId() {
        when(users.findById(ID)).thenReturn(Optional.of(user("Ada Lovelace", "ada@materia.com")));

        Optional<UserSummary> found = directory.findUser("  " + ID + " ");

        assertThat(found).hasValueSatisfying(summary -> {
            assertThat(summary.id()).isEqualTo(ID);
            assertThat(summary.email()).isEqualTo("ada@materia.com");
            assertThat(summary.displayName()).isEqualTo("Ada Lovelace");
            assertThat(summary.roleCode()).isEqualTo("PURCHASER");
            assertThat(summary.statusCode()).isEqualTo("ACTIVE");
            assertThat(summary.isActive()).isTrue();
        });
    }

    @Test
    @DisplayName("findUser: no id, an id that is not a UUID or an unknown id finds nobody")
    void findUser_nobody() {
        when(users.findById(any())).thenReturn(Optional.empty());

        assertThat(directory.findUser(null)).isEmpty();
        assertThat(directory.findUser("  ")).isEmpty();
        assertThat(directory.findUser("not-a-uuid")).isEmpty();
        assertThat(directory.findUser(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    @DisplayName("findUserByEmail: the e-mail is trimmed and lower-cased; no e-mail asks nothing")
    void findUserByEmail() {
        when(users.findByEmail("ada@materia.com")).thenReturn(Optional.of(user("Ada Lovelace", "ada@materia.com")));

        assertThat(directory.findUserByEmail(" Ada@Materia.COM ")).map(UserSummary::id).contains(ID);
        assertThat(directory.findUserByEmail(null)).isEmpty();
        assertThat(directory.findUserByEmail(" ")).isEmpty();
        verify(users, never()).findByEmail(" ");
        verify(users, never()).findByEmail((String) null);
    }

    @Test
    @DisplayName("findUsersWithRole: the users of a role code; an unknown code is refused")
    void findUsersWithRole() {
        when(users.findByRole(Role.PURCHASER)).thenReturn(List.of(user("Ada Lovelace", "ada@materia.com")));

        assertThat(directory.findUsersWithRole("PURCHASER")).extracting(UserSummary::email)
                .containsExactly("ada@materia.com");
        assertThatThrownBy(() -> directory.findUsersWithRole("PIRATE")).isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("display name: the full name, else the e-mail, else the id")
    void displayName_fallsBack() {
        assertThat(UserDirectoryService.toSummary(user("Ada Lovelace", "ada@materia.com")).displayName())
                .isEqualTo("Ada Lovelace");
        assertThat(UserDirectoryService.toSummary(user(" ", "ada@materia.com")).displayName())
                .isEqualTo("ada@materia.com");
        assertThat(UserDirectoryService.toSummary(user(null, null)).displayName()).isEqualTo(ID.toString());

        User nothing = User.builder().build();
        nothing.setId(null);
        assertThat(UserDirectoryService.toSummary(nothing).displayName()).isNull();
    }

    @Test
    @DisplayName("a legacy account without role or status has no codes, and counts as active while enabled")
    void legacyAccount_withoutRoleOrStatus() {
        User legacy = user("Ada Lovelace", "ada@materia.com");
        legacy.setRole(null);
        legacy.setStatus(null);

        UserSummary summary = UserDirectoryService.toSummary(legacy);

        assertThat(summary.roleCode()).isNull();
        assertThat(summary.statusCode()).isNull();
        assertThat(summary.isActive()).isTrue();
    }

    @Test
    @DisplayName("a deactivated account is not active")
    void deactivatedAccount_isNotActive() {
        User inactive = user("Ada Lovelace", "ada@materia.com");
        inactive.setStatus(UserStatus.INACTIVE);

        assertThat(UserDirectoryService.toSummary(inactive).isActive()).isFalse();
    }

    private static User user(String fullName, String email) {
        return User.builder()
                .id(ID)
                .email(email)
                .fullName(fullName)
                .role(Role.PURCHASER)
                .enabled(true)
                .build();
    }
}
