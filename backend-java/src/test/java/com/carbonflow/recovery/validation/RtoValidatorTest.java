package com.carbonflow.recovery.validation;

import com.carbonflow.recovery.coordination.QuiesceGuard;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-12 — RTO validation.
 *
 * <p>Takes a real backup, formally declares a qualifying failure, and measures
 * the recovery until every approved usable-state condition is verified. Reports
 * only what it measured.
 */
class RtoValidatorTest {

    /** Requires tooling plus a version-matched PostgreSQL server. */
    static boolean environmentReady() {
        return RecoveryTestEnvironment.ready();
    }

    @Test
    @DisplayName("the approved RTO is four hours")
    void approvedRtoIsFourHours() {
        assertThat(RtoValidationResult.APPROVED_RTO).isEqualTo(Duration.ofHours(4));
    }

    @Test
    @DisplayName("every measurement carries the manual-detection limitation")
    void manualDetectionIsAlwaysDisclosed() {
        assertThat(RtoValidationResult.standardLimitations())
                .anyMatch(l -> l.contains("MANUAL"))
                .anyMatch(l -> l.contains("NOT IMPLEMENTED"))
                .anyMatch(l -> l.contains("NOT an SLA measurement"));
    }

    @Test
    @DisplayName("a measurement inside the budget is reported as within budget")
    void withinBudgetIsReported() {
        var result = new RtoValidationResult(true, "set-1", java.time.Instant.now(),
                java.time.Instant.now(), null, null, null, null,
                java.time.Instant.now(), Duration.ofSeconds(90), true,
                List.of("usable"), RtoValidationResult.standardLimitations(), "local");

        assertThat(result.withinApprovedBudget()).isTrue();
        assertThat(result.targetMet()).isTrue();
        assertThat(result.summary()).contains("VERIFIED").contains("measured=90s");
    }

    @Test
    @DisplayName("a measurement beyond the budget is not reported as within budget")
    void beyondBudgetIsReported() {
        var result = new RtoValidationResult(true, "set-1", java.time.Instant.now(),
                java.time.Instant.now(), null, null, null, null,
                java.time.Instant.now(), Duration.ofHours(5), false,
                List.of("usable"), RtoValidationResult.standardLimitations(), "local");

        assertThat(result.withinApprovedBudget()).isFalse();
        assertThat(result.targetMet()).isFalse();
    }

