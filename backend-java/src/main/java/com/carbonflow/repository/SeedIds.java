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
 *
 * <h2>RFC 4122 conformance (Phase 10.4.1 finding 2)</h2>
 *
 * <p>These constants must be <b>RFC 4122 conformant</b>, not merely
 * dash-shaped. {@link com.carbonflow.service.UuidContract} reproduces the Node
 * reference backend's {@code assertUuid}
 * ({@code /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i}),
 * which requires version nibble {@code 1}-{@code 5} and variant nibble
 * {@code 8}/{@code 9}/{@code a}/{@code b}. The previous
 * {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} pattern carried variant nibble
 * {@code 2}, so a demo organization id was rejected by every endpoint that
 * validates a client-supplied identifier — most visibly
 * {@code GET /api/v1/platform/tenants/{id}}, which answered
 * {@code 404 ORGANIZATION_NOT_FOUND} for a tenant that plainly existed.
 *
 * <p>The fixture is therefore pinned to version {@code 4} and variant
 * {@code 8} in the third and fourth groups respectively
 * ({@code -4222-8222-}), keeping the readable {@code 2222…}/{@code 3333…}/
 * {@code 4444…} prefixes that make the rows easy to recognise in a database
 * browser.
 *
 * <p><b>Scope note.</b> Only the seed <em>fixture</em> changed. Production
 * identifier validation was deliberately left untouched: {@code UuidContract}
 * still rejects version {@code 0}, non-RFC variants and malformed strings, and
 * no API contract was altered. Already-seeded databases keep the legacy rows
 * — this is fixture data behind {@code carbonflow.seed.demo-data}, so the
 * remedy for such a database is to recreate it, never to weaken validation.
 */
public final class SeedIds {

    private SeedIds() {
    }

    // Organizations
    public static final String ORG_ACME = "22222222-2222-4222-8222-222222222201";
    public static final String ORG_APEX = "22222222-2222-4222-8222-222222222202";
    public static final String ORG_PLATFORM = "22222222-2222-4222-8222-222222222299";

    // Users
    public static final String USER_ACME_ADMIN = "33333333-3333-4333-8333-333333333301";
    public static final String USER_ACME_MANAGER = "33333333-3333-4333-8333-333333333302";
    public static final String USER_ACME_AUDITOR = "33333333-3333-4333-8333-333333333303";
    public static final String USER_APEX_ADMIN = "33333333-3333-4333-8333-333333333304";
    public static final String USER_PLATFORM_ADMIN = "33333333-3333-4333-8333-333333333305";

    // Memberships (auditor holds two: Acme AND Apex, for switch-tenant flows)
    public static final String MEMBER_ACME_ADMIN = "44444444-4444-4444-8444-444444444401";
    public static final String MEMBER_ACME_MANAGER = "44444444-4444-4444-8444-444444444402";
    public static final String MEMBER_ACME_AUDITOR = "44444444-4444-4444-8444-444444444403";
    public static final String MEMBER_APEX_ADMIN = "44444444-4444-4444-8444-444444444404";
    public static final String MEMBER_APEX_AUDITOR = "44444444-4444-4444-8444-444444444405";
    public static final String MEMBER_PLATFORM_ADMIN = "44444444-4444-4444-8444-444444444406";

    // Development credentials (BCrypt-hashed at seed time — see README)
    public static final String DEMO_PASSWORD = "Password123!";
}
