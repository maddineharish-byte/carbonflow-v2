package com.carbonflow.repository;

/**
 * Fixed identifiers for the development seed tenants and their users.
 *
 * <p>PostgreSQL keys identity rows with UUIDs (V1 schema), so the semantic ids
 * of the prototype era ({@code org-acme-corp}, {@code usr-acme-admin}) are
 * mapped to stable, fixed UUIDs — the same approach ADR-006 recorded for the
 * Node backend's semantic identities, and the same fixed-UUID convention V2
 * uses for the canonical roles. Sharing these constants between
 * {@link DemoDataSeeder}, the remaining in-memory {@link DataStore} seeds
 * (facilities, activity data …) and the tests keeps tenant references aligned
 * across both persistence worlds until Phase 4 migrates the remaining
 * domains to PostgreSQL.
 */
public final class SeedIds {

    private SeedIds() {
    }

    // Organizations
    public static final String ORG_ACME = "22222222-2222-2222-2222-222222222201";
    public static final String ORG_APEX = "22222222-2222-2222-2222-222222222202";
    public static final String ORG_PLATFORM = "22222222-2222-2222-2222-222222222299";

    // Users
    public static final String USER_ACME_ADMIN = "33333333-3333-3333-3333-333333333301";
    public static final String USER_ACME_MANAGER = "33333333-3333-3333-3333-333333333302";
    public static final String USER_ACME_AUDITOR = "33333333-3333-3333-3333-333333333303";
    public static final String USER_APEX_ADMIN = "33333333-3333-3333-3333-333333333304";
    public static final String USER_PLATFORM_ADMIN = "33333333-3333-3333-3333-333333333305";

    // Memberships (auditor holds two: Acme AND Apex, for switch-tenant flows)
    public static final String MEMBER_ACME_ADMIN = "44444444-4444-4444-4444-444444444401";
    public static final String MEMBER_ACME_MANAGER = "44444444-4444-4444-4444-444444444402";
    public static final String MEMBER_ACME_AUDITOR = "44444444-4444-4444-4444-444444444403";
    public static final String MEMBER_APEX_ADMIN = "44444444-4444-4444-4444-444444444404";
    public static final String MEMBER_APEX_AUDITOR = "44444444-4444-4444-4444-444444444405";
    public static final String MEMBER_PLATFORM_ADMIN = "44444444-4444-4444-4444-444444444406";

    // Development credentials (BCrypt-hashed at seed time — see README)
    public static final String DEMO_PASSWORD = "Password123!";
}
