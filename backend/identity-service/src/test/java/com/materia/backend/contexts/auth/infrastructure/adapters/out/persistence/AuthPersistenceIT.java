package com.materia.backend.contexts.auth.infrastructure.adapters.out.persistence;

import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.enums.Role;
import com.materia.backend.contexts.auth.domain.enums.UserStatus;
import com.materia.backend.contexts.auth.domain.ports.out.UserRepository;
import com.materia.backend.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The user persistence adapter against real PostgreSQL with the migrated schema (T046, T047).
 * Each test rolls back, and fixtures use unique emails to avoid the seed accounts.
 */
class AuthPersistenceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository users;
    @PersistenceContext private EntityManager em;

    private static String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.com";
    }

    private User newUser(String email) {
        return User.builder().email(email).passwordHash("hash").role(Role.RECEIVER)
                .status(UserStatus.ACTIVE).enabled(true).firstName("Ada").lastName("Lovelace")
                .mustChangePassword(true).build();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("users: a saved user reads back with every field intact")
    void user_roundTrip() {
        String email = uniqueEmail();
        User saved = users.save(newUser(email));
        flushAndClear();

        User read = users.findById(saved.getId()).orElseThrow();
        assertEquals(email, read.getEmail());
        assertEquals(Role.RECEIVER, read.getRole());
        assertEquals(UserStatus.ACTIVE, read.getStatus());
        assertTrue(read.isEnabled());
        assertTrue(read.isMustChangePassword(), "the column restored by the drift fix must persist");
        assertEquals("Ada Lovelace", read.getFullName());
    }

    @Test
    @DisplayName("users: a second account with the same email is rejected by the database")
    void user_duplicateEmail_isRejected() {
        String email = uniqueEmail();
        users.save(newUser(email));
        flushAndClear();

        users.save(newUser(email));
        assertThrows(RuntimeException.class, this::flushAndClear);
    }

    @Test
    @DisplayName("users: lookup matches the stored form exactly, so callers must normalise first, as account provisioning does")
    void user_lookupIsExactMatch() {
        String email = uniqueEmail();
        users.save(newUser(email));
        flushAndClear();

        assertTrue(users.findByEmail(email).isPresent());
        // Documents actual behaviour: the adapter does not normalise. AccountProvisioningService trims and
        // lower-cases before calling.
        assertTrue(users.findByEmail(email.toUpperCase()).isEmpty());
        assertTrue(users.existsByEmail(email));
    }

    @Test
    @DisplayName("users: two accounts whose emails differ only by case are rejected as duplicates")
    void user_caseVariantEmail_isRejected() {
        String email = uniqueEmail();
        users.save(newUser(email));
        flushAndClear();

        users.save(newUser(email.toUpperCase()));
        assertThrows(RuntimeException.class, this::flushAndClear);
    }
}
