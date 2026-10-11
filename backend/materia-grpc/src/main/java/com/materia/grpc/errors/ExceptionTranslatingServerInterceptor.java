package com.materia.grpc.errors;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the call with the status {@link GrpcErrors} gives an exception a handler threw, so handlers can simply call
 * the use case and let its exceptions out. Unexpected exceptions are logged here with their stack trace, since the
 * caller only gets a generic message.
 */
public class ExceptionTranslatingServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ExceptionTranslatingServerInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(call, headers);
        } catch (RuntimeException e) {
            close(call, e);
            return new ServerCall.Listener<>() {
            };
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                try {
                    super.onMessage(message);
                } catch (RuntimeException e) {
                    close(call, e);
                }
            }

            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (RuntimeException e) {
                    close(call, e);
                }
            }

            @Override
            public void onReady() {
                try {
                    super.onReady();
                } catch (RuntimeException e) {
                    close(call, e);
                }
            }
        };
    }

    private static void close(ServerCall<?, ?> call, RuntimeException error) {
        StatusRuntimeException status = GrpcErrors.toStatusException(error);
        if (status.getStatus().getCode() == Status.Code.INTERNAL) {
            log.error("gRPC call {} failed", call.getMethodDescriptor().getFullMethodName(), error);
        }
        try {
            call.close(status.getStatus(), status.getTrailers() != null ? status.getTrailers() : new Metadata());
        } catch (IllegalStateException alreadyClosed) {
            log.debug("gRPC call {} was already closed", call.getMethodDescriptor().getFullMethodName());
        }
    }
}
