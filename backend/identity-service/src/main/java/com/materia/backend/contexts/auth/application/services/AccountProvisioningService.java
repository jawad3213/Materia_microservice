package com.materia.backend.contexts.auth.application.services;

import com.materia.backend.contexts.auth.domain.entities.User;
import com.materia.backend.contexts.auth.domain.enums.Role;
import com.materia.backend.contexts.auth.domain.enums.UserStatus;
import com.materia.backend.contexts.auth.domain.exceptions.EmailAlreadyExistsException;
import com.materia.backend.contexts.auth.domain.ports.in.AccountProvisioningUseCase;
import com.materia.backend.contexts.auth.domain.ports.out.EmailSender;
import com.materia.backend.contexts.auth.domain.ports.out.IdentityProvider;
import com.materia.backend.contexts.auth.domain.ports.out.UserRepository;
import com.materia.backend.contexts.auth.domain.valueObjects.Password;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Provisions and revokes the user accounts of employees. The account itself (its password, its sessions) lives in
 * the identity provider; identity keeps its directory entry under the same id.
 */
@Service
@Transactional
public class AccountProvisioningService implements AccountProvisioningUseCase {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);

    /** Role granted when the caller does not specify one. An unknown code is rejected, never downgraded to this. */
    private static final Role DEFAULT_ROLE = Role.PURCHASER;

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;
    private final EmailSender emailSender;

    public AccountProvisioningService(UserRepository userRepository,
                                      IdentityProvider identityProvider,
                                      EmailSender emailSender) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.emailSender = emailSender;
    }

    @Override
    public UUID provisionAccount(String email, String firstName, String lastName, String roleCode, String rawPassword) {
        String normalizedEmail = email.trim().toLowerCase();
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new EmailAlreadyExistsException("A user account already exists with email: " + normalizedEmail);
        }

        Role role = roleCode == null || roleCode.isBlank() ? DEFAULT_ROLE : Role.fromCode(roleCode.trim().toUpperCase());

        // A caller-supplied password must satisfy the same policy as one the user chooses,
        // so provisioning cannot become a way around the password rules.
        boolean passwordWasGenerated = rawPassword == null || rawPassword.trim().isEmpty();
        String password = passwordWasGenerated ? generateSecureTemporaryPassword() : rawPassword.trim();
        Password.fromRaw(password);

        User user = User.builder()
                .email(normalizedEmail)
                .firstName(firstName)
                .lastName(lastName)
                .role(role)
                .status(UserStatus.ACTIVE)
                .enabled(true)
                .build();

        // The identity provider chooses the id; the directory entry takes it, so it is the subject of the user's tokens.
        UUID id = identityProvider.createAccount(user, password, true);
        user.setId(id);
        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (RuntimeException e) {
            identityProvider.deleteAccount(id);
            throw e;
        }
        log.info("[ACCOUNT-PROVISIONING] Provisioned User ID {} with role {} for {} (new password at first sign-in)",
                savedUser.getId(), role.getCode(), normalizedEmail);

        // Deliver credentials via transactional email
        emailSender.sendTemporaryPasswordEmail(normalizedEmail, password);
        return savedUser.getId();
    }

    @Override
    public void revokeAccess(UUID userId) {
        if (userId == null) return;
        userRepository.findById(userId).ifPresent(user -> {
            user.setStatus(UserStatus.INACTIVE);
            user.setEnabled(false);
            userRepository.save(user);
            // Disabled where the user signs in, with every session ended: their tokens are not renewed
            identityProvider.disableAccount(userId);
            log.info("[ACCOUNT-PROVISIONING] Disabled user ID {} and ended their sessions", userId);
        });
    }

    @Override
    public List<String> roleCodes() {
        return Role.getCodes();
    }

    /**
     * Builds a temporary password that satisfies the {@link Password} policy by construction:
     * the prefix carries the upper case, lower case and special character, and a digit is
     * appended because the random Base64 segment is not guaranteed to contain one.
     */
    private String generateSecureTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[9];
        random.nextBytes(bytes);
        return "Tmp!" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + random.nextInt(10);
    }
}
