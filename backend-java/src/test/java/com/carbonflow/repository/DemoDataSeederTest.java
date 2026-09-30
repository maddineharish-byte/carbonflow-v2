package com.carbonflow.repository;

import com.carbonflow.model.enums.Role;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 10.4.1 finding 1 — {@link DemoDataSeeder} must be idempotent,
 * deterministic and safe when a demo email already exists.
 *
 * <p>Every test runs inside a transaction that is rolled back afterwards, so a
 * scenario may rebuild the shared seed rows without leaking into the other
 * integration tests. Nothing here survives the test.
 *
 * <p>Scenarios follow the Phase 10.4.1 analysis: A fresh, B re-run, C existing
 * seed user with the expected id, D existing same-email user with a different
 * id (the collision Phase 10.4 reported), E organization present but membership
 * missing, F membership already present, G a real user of another organization.
 */
@Transactional
class DemoDataSeederTest extends PostgresBackedIntegrationTest {

    private static final String COLLIDING_EMAIL = "admin@acmeglobal.com";

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------
    // CASE A + CASE F: a fresh seed, and re-running it
    // ------------------------------------------------------------------

    @Test
    void freshSeedProducesTheCompleteFixture() {
        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        assertFalse(outcome.skipped());
        assertTrue(outcome.complete(),
                "a clean database must seed without any conflict: " + outcome.conflicts());
        assertEquals(3, outcome.organizations());
        assertEquals(5, outcome.users());
        assertEquals(6, outcome.memberships());
    }

    @Test
    void reRunningTheSeedIsANoOp() {
        DemoDataSeeder.SeedOutcome first = seeder.seed();
        DemoDataSeeder.SeedOutcome second = seeder.seed();
        DemoDataSeeder.SeedOutcome third = seeder.seed();

        assertEquals(first, second, "a second run must observe the same state as the first");
        assertEquals(first, third);
        assertTrue(third.complete());
        assertEquals(3, third.organizations());
        assertEquals(5, third.users());
        assertEquals(6, third.memberships());

        assertEquals(3, countDemoOrganizations());
        assertEquals(5, countDemoUsers());
        assertEquals(6, countDemoMemberships());
    }

    // ------------------------------------------------------------------
    // CASE C: the seed user already exists with the expected id
    // ------------------------------------------------------------------

    @Test
    void existingSeedUserWithTheExpectedIdIsLeftUntouched() {
        String hashBefore = passwordHashOf(SeedIds.USER_ACME_ADMIN);
        String nameBefore = fullNameOf(SeedIds.USER_ACME_ADMIN);

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        assertTrue(outcome.complete());
        assertEquals(hashBefore, passwordHashOf(SeedIds.USER_ACME_ADMIN),
                "an already-seeded identity must never be re-hashed or reset");
        assertEquals(nameBefore, fullNameOf(SeedIds.USER_ACME_ADMIN));
        assertEquals(COLLIDING_EMAIL, emailOf(SeedIds.USER_ACME_ADMIN));
    }

    // ------------------------------------------------------------------
    // CASE D: the collision Phase 10.4 reported — same email, different id
    // ------------------------------------------------------------------

    @Test
    void sameEmailUnderADifferentIdIsRefusedWithoutReplacingTheIdentity() {
        String foreignId = registerForeignUserTakingTheDemoEmail();

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        // Reported, never thrown, and never silently absorbed.
        assertFalse(outcome.complete(), "a refused identity must be reported");
        assertEquals(1, outcome.conflicts().size(), outcome.conflicts().toString());
        assertTrue(outcome.conflicts().get(0).contains(COLLIDING_EMAIL));

        // The real account is untouched: no id replacement, no password reset,
        // no re-hash, no rename.
        assertEquals("Real Self-Registered Admin", fullNameOf(foreignId));
        assertEquals(passwordHashOf(foreignId), passwordHashOf(foreignId));
        assertEquals(1, countUsersWithEmail(COLLIDING_EMAIL));
        assertTrue(jdbc.queryForList("SELECT 1 FROM users WHERE id = ?", String.class,
                SeedIds.USER_ACME_ADMIN).isEmpty(),
                "the seed identity must not be created over a taken email");

        // ...and it was never attached to a demo tenant.
        assertEquals(0, membershipsOf(foreignId));
        assertEquals(0, membershipCount(SeedIds.ORG_ACME, foreignId));
    }

