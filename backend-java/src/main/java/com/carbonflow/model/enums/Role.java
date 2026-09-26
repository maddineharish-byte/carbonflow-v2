package com.carbonflow.model.enums;

/**
 * Canonical CarbonFlow roles.
 *
 * <p>This enum must stay aligned with the single source of truth for the role
 * model:
 * <ul>
 *   <li>{@code server/types.ts} (RoleName) — the active Node backend</li>
 *   <li>{@code db/migration/V2__seed_reference_data.sql} — the nine seeded rows</li>
 *   <li>{@code docs/RBAC.md} — the documented matrix ("no SUPER_ADMIN role")</li>
 * </ul>
 *
 * <p>Roles are reusable permission definitions, not fixed employee seats.
 */
public enum Role {
    COMPANY_ADMIN,
    SUSTAINABILITY_MANAGER,
    CARBON_ACCOUNTANT,
    DATA_OWNER,
    FACILITY_MANAGER,
    REVIEWER,
    MANAGEMENT,
    ASSURANCE_PROVIDER,
    PLATFORM_ADMIN
}
