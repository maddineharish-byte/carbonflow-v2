package com.carbonflow.config;

import com.carbonflow.model.enums.Role;
import java.util.List;

public class TenantContext {
    private static final ThreadLocal<TenantContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final String organizationId;
    private final String userId;
    private final String email;
    private final Role role;
    private final List<String> facilityScopes;

    public TenantContext(String organizationId, String userId, String email, Role role, List<String> facilityScopes) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.facilityScopes = facilityScopes;
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
    public List<String> getFacilityScopes() { return facilityScopes; }
}
