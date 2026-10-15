package com.materia.backend;

import com.materia.backend.infrastructure.config.DotenvEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * The master data service: the masterData context, on the shared platform. Its package is procurement's, so the
 * same component scan finds the context and the platform on its classpath, and nothing else.
 */
@SpringBootApplication
@EnableRetry
public class MasterDataServiceApplication {

    public static void main(String[] args) {
        DotenvEnvironmentPostProcessor.loadEnv(null);
        SpringApplication.run(MasterDataServiceApplication.class, args);
    }
}
