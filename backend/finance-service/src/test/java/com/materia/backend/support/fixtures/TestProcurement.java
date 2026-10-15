package com.materia.backend.support.fixtures;

import com.materia.backend.support.fixtures.ProcurementFixtures.TestOrder;
import com.materia.backend.support.fixtures.ProcurementFixtures.TestReceipt;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Procurement's stand-in for finance's tests: procurement.v1 (PurchaseOrderLookup, GoodsReceiptLookup) on the
 * in-process server {@value #SERVER_NAME}, which {@code app.grpc.monolith.target} points at, over the orders and
 * receipts tests register ({@link #save}).
 *
 * <p>One server for the JVM, shared by every test context, so orders outlive a test's rollback: give each test its own
 * ({@link ProcurementFixtures} makes unique ones). A component of the test sources, so every full application context
 * in the suite registers it.
 */
@Component
public class TestProcurement {

    public static final String SERVER_NAME = "test-procurement";

    private static final TestProcurementLookups LOOKUPS = new TestProcurementLookups();

    static {
        InProcessServerBuilder server = InProcessServerBuilder.forName(SERVER_NAME).directExecutor();
        for (BindableService service : LOOKUPS.services()) {
            server.addService(ServerInterceptors.intercept(service, new ExceptionTranslatingServerInterceptor(),
                    new TraceIdServerInterceptor()));
        }
        try {
            server.build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Registers an order with procurement; finance then finds it by id. */
    public TestOrder save(TestOrder order) {
        return LOOKUPS.save(order);
    }

    /** Registers a receipt with procurement; finance then finds it with its order's receipts. */
    public TestReceipt save(TestReceipt receipt) {
        return LOOKUPS.save(receipt);
    }
}
