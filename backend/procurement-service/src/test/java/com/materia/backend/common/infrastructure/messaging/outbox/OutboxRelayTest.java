package com.materia.backend.common.infrastructure.messaging.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.KafkaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pending outbox messages are published in order, once acknowledged; a broker failure stops the batch, a message that
 * can never be published is parked and the later ones go on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    @Mock private SpringDataOutboxRepository outbox;
    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private PlatformTransactionManager transactionManager;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), new ObjectMapper(),
                new StaticListableBeanFactory(Map.of("meters", meters)).getBeanProvider(MeterRegistry.class),
                1000, 10, 1000);
    }

    private static OutboxMessageJpaEntity message(String context, String aggregateId) {
        return new OutboxMessageJpaEntity(UUID.randomUUID(), "SomethingHappened", context, aggregateId,
                "{\"code\":\"X-1\"}", LocalDateTime.now());
    }

    @Test
    @DisplayName("relay: each message goes to its context topic, keyed by aggregate, and is then marked published")
    @SuppressWarnings("unchecked")
    void publishesToContextTopic() {
        OutboxMessageJpaEntity first = message("purchaseOrder", "po-1");
        OutboxMessageJpaEntity second = message("invoice", "inv-1");
        when(outbox.findPending(any())).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertEquals(2, relay.relayBatch());

        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("materia.purchaseOrder.events"), eq("po-1"), envelope.capture());
        verify(kafka).send(eq("materia.invoice.events"), eq("inv-1"), any());
        Map<String, Object> sent = (Map<String, Object>) envelope.getValue();
        assertEquals(first.getEventId().toString(), sent.get("eventId"));
        assertEquals(Map.of("code", "X-1"), sent.get("payload"));
        assertNotNull(first.getPublishedAt());
        assertNotNull(second.getPublishedAt());
    }

    @Test
    @DisplayName("relay: a message the broker refuses stays pending, records the error and stops the batch")
    void failureStopsTheBatch() {
        OutboxMessageJpaEntity first = message("purchaseOrder", "po-1");
        OutboxMessageJpaEntity second = message("purchaseOrder", "po-1");
        when(outbox.findPending(any())).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertEquals(0, relay.relayBatch());

        assertNull(first.getPublishedAt());
        assertEquals(1, first.getAttempts());
        assertTrue(first.getLastError().contains("broker down"));
        verify(kafka, times(1)).send(anyString(), anyString(), any());
        assertEquals(0, second.getAttempts());
    }

    @Test
    @DisplayName("relay: a message that can never be published is parked, and the later ones are published")
    void poisonMessage_isParked() {
        OutboxMessageJpaEntity poison = message("purchaseOrder", "po-1");
        OutboxMessageJpaEntity next = message("purchaseOrder", "po-2");
        when(outbox.findPending(any())).thenReturn(List.of(poison, next));
        when(kafka.send(anyString(), eq("po-1"), any())).thenReturn(CompletableFuture.failedFuture(
                new KafkaException("Send failed", new RecordTooLargeException("The message is 2 MB"))));
        when(kafka.send(anyString(), eq("po-2"), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertEquals(1, relay.relayBatch());

        assertNotNull(poison.getParkedAt());
        assertNull(poison.getPublishedAt());
        assertTrue(poison.getLastError().contains("The message is 2 MB"));
        assertNotNull(next.getPublishedAt());
    }

    @Test
    @DisplayName("relay: a broker failure is not the message's own, so nothing is parked")
    void brokerFailure_isNotPermanent() {
        assertTrue(OutboxRelay.isPermanent(new KafkaException("x", new SerializationException("bad"))));
        assertFalse(OutboxRelay.isPermanent(new KafkaException("x", new TimeoutException("broker down"))));
        assertFalse(OutboxRelay.isPermanent(new IllegalStateException("broker down")));
    }

    @Test
    @DisplayName("relay: the backlog and the parked messages are metrics, for the alerts")
    void backlog_isAMetric() {
        when(outbox.findPending(any())).thenReturn(List.of());
        when(outbox.countPending()).thenReturn(42L);
        when(outbox.countParked()).thenReturn(1L);

        relay.relayBatch();

        assertEquals(42.0, meters.get("materia.outbox.pending").gauge().value());
        assertEquals(1.0, meters.get("materia.outbox.parked").gauge().value());
    }
}
