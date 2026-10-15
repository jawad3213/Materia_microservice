package com.materia.backend;

import com.materia.backend.infrastructure.config.DotenvEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * The analytics service: the dashboard and analytics contexts, on the shared platform. Its package is
 * procurement's, so the same component scan finds the contexts and the platform on its classpath, and nothing else.
 */
@SpringBootApplication
@EnableRetry
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        DotenvEnvironmentPostProcessor.loadEnv(null);
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
