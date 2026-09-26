package com.carbonflow.config;

import com.carbonflow.model.enums.Role;

/**
 * Thread-bound tenant context set by {@link JwtAuthenticationFilter} after it
 * has re-validated the token against the database (user active, membership
 * active for the token's organization). Controllers derive tenant scoping
 * exclusively from this object — never from client input.
 *
 * <p>{@code facilityScopes} was removed with Phase 3: the V1 schema has no
 * facility-scoping column, and nothing consumed the field (revisit with
 * facility-level permissions, if that feature is ever specified).
 */
public class TenantContext {
    private static final ThreadLocal<TenantContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final String organizationId;
    private final String userId;
    private final String email;
    private final Role role;

    public TenantContext(String organizationId, String userId, String email, Role role) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.email = email;
        this.role = role;
    }

    public static void set(TenantContext context) {
        CURRENT_CONTEXT.set(context);
    }

    public static TenantContext get() {
        return CURRENT_CONTEXT.get();
    }

    public static void clear() {
        CURRENT_CONTEXT.remove();
    }

    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
}
