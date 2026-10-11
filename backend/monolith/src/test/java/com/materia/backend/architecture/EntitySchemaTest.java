package com.materia.backend.architecture;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table of a context lives in the schema of the service that owns the context (V13__schema_per_service.sql),
 * and its entity says so ({@code @Table(schema = ...)}), so Hibernate checks and queries it there. The shared
 * kernel's entities name no schema: they are in public, first on the search_path.
 */
class EntitySchemaTest {

    /** The service schema of each context (docs/architecture/modular-monolith.md). */
    private static final Map<String, String> SCHEMA_OF_CONTEXT = Map.of(
            "auth", "identity",
            "employee", "identity",
            "masterData", "masterdata",
            "purchaseRequisition", "procurement",
            "purchaseOrder", "procurement",
            "goodsReceipt", "procurement",
            "returnToVendor", "procurement",
            "invoice", "finance",
            "payment", "finance",
            "settings", "finance");

    private static final Pattern CONTEXT = Pattern.compile("^com\\.materia\\.backend\\.contexts\\.([^.]+)\\..*$");

    @Test
    @DisplayName("schema: every entity of a context names its service's schema; shared-kernel entities name none")
    void everyEntityIsInItsServicesSchema() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> violations = new ArrayList<>();
        int entities = 0;

        for (BeanDefinition candidate : scanner.findCandidateComponents("com.materia.backend")) {
            Class<?> entity = Class.forName(candidate.getBeanClassName());
            entities++;
            Matcher context = CONTEXT.matcher(entity.getName());
            String expected = context.matches() ? SCHEMA_OF_CONTEXT.get(context.group(1)) : "";
            if (expected == null) {
                violations.add(entity.getName() + ": context " + context.group(1) + " has no service schema");
                continue;
            }
            Table table = entity.getAnnotation(Table.class);
            String actual = table == null ? "" : table.schema();
            if (!expected.equals(actual)) {
                violations.add(entity.getName() + ": schema \"" + actual + "\", expected \"" + expected + "\"");
            }
            for (Field field : entity.getDeclaredFields()) {
                CollectionTable collection = field.getAnnotation(CollectionTable.class);
                if (collection != null && !expected.equals(collection.schema())) {
                    violations.add(entity.getName() + "." + field.getName() + ": collection table schema \""
                            + collection.schema() + "\", expected \"" + expected + "\"");
                }
            }
        }

        assertThat(entities).as("entities found").isGreaterThan(20);
        assertThat(violations).isEmpty();
    }
}
