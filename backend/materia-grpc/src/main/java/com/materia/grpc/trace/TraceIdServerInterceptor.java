package com.materia.grpc.trace;

import com.materia.grpc.GrpcHeaders;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Puts the caller's trace id (or a new one) in the logging context while the call is served, so its log lines carry
 * the same {@code traceId} as the user's request at the gateway.
 */
public class TraceIdServerInterceptor implements ServerInterceptor {

    /** The MDC key the gateway's logging filter uses too. */
    public static final String MDC_KEY = "traceId";

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        String received = headers.get(GrpcHeaders.TRACE_ID);
        String traceId = received != null && !received.isBlank() ? received : UUID.randomUUID().toString();
        ServerCall.Listener<ReqT> delegate = withTraceId(traceId, () -> next.startCall(call, headers));
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                withTraceId(traceId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withTraceId(traceId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                withTraceId(traceId, super::onCancel);
            }

            @Override
            public void onComplete() {
                withTraceId(traceId, super::onComplete);
            }

            @Override
            public void onReady() {
                withTraceId(traceId, super::onReady);
            }
        };
    }

    private static void withTraceId(String traceId, Runnable work) {
        withTraceId(traceId, () -> {
            work.run();
            return null;
        });
    }

    private static <T> T withTraceId(String traceId, Supplier<T> work) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId);
        try {
            return work.get();
        } finally {
            if (previous == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, previous);
        }
    }
}
