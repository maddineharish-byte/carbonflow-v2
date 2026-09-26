package com.carbonflow.repository;

import com.carbonflow.model.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Demo identity seeding skipped (carbonflow.seed.demo-data=false).");
            return;
        }

        String demoHash = passwordEncoder.encode(SeedIds.DEMO_PASSWORD);

        // Organizations first (FK order), status defaults to ACTIVE (V8 default).
        insertOrganization(SeedIds.ORG_ACME, "Acme Global Manufacturing");
        insertOrganization(SeedIds.ORG_APEX, "Apex CleanTech Logistics");
        insertOrganization(SeedIds.ORG_PLATFORM, "CarbonFlow Platform");

        insertUser(SeedIds.USER_ACME_ADMIN, "admin@acmeglobal.com", demoHash, "Elena Rostova");
        insertUser(SeedIds.USER_ACME_MANAGER, "manager@acmeglobal.com", demoHash, "Marcus Vance");
        insertUser(SeedIds.USER_ACME_AUDITOR, "auditor@ey-assurance.com", demoHash, "Sarah Jenkins (EY Auditor)");
        insertUser(SeedIds.USER_APEX_ADMIN, "admin@apexcorp.com", demoHash, "David Chen");
        insertUser(SeedIds.USER_PLATFORM_ADMIN, "platform.admin@carbonflow.test", demoHash, "Platform Administrator");

        insertMembership(SeedIds.MEMBER_ACME_ADMIN, SeedIds.ORG_ACME, SeedIds.USER_ACME_ADMIN, Role.COMPANY_ADMIN);
        insertMembership(SeedIds.MEMBER_ACME_MANAGER, SeedIds.ORG_ACME, SeedIds.USER_ACME_MANAGER, Role.SUSTAINABILITY_MANAGER);
        insertMembership(SeedIds.MEMBER_ACME_AUDITOR, SeedIds.ORG_ACME, SeedIds.USER_ACME_AUDITOR, Role.ASSURANCE_PROVIDER);
        insertMembership(SeedIds.MEMBER_APEX_ADMIN, SeedIds.ORG_APEX, SeedIds.USER_APEX_ADMIN, Role.COMPANY_ADMIN);
        // Auditor belongs to BOTH tenants — the seed for switch-tenant-or-role flows.
        insertMembership(SeedIds.MEMBER_APEX_AUDITOR, SeedIds.ORG_APEX, SeedIds.USER_ACME_AUDITOR, Role.ASSURANCE_PROVIDER);
        insertMembership(SeedIds.MEMBER_PLATFORM_ADMIN, SeedIds.ORG_PLATFORM, SeedIds.USER_PLATFORM_ADMIN, Role.PLATFORM_ADMIN);

        log.info("Demo identities seeded (idempotent): 3 organizations, 5 users, 6 memberships.");
    }

    private void insertOrganization(String id, String name) {
        jdbc.update("INSERT INTO organizations (id, name, country) VALUES (?, ?, 'US') ON CONFLICT DO NOTHING",
                id, name);
    }

    private void insertUser(String id, String email, String passwordHash, String fullName) {
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                id, email, passwordHash, fullName);
    }

    private void insertMembership(String id, String organizationId, String userId, Role role) {
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (organization_id, user_id) DO NOTHING",
                id, organizationId, userId, role.uuid());
    }
}
