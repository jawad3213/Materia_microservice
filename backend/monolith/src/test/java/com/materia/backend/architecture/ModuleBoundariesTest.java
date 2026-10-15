package com.materia.backend.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The bounded contexts stay separable, so each can become its own service (see docs/architecture/modular-monolith.md).
 *
 * <ul>
 *   <li>The domain and application layers of a context know no other context: they talk to the outside through
 *   their own outbound ports.</li>
 *   <li>Code outside a context (its adapters in the other contexts, the gateway) only uses the context's public
 *   API: its inbound ports, application DTOs, and the enums, events and exceptions those expose.</li>
 *   <li>The shared kernel ({@code common}) knows no context.</li>
 *   <li>A context reacts to another context's events only through an {@code IntegrationEventHandler}, which reads the
 *   event's JSON payload: never with a Spring listener ({@code @EventListener}, {@code @TransactionalEventListener})
 *   on the other context's event class, which will not exist once that context is another service.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.materia.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    private static final Pattern CONTEXT_PACKAGE = Pattern.compile("^com\\.materia\\.backend\\.contexts\\.([^.]+)(\\..*)?$");

    /** Packages of a context that other code may use. */
    private static final List<String> PUBLIC_API = List.of(
            ".domain.ports.in", ".application.dtos", ".domain.enums", ".domain.events", ".domain.exceptions");

    @ArchTest
    static final ArchRule domainAndApplicationKnowNoOtherContext =
            classes().that().resideInAnyPackage("com.materia.backend.contexts.*.domain..",
                            "com.materia.backend.contexts.*.application..")
                    .should(new ArchCondition<JavaClass>("depend on no other context") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            String own = contextOf(item.getPackageName()).orElseThrow();
                            for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                                contextOf(dependency.getTargetClass().getPackageName())
                                        .filter(target -> !target.equals(own))
                                        .ifPresent(target -> events.add(SimpleConditionEvent.violated(
                                                dependency, dependency.getDescription())));
                            }
                        }
                    });

    @ArchTest
    static final ArchRule otherContextsOnlyUseThePublicApi =
            classes().should(new ArchCondition<JavaClass>("use other contexts only through their public API") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    Optional<String> own = contextOf(item.getPackageName());
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        String targetPackage = dependency.getTargetClass().getPackageName();
                        Optional<String> target = contextOf(targetPackage);
                        if (target.isEmpty() || target.equals(own)) continue;
                        String inside = targetPackage.substring(("com.materia.backend.contexts." + target.get()).length());
                        boolean publicApi = PUBLIC_API.stream()
                                .anyMatch(api -> inside.equals(api) || inside.startsWith(api + "."));
                        if (!publicApi) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    }
                }
            });

    /** Security is resolved at the edge: use cases receive the user id; the gateway may become its own service. */
    @ArchTest
    static final ArchRule domainAndApplicationKnowNoGateway =
            noClasses().that().resideInAnyPackage("com.materia.backend.contexts.*.domain..",
                            "com.materia.backend.contexts.*.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.materia.backend.gateway..");

    @ArchTest
    static final ArchRule sharedKernelKnowsNoContext =
            noClasses().that().resideInAPackage("com.materia.backend.common..")
                    .should().dependOnClassesThat().resideInAPackage("com.materia.backend.contexts..");

    @ArchTest
    static final ArchRule listenersOnlyHearTheirOwnContext =
            methods().that().areMetaAnnotatedWith(EventListener.class)
                    .should(new ArchCondition<JavaMethod>("listen only to events of their own context") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            Optional<String> own = contextOf(method.getOwner().getPackageName());
                            for (JavaClass parameter : method.getRawParameterTypes()) {
                                Optional<String> source = contextOf(parameter.getPackageName());
                                if (source.isPresent() && !source.equals(own)) {
                                    events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                            + " handles an event of " + source.get()
                                            + ": use an IntegrationEventHandler on its JSON payload"));
                                }
                            }
                        }
                    });

    /** The service each context belongs to (docs/architecture/modular-monolith.md). */
    private static final java.util.Map<String, String> SERVICE_OF = java.util.Map.ofEntries(
            java.util.Map.entry("auth", "identity"),
            java.util.Map.entry("employee", "identity"),
            java.util.Map.entry("masterData", "masterdata"),
            java.util.Map.entry("purchaseRequisition", "procurement"),
            java.util.Map.entry("purchaseOrder", "procurement"),
            java.util.Map.entry("goodsReceipt", "procurement"),
            java.util.Map.entry("returnToVendor", "procurement"),
            java.util.Map.entry("invoice", "finance"),
            java.util.Map.entry("payment", "finance"),
            java.util.Map.entry("settings", "finance"),
            java.util.Map.entry("dashboard", "analytics"),
            java.util.Map.entry("analytics", "analytics"));

    /**
     * Contexts of different services reach each other only through gRPC contracts and events, never through each
     * other's classes. Dev seeders, which fill a local database, are the exception.
     */
    @ArchTest
    static final ArchRule servicesUseNoOtherServicesClasses =
            classes().should(new ArchCondition<JavaClass>("use no class of another service's contexts") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    Optional<String> own = contextOf(item.getPackageName());
                    if (own.isEmpty() || item.getPackageName().contains(".infrastructure.seed")) return;
                    String ownService = SERVICE_OF.get(own.get());
                    if (ownService == null) {
                        events.add(SimpleConditionEvent.violated(item, own.get() + " belongs to no service"));
                        return;
                    }
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        contextOf(dependency.getTargetClass().getPackageName())
                                .map(SERVICE_OF::get)
                                .filter(service -> !service.equals(ownService))
                                .ifPresent(service -> events.add(SimpleConditionEvent.violated(dependency,
                                        dependency.getDescription() + " (" + ownService + " -> " + service
                                                + "): call its gRPC contract or react to its events")));
                    }
                }
            });

    private static Optional<String> contextOf(String packageName) {
        Matcher matcher = CONTEXT_PACKAGE.matcher(packageName);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
