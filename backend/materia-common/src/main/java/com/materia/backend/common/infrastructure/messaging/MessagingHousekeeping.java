package com.materia.backend.common.infrastructure.messaging;

import com.materia.backend.common.infrastructure.messaging.integration.ProcessedEvents;
import com.materia.backend.common.infrastructure.messaging.outbox.SpringDataOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the outbox and the inbox from growing for ever: every hour it deletes the outbox messages published more than
 * {@code app.messaging.outbox-retention} ago (the broker has them) and the inbox records older than
 * {@code app.messaging.inbox-retention} (longer than the broker keeps messages, so a redelivery is still recognised).
 * Parked and pending messages are never deleted.
 *
 * <p>Its own thread, like the outbox relay, so it does not depend on SchedulingConfig; its first run is an interval
 * after the start, which keeps it out of short-lived test contexts.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingHousekeeping implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MessagingHousekeeping.class);

    private final SpringDataOutboxRepository outbox;
    private final ProcessedEvents processedEvents;
    private final TransactionTemplate transactions;
    private final Duration outboxRetention;
    private final Duration inboxRetention;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public MessagingHousekeeping(SpringDataOutboxRepository outbox, ProcessedEvents processedEvents,
                                 TransactionTemplate transactions,
                                 @Value("${app.messaging.outbox-retention:7d}") Duration outboxRetention,
                                 @Value("${app.messaging.inbox-retention:30d}") Duration inboxRetention,
                                 @Value("${app.messaging.housekeeping.interval:1h}") Duration interval) {
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.outboxRetention = outboxRetention;
        this.inboxRetention = inboxRetention;
        this.interval = interval;
    }

    /** @return how many outbox messages and inbox records were deleted */
    public int[] purge() {
        LocalDateTime now = LocalDateTime.now();
        Integer published = transactions.execute(status -> outbox.deletePublishedBefore(now.minus(outboxRetention)));
        int processed = processedEvents.forgetProcessedBefore(now.minus(inboxRetention));
        int[] deleted = {published == null ? 0 : published, processed};
        if (deleted[0] + deleted[1] > 0) {
            log.info("Messaging housekeeping deleted {} published outbox messages and {} inbox records", deleted[0],
                    deleted[1]);
        }
        return deleted;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "messaging-housekeeping");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                purge();
            } catch (RuntimeException e) {
                log.warn("Messaging housekeeping failed; next try in {}: {}", interval, e.toString());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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
