package com.materia.backend.infrastructure.seed;

import com.materia.backend.common.infrastructure.seed.DevSeed;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.identity.v1.FindUsersWithRoleRequest;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.identity.v1.UserSummary;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The dev accounts (DevSeed's three e-mails), for the seeders of this application: identity seeds them, so they are
 * looked up in identity's directory, by the role each has. Identity may still be starting when this application
 * seeds, so the lookup waits for it for a while; without it the seeders run without users and say so.
 */
@Component
@Profile(DevSeed.PROFILE)
public class DevUsers {

    private static final Logger log = LoggerFactory.getLogger(DevUsers.class);
    private static final Map<String, String> ROLE_OF = Map.of(
            DevSeed.ADMIN_EMAIL, "ADMIN",
            DevSeed.PURCHASER_EMAIL, "PURCHASER",
            DevSeed.RECEIVER_EMAIL, "RECEIVER");
    private static final int ATTEMPTS = 30;
    private static final long PAUSE_MS = 2_000;

    /** What a seeder needs of an account. */
    public record SeedUser(UUID id, String email, String displayName) {
    }

    private final UserDirectoryGrpc.UserDirectoryBlockingStub users;

    public DevUsers(UserDirectoryGrpc.UserDirectoryBlockingStub users) {
        this.users = users;
    }

    public Optional<SeedUser> findUserByEmail(String email) {
        String role = ROLE_OF.get(email);
        if (role == null) return Optional.empty();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return GrpcCalls.call(() -> users.findUsersWithRole(
                                FindUsersWithRoleRequest.newBuilder().setRoleCode(role).build()))
                        .getUsersList().stream()
                        .filter(user -> user.hasEmail() && email.equalsIgnoreCase(user.getEmail()))
                        .findFirst()
                        .map(DevUsers::toSeedUser);
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.UNAVAILABLE) throw e;
                if (attempt == ATTEMPTS) {
                    log.warn("[DEV-SEED] Identity unreachable: seeding without the account {}", email);
                    return Optional.empty();
                }
                sleep();
            }
        }
        return Optional.empty();
    }

    private static SeedUser toSeedUser(UserSummary user) {
        return new SeedUser(UUID.fromString(user.getId()), user.getEmail(),
                user.hasDisplayName() ? user.getDisplayName() : user.getEmail());
    }

    private static void sleep() {
        try {
            Thread.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
