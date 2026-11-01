package com.materia.backend.contexts.auth.infrastructure.bootstrap;

import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.ports.out.IdentityProvider;
import com.materia.backend.contexts.auth.domain.ports.out.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 🔹 ACCOUNTS INTO KEYCLOAK
 *
 * The accounts identity had before Keycloak still hold their password hash here. At start-up this imports them into
 * Keycloak under the same ids, with those hashes (BCrypt, which the realm's provider verifies), and then forgets the
 * hashes: from then on the password lives in Keycloak only. Accounts Keycloak already has are left alone there, so an
 * interrupted import is simply finished at the next start. Keycloak unreachable or refusing (identity's client needs
 * manage-realm for this) is logged and tried again at the next start; identity starts anyway.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.keycloak.import-accounts", havingValue = "true", matchIfMissing = true)
public class KeycloakAccountImport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAccountImport.class);
    private static final int BATCH = 100;

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;

    public KeycloakAccountImport(UserRepository userRepository, IdentityProvider identityProvider) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        importPending();
    }

    /** @return how many accounts were handed to Keycloak (added there, or found there already) */
    int importPending() {
        List<User> pending = userRepository.findAll().stream()
                .filter(user -> StringUtils.hasText(user.getPasswordHash()))
                .toList();
        if (pending.isEmpty()) return 0;

        int done = 0;
        int added = 0;
        try {
            for (int from = 0; from < pending.size(); from += BATCH) {
                List<User> batch = pending.subList(from, Math.min(from + BATCH, pending.size()));
                added += identityProvider.importAccounts(batch);
                for (User user : batch) {
                    user.setPasswordHash(null);
                    userRepository.save(user);
                }
                done += batch.size();
            }
        } catch (RuntimeException e) {
            log.error("[KEYCLOAK-IMPORT] {} of {} accounts imported into Keycloak; the rest at the next start: {}",
                    done, pending.size(), e.getMessage());
            return done;
        }
        log.info("[KEYCLOAK-IMPORT] {} accounts imported into Keycloak ({} were there already); their passwords now "
                + "live there only", done, done - added);
        return done;
    }
}
