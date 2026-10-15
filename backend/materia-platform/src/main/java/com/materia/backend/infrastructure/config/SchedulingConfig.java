package com.materia.backend.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the {@code @Scheduled} jobs (the nightly reorder check). On by default; off with app.scheduling.enabled=false
 * (tests, or a service that must not run another context's jobs).
 *
 * <p>The outbox relay and the exchange-rate refresh have their own schedulers and do not depend on this.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
