package com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.out.directory;

import com.materia.backend.contexts.purchaseOrder.domain.ports.out.ReceiverDirectory;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.identity.v1.FindUserRequest;
import com.materia.proto.identity.v1.FindUserResponse;
import com.materia.proto.identity.v1.FindUsersWithRoleRequest;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.identity.v1.UserSummary;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Resolves assignable receivers from the user accounts held by identity (identity.v1.UserDirectory). */
@Component
public class AuthReceiverDirectory implements ReceiverDirectory {

    private static final String RECEIVER_ROLE = "RECEIVER";

    private final UserDirectoryGrpc.UserDirectoryBlockingStub users;

    public AuthReceiverDirectory(UserDirectoryGrpc.UserDirectoryBlockingStub users) {
        this.users = users;
    }

    @Override
    public List<Receiver> findAssignableReceivers() {
        return GrpcCalls.call(() -> users.findUsersWithRole(
                        FindUsersWithRoleRequest.newBuilder().setRoleCode(RECEIVER_ROLE).build()))
                .getUsersList().stream()
                .filter(AuthReceiverDirectory::isAssignable)
                .map(AuthReceiverDirectory::toReceiver)
                .sorted(Comparator.comparing(Receiver::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public Optional<Receiver> findAssignableReceiver(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        FindUserResponse found = GrpcCalls.call(() -> users.findUser(FindUserRequest.newBuilder().setUserId(userId).build()));
        return Optional.of(found)
                .filter(FindUserResponse::hasUser)
                .map(FindUserResponse::getUser)
                .filter(user -> user.hasRoleCode() && RECEIVER_ROLE.equals(user.getRoleCode()))
                .filter(AuthReceiverDirectory::isAssignable)
                .map(AuthReceiverDirectory::toReceiver);
    }

    /** Only an enabled account with the ACTIVE status can be assigned; a missing status does not qualify. */
    private static boolean isAssignable(UserSummary user) {
        return user.getEnabled() && user.hasStatusCode() && "ACTIVE".equals(user.getStatusCode());
    }

    private static Receiver toReceiver(UserSummary user) {
        return new Receiver(user.getId(), user.hasDisplayName() ? user.getDisplayName() : null,
                user.hasEmail() ? user.getEmail() : null);
    }
}
