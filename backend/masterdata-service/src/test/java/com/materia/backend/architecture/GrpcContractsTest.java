package com.materia.backend.architecture;

import com.materia.backend.contexts.masterData.domain.ports.in.MaterialInventoryUseCase;
import com.materia.backend.contexts.masterData.domain.ports.in.MaterialMetricsUseCase;
import com.materia.backend.contexts.masterData.domain.ports.in.OrderableMaterialsUseCase;
import com.materia.proto.masterdata.v1.MasterDataMetricsGrpc;
import com.materia.proto.masterdata.v1.MaterialCatalogGrpc;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master data's gRPC contracts (masterdata.v1) mirror the use cases the other services call, method for method. A
 * method added to one of these use cases needs its RPC, or an explicit reason to stay off the contract; an RPC needs
 * the method it serves.
 */
class GrpcContractsTest {

    /** A use case, which RPC serves each of its methods, and the methods deliberately left off the contract. */
    private record Mirror(Class<?> useCase, Map<String, String> rpcByMethod, Set<String> notExposed) {
        Mirror(Class<?> useCase, Map<String, String> rpcByMethod) {
            this(useCase, rpcByMethod, Set.of());
        }
    }

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of(MaterialCatalogGrpc.getServiceDescriptor(), List.of(
                        new Mirror(MaterialInventoryUseCase.class,
                                Map.of("findMaterial", "FindMaterial", "findMaterialByCode", "FindMaterialByCode"),
                                // Stock changes travel as events (StockOnOrderChanged, GoodsReceiptLineReceived).
                                Set.of("addStockOnOrder", "releaseStockOnOrder", "receiveGoods")),
                        new Mirror(OrderableMaterialsUseCase.class,
                                Map.of("findOrderableMaterials", "FindOrderableMaterials")))),
                Arguments.of(MasterDataMetricsGrpc.getServiceDescriptor(), List.of(
                        new Mirror(MaterialMetricsUseCase.class, Map.of(
                                "countStockAlerts", "CountStockAlerts",
                                "stockAlerts", "StockAlerts",
                                "categoryNamesById", "CategoryNamesById",
                                "categoryNamesByCode", "CategoryNamesByCode")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    @DisplayName("grpc: every method of the mirrored use cases has its RPC (or a reason not to), and every RPC a method")
    void contractMirrorsItsUseCases(ServiceDescriptor service, List<Mirror> mirrors) {
        Set<String> rpcs = service.getMethods().stream()
                .map(MethodDescriptor::getBareMethodName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> served = new HashSet<>();

        for (Mirror mirror : mirrors) {
            Set<String> methods = Arrays.stream(mirror.useCase().getMethods())
                    .filter(m -> !Modifier.isStatic(m.getModifiers()) && !m.isDefault())
                    .map(Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> unmapped = new TreeSet<>(methods);
            unmapped.removeAll(mirror.rpcByMethod().keySet());
            unmapped.removeAll(mirror.notExposed());

            assertThat(unmapped)
                    .as("%s methods with no RPC in %s: add the RPC to the .proto, or list the method as not exposed",
                            mirror.useCase().getSimpleName(), service.getName())
                    .isEmpty();
            assertThat(methods)
                    .as("%s no longer has these methods", mirror.useCase().getSimpleName())
                    .containsAll(mirror.rpcByMethod().keySet())
                    .containsAll(mirror.notExposed());
            served.addAll(mirror.rpcByMethod().values());
        }

        assertThat(served).as("RPCs %s maps that %s does not define", served, service.getName())
                .isSubsetOf(rpcs);
        assertThat(rpcs).as("RPCs of %s that serve no use case method", service.getName())
                .isSubsetOf(served);
    }
}
