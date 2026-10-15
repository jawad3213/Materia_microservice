package com.materia.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class ProcurementServiceApplication {

    public static void main(String[] args) {
        com.materia.backend.infrastructure.config.DotenvEnvironmentPostProcessor.loadEnv(null);
        SpringApplication.run(ProcurementServiceApplication.class, args);
    }

}   