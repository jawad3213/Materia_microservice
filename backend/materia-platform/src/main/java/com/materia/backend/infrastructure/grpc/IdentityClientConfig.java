package com.materia.backend.infrastructure.grpc;

import com.materia.backend.common.domain.ports.out.UserDisplayNames;
import com.materia.grpc.auth.CallerTokenClientInterceptor;
import com.materia.grpc.deadline.DefaultDeadlineClientInterceptor;
import com.materia.grpc.trace.TraceIdClientInterceptor;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * What a service other than identity needs to call the others: the token every outgoing call carries ({@link
 * OutgoingCallerToken}), with its own service tokens from Keycloak ({@link KeycloakServiceTokens}), and identity's
 * directory (identity.v1.UserDirectory, at {@code app.grpc.identity.target} with a short deadline) with the names the
 * change history and attachments show. Identity itself sets no target.
 */
@Configuration
@ConditionalOnProperty(name = "app.grpc.identity.target")
public class IdentityClientConfig {

    /** This service's client credentials grant; the realm is reached at the issuer unless set apart. */
    @Bean
    public KeycloakServiceTokens keycloakServiceTokens(
            RestClient.Builder http,
            @Value("${app.keycloak.realm-uri:${app.keycloak.issuer-uri:}}") String realmUri,
            @Value("${app.service-client.id}") String clientId,
            @Value("${app.service-client.secret}") String clientSecret) {
        return new KeycloakServiceTokens(http, realmUri, clientId, clientSecret);
    }

    /** Keycloak's service tokens, unless another source is primary (the tests' stand-in). */
    @Bean
    public OutgoingCallerToken outgoingCallerToken(ServiceTokenSource serviceTokens) {
        return new OutgoingCallerToken(serviceTokens);
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
