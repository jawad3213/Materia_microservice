package com.materia.backend.common.infrastructure.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.materia.backend.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Against PostgreSQL: while one copy of a service holds its relay lock, another copy relays nothing. */
class OutboxRelayLockIT extends AbstractIntegrationTest {

    @Autowired private SpringDataOutboxRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("relay: a copy finding the service's relay lock taken leaves the batch to the copy holding it")
    @SuppressWarnings("unchecked")
    void lockHeldByAnotherCopy_relaysNothing() throws Exception {
        String service = "lock-it-" + UUID.randomUUID();
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), new ObjectMapper(),
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class), jdbc, service, 1000, 10, 1000);

        // The other copy: its own transaction, on its own connection, holding the lock until released
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate otherCopy = new TransactionTemplate(transactionManager);
        otherCopy.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Thread holder = new Thread(() -> otherCopy.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtext(?))", Boolean.class,
                    "outbox-relay:" + service)).isTrue();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(relay.relayBatch()).isZero();
            verify(kafka, never()).send(anyString(), anyString(), any());
        } finally {
            release.countDown();
            holder.join(30_000);
        }
    }
}
