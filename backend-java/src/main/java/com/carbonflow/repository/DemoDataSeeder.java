package com.carbonflow.repository;

import com.carbonflow.model.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Idempotent development seed for identities (organizations, users,
 * memberships) behind {@code carbonflow.seed.demo-data} (default
 * {@code false}).
 *
 * <p>Deliberately application-level rather than a Flyway migration: the
 * shared {@code db/migration} folder must stay schema/reference-only, and no
 * demo credential may ever be inserted into a database that was not explicitly
 * opted in (Phase 1 finding R11 against the Node backend's unconditional
 * production seeding). Fixed ids come from {@link SeedIds}; passwords are
 * stored only as BCrypt cost-10 hashes.
 *
 * <h2>Deterministic conflict resolution (Phase 10.4.1 finding 1)</h2>
 *
 * <p>The previous implementation inserted every row with a blanket
 * {@code ON CONFLICT DO NOTHING} and then, unconditionally, inserted the
 * memberships. {@code ON CONFLICT} suppresses <em>unique</em> violations only —
 * never a foreign-key violation — so a single pre-existing
 * {@code admin@acmeglobal.com} (self-registered, therefore holding a different
 * {@code id}) silently dropped the seed's own user row, and the following
 * membership insert then aborted on {@code organization_memberships_user_id_fkey}.
 * The run died mid-way, leaving all three demo organizations with zero
 * memberships and no {@code PLATFORM_ADMIN} anywhere.
 *
 * <p>The seed is now <b>read-then-write, and every conflict is a reported
 * decision rather than an accident</b>:
 *
 * <ol>
 *   <li>Each identity is resolved against the database <em>before</em> it is
 *       written. An email that already exists under a different id is a
 *       <b>refusal</b>: the existing account is never modified, the seed
 *       identity is not created, and none of its memberships is written — a
 *       real person who registered that address is never silently attached to a
 *       demo tenant.</li>
 *   <li>The same applies to a seed id already occupied by an unrelated account,
 *       and to a membership id already linking a different organization/user
 *       pair. Nothing is ever deleted, renamed, or overwritten; the existing row
 *       is left exactly as it is.</li>
 *   <li>Memberships are only ever written for identities this run confirmed to
 *       be the demo identity, so a foreign-key violation is unreachable by
 *       construction — not merely unlikely.</li>
 *   <li>Re-running is a no-op: {@code ON CONFLICT (organization_id, user_id)
 *       DO NOTHING} still guards the pair-level duplicate, and the pre-checks
 *       make the already-seeded state an explicit "nothing to do".</li>
 * </ol>
 *
 * <p>Conflicts are returned in {@link SeedOutcome#conflicts()} and logged as
 * {@code SEED CONFLICT} warnings, so an operator sees exactly which demo
 * identity was refused and why. The seeder never fails the application: a demo
 * fixture must not be able to stop a real deployment from booting.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DemoDataSeeder(JdbcTemplate jdbc,
                          PasswordEncoder passwordEncoder,
                          @Value("${carbonflow.seed.demo-data:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    /**
     * What one seed run did. {@code organizations}/{@code users}/{@code
     * memberships} are the demo identities <b>confirmed present</b> afterwards
     * (inserted or already there), not merely attempted, so the numbers always
     * describe the resulting state.
     */
    public record SeedOutcome(boolean skipped,
                              int organizations,
                              int users,
                              int memberships,
                              List<String> conflicts) {

        public static SeedOutcome disabled() {
            return new SeedOutcome(true, 0, 0, 0, List.of());
        }

        /** True when every demo identity resolved with no refusal. */
        public boolean complete() {
            return !skipped && conflicts.isEmpty();
        }
    }

    private record DemoOrg(String id, String name) {
    }

    private record DemoUser(String id, String email, String fullName) {
    }

    private record DemoMembership(String id, String organizationId, String userId, Role role) {
    }

    private static final List<DemoOrg> ORGANIZATIONS = List.of(
            new DemoOrg(SeedIds.ORG_ACME, "Acme Global Manufacturing"),
            new DemoOrg(SeedIds.ORG_APEX, "Apex CleanTech Logistics"),
            new DemoOrg(SeedIds.ORG_PLATFORM, "CarbonFlow Platform"));

    private static final List<DemoUser> USERS = List.of(
            new DemoUser(SeedIds.USER_ACME_ADMIN, "admin@acmeglobal.com", "Elena Rostova"),
            new DemoUser(SeedIds.USER_ACME_MANAGER, "manager@acmeglobal.com", "Marcus Vance"),
            new DemoUser(SeedIds.USER_ACME_AUDITOR, "auditor@ey-assurance.com", "Sarah Jenkins (EY Auditor)"),
            new DemoUser(SeedIds.USER_APEX_ADMIN, "admin@apexcorp.com", "David Chen"),
            new DemoUser(SeedIds.USER_PLATFORM_ADMIN, "platform.admin@carbonflow.test",
                    "Platform Administrator"));

    private static final List<DemoMembership> MEMBERSHIPS = List.of(
            new DemoMembership(SeedIds.MEMBER_ACME_ADMIN, SeedIds.ORG_ACME, SeedIds.USER_ACME_ADMIN,
                    Role.COMPANY_ADMIN),
            new DemoMembership(SeedIds.MEMBER_ACME_MANAGER, SeedIds.ORG_ACME, SeedIds.USER_ACME_MANAGER,
                    Role.SUSTAINABILITY_MANAGER),
            new DemoMembership(SeedIds.MEMBER_ACME_AUDITOR, SeedIds.ORG_ACME, SeedIds.USER_ACME_AUDITOR,
                    Role.ASSURANCE_PROVIDER),
            new DemoMembership(SeedIds.MEMBER_APEX_ADMIN, SeedIds.ORG_APEX, SeedIds.USER_APEX_ADMIN,
                    Role.COMPANY_ADMIN),
            // Auditor belongs to BOTH tenants — the seed for switch-tenant-or-role flows.
            new DemoMembership(SeedIds.MEMBER_APEX_AUDITOR, SeedIds.ORG_APEX, SeedIds.USER_ACME_AUDITOR,
                    Role.ASSURANCE_PROVIDER),
            new DemoMembership(SeedIds.MEMBER_PLATFORM_ADMIN, SeedIds.ORG_PLATFORM,
                    SeedIds.USER_PLATFORM_ADMIN, Role.PLATFORM_ADMIN));

    @Override
    public void run(ApplicationArguments args) {
        SeedOutcome outcome = seed();
        if (outcome.skipped()) {
            return;
        }
        if (outcome.complete()) {
            log.info("Demo identities seeded (idempotent): {} organizations, {} users, {} memberships.",
                    outcome.organizations(), outcome.users(), outcome.memberships());
        } else {
            log.warn("Demo identity seeding finished with {} unresolved conflict(s). "
                            + "Present: {} organizations, {} users, {} memberships.",
                    outcome.conflicts().size(), outcome.organizations(),
                    outcome.users(), outcome.memberships());
            for (String conflict : outcome.conflicts()) {
                log.warn("SEED CONFLICT: {}", conflict);
            }
        }
    }

    /**
     * Runs the seed. Safe to call repeatedly; returns the resulting state and
     * every refusal instead of throwing. Exposed (rather than only reachable
     * through {@link #run}) so the behaviour can be asserted directly.
     */
    public SeedOutcome seed() {
        if (!enabled) {
            log.info("Demo identity seeding skipped (carbonflow.seed.demo-data=false).");
            return SeedOutcome.disabled();
        }

        String demoHash = passwordEncoder.encode(SeedIds.DEMO_PASSWORD);
        List<String> conflicts = new ArrayList<>();

        // Organizations first (FK order); status defaults to ACTIVE (V8 default).
        for (DemoOrg organization : ORGANIZATIONS) {
            ensureOrganization(organization);
        }

        // Only identities this run confirmed to be the demo identity are allowed
        // to receive memberships — the foreign key can never dangle.
        Set<String> seededUserIds = new HashSet<>();
        for (DemoUser user : USERS) {
            if (ensureUser(user, demoHash, conflicts)) {
                seededUserIds.add(user.id());
            }
        }

        for (DemoMembership membership : MEMBERSHIPS) {
            if (seededUserIds.contains(membership.userId())) {
                ensureMembership(membership, conflicts);
            }
        }

        return new SeedOutcome(false, countOrganizations(), countUsers(), countMemberships(),
                List.copyOf(conflicts));
    }

    /**
     * An organization row already carrying a demo seed id <em>is</em> that demo
     * tenant — the primary key is the identity, so there is nothing to reconcile.
     * The existing row is never renamed or deleted; only a display-name drift is
     * reported.
     */
    private void ensureOrganization(DemoOrg organization) {
        List<String> existing = jdbc.queryForList(
                "SELECT name FROM organizations WHERE id = ?", String.class, organization.id());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO organizations (id, name, country) VALUES (?, ?, 'US')",
                    organization.id(), organization.name());
            return;
        }
        if (!existing.get(0).equals(organization.name())) {
            log.info("Demo organization {} already exists as '{}' (seed name '{}'); keeping the existing row.",
                    organization.id(), existing.get(0), organization.name());
        }
    }

    /**
     * @return {@code true} when {@code user} is the demo identity in the database
     *         afterwards — either it already was, or this run created it.
     *         {@code false} means the identity was refused (recorded in
     *         {@code conflicts}); the existing account is untouched in that case.
     */
    private boolean ensureUser(DemoUser user, String passwordHash, List<String> conflicts) {
        List<String> emailAtSeedId = jdbc.queryForList(
                "SELECT email FROM users WHERE id = ?", String.class, user.id());
        if (!emailAtSeedId.isEmpty()) {
            if (emailAtSeedId.get(0).equalsIgnoreCase(user.email())) {
                return true; // already the seeded identity — idempotent no-op
            }
            conflicts.add("demo user id " + user.id() + " is registered to '" + emailAtSeedId.get(0)
                    + "' but the seed expects '" + user.email() + "'; the existing account was left "
                    + "untouched, the seed identity was not created, and no membership was added");
            return false;
        }

        // Case-insensitive, exactly how IdentityRepository resolves a login:
        // the column is only UNIQUE case-sensitively, so variants can coexist.
        List<String> idsAtEmail = jdbc.queryForList(
                "SELECT id::text FROM users WHERE lower(email) = lower(?) ORDER BY id",
                String.class, user.email());
        if (!idsAtEmail.isEmpty()) {
            conflicts.add("demo user '" + user.email() + "' is already registered as user "
                    + String.join(", ", idsAtEmail) + "; the existing account was left untouched, the "
                    + "seed identity " + user.id() + " was not created, and no membership was added");
            return false;
        }

        // Neither the id nor the email is taken: this is the only write path, and
        // it is reached only for a genuinely free identity.
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                user.id(), user.email(), passwordHash, user.fullName());
        return true;
    }

    private void ensureMembership(DemoMembership membership, List<String> conflicts) {
        List<String> existing = jdbc.queryForList(
                "SELECT organization_id::text || '|' || user_id::text "
                        + "FROM organization_memberships WHERE id = ?",
                String.class, membership.id());
        if (!existing.isEmpty() && !existing.get(0).equals(
                membership.organizationId() + "|" + membership.userId())) {
            // A pair-level ON CONFLICT cannot absorb a primary-key collision that
            // points at a different pair, so it is refused before the write.
            conflicts.add("membership id " + membership.id() + " already links '" + existing.get(0)
                    + "', not '" + membership.organizationId() + "|" + membership.userId()
                    + "'; the existing row was left untouched");
            return;
        }

        // Both the user and the organization are known to exist by now, and the
        // pair is unique — so the only remaining outcome is insert or no-op.
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (organization_id, user_id) DO NOTHING",
                membership.id(), membership.organizationId(), membership.userId(),
                membership.role().uuid());
    }

    private int countOrganizations() {
        int present = 0;
        for (DemoOrg organization : ORGANIZATIONS) {
            if (!jdbc.queryForList("SELECT 1 FROM organizations WHERE id = ?",
                    String.class, organization.id()).isEmpty()) {
                present++;
            }
        }
        return present;
    }

    private int countUsers() {
        int present = 0;
        for (DemoUser user : USERS) {
            if (!jdbc.queryForList("SELECT 1 FROM users WHERE id = ?",
                    String.class, user.id()).isEmpty()) {
                present++;
            }
        }
        return present;
    }

    private int countMemberships() {
        int present = 0;
        for (DemoMembership membership : MEMBERSHIPS) {
            if (!jdbc.queryForList("SELECT 1 FROM organization_memberships WHERE id = ?",
                    String.class, membership.id()).isEmpty()) {
                present++;
            }
        }
        return present;
    }
}
