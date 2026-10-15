package com.materia.backend.contexts.employee.infrastructure.adapters.in.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.materia.backend.common.infrastructure.messaging.integration.InProcessIntegrationEvents;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEvent;
import com.materia.backend.contexts.auth.domain.events.UserProfileUpdatedEvent;
import com.materia.backend.contexts.employee.domain.ports.in.EmployeeUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The employee record follows the profile from the event's JSON alone, as it will once auth is another service. */
class UserProfileSyncHandlerTest {

    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    private final EmployeeUseCase employees = mock(EmployeeUseCase.class);
    private final UserProfileSyncHandler handler = new UserProfileSyncHandler(employees);

    private static IntegrationEvent envelope(UserProfileUpdatedEvent event) {
        return InProcessIntegrationEvents.toIntegrationEvent(event, JSON);
    }

    @Test
    @DisplayName("employee: a profile edit in auth updates the employee's name and phone from the event payload")
    void profileUpdated_syncsContactDetailsFromThePayload() {
        UUID userId = UUID.randomUUID();
        IntegrationEvent event = envelope(new UserProfileUpdatedEvent(userId, "Sara", "Benali", "+212600000000"));

        assertThat(handler.handles(event)).isTrue();
        handler.handle(event);

        verify(employees).syncContactDetailsFromAccount(userId, "Sara", "Benali", "+212600000000");
    }

    @Test
    @DisplayName("employee: a profile edit without a phone passes no phone rather than the text \"null\"")
    void profileUpdated_withoutPhone_passesNull() {
        UUID userId = UUID.randomUUID();
        handler.handle(envelope(new UserProfileUpdatedEvent(userId, "Sara", "Benali", null)));

        verify(employees).syncContactDetailsFromAccount(userId, "Sara", "Benali", null);
    }

    @Test
    @DisplayName("employee: the sync handler ignores another context's event of the same name")
    void otherEvents_areIgnored() {
        IntegrationEvent other = new IntegrationEvent(UUID.randomUUID(), "UserProfileUpdated", "masterData", null,
                null, JSON.createObjectNode());

        assertThat(handler.handles(other)).isFalse();
    }
}
