package com.materia.grpc.trace;

import com.materia.grpc.GrpcHeaders;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;

/**
 * Passes on the trace id of the work in progress: the one in the logging context ({@code traceId}), which the
 * gateway's logging filter sets for a REST request and {@link TraceIdServerInterceptor} for a gRPC call.
 */
public class TraceIdClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String traceId = MDC.get(TraceIdServerInterceptor.MDC_KEY);
                if (traceId != null && !traceId.isBlank()) {
                    headers.put(GrpcHeaders.TRACE_ID, traceId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
