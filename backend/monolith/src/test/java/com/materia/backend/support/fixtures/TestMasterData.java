package com.materia.backend.support.fixtures;

import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEvent;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEventDispatcher;
import com.materia.backend.common.infrastructure.messaging.integration.IntegrationEventHandler;
import com.materia.grpc.errors.ExceptionTranslatingServerInterceptor;
import com.materia.grpc.trace.TraceIdServerInterceptor;
import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Master data's stand-in for this application's tests: masterdata.v1 (MaterialCatalog, MasterDataMetrics) on the
 * in-process server {@value #SERVER_NAME}, which {@code app.grpc.masterdata.target} points at, over the materials tests
 * register ({@link #save}); and master data's side of the stock events ({@link TestStockKeeper}), which receives this
 * application's events as master data does and answers through the dispatcher, as from Kafka.
 *
 * <p>One server for the JVM, shared by every test context, so materials outlive a test's rollback: give each test its
 * own ({@link MaterialFixtures} makes unique ones). A component of the test sources, so every full application context
 * in the suite registers it.
 */
@Component
public class TestMasterData implements IntegrationEventHandler {

    public static final String SERVER_NAME = "test-masterdata";

    private static final TestMaterialCatalog CATALOG = new TestMaterialCatalog();
    private static final TestMasterDataMetrics METRICS = new TestMasterDataMetrics();

    static {
        InProcessServerBuilder server = InProcessServerBuilder.forName(SERVER_NAME).directExecutor();
        for (BindableService service : new BindableService[]{CATALOG, METRICS}) {
            server.addService(ServerInterceptors.intercept(service, new ExceptionTranslatingServerInterceptor(),
                    new TraceIdServerInterceptor()));
        }
        try {
            server.build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final TestStockKeeper stock;

    /** The dispatcher lists this handler, so it is looked up when a reply leaves, not when this is created. */
    public TestMasterData(ObjectProvider<IntegrationEventDispatcher> dispatcher,
                          @Value("${app.events.in-process.mode:after-commit}") String mode) {
        boolean immediate = "immediate".equalsIgnoreCase(mode);
        this.stock = new TestStockKeeper(CATALOG, reply -> {
            if (immediate) {
                dispatcher.getObject().dispatchInCurrentTransaction(reply);
            } else {
                dispatcher.getObject().dispatch(reply);
            }
        });
    }

    /** Registers a material with master data; this application then finds it by id or code. */
    public TestMaterial save(TestMaterial material) {
        return CATALOG.save(material);
    }

    /** For tests whose work commits: the material leaves master data with the rest of their data. */
    public void remove(UUID id) {
        CATALOG.remove(id);
    }

    /** What master data answers the dashboard. */
    public TestMasterDataMetrics metrics() {
        return METRICS;
    }

    @Override
    public String consumerName() {
        return stock.consumerName();
    }

    @Override
    public boolean handles(IntegrationEvent event) {
        return stock.handles(event);
    }

    /**
     * Applied at once (the tests deliver events inside the transaction that raised them, app.events.in-process.mode),
     * and put back if that transaction rolls back, as master data's own database change would be.
     */
    @Override
    public void handle(IntegrationEvent event) {
        stock.materialOf(event).ifPresent(material -> {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
            int[] before = material.snapshot();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) material.restore(before);
                }
            });
        });
        stock.handle(event);
    }
}
