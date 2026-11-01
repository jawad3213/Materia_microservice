package com.materia.backend.identity;

import com.materia.backend.infrastructure.grpc.GrpcNetworkServer;
import com.materia.grpc.auth.TokenVerifier;
import io.grpc.BindableService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/** Serves identity.v1 (UserDirectory) to the other services on {@code app.grpc.server.port}; every call needs a valid token. */
@Configuration
public class IdentityGrpcConfig {

    @Bean
    public GrpcNetworkServer identityGrpcNetworkServer(List<BindableService> services, TokenVerifier verifier,
                                                       @Value("${app.grpc.server.port:9091}") int port) {
        return new GrpcNetworkServer("Identity", port, services, verifier, Set.of());
    }
}
