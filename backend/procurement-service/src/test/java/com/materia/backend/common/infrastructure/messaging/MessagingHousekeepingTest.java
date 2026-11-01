package com.materia.backend.common.infrastructure.messaging;

import com.materia.backend.common.infrastructure.messaging.integration.ProcessedEvents;
import com.materia.backend.common.infrastructure.messaging.outbox.SpringDataOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The outbox and the inbox are purged after their retention, so they do not grow for ever. */
@ExtendWith(MockitoExtension.class)
class MessagingHousekeepingTest {

    @Mock private SpringDataOutboxRepository outbox;
    @Mock private ProcessedEvents processedEvents;
    @Mock private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("housekeeping: published outbox messages go after 7 days, inbox records after 30")
    void purge_usesEachRetention() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(outbox.deletePublishedBefore(any())).thenReturn(12);
        when(processedEvents.forgetProcessedBefore(any())).thenReturn(3);
        MessagingHousekeeping housekeeping = new MessagingHousekeeping(outbox, processedEvents,
                new TransactionTemplate(transactionManager), Duration.ofDays(7), Duration.ofDays(30), Duration.ofHours(1));

        assertThat(housekeeping.purge()).containsExactly(12, 3);

        ArgumentCaptor<LocalDateTime> outboxBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> inboxBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outbox).deletePublishedBefore(outboxBefore.capture());
        verify(processedEvents).forgetProcessedBefore(inboxBefore.capture());
        assertThat(outboxBefore.getValue()).isCloseTo(LocalDateTime.now().minusDays(7), within(1, ChronoUnit.MINUTES));
        assertThat(inboxBefore.getValue()).isCloseTo(LocalDateTime.now().minusDays(30), within(1, ChronoUnit.MINUTES));
    }
}
