package com.materia.grpc.auth;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Optional;
import java.util.function.Function;

/**
 * Refuses a call its caller may not make ({@code PERMISSION_DENIED}) before any handler runs, by the RPC's policy
 * ({@link GrpcPolicies}): a valid token proves who calls, not what they may do. An RPC without a policy is refused.
 *
 * <p>Installed after {@link AuthenticationServerInterceptor}, which sets the caller it reads.
 */
public class AuthorizationServerInterceptor implements ServerInterceptor {

    private final Function<String, Optional<RpcPolicy>> policies;

    /** With the contracts' policies. */
    public AuthorizationServerInterceptor() {
        this(GrpcPolicies::of);
    }

    /** @param policies the policy of a full method name ({@code package.Service/Method}) */
    public AuthorizationServerInterceptor(Function<String, Optional<RpcPolicy>> policies) {
        this.policies = policies;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        String method = call.getMethodDescriptor().getFullMethodName();
        Optional<RpcPolicy> policy = policies.apply(method);
        Caller caller = GrpcCaller.current().orElse(null);
        if (policy.isPresent() && policy.get().allows(caller)) {
            return next.startCall(call, headers);
        }
        String who = caller == null ? "an anonymous caller"
                : caller.service().map(s -> "the " + s + " service").orElse("this user");
        String reason = policy.isEmpty() ? method + " has no access policy" : who + " may not call " + method;
        call.close(Status.PERMISSION_DENIED.withDescription(reason), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
