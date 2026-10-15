package com.materia.backend.infrastructure.grpc;

import io.grpc.Context;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Runs work on other threads as the work that hands it over: for the same user (Spring Security's context), within the
 * same gRPC call being served ({@link io.grpc.Context}) and under the same trace id (the logging context). Calls to
 * other services made from another thread need all three: without the user, {@link OutgoingCallerToken} would send
 * this service's own token, so the call would run with the service's rights instead of the user's.
 */
public final class CallContextExecutor implements Executor {

    private final Executor delegate;

    public CallContextExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        SecurityContext security = SecurityContextHolder.getContext();
        Map<String, String> logging = MDC.getCopyOfContextMap();
        Runnable inCall = Context.current().wrap(task);
        delegate.execute(() -> {
            SecurityContext ownSecurity = SecurityContextHolder.getContext();
            Map<String, String> ownLogging = MDC.getCopyOfContextMap();
            SecurityContextHolder.setContext(security);
            setLogging(logging);
            try {
                inCall.run();
            } finally {
                SecurityContextHolder.setContext(ownSecurity);
                setLogging(ownLogging);
            }
        });
    }

    private static void setLogging(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
