package com.materia.backend.support.fixtures;

import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Finance's stand-in for this application's tests: finance.v1 (FinanceMetrics) on the in-process server
 * {@value #SERVER_NAME}, which {@code app.grpc.finance.target} points at, answering what a test set
 * ({@link #metrics}). One server for the JVM, shared by every test context; a component of the test sources, so every
 * full application context in the suite registers it.
 */
@Component
public class TestFinance {

    public static final String SERVER_NAME = "test-finance";

    private static final TestFinanceMetrics METRICS = new TestFinanceMetrics();

    static {
        try {
            InProcessServerBuilder.forName(SERVER_NAME).directExecutor()
                    .addService(ServerInterceptors.intercept(METRICS, new ExceptionTranslatingServerInterceptor(),
                            new TraceIdServerInterceptor()))
                    .build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What finance answers the dashboard. */
    public TestFinanceMetrics metrics() {
        return METRICS;
    }
}
