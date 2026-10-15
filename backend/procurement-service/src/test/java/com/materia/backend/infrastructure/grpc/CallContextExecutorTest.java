package com.materia.backend.infrastructure.grpc;

import io.grpc.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Work handed to another thread runs as the work that handed it over: same user, same gRPC call, same trace id. */
class CallContextExecutorTest {

    private static final Context.Key<String> CALL = Context.key("test-call");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CallContextExecutor executor = new CallContextExecutor(worker);

    @AfterEach
    void tearDown() throws InterruptedException {
        SecurityContextHolder.clearContext();
        MDC.clear();
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("the user, the gRPC call being served and the trace id reach the other thread")
    void contextReachesTheWorker() {
        Authentication user = new TestingAuthenticationToken("ada", "token-of-ada", "order:read");
        SecurityContextHolder.getContext().setAuthentication(user);
        MDC.put("traceId", "trace-1");
        AtomicReference<Authentication> seenUser = new AtomicReference<>();
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenCall = new AtomicReference<>();

        Context.current().withValue(CALL, "call-1").run(() -> CompletableFuture.runAsync(() -> {
            seenUser.set(SecurityContextHolder.getContext().getAuthentication());
            seenTrace.set(MDC.get("traceId"));
            seenCall.set(CALL.get());
        }, executor).join());

        assertThat(seenUser.get()).isSameAs(user);
        assertThat(seenTrace.get()).isEqualTo("trace-1");
        assertThat(seenCall.get()).isEqualTo("call-1");
    }

    @Test
    @DisplayName("once the work is done, the other thread is back to its own context")
    void workerIsRestored() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ada", "token-of-ada"));
        MDC.put("traceId", "trace-1");
        CompletableFuture.runAsync(() -> { }, executor).join();

        AtomicReference<Authentication> leftUser = new AtomicReference<>();
        AtomicReference<String> leftTrace = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            leftUser.set(SecurityContextHolder.getContext().getAuthentication());
            leftTrace.set(MDC.get("traceId"));
        }, worker).join();

        assertThat(leftUser.get()).isNull();
        assertThat(leftTrace.get()).isNull();
    }

    @Test
    @DisplayName("work handed over without a user runs without one")
    void noUser_staysWithoutUser() {
        AtomicReference<Authentication> seenUser = new AtomicReference<>(new TestingAuthenticationToken("x", "y"));

        CompletableFuture.runAsync(() -> seenUser.set(SecurityContextHolder.getContext().getAuthentication()),
                executor).join();

        assertThat(seenUser.get()).isNull();
    }
}