    @Test
    @DisplayName("end to end: a declared failure is recovered and the duration measured")
    @EnabledIf("com.carbonflow.recovery.validation.RtoValidatorTest#environmentReady")
    void measuresRecoveryDurationEndToEnd(@TempDir Path temp) throws Exception {
        PostgreSqlBackupTarget server = RecoveryTestEnvironment.matchingServer();
        String source = "carbonflow_rto_" + UUID.randomUUID().toString().substring(0, 8);
        String recovery = "carbonflow_rto_r_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            try (Connection admin = RecoveryTestEnvironment.connect(server, "postgres");
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE \"" + source + "\"");
            }

            Path vault = Files.createDirectories(temp.resolve("vault"));
            String evidence = "RTO EVIDENCE\n";
            Path evidenceFile = vault.resolve("org-a/1_abc.pdf");
            Files.createDirectories(evidenceFile.getParent());
            Files.writeString(evidenceFile, evidence, StandardCharsets.UTF_8);
            String evidenceDigest = hex(MessageDigest.getInstance("SHA-256")
                    .digest(evidence.getBytes(StandardCharsets.UTF_8)));

            try (Connection connection =
                         RecoveryTestEnvironment.connect(server, source);
                 Statement statement = connection.createStatement()) {
                RpoValidatorTestTables.create(statement);
                statement.execute("INSERT INTO organizations VALUES ('"
                        + UUID.randomUUID() + "', 'Acme')");
                statement.execute("INSERT INTO activity_data VALUES ('"
                        + UUID.randomUUID() + "', (select id from organizations limit 1), "
                        + "'gas')");
                statement.execute("INSERT INTO carbon_audits VALUES ('"
                        + UUID.randomUUID() + "', (select id from organizations limit 1), "
                        + "'LOCKED')");
                statement.execute("INSERT INTO users VALUES ('" + UUID.randomUUID()
                        + "', (select id from organizations limit 1), 'a@acme.invalid')");
                statement.execute("INSERT INTO evidence_records VALUES ('"
                        + UUID.randomUUID() + "', (select id from organizations limit 1), "
                        + "'a.pdf', " + evidence.length() + ", 'application/pdf', '"
                        + evidenceDigest + "', '"
                        + evidenceFile.toAbsolutePath().toString().replace("'", "''") + "')");
            }

            Map<String, String> env = RecoveryTestEnvironment.toolEnvironment();
            var coordinator = new RecoverySetCoordinator(
                    com.carbonflow.recovery.postgres.PostgreSqlBackupService
                            .productionDefaults(),
                    EvidenceVaultBackupService.productionDefaults(),
                    new com.carbonflow.recovery.RecoveryManifestWriter(Clock.systemUTC()),
                    Clock.systemUTC());

            var backup = coordinator.run(new RecoverySetCoordinator.Request(
                    Files.createDirectories(temp.resolve("backups")), vault,
                    RecoveryTestEnvironment.targetFor(source), env,
                    List.of(new EvidenceVaultBackupService.RequiredFile(
                            evidenceFile.toString(), evidenceDigest, evidence.length())),
                    com.carbonflow.recovery.RecoveryManifest.Application.of(
                            "1.0.0-PRO", "d42af8b"),
                    new com.carbonflow.recovery.RecoveryManifest.Schema(
                            List.of("1"), Boolean.TRUE, 8), "robocopy"),
                    new QuiesceGuard.NoOp());
            assertThat(backup.success())
                    .as("coordinated backup must succeed: %s", backup.failureReason())
                    .isTrue();

            // ---- the qualifying failure is declared ------------------------
            try (Connection admin = RecoveryTestEnvironment.connect(server, "postgres");
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE \"" + source + "\"");
            }

            RtoValidator validator = new RtoValidator(
                    new RecoveryDrill(Clock.systemUTC(), env), Clock.systemUTC());
            RtoValidationResult result = validator.measure(backup.setDirectory(), server,
                    recovery, temp.resolve("restored-vault"), vault,
                    "PostgreSQL " + server.host() + ":" + server.port()
                            + ", synthetic dataset, loopback, MANUAL detection",
                    env);

            System.out.println(RtoValidator.toJson(result));

            assertThat(result.verified())
                    .as("every usable-state phase must have been verified: %s",
                            result.phasesCompleted())
                    .isTrue();
            assertThat(result.totalRecovery()).isNotNull();
            assertThat(result.withinApprovedBudget()).isTrue();

            // Every approved usable-state condition must appear in the record.
            assertThat(result.phasesCompleted()).contains(
                    "discovery", "verification", "database.restore", "vault.restore",
                    "schema.flyway", "data.rows", "evidence.sha256",
                    "tenant.isolation");

            // The clock must span the declared failure to verified usability.
            assertThat(result.failureDeclaredAt()).isNotNull();
            assertThat(result.usableStateVerifiedAt())
                    .isAfter(result.failureDeclaredAt());
            assertThat(result.limitations())
                    .anyMatch(l -> l.contains("MANUAL"));
            assertThat(result.targetMet()).isTrue();

            System.out.println("MEASURED RTO: " + result.summary());

        } finally {
            RecoveryTestEnvironment.dropDatabase(recovery);
            RecoveryTestEnvironment.dropDatabase(source);
        }
    }

    /** Shared CarbonFlow DDL for the validation suites. */
    static final class RpoValidatorTestTables {
        private RpoValidatorTestTables() {
        }

        static void create(Statement statement) throws Exception {
            statement.execute("CREATE TABLE organizations (id UUID PRIMARY KEY, "
                    + "name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE users (id UUID PRIMARY KEY, "
                    + "organization_id UUID REFERENCES organizations(id), "
                    + "email VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE activity_data (id UUID PRIMARY KEY, "
                    + "organization_id UUID NOT NULL REFERENCES organizations(id), "
                    + "name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE carbon_audits (id UUID PRIMARY KEY, "
                    + "organization_id UUID NOT NULL REFERENCES organizations(id), "
                    + "status VARCHAR(20) NOT NULL)");
            statement.execute("CREATE TABLE evidence_records (id UUID PRIMARY KEY, "
                    + "organization_id UUID NOT NULL REFERENCES organizations(id), "
                    + "file_name VARCHAR(255) NOT NULL, file_size_bytes BIGINT NOT NULL, "
                    + "mime_type VARCHAR(100) NOT NULL, sha256_hash VARCHAR(64) NOT NULL, "
                    + "storage_path VARCHAR(500) NOT NULL)");
            statement.execute("CREATE TABLE evidence_versions (id UUID PRIMARY KEY, "
                    + "evidence_record_id UUID NOT NULL REFERENCES evidence_records(id), "
                    + "version_number INT NOT NULL, sha256_hash VARCHAR(64) NOT NULL, "
                    + "storage_path VARCHAR(500) NOT NULL)");
            statement.execute("CREATE TABLE flyway_schema_history ("
                    + "installed_rank INT PRIMARY KEY, version VARCHAR(50), "
                    + "description VARCHAR(200), success BOOLEAN)");
            statement.execute("INSERT INTO flyway_schema_history VALUES "
                    + "(1, '1', 'initial schema', true)");
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unused")
    private static Class<?> drillGuard() {
        return DrillResult.class;
    }
}