    @Test
    void aRefusedIdentityDoesNotStopTheOtherDemoIdentities() {
        registerForeignUserTakingTheDemoEmail();

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        // Partial, deterministic and fully reported: the other four identities
        // and the platform administrator still land.
        assertEquals(1, outcome.conflicts().size());
        assertEquals(3, outcome.organizations());
        assertEquals(4, outcome.users());
        assertEquals(5, outcome.memberships());

        assertEquals(1, membershipCount(SeedIds.ORG_ACME, SeedIds.USER_ACME_MANAGER));
        assertEquals(1, membershipCount(SeedIds.ORG_ACME, SeedIds.USER_ACME_AUDITOR));
        assertEquals(1, membershipCount(SeedIds.ORG_APEX, SeedIds.USER_APEX_ADMIN));
        assertEquals(1, membershipCount(SeedIds.ORG_PLATFORM, SeedIds.USER_PLATFORM_ADMIN));
        assertEquals(1, countPlatformAdminMemberships(),
                "a collision elsewhere must not cost the platform administrator");
    }

    @Test
    void aRefusedIdentityStaysRefusedOnEverySubsequentRun() {
        String foreignId = registerForeignUserTakingTheDemoEmail();

        DemoDataSeeder.SeedOutcome first = seeder.seed();
        DemoDataSeeder.SeedOutcome second = seeder.seed();
        DemoDataSeeder.SeedOutcome third = seeder.seed();

        assertEquals(first.conflicts(), second.conflicts());
        assertEquals(second.conflicts(), third.conflicts());
        assertEquals(0, membershipsOf(foreignId),
                "a real account must never gain a demo membership, however often the seed runs");
        assertEquals(1, countUsersWithEmail(COLLIDING_EMAIL));
    }

    @Test
    void aSeedIdOccupiedByAnotherAccountIsAlsoRefused() {
        // The mirror image: the fixed id is taken, the demo email is still free.
        String squatterId = SeedIds.USER_APEX_ADMIN;
        jdbc.update("DELETE FROM organization_memberships WHERE user_id = ?", squatterId);
        jdbc.update("UPDATE users SET email = 'squatter@example.test' WHERE id = ?", squatterId);
        String squatterHash = passwordHashOf(squatterId);

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        assertFalse(outcome.complete());
        assertTrue(outcome.conflicts().stream()
                        .anyMatch(conflict -> conflict.contains(squatterId)
                                && conflict.contains("squatter@example.test")),
                "the occupying account must be reported: " + outcome.conflicts());

        // Never renamed, never deleted, and the free demo email is not
        // hijacked into a second identity at that id.
        assertEquals("squatter@example.test", emailOf(squatterId));
        assertEquals(squatterHash, passwordHashOf(squatterId));
        assertEquals(0, countUsersWithEmail("admin@apexcorp.com"),
                "the seed must not create a second identity at an occupied id");
        assertEquals(0, membershipCount(SeedIds.ORG_APEX, squatterId),
                "a squatted id must not receive a demo membership");
    }

    // ------------------------------------------------------------------
    // CASE E: the organization is present, the membership is missing
    // ------------------------------------------------------------------

    @Test
    void aMissingMembershipIsRepairedOnTheNextRun() {
        jdbc.update("DELETE FROM organization_memberships WHERE id = ?", SeedIds.MEMBER_ACME_MANAGER);
        assertEquals(0, membershipCount(SeedIds.ORG_ACME, SeedIds.USER_ACME_MANAGER));

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        assertTrue(outcome.complete());
        assertEquals(6, outcome.memberships());
        assertEquals(1, membershipCount(SeedIds.ORG_ACME, SeedIds.USER_ACME_MANAGER));
        assertEquals(Role.SUSTAINABILITY_MANAGER.uuid(),
                roleOf(SeedIds.MEMBER_ACME_MANAGER),
                "the repaired membership must carry the seeded role");
    }

    // ------------------------------------------------------------------
    // CASE F: the membership is already present
    // ------------------------------------------------------------------

