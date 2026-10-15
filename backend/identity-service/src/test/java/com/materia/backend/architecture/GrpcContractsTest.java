package com.materia.backend.architecture;

import com.materia.backend.contexts.auth.domain.ports.in.ServiceTokenUseCase;
import com.materia.backend.contexts.auth.domain.ports.in.UserDirectoryUseCase;
import com.materia.proto.identity.v1.ServiceTokensGrpc;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity's gRPC contracts (identity.v1) mirror the use cases the other services call, method for method. A method
 * added to one of these use cases needs its RPC, or an explicit reason to stay off the contract; an RPC needs the
 * method it serves.
 */
class GrpcContractsTest {

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of(UserDirectoryGrpc.getServiceDescriptor(), UserDirectoryUseCase.class,
                        Map.of("findUser", "FindUser", "findUsersWithRole", "FindUsersWithRole"),
                        // No other service looks accounts up by email.
                        Set.of("findUserByEmail")),
                Arguments.of(ServiceTokensGrpc.getServiceDescriptor(), ServiceTokenUseCase.class,
                        Map.of("issue", "IssueServiceToken"), Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    @DisplayName("grpc: every method of the mirrored use case has its RPC (or a reason not to), and every RPC a method")
    void contractMirrorsItsUseCase(ServiceDescriptor service, Class<?> useCase, Map<String, String> rpcByMethod,
                                   Set<String> notExposed) {
        Set<String> rpcs = service.getMethods().stream()
                .map(MethodDescriptor::getBareMethodName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> methods = Arrays.stream(useCase.getMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()) && !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> unmapped = new TreeSet<>(methods);
        unmapped.removeAll(rpcByMethod.keySet());
        unmapped.removeAll(notExposed);

        assertThat(unmapped)
                .as("%s methods with no RPC in %s: add the RPC to the .proto, or list the method as not exposed",
                        useCase.getSimpleName(), service.getName())
                .isEmpty();
        assertThat(methods)
                .as("%s no longer has these methods", useCase.getSimpleName())
                .containsAll(rpcByMethod.keySet())
                .containsAll(notExposed);
        assertThat(Set.copyOf(rpcByMethod.values())).as("RPCs that serve %s", useCase.getSimpleName())
                .isEqualTo(rpcs);
    }
}
