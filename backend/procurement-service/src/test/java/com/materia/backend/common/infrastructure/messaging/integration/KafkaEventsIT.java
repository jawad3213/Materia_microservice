package com.materia.backend.common.infrastructure.messaging.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.materia.backend.common.domain.DomainEvent;
import com.materia.backend.common.infrastructure.messaging.outbox.SpringDataOutboxRepository;
import com.materia.backend.support.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event pipeline as it runs between services, against a real broker: a committed event goes to the outbox, the
 * relay publishes it to {@code materia.<context>.events}, the consumer reads it back and hands it to the handlers. The
 * in-process delivery is off, so whatever a handler receives came through Kafka.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "app.messaging.type=kafka",
        "app.outbox.relay.enabled=true",
        "app.outbox.relay.interval-ms=200",
        "app.events.in-process.enabled=false",
        "spring.kafka.listener.auto-startup=true"})
class KafkaEventsIT extends AbstractIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");
    private static final String TOPIC = "materia.common.events";
    private static final String GROUP = "kafka-events-it-" + UUID.randomUUID();
    /** A probe with this note fails in its handler every time: it ends in the dead-letter topic. */
    private static final String ALWAYS_FAILS = "always fails";

    static {
        KAFKA.start();
        // Created before the consumer starts: a topic created later is only found at the next metadata refresh.
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create " + TOPIC, e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.events.consumer-group", () -> GROUP);
    }

    /** A domain event of the shared kernel, so its source context is "common" and its topic materia.common.events. */
    static class KafkaProbeEvent extends DomainEvent {
        private final String note;

        KafkaProbeEvent(String note) {
            super(UUID.randomUUID());
            this.note = note;
        }

        public String getNote() {
            return note;
        }
    }

    static class KafkaProbeHandler implements IntegrationEventHandler {
        final List<IntegrationEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public String consumerName() {
            return "test.kafka-probe";
        }

        @Override
        public boolean handles(IntegrationEvent event) {
            return event.is("common", "KafkaProbe");
        }

        @Override
        public void handle(IntegrationEvent event) {
            if (ALWAYS_FAILS.equals(event.payload().path("note").asText())) {
                throw new IllegalStateException("The probe handler refuses this event");
            }
            received.add(event);
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        KafkaProbeHandler kafkaProbeHandler() {
            return new KafkaProbeHandler();
        }
    }

    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactions;
    @Autowired private KafkaProbeHandler handler;
    @Autowired private SpringDataOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        outbox.deleteAll();
        jdbc.update("delete from processed_events where consumer = ?", handler.consumerName());
        handler.received.clear();
    }

    private void awaitReceived(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (handler.received.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
    }

    @Test
    @DisplayName("events: a committed event reaches the handlers through the outbox, the relay and Kafka, with its payload")
    void committedEvent_travelsThroughKafka() throws InterruptedException {
        KafkaProbeEvent event = new KafkaProbeEvent("over the broker");
        transactions.executeWithoutResult(status -> events.publishEvent(event));

        awaitReceived(1);

        assertThat(handler.received).hasSize(1);
        IntegrationEvent received = handler.received.get(0);
        assertThat(received.eventId()).isEqualTo(event.getEventId());
        assertThat(received.sourceContext()).isEqualTo("common");
        assertThat(received.payload().path("note").asText()).isEqualTo("over the broker");
        assertThat(outbox.findAll()).allSatisfy(message -> assertThat(message.getPublishedAt()).isNotNull());
    }

    @Test
    @DisplayName("events: an event whose transaction rolls back never leaves the application")
    void rolledBackEvent_isNeverPublished() throws InterruptedException {
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new KafkaProbeEvent("rolled back"));
            status.setRollbackOnly();
        });
        KafkaProbeEvent committed = new KafkaProbeEvent("committed");
        transactions.executeWithoutResult(status -> events.publishEvent(committed));

        awaitReceived(1);
        Thread.sleep(1_000);

        assertThat(handler.received).extracting(IntegrationEvent::eventId).containsExactly(committed.getEventId());
    }

    @Test
    @DisplayName("events: an event a handler keeps failing on is kept in the group's dead-letter topic, not skipped")
    void failingEvent_endsInTheDeadLetterTopic() {
        KafkaProbeEvent event = new KafkaProbeEvent(ALWAYS_FAILS);
        transactions.executeWithoutResult(status -> events.publishEvent(event));

        String deadLetters = TOPIC + ".dlt." + GROUP;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(deadLetters));
            long deadline = System.currentTimeMillis() + 90_000;
            List<String> kept = new ArrayList<>();
            while (kept.stream().noneMatch(value -> value.contains(event.getEventId().toString()))
                    && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> kept.add(record.value()));
            }
            assertThat(kept).as("messages in %s", deadLetters)
                    .anySatisfy(value -> assertThat(value).contains(event.getEventId().toString(), ALWAYS_FAILS));
        }
        assertThat(handler.received).isEmpty();
    }
}
