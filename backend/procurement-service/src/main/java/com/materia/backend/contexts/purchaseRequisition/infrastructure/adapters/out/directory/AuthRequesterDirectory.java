package com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.out.directory;

import com.materia.backend.contexts.purchaseRequisition.domain.ports.out.RequesterDirectory;
import com.materia.grpc.errors.GrpcCalls;
import com.materia.proto.identity.v1.FindUserRequest;
import com.materia.proto.identity.v1.FindUserResponse;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import org.springframework.stereotype.Component;

/** Resolves requester names from the user accounts held by identity (identity.v1.UserDirectory). */
@Component
public class AuthRequesterDirectory implements RequesterDirectory {

    private final UserDirectoryGrpc.UserDirectoryBlockingStub users;

    public AuthRequesterDirectory(UserDirectoryGrpc.UserDirectoryBlockingStub users) {
        this.users = users;
    }

    @Override
    public String displayName(String userId) {
        if (userId == null || userId.isBlank()) return userId;
        FindUserResponse found = GrpcCalls.call(() -> users.findUser(FindUserRequest.newBuilder().setUserId(userId).build()));
        return found.hasUser() && found.getUser().hasDisplayName() ? found.getUser().getDisplayName() : userId;
    }
}
