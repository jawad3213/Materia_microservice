package com.materia.backend.infrastructure.grpc;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/** Channels to another service's gRPC server, as each service's gRPC configuration opens them. */
public final class GrpcChannels {

    private static final String IN_PROCESS_PREFIX = "in-process:";

    private GrpcChannels() {
    }

    /**
     * A channel to {@code target}: {@code host:port} of the service's gRPC server (e.g. {@code identity:9091}), or
     * {@code in-process:<name>} for an in-process server of that name (tests).
     */
    public static ManagedChannel to(String target, ClientInterceptor... interceptors) {
        if (target.startsWith(IN_PROCESS_PREFIX)) {
            return InProcessChannelBuilder.forName(target.substring(IN_PROCESS_PREFIX.length()))
                    .directExecutor().intercept(interceptors).build();
        }
        return NettyChannelBuilder.forTarget(target).usePlaintext().intercept(interceptors).build();
    }
}
