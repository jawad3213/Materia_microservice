package com.materia.backend.common.infrastructure.messaging.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes the envelopes {@code OutboxRelay} publishes on {@code materia.<context>.events} and hands them to the
 * {@link IntegrationEventHandler}s. Messages are read as plain JSON text: no Java type named by a message is ever
 * instantiated. A handler failure is retried a few times; {@link ProcessedEvents} makes the retries safe.
 *
 * <p>A message still failing after the retries, or that cannot be read, goes unchanged to this consumer group's
 * dead-letter topic, {@code <topic>.dlt.<group>} (outside the {@code materia.*.events} pattern, so nobody consumes it
 * by mistake), and counts in {@code materia.events.dead.lettered}: it is kept to replay once the cause is fixed,
 * instead of being skipped. The consumers' own metrics (lag included) are registered too.
 */
@Configuration
@ConditionalOnProperty(name = "app.messaging.type", havingValue = "kafka")
public class KafkaIntegrationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaIntegrationEventConsumer.class);

    private final IntegrationEventDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public KafkaIntegrationEventConsumer(IntegrationEventDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Static, so the listener below can use it while this class is still being created (an instance factory method
     * would be a circular reference, and the consumer would never start).
     */
    @Bean
    public static ConcurrentKafkaListenerContainerFactory<String, String> integrationEventListenerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.events.consumer-group:materia}") String groupId,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup,
            ObjectProvider<MeterRegistry> meters) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        DefaultKafkaConsumerFactory<String, String> consumers = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        meters.ifAvailable(registry -> consumers.addListener(new MicrometerConsumerListener<>(registry)));
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                deadLetters(bootstrapServers, groupId, meters.getIfAvailable()), new FixedBackOff(2_000L, 4)));
        factory.setAutoStartup(autoStartup);
        return factory;
    }

    /**
     * Sends a message that kept failing to {@code <topic>.dlt.<group>}, as it was read (plain text, the original key),
     * on whichever partition the broker picks: the dead-letter topic is created with its own partition count.
     */
    static ConsumerRecordRecoverer deadLetters(String bootstrapServers, String groupId, MeterRegistry registry) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(), new StringSerializer()));
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(deadLetterTopic(record.topic(), groupId), -1));
        Counter counter = registry == null ? null : Counter.builder("materia.events.dead.lettered")
                .description("Events a consumer could not apply after its retries, kept in its dead-letter topic")
                .tag("group", groupId)
                .register(registry);
        return (record, failure) -> {
            log.error("Event at {}-{}@{} failed after its retries: kept in {} ({})", record.topic(), record.partition(),
                    record.offset(), deadLetterTopic(record.topic(), groupId), failure.toString());
            publisher.accept(record, failure);
            if (counter != null) counter.increment();
        };
    }

    static String deadLetterTopic(String topic, String groupId) {
        return topic + ".dlt." + groupId;
    }

    @KafkaListener(topicPattern = "materia\\..*\\.events", containerFactory = "integrationEventListenerFactory")
    public void consume(String message) throws Exception {
        IntegrationEvent event = parse(message, objectMapper);
        List<String> failed = dispatcher.dispatch(event);
        if (!failed.isEmpty()) {
            // Redelivered by the error handler; the handlers that already succeeded skip it then.
            throw new IllegalStateException("Event " + event.eventId() + " failed in " + failed);
        }
    }

    /** Reads an envelope written by OutboxRelay. */
    static IntegrationEvent parse(String message, ObjectMapper objectMapper) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        JsonNode aggregateId = envelope.path("aggregateId");
        return new IntegrationEvent(
                UUID.fromString(envelope.path("eventId").asText()),
                envelope.path("eventType").asText(),
                envelope.path("sourceContext").asText(),
                aggregateId.isMissingNode() || aggregateId.isNull() ? null : aggregateId.asText(),
                envelope.path("occurredAt").asText(null),
                envelope.path("payload"));
    }
}