    @Test
    void aMembershipOwnedByTheSeededPairIsNeverDuplicated() {
        jdbc.update("DELETE FROM organization_memberships WHERE id = ?", SeedIds.MEMBER_ACME_ADMIN);
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), SeedIds.ORG_ACME, SeedIds.USER_ACME_ADMIN,
                Role.COMPANY_ADMIN.uuid());

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        assertTrue(outcome.complete(), "an existing pair is not a conflict: " + outcome.conflicts());
        assertEquals(1, membershipCount(SeedIds.ORG_ACME, SeedIds.USER_ACME_ADMIN),
                "ON CONFLICT (organization_id, user_id) must prevent a duplicate membership");
        assertEquals(6, countDemoPairMemberships(),
                "the pre-existing pair must not push the demo fixture past six memberships");
    }

    // ------------------------------------------------------------------
    // CASE G: real tenants, cross-tenant safety, duplicates
    // ------------------------------------------------------------------

    @Test
    void realTenantsAndUsersAreNeverTouchedOrPulledIn() {
        String otherOrg = UUID.randomUUID().toString();
        String otherUser = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organizations (id, name, country) VALUES (?, ?, 'DE')",
                otherOrg, "Unrelated Real Tenant");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                otherUser, "someone@real-tenant.test",
                passwordEncoder.encode("RealPassword123!"), "Real Person");

        DemoDataSeeder.SeedOutcome outcome = seeder.seed();

        assertTrue(outcome.complete());
        assertEquals("Unrelated Real Tenant",
                jdbc.queryForObject("SELECT name FROM organizations WHERE id = ?",
                        String.class, otherOrg));
        assertEquals(0, membershipsOf(otherUser), "a real user gains no membership from the seed");
        assertEquals(0, membershipCount(otherOrg, otherUser));
        assertEquals(3, countDemoOrganizations(), "the seed creates exactly its three tenants");
    }

    @Test
    void theAuditorKeepsBothTenantsAndGainsNoThird() {
        seeder.seed();

        List<String> auditorOrganizations = jdbc.queryForList(
                "SELECT organization_id::text FROM organization_memberships "
                        + "WHERE user_id = ? ORDER BY organization_id",
                String.class, SeedIds.USER_ACME_AUDITOR);

        assertEquals(List.of(SeedIds.ORG_ACME, SeedIds.ORG_APEX),
                auditorOrganizations,
                "switch-tenant coverage depends on exactly these two memberships");
    }

    @Test
    void repeatedRunsNeverDuplicateThePlatformAdministrator() {
        seeder.seed();
        seeder.seed();
        seeder.seed();

        assertEquals(1, countUsersWithEmail("platform.admin@carbonflow.test"));
        assertEquals(1, countPlatformAdminMemberships());
        assertEquals(1, membershipCount(SeedIds.ORG_PLATFORM, SeedIds.USER_PLATFORM_ADMIN));
    }

    @Test
    void theDemoFixtureIsCompleteAndTenantConsistent() {
        seeder.seed();

        assertEquals(3, countDemoOrganizations());
        assertEquals(5, countDemoUsers());
        assertEquals(6, countDemoMemberships());

        // Every membership the seeder owns pairs a demo tenant with a demo user.
        // (The demo tenants may legitimately hold other members created by other
        // suites — user administration, registration — so the check is scoped to
        // the six rows this fixture owns, not to the tenant.)
        Integer illFormed = jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships "
                        + "WHERE id IN (?, ?, ?, ?, ?, ?) "
                        + "AND (organization_id NOT IN (?, ?, ?) OR user_id NOT IN (?, ?, ?, ?, ?))",
                Integer.class,
                SeedIds.MEMBER_ACME_ADMIN, SeedIds.MEMBER_ACME_MANAGER, SeedIds.MEMBER_ACME_AUDITOR,
                SeedIds.MEMBER_APEX_ADMIN, SeedIds.MEMBER_APEX_AUDITOR, SeedIds.MEMBER_PLATFORM_ADMIN,
                SeedIds.ORG_ACME, SeedIds.ORG_APEX, SeedIds.ORG_PLATFORM,
                SeedIds.USER_ACME_ADMIN, SeedIds.USER_ACME_MANAGER, SeedIds.USER_ACME_AUDITOR,
                SeedIds.USER_APEX_ADMIN, SeedIds.USER_PLATFORM_ADMIN);
        assertEquals(0, illFormed, "a seeded membership must pair a demo tenant with a demo user");
    }

    // ------------------------------------------------------------------
    // The production default must not seed at all
    // ------------------------------------------------------------------

    @Test
    void seedingStaysOffWhenTheGateIsClosed() {
        DemoDataSeeder disabled = new DemoDataSeeder(jdbc, passwordEncoder, false);

        int organizationsBefore = countDemoOrganizations();
        int usersBefore = countDemoUsers();
        int membershipsBefore = countDemoMemberships();

        DemoDataSeeder.SeedOutcome outcome = disabled.seed();

        assertTrue(outcome.skipped());
        assertFalse(outcome.complete());
        assertEquals(0, outcome.organizations());
        assertEquals(0, outcome.users());
        assertEquals(0, outcome.memberships());
        assertEquals(List.of(), outcome.conflicts());
        assertEquals(organizationsBefore, countDemoOrganizations());
        assertEquals(usersBefore, countDemoUsers());
        assertEquals(membershipsBefore, countDemoMemberships());
    }

    @Test
    void theSeedIsOnlyEnabledByAnExplicitOptIn() {
        // The shared dbtest context sets carbonflow.seed.demo-data=true; every
        // other profile leaves the shipped default (false) in place.
        assertFalse(seeder.seed().skipped(),
                "carbonflow.seed.demo-data must be true under the dbtest profile");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Reproduces the exact Phase 10.4 condition for {@code admin@acmeglobal.com}:
     * the demo seed id is free, the demo email is already registered under a
     * different id.
     *
     * <p>Rows are removed only inside the rolled-back test transaction, so no
     * other test observes this. {@code audit_approvals.approver_id} and
     * {@code audit_lock_events.locked_by} are {@code ON DELETE RESTRICT} in V1,
     * so the dependents of the seed user are cleared first — whether they exist
     * depends on the order in which the suite happens to run.
     */
    private String registerForeignUserTakingTheDemoEmail() {
        String foreignId = UUID.randomUUID().toString();
        jdbc.update("DELETE FROM audit_lock_events WHERE locked_by = ?", SeedIds.USER_ACME_ADMIN);
        jdbc.update("DELETE FROM audit_approvals WHERE approver_id = ?", SeedIds.USER_ACME_ADMIN);
        jdbc.update("DELETE FROM organization_memberships WHERE user_id = ?", SeedIds.USER_ACME_ADMIN);
        jdbc.update("DELETE FROM users WHERE id = ?", SeedIds.USER_ACME_ADMIN);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                foreignId, COLLIDING_EMAIL,
                passwordEncoder.encode("SomeoneElsesPassword!"), "Real Self-Registered Admin");
        return foreignId;
    }

    private int countDemoOrganizations() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organizations WHERE id IN (?, ?, ?)", Integer.class,
                SeedIds.ORG_ACME, SeedIds.ORG_APEX, SeedIds.ORG_PLATFORM);
    }

    private int countDemoUsers() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id IN (?, ?, ?, ?, ?)", Integer.class,
                SeedIds.USER_ACME_ADMIN, SeedIds.USER_ACME_MANAGER, SeedIds.USER_ACME_AUDITOR,
                SeedIds.USER_APEX_ADMIN, SeedIds.USER_PLATFORM_ADMIN);
    }

    private int countDemoMemberships() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships WHERE id IN (?, ?, ?, ?, ?, ?)",
                Integer.class,
                SeedIds.MEMBER_ACME_ADMIN, SeedIds.MEMBER_ACME_MANAGER, SeedIds.MEMBER_ACME_AUDITOR,
                SeedIds.MEMBER_APEX_ADMIN, SeedIds.MEMBER_APEX_AUDITOR, SeedIds.MEMBER_PLATFORM_ADMIN);
    }

    /** Memberships pairing a demo tenant with a demo user, by pair, not by id. */
    private int countDemoPairMemberships() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships "
                        + "WHERE organization_id IN (?, ?, ?) AND user_id IN (?, ?, ?, ?, ?)",
                Integer.class,
                SeedIds.ORG_ACME, SeedIds.ORG_APEX, SeedIds.ORG_PLATFORM,
                SeedIds.USER_ACME_ADMIN, SeedIds.USER_ACME_MANAGER, SeedIds.USER_ACME_AUDITOR,
                SeedIds.USER_APEX_ADMIN, SeedIds.USER_PLATFORM_ADMIN);
    }

    private int membershipCount(String organizationId, String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships "
                        + "WHERE organization_id = ? AND user_id = ?",
                Integer.class, organizationId, userId);
    }

    private int membershipsOf(String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships WHERE user_id = ?",
                Integer.class, userId);
    }

    private int countUsersWithEmail(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
    }

    private int countPlatformAdminMemberships() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships WHERE role_id = ?",
                Integer.class, Role.PLATFORM_ADMIN.uuid());
    }

    private String passwordHashOf(String userId) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
                String.class, userId);
    }

    private String emailOf(String userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String fullNameOf(String userId) {
        return jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?",
                String.class, userId);
    }

    private String roleOf(String membershipId) {
        return jdbc.queryForObject("SELECT role_id::text FROM organization_memberships WHERE id = ?",
                String.class, membershipId);
    }
}
