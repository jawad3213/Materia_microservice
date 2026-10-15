package com.materia.backend.infrastructure.grpc;

import com.materia.backend.common.domain.ports.out.UserDisplayNames;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * What a service other than identity needs of it, at {@code app.grpc.identity.target} with a short deadline: its
 * service tokens (identity.v1.ServiceTokens, on a channel without the caller token, since asking for a service token
 * must not need one), the token every outgoing call carries ({@link OutgoingCallerToken}), and its directory
 * (identity.v1.UserDirectory) with the names the change history and attachments show. Identity itself sets no target.
 */
@Configuration
@ConditionalOnProperty(name = "app.grpc.identity.target")
public class IdentityClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel identityServiceTokenChannel(@Value("${app.grpc.identity.target}") String target,
                                                      @Value("${app.grpc.identity.deadline:5s}") Duration deadline) {
        return GrpcChannels.to(target, new TraceIdClientInterceptor(), new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public OutgoingCallerToken outgoingCallerToken(
            @Qualifier("identityServiceTokenChannel") ManagedChannel identityServiceTokenChannel,
            @Value("${app.service-client.id}") String clientId,
            @Value("${app.service-client.secret}") String clientSecret) {
        return new OutgoingCallerToken(ServiceTokensGrpc.newBlockingStub(identityServiceTokenChannel), clientId,
                clientSecret);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel identityChannel(OutgoingCallerToken tokens,
                                          @Value("${app.grpc.identity.target}") String target,
                                          @Value("${app.grpc.identity.deadline:5s}") Duration deadline) {
        return GrpcChannels.to(target, new CallerTokenClientInterceptor(tokens), new TraceIdClientInterceptor(),
                new DefaultDeadlineClientInterceptor(deadline));
    }

    @Bean
    public UserDirectoryGrpc.UserDirectoryBlockingStub userDirectoryStub(
            @Qualifier("identityChannel") ManagedChannel identityChannel) {
        return UserDirectoryGrpc.newBlockingStub(identityChannel);
    }

    /** Names in the change history and on attachments, from identity's directory. */
    @Bean
    public UserDisplayNames userDisplayNames(UserDirectoryGrpc.UserDirectoryBlockingStub userDirectoryStub) {
        return new GrpcUserDisplayNames(userDirectoryStub);
    }
}
