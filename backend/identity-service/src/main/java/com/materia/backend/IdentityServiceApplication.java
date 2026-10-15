package com.materia.backend;

import com.materia.backend.infrastructure.config.DotenvEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * The identity service: the auth and employee contexts, on the shared platform. Its package is the monolith's, so
 * the same component scan finds the contexts and the platform on its classpath, and nothing else.
 */
@SpringBootApplication
@EnableRetry
public class IdentityServiceApplication {

    public static void main(String[] args) {
        DotenvEnvironmentPostProcessor.loadEnv(null);
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
