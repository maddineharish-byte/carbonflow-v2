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
 *
 * <p>{@link #uuid()} returns the fixed primary key V2 seeds for the role row;
 * membership inserts and refresh-token rotation reference it directly instead
 * of resolving by name at runtime.
 */
public enum Role {
    COMPANY_ADMIN("11111111-1111-1111-1111-111111111101"),
    SUSTAINABILITY_MANAGER("11111111-1111-1111-1111-111111111102"),
    CARBON_ACCOUNTANT("11111111-1111-1111-1111-111111111103"),
    DATA_OWNER("11111111-1111-1111-1111-111111111104"),
    FACILITY_MANAGER("11111111-1111-1111-1111-111111111105"),
    REVIEWER("11111111-1111-1111-1111-111111111106"),
    MANAGEMENT("11111111-1111-1111-1111-111111111107"),
    ASSURANCE_PROVIDER("11111111-1111-1111-1111-111111111108"),
    PLATFORM_ADMIN("11111111-1111-1111-1111-111111111109");

    private final String uuid;

    Role(String uuid) {
        this.uuid = uuid;
    }

    public String uuid() {
        return uuid;
    }

    /** Parses a role name coming from the database with a clear failure. */
    public static Role fromDb(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown role name in database: " + name, e);
        }
    }
}
