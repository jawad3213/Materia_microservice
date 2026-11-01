package com.materia.backend.common.infrastructure.messaging.outbox;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the events recorded by {@link OutboxRecorder} to Kafka, oldest first, on the topic of the context that
 * raised them ({@code materia.<context>.events}), keyed by aggregate id so one aggregate's events stay in order.
 *
 * <p>Delivery is at least once: a message is marked published only after the broker acknowledged it, so consumers
 * must ignore an {@code eventId} they already processed. Off by default; enable with
 * {@code app.outbox.relay.enabled=true} together with {@code app.messaging.type=kafka}.
 *
 * <p>A failure the broker may get over (unreachable, timing out) stops the batch, so later events wait and the order
 * holds. A message that can never be published as it is (it cannot be serialized, is too large, names an invalid
 * topic) is <em>parked</em> instead: kept with why, and skipped, so it no longer holds back every later event. The
 * backlog and the parked messages are metrics ({@code materia.outbox.pending}, {@code materia.outbox.parked}) the
 * alerts watch.
 *
 * <p>It runs on its own thread rather than through {@code @Scheduled}, so it does not depend on SchedulingConfig.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final long intervalMs;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();
    private ScheduledExecutorService executor;

    public OutboxRelay(SpringDataOutboxRepository outbox,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       TransactionTemplate transactions,
                       ObjectMapper objectMapper,
                       ObjectProvider<MeterRegistry> meters,
                       @Value("${app.outbox.relay.interval-ms:2000}") long intervalMs,
                       @Value("${app.outbox.relay.batch-size:100}") int batchSize,
                       @Value("${app.outbox.relay.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.intervalMs = intervalMs;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        meters.ifAvailable(registry -> {
            Gauge.builder("materia.outbox.pending", pending, AtomicLong::get)
                    .description("Events waiting to be published: a growing number means the broker is unreachable")
                    .register(registry);
            Gauge.builder("materia.outbox.parked", parked, AtomicLong::get)
                    .description("Events that can never be published as they are, waiting to be fixed")
                    .register(registry);
        });
    }

    /** Relays one batch; returns how many messages were published. Stops at the first failure to keep the order. */
    public int relayBatch() {
        List<OutboxMessageJpaEntity> batch = transactions.execute(status -> outbox.findPending(PageRequest.of(0, batchSize)));
        int published = 0;
        for (OutboxMessageJpaEntity message : batch != null ? batch : List.<OutboxMessageJpaEntity>of()) {
            try {
                kafkaTemplate.send(topicOf(message), message.getAggregateId(), envelope(message))
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                message.markPublished();
                transactions.executeWithoutResult(status -> outbox.save(message));
                published++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (isPermanent(e)) {
                    log.error("Outbox relay parked {} {}: it can never be published as it is ({}). Later events go on; "
                                    + "set its parked_at back to null once fixed to publish it.",
                            message.getEventType(), message.getEventId(), rootCause(e).toString());
                    message.markParked(rootCause(e).toString());
                    transactions.executeWithoutResult(status -> outbox.save(message));
                    continue;
                }
                log.warn("Outbox relay could not publish {} {}: {}", message.getEventType(), message.getEventId(), e.toString());
                message.markFailed(e.toString());
                transactions.executeWithoutResult(status -> outbox.save(message));
                break;
            }
        }
        refreshCounts();
        return published;
    }

    /**
     * Whether a failure is the message's own, which retrying cannot cure, rather than the broker's: then waiting
     * behind it would stop every later event for good.
     */
    static boolean isPermanent(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RecordTooLargeException || t instanceof InvalidTopicException
                    || t instanceof SerializationException || t instanceof JacksonException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private void refreshCounts() {
        try {
            transactions.executeWithoutResult(status -> {
                pending.set(outbox.countPending());
                parked.set(outbox.countParked());
            });
        } catch (RuntimeException e) {
            log.debug("Outbox relay could not count its backlog: {}", e.toString());
        }
    }

    static String topicOf(OutboxMessageJpaEntity message) {
        return "materia." + message.getSourceContext() + ".events";
    }

    private Map<String, Object> envelope(OutboxMessageJpaEntity message) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", message.getEventId().toString());
        envelope.put("eventType", message.getEventType());
        envelope.put("sourceContext", message.getSourceContext());
        envelope.put("aggregateId", message.getAggregateId());
        envelope.put("occurredAt", message.getOccurredAt().toString());
        envelope.put("payload", objectMapper.readValue(message.getPayload(), JSON_OBJECT));
        return envelope;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                relayBatch();
            } catch (RuntimeException e) {
                log.error("Outbox relay batch failed", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Outbox relay started (every {} ms, batches of {})", intervalMs, batchSize);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
