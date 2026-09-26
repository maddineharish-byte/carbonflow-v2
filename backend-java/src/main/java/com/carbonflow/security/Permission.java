package com.carbonflow.security;

/**
 * The 44 canonical CarbonFlow permission codes.
 *
 * <p>Codes are identical (including underscores and dots) to
 * {@code PermissionCode} in {@code server/types.ts} so that authorization
 * decisions are interchangeable between the Node reference backend and this
 * Java backend. Verified by {@code RolePermissionsParityTest} against the
 * matrix exported from {@code server/rbac.ts}.
 */
public enum Permission {
    ORGANIZATION_READ("organization.read"),
    ORGANIZATION_UPDATE("organization.update"),
    USERS_READ("users.read"),
    USERS_CREATE("users.create"),
    USERS_UPDATE("users.update"),
    USERS_DISABLE("users.disable"),
    FACILITIES_READ("facilities.read"),
    FACILITIES_CREATE("facilities.create"),
    FACILITIES_UPDATE("facilities.update"),
    FACILITIES_DELETE("facilities.delete"),
    REPORTING_PERIODS_READ("reporting_periods.read"),
    REPORTING_PERIODS_CREATE("reporting_periods.create"),
    REPORTING_PERIODS_UPDATE("reporting_periods.update"),
    ACTIVITY_DATA_READ("activity_data.read"),
    ACTIVITY_DATA_CREATE("activity_data.create"),
    ACTIVITY_DATA_UPDATE("activity_data.update"),
    ACTIVITY_DATA_SUBMIT("activity_data.submit"),
    EMISSION_FACTORS_READ("emission_factors.read"),
    EMISSION_FACTORS_MANAGE("emission_factors.manage"),
    CALCULATIONS_READ("calculations.read"),
    CALCULATIONS_CREATE("calculations.create"),
    EVIDENCE_READ("evidence.read"),
    EVIDENCE_UPLOAD("evidence.upload"),
    EVIDENCE_DELETE("evidence.delete"),
    EVIDENCE_VERSION("evidence.version"),
    AUDITS_READ("audits.read"),
    AUDITS_CREATE("audits.create"),
    AUDITS_SUBMIT("audits.submit"),
    AUDITS_REVIEW("audits.review"),
    AUDITS_APPROVE("audits.approve"),
    AUDITS_LOCK("audits.lock"),
    INVENTORY_READ("inventory.read"),
    INVENTORY_CREATE("inventory.create"),
    INVENTORY_LOCK("inventory.lock"),
    TARGETS_READ("targets.read"),
    TARGETS_CREATE("targets.create"),
    TARGETS_UPDATE("targets.update"),
    REDUCTION_PROJECTS_READ("reduction_projects.read"),
    REDUCTION_PROJECTS_CREATE("reduction_projects.create"),
    REDUCTION_PROJECTS_UPDATE("reduction_projects.update"),
    REPORTS_READ("reports.read"),
    ANALYTICS_READ("analytics.read"),
    PLATFORM_TENANTS_READ("platform.tenants.read"),
    PLATFORM_TENANTS_MANAGE("platform.tenants.manage");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The canonical wire format, e.g. {@code facilities.read}. */
    public String code() {
        return code;
    }

    /**
     * Authority granted to a caller holding this permission, used with
     * {@code @PreAuthorize("hasAuthority(...)")}.
     */
    public String authority() {
        return Authorities.PREFIX + code;
    }
}
