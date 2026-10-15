package com.materia.backend.support.fixtures;

import com.materia.proto.identity.v1.FindUserRequest;
import com.materia.proto.identity.v1.FindUserResponse;
import com.materia.proto.identity.v1.FindUsersWithRoleRequest;
import com.materia.proto.identity.v1.FindUsersWithRoleResponse;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import com.materia.proto.identity.v1.UserSummary;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * identity.v1.UserDirectory over the accounts a test registers, answering as identity does: unknown, blank and
 * malformed ids find nobody; the display name is the full name, else the e-mail.
 */
public class TestUserDirectory extends UserDirectoryGrpc.UserDirectoryImplBase {

    private final Map<UUID, TestAccount> accounts = new ConcurrentHashMap<>();

    public TestAccount save(TestAccount account) {
        accounts.put(account.getId(), account);
        return account;
    }

    public void remove(UUID id) {
        accounts.remove(id);
    }

    @Override
    public void findUser(FindUserRequest request, StreamObserver<FindUserResponse> response) {
        FindUserResponse.Builder reply = FindUserResponse.newBuilder();
        parse(request.getUserId()).map(accounts::get).ifPresent(account -> reply.setUser(toProto(account)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    @Override
    public void findUsersWithRole(FindUsersWithRoleRequest request, StreamObserver<FindUsersWithRoleResponse> response) {
        FindUsersWithRoleResponse.Builder reply = FindUsersWithRoleResponse.newBuilder();
        accounts.values().stream()
                .filter(account -> request.getRoleCode().equalsIgnoreCase(account.getRoleCode()))
                .forEach(account -> reply.addUsers(toProto(account)));
        response.onNext(reply.build());
        response.onCompleted();
    }

    private static Optional<UUID> parse(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(id.trim()));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static UserSummary toProto(TestAccount account) {
        UserSummary.Builder proto = UserSummary.newBuilder()
                .setId(account.getId().toString())
                .setEnabled(account.isEnabled());
        if (account.getEmail() != null) proto.setEmail(account.getEmail());
        if (account.getDisplayName() != null) proto.setDisplayName(account.getDisplayName());
        if (account.getRoleCode() != null) proto.setRoleCode(account.getRoleCode());
        if (account.getStatusCode() != null) proto.setStatusCode(account.getStatusCode());
        return proto.build();
    }
}
