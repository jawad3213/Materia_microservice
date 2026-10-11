package com.materia.grpc.deadline;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.MethodDescriptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Gives every call a deadline, so a slow or stuck service fails its callers fast ({@code DEADLINE_EXCEEDED}) instead
 * of holding their threads. A deadline the caller set, or one inherited from the call being served, is kept.
 */
public class DefaultDeadlineClientInterceptor implements ClientInterceptor {

    private final Duration timeout;

    public DefaultDeadlineClientInterceptor(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The default deadline must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        boolean hasDeadline = callOptions.getDeadline() != null || Context.current().getDeadline() != null;
        CallOptions options = hasDeadline
                ? callOptions
                : callOptions.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return next.newCall(method, options);
    }
}
