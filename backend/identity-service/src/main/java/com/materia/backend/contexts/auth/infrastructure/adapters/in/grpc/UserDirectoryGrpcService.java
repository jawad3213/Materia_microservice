package com.materia.backend.contexts.auth.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.auth.application.dtos.UserSummary;
import com.materia.backend.contexts.auth.domain.ports.in.UserDirectoryUseCase;
import com.materia.proto.identity.v1.FindUserRequest;
import com.materia.proto.identity.v1.FindUserResponse;
import com.materia.proto.identity.v1.FindUsersWithRoleRequest;
import com.materia.proto.identity.v1.FindUsersWithRoleResponse;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** Serves identity.v1.UserDirectory from {@link UserDirectoryUseCase}. */
@Component
public class UserDirectoryGrpcService extends UserDirectoryGrpc.UserDirectoryImplBase {

    private final UserDirectoryUseCase users;

    public UserDirectoryGrpcService(UserDirectoryUseCase users) {
        this.users = users;
    }

    @Override
    public void findUser(FindUserRequest request, StreamObserver<FindUserResponse> response) {
        FindUserResponse.Builder reply = FindUserResponse.newBuilder();
        users.findUser(request.getUserId()).ifPresent(user -> reply.setUser(toProto(user)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void findUsersWithRole(FindUsersWithRoleRequest request, StreamObserver<FindUsersWithRoleResponse> response) {
        FindUsersWithRoleResponse.Builder reply = FindUsersWithRoleResponse.newBuilder();
        users.findUsersWithRole(request.getRoleCode()).forEach(user -> reply.addUsers(toProto(user)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    static com.materia.proto.identity.v1.UserSummary toProto(UserSummary user) {
        com.materia.proto.identity.v1.UserSummary.Builder proto = com.materia.proto.identity.v1.UserSummary.newBuilder()
                .setEnabled(user.enabled());
        if (user.id() != null) proto.setId(user.id().toString());
        if (user.email() != null) proto.setEmail(user.email());
        if (user.displayName() != null) proto.setDisplayName(user.displayName());
        if (user.roleCode() != null) proto.setRoleCode(user.roleCode());
        if (user.statusCode() != null) proto.setStatusCode(user.statusCode());
        return proto.build();
    }
}
