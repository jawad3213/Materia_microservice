package com.materia.backend.infrastructure.grpc;

import com.materia.backend.common.domain.ports.out.UserDisplayNames;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.identity.v1.FindUserRequest;
import com.materia.proto.identity.v1.FindUserResponse;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.identity.v1.UserSummary;

import java.util.Optional;

/**
 * The names the shared kernel shows (change history, attachments) for a service other than identity: asked of
 * identity's directory (identity.v1.UserDirectory). Registered by each service's gRPC configuration.
 */
public class GrpcUserDisplayNames implements UserDisplayNames {

    private final UserDirectoryGrpc.UserDirectoryBlockingStub users;

    public GrpcUserDisplayNames(UserDirectoryGrpc.UserDirectoryBlockingStub users) {
        this.users = users;
    }

    @Override
    public Optional<String> displayName(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        FindUserResponse found = GrpcCalls.call(() -> users.findUser(FindUserRequest.newBuilder().setUserId(userId).build()));
        if (!found.hasUser()) return Optional.empty();
        UserSummary user = found.getUser();
        if (user.hasDisplayName() && !user.getDisplayName().isBlank()) return Optional.of(user.getDisplayName());
        return user.hasEmail() ? Optional.of(user.getEmail()) : Optional.empty();
    }
}
