package com.materia.backend.support.fixtures;

import java.util.UUID;

/**
 * An account held by identity, as this application's tests set it up: what identity.v1.UserDirectory tells about it.
 * Built with {@link UserFixtures}, registered with {@link TestIdentity}.
 */
public final class TestAccount {

    private final UUID id;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final String roleCode;
    private final boolean enabled;
    private final String statusCode;

    TestAccount(UUID id, String email, String firstName, String lastName, String roleCode, boolean enabled,
                String statusCode) {
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.roleCode = roleCode;
        this.enabled = enabled;
        this.statusCode = statusCode;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return firstName + " " + lastName; }
    public String getRoleCode() { return roleCode; }
    public boolean isEnabled() { return enabled; }
    public String getStatusCode() { return statusCode; }

    /** As identity shows it: the full name, else the e-mail. */
    public String getDisplayName() {
        return getFullName().isBlank() ? email : getFullName();
    }
}
