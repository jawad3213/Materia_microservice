package com.materia.backend.contexts.auth.infrastructure.adapters.out.clients;

import com.materia.backend.contexts.auth.domain.ports.out.ServiceClients;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * The services allowed a service token, from configuration: {@code app.service-clients.<client id>=<secret>}
 * (in deployments, one secret per service from Parameter Store). A client with a blank secret is not registered.
 */
@Component
public class ConfiguredServiceClients implements ServiceClients {

    private final Map<String, String> secrets;

    public ConfiguredServiceClients(Environment environment) {
        this.secrets = Binder.get(environment)
                .bind("app.service-clients", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    @Override
    public boolean matches(String clientId, String clientSecret) {
        String expected = secrets.get(clientId);
        if (expected == null || expected.isBlank() || clientSecret == null) return false;
        // Constant time, so the comparison does not tell how much of a guess was right.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                clientSecret.getBytes(StandardCharsets.UTF_8));
    }
}
