package com.materia.keycloak.bcrypt;

import org.keycloak.Config;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.credential.hash.PasswordHashProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/** Registers {@link BCryptPasswordHashProvider} as the password hash algorithm {@code bcrypt}. */
public class BCryptPasswordHashProviderFactory implements PasswordHashProviderFactory {

    private static final BCryptPasswordHashProvider PROVIDER = new BCryptPasswordHashProvider();

    @Override
    public PasswordHashProvider create(KeycloakSession session) {
        return PROVIDER;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return BCryptPasswordHashProvider.ID;
    }
}
