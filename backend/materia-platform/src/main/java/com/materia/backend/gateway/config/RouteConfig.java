package com.materia.backend.gateway.config;

import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 🔹 ROUTE CONFIGURATION
 * 
 * Définit la cartographie des routes API et leur association
 * vers les différents modules métiers (Bounded Contexts) de Materia.
 */
@Configuration
public class RouteConfig {

    /**
     * Route metadata. {@code allowedRoles} documents the intended audience of a route and is
     * descriptive only — enforcement happens through method security on the controllers.
     * An empty list means every authenticated user may call the route.
     */
    public record RouteDefinition(
            String moduleName,
            String pathPattern,
            String description,
            boolean requiresAuth,
            List<String> allowedRoles
    ) {}

    /** The role codes of identity's Role enum; RoleCodesTest (identity) keeps them in step with it. */
    public static final String ADMIN = "ADMIN";
    public static final String PURCHASER = "PURCHASER";
    public static final String RECEIVER = "RECEIVER";

    /**
     * Builds the authority names for the given role codes, in the exact "ROLE_&lt;CODE&gt;" form that
     * AuthService puts into the JWT.
     */
    private static List<String> authorities(String... roleCodes) {
        return Arrays.stream(roleCodes)
                .map(code -> "ROLE_" + code)
                .collect(Collectors.toUnmodifiableList());
    }

    private static final Map<String, RouteDefinition> ROUTES;

    static {
        Map<String, RouteDefinition> map = new LinkedHashMap<>();

        // 1. Auth Module
        map.put("auth", new RouteDefinition(
                "auth-service",
                "/api/v1/auth/**",
                "Authentication, registration and token management",
                false,
                Collections.emptyList()
        ));

        // 2. Master Data Module
        map.put("masterdata", new RouteDefinition(
                "masterData",
                "/api/v1/masterdata/**",
                "Master data, suppliers, currencies, and reference entities",
                true,
                authorities(ADMIN, PURCHASER, RECEIVER)
        ));

        // 3. Purchase Requisition Module
        map.put("purchaseRequisition", new RouteDefinition(
                "purchaseRequisition",
                "/api/v1/purchase-requisitions/**",
                "Internal purchase requisitions and approvals",
                true,
                authorities(ADMIN, PURCHASER, RECEIVER)
        ));

        // 4. Purchase Order Module
        map.put("purchaseOrder", new RouteDefinition(
                "purchaseOrder",
                "/api/v1/purchase-orders/**",
                "Purchase orders management and lifecycle",
                true,
                authorities(ADMIN, PURCHASER, RECEIVER)
        ));

        // 5. Goods Receipt Module
        map.put("goodsReceipt", new RouteDefinition(
                "goodsReceipt",
                "/api/v1/goods-receipts/**",
                "Warehouse receipts and inventory inspection",
                true,
                authorities(ADMIN, PURCHASER, RECEIVER)
        ));

        // 6. Invoice Module
        map.put("invoice", new RouteDefinition(
                "invoice",
                "/api/v1/invoices/**",
                "Supplier invoices, matching, and validations",
                true,
                authorities(ADMIN, PURCHASER)
        ));

        // 7. Payment Module
        map.put("payment", new RouteDefinition(
                "payment",
                "/api/v1/payments/**",
                "Payment processing and reconciliation",
                true,
                authorities(ADMIN, PURCHASER)
        ));

        // 8. Return To Vendor Module
        map.put("returnToVendor", new RouteDefinition(
                "returnToVendor",
                "/api/v1/return-to-vendors/**",
                "Merchandise returns and vendor claims",
                true,
                authorities(ADMIN, PURCHASER, RECEIVER)
        ));

        // 9. Analytics Service
        map.put("analytics", new RouteDefinition(
                "analytics-service",
                "/api/v1/analytics/**",
                "Business metrics, KPI dashboards, and reporting",
                true,
                authorities(ADMIN, PURCHASER, RECEIVER)
        ));

        // 10. Notification Service
        map.put("notification", new RouteDefinition(
                "notification-service",
                "/api/v1/notifications/**",
                "User alerts, system notifications, and messaging",
                true,
                Collections.emptyList()
        ));

        // 11. Employee Service
        map.put("employee", new RouteDefinition(
                "employee-service",
                "/api/v1/employees/**",
                "Employee records, organizational structure, onboarding, and offboarding",
                true,
                authorities(ADMIN)
        ));

        ROUTES = Collections.unmodifiableMap(map);
    }

    public Map<String, RouteDefinition> getRoutes() {
        return ROUTES;
    }

    public boolean isPublicRoute(String uri) {
        if (uri == null) return false;
        return uri.startsWith("/api/v1/auth/")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/actuator")
                || uri.equals("/error");
    }
}
