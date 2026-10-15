package com.materia.backend.support.fixtures;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accounts for this application's tests. Identity holds the accounts, so these are what its directory tells about
 * them ({@link TestAccount}); register one with {@link TestIdentity#save}.
 */
public final class UserFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    private UserFixtures() {
    }

    public static Builder aUser() {
        return new Builder();
    }

    /** An active, enabled receiver: the only kind of user an order may be assigned to. */
    public static Builder aReceiver() {
        return new Builder().role("RECEIVER").firstName("Rita").lastName("Receiver");
    }

    public static Builder anInactiveReceiver() {
        return aReceiver().status("INACTIVE");
    }

    public static Builder aDisabledReceiver() {
        return aReceiver().enabled(false);
    }

    public static Builder aPurchaser() {
        return new Builder().role("PURCHASER").firstName("Bob").lastName("Buyer");
    }

    public static String uniqueEmail() {
        return String.format("user-%04d@materia.test", SEQUENCE.getAndIncrement() % 10000);
    }

    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private String email = uniqueEmail();
        private String role = "PURCHASER";
        private boolean enabled = true;
        private String firstName = "John";
        private String lastName = "Doe";
        private String status = "ACTIVE";

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder role(String roleCode) { this.role = roleCode; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder firstName(String firstName) { this.firstName = firstName; return this; }
        public Builder lastName(String lastName) { this.lastName = lastName; return this; }
        public Builder status(String statusCode) { this.status = statusCode; return this; }

        public TestAccount build() {
            return new TestAccount(id, email, firstName, lastName, role, enabled, status);
        }
    }
}
