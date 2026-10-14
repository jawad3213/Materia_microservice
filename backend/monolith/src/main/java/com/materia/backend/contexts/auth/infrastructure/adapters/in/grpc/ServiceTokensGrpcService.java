package com.materia.backend.contexts.auth.infrastructure.adapters.in.grpc;

import com.materia.backend.contexts.auth.domain.exceptions.InvalidCredentialsException;
import com.materia.backend.contexts.auth.domain.ports.in.ServiceTokenUseCase;
import com.materia.proto.identity.v1.IssueServiceTokenRequest;
import com.materia.proto.identity.v1.IssueServiceTokenResponse;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/**
 * Serves identity.v1.ServiceTokens. Callable without a token (it is how a service gets one), so the gRPC server
 * lists it as public; wrong credentials answer {@code UNAUTHENTICATED}.
 */
@Component
public class ServiceTokensGrpcService extends ServiceTokensGrpc.ServiceTokensImplBase {

    /** The full method name the server's authentication lets through without a token. */
    public static final String ISSUE_METHOD = ServiceTokensGrpc.getIssueServiceTokenMethod().getFullMethodName();

    private final ServiceTokenUseCase serviceTokens;

    public ServiceTokensGrpcService(ServiceTokenUseCase serviceTokens) {
        this.serviceTokens = serviceTokens;
    }

    @Override
    public void issueServiceToken(IssueServiceTokenRequest request, StreamObserver<IssueServiceTokenResponse> response) {
        ServiceTokenUseCase.IssuedServiceToken issued;
        try {
            issued = serviceTokens.issue(request.getClientId(), request.getClientSecret());
        } catch (InvalidCredentialsException e) {
            response.onError(Status.UNAUTHENTICATED.withDescription("Unknown service client or wrong secret")
                    .asRuntimeException());
            return;
        }
        response.onNext(IssueServiceTokenResponse.newBuilder()
                .setToken(issued.token())
                .setExpiresInSeconds(issued.expiresInSeconds())
                .build());
        response.onCompleted();
    }
}
