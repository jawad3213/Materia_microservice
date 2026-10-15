package com.materia.backend.contexts.employee.infrastructure.adapters.in.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEvent;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEventHandler;
import com.materia.backend.contexts.employee.domain.ports.in.EmployeeUseCase;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Keeps the employee record's name and phone in step with the account the employee edits on their profile
 * ({@code UserProfileUpdated} from auth). Runs after the profile change committed, in its own transaction: a failure
 * here never undoes the profile edit, and the event is delivered again.
 */
@Component
public class UserProfileSyncHandler implements IntegrationEventHandler {

    private final EmployeeUseCase employeeUseCase;

    public UserProfileSyncHandler(EmployeeUseCase employeeUseCase) {
        this.employeeUseCase = employeeUseCase;
    }

    @Override
    public String consumerName() {
        return "employee.user-profile-sync";
    }

    @Override
    public boolean handles(IntegrationEvent event) {
        return event.is("auth", "UserProfileUpdated");
    }

    @Override
    public void handle(IntegrationEvent event) {
        JsonNode payload = event.payload();
        String userId = text(payload, "userId");
        employeeUseCase.syncContactDetailsFromAccount(
                userId == null || userId.isBlank() ? null : UUID.fromString(userId),
                text(payload, "firstName"), text(payload, "lastName"), text(payload, "phone"));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
