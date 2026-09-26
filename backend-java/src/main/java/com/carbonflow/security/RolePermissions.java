package com.carbonflow.security;

import com.carbonflow.model.enums.Role;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical role → permission matrix.
 *
 * <p>This is a direct, code-for-code port of {@code ROLE_PERMISSIONS} in
 * {@code server/rbac.ts} (the active Node backend). It is intentionally held in
 * code rather than in the {@code permissions}/{@code role_permissions} tables,
 * because those tables are seeded empty and are never queried by either
 * backend; the code matrix is therefore the operative source of truth
 * (ADR-011).
 *
 * <p>{@code RolePermissionsParityTest} asserts set-for-set equality against a
 * matrix exported from {@code server/rbac.ts}, so any drift fails the build.
 */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> MATRIX;

    static {
        Map<Role, Set<Permission>> matrix = new EnumMap<>(Role.class);

        matrix.put(Role.COMPANY_ADMIN, set(
                Permission.ORGANIZATION_READ,
                Permission.ORGANIZATION_UPDATE,
                Permission.USERS_READ,
                Permission.USERS_CREATE,
                Permission.USERS_UPDATE,
                Permission.USERS_DISABLE,
                Permission.FACILITIES_READ,
                Permission.FACILITIES_CREATE,
                Permission.FACILITIES_UPDATE,
                Permission.FACILITIES_DELETE,
                Permission.REPORTING_PERIODS_READ,
                Permission.REPORTING_PERIODS_CREATE,
                Permission.REPORTING_PERIODS_UPDATE,
                Permission.ACTIVITY_DATA_READ,
                Permission.ACTIVITY_DATA_CREATE,
                Permission.ACTIVITY_DATA_UPDATE,
                Permission.ACTIVITY_DATA_SUBMIT,
                Permission.EMISSION_FACTORS_READ,
                Permission.EMISSION_FACTORS_MANAGE,
                Permission.CALCULATIONS_READ,
                Permission.CALCULATIONS_CREATE,
                Permission.EVIDENCE_READ,
                Permission.EVIDENCE_UPLOAD,
                Permission.EVIDENCE_DELETE,
                Permission.EVIDENCE_VERSION,
                Permission.AUDITS_READ,
                Permission.AUDITS_CREATE,
                Permission.AUDITS_SUBMIT,
                Permission.AUDITS_APPROVE,
                Permission.AUDITS_LOCK,
                Permission.INVENTORY_READ,
                Permission.INVENTORY_CREATE,
                Permission.INVENTORY_LOCK,
                Permission.TARGETS_READ,
                Permission.TARGETS_CREATE,
                Permission.TARGETS_UPDATE,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REDUCTION_PROJECTS_CREATE,
                Permission.REDUCTION_PROJECTS_UPDATE,
                Permission.REPORTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.SUSTAINABILITY_MANAGER, set(
                Permission.ORGANIZATION_READ,
                Permission.USERS_READ,
                Permission.FACILITIES_READ,
                Permission.FACILITIES_CREATE,
                Permission.FACILITIES_UPDATE,
                Permission.REPORTING_PERIODS_READ,
                Permission.REPORTING_PERIODS_CREATE,
                Permission.REPORTING_PERIODS_UPDATE,
                Permission.ACTIVITY_DATA_READ,
                Permission.ACTIVITY_DATA_CREATE,
                Permission.ACTIVITY_DATA_UPDATE,
                Permission.ACTIVITY_DATA_SUBMIT,
                Permission.EMISSION_FACTORS_READ,
                Permission.EMISSION_FACTORS_MANAGE,
                Permission.CALCULATIONS_READ,
                Permission.CALCULATIONS_CREATE,
                Permission.EVIDENCE_READ,
                Permission.EVIDENCE_UPLOAD,
                Permission.EVIDENCE_DELETE,
                Permission.EVIDENCE_VERSION,
                Permission.AUDITS_READ,
                Permission.AUDITS_CREATE,
                Permission.AUDITS_SUBMIT,
                Permission.AUDITS_APPROVE,
                Permission.AUDITS_LOCK,
                Permission.INVENTORY_READ,
                Permission.INVENTORY_CREATE,
                Permission.INVENTORY_LOCK,
                Permission.TARGETS_READ,
                Permission.TARGETS_CREATE,
                Permission.TARGETS_UPDATE,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REDUCTION_PROJECTS_CREATE,
                Permission.REDUCTION_PROJECTS_UPDATE,
                Permission.REPORTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.CARBON_ACCOUNTANT, set(
                Permission.ORGANIZATION_READ,
                Permission.FACILITIES_READ,
                Permission.REPORTING_PERIODS_READ,
                Permission.ACTIVITY_DATA_READ,
                Permission.ACTIVITY_DATA_CREATE,
                Permission.ACTIVITY_DATA_UPDATE,
                Permission.ACTIVITY_DATA_SUBMIT,
                Permission.EMISSION_FACTORS_READ,
                Permission.EMISSION_FACTORS_MANAGE,
                Permission.CALCULATIONS_READ,
                Permission.CALCULATIONS_CREATE,
                Permission.EVIDENCE_READ,
                Permission.EVIDENCE_UPLOAD,
                Permission.EVIDENCE_VERSION,
                Permission.AUDITS_READ,
                Permission.INVENTORY_READ,
                Permission.INVENTORY_CREATE,
                Permission.TARGETS_READ,
                Permission.TARGETS_UPDATE,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REDUCTION_PROJECTS_UPDATE,
                Permission.REPORTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.DATA_OWNER, set(
                Permission.ORGANIZATION_READ,
                Permission.FACILITIES_READ,
                Permission.REPORTING_PERIODS_READ,
                Permission.ACTIVITY_DATA_READ,
                Permission.ACTIVITY_DATA_CREATE,
                Permission.ACTIVITY_DATA_UPDATE,
                Permission.ACTIVITY_DATA_SUBMIT,
                Permission.EMISSION_FACTORS_READ,
                Permission.EVIDENCE_READ,
                Permission.EVIDENCE_UPLOAD,
                Permission.EVIDENCE_VERSION,
                Permission.TARGETS_READ,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.FACILITY_MANAGER, set(
                Permission.ORGANIZATION_READ,
                Permission.FACILITIES_READ,
                Permission.FACILITIES_UPDATE,
                Permission.REPORTING_PERIODS_READ,
                Permission.ACTIVITY_DATA_READ,
                Permission.ACTIVITY_DATA_CREATE,
                Permission.ACTIVITY_DATA_UPDATE,
                Permission.ACTIVITY_DATA_SUBMIT,
                Permission.EMISSION_FACTORS_READ,
                Permission.EVIDENCE_READ,
                Permission.EVIDENCE_UPLOAD,
                Permission.TARGETS_READ,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REDUCTION_PROJECTS_UPDATE,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.REVIEWER, set(
                Permission.ORGANIZATION_READ,
                Permission.FACILITIES_READ,
                Permission.REPORTING_PERIODS_READ,
                Permission.ACTIVITY_DATA_READ,
                Permission.EMISSION_FACTORS_READ,
                Permission.CALCULATIONS_READ,
                Permission.EVIDENCE_READ,
                Permission.AUDITS_READ,
                Permission.AUDITS_REVIEW,
                Permission.AUDITS_APPROVE,
                Permission.INVENTORY_READ,
                Permission.TARGETS_READ,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REPORTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.MANAGEMENT, set(
                Permission.ORGANIZATION_READ,
                Permission.FACILITIES_READ,
                Permission.REPORTING_PERIODS_READ,
                Permission.ACTIVITY_DATA_READ,
                Permission.EMISSION_FACTORS_READ,
                Permission.CALCULATIONS_READ,
                Permission.EVIDENCE_READ,
                Permission.AUDITS_READ,
                Permission.INVENTORY_READ,
                Permission.TARGETS_READ,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REPORTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.ASSURANCE_PROVIDER, set(
                Permission.ORGANIZATION_READ,
                Permission.FACILITIES_READ,
                Permission.REPORTING_PERIODS_READ,
                Permission.ACTIVITY_DATA_READ,
                Permission.EMISSION_FACTORS_READ,
                Permission.CALCULATIONS_READ,
                Permission.EVIDENCE_READ,
                Permission.AUDITS_READ,
                Permission.AUDITS_REVIEW,
                Permission.INVENTORY_READ,
                Permission.TARGETS_READ,
                Permission.REDUCTION_PROJECTS_READ,
                Permission.REPORTS_READ,
                Permission.ANALYTICS_READ
        ));

        matrix.put(Role.PLATFORM_ADMIN, set(
                Permission.USERS_READ,
                Permission.EMISSION_FACTORS_READ,
                Permission.EMISSION_FACTORS_MANAGE,
                Permission.PLATFORM_TENANTS_READ,
                Permission.PLATFORM_TENANTS_MANAGE
        ));

        MATRIX = Collections.unmodifiableMap(matrix);
    }

    private RolePermissions() {
    }

    /** All permissions granted to the given role (never {@code null}). */
    public static Set<Permission> forRole(Role role) {
        Set<Permission> permissions = MATRIX.get(role);
        return permissions != null ? permissions : Set.of();
    }

    /** Membership test mirroring {@code hasPermission()} in {@code server/rbac.ts}. */
    public static boolean has(Role role, Permission permission) {
        return forRole(role).contains(permission);
    }

    /** Lookup by canonical wire code, e.g. {@code facilities.read}. */
    public static boolean hasCode(Role role, String code) {
        for (Permission permission : forRole(role)) {
            if (permission.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** Every role in the matrix (for diagnostics and tests). */
    public static Map<Role, Set<Permission>> all() {
        return MATRIX;
    }

    private static Set<Permission> set(Permission first, Permission... rest) {
        return Collections.unmodifiableSet(
                EnumSet.of(first, Arrays.copyOfRange(rest, 0, rest.length)));
    }
}
