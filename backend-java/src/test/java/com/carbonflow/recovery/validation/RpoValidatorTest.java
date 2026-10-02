package com.carbonflow.recovery.validation;

import com.carbonflow.recovery.coordination.QuiesceGuard;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-11 — RPO validation.
 *
 * <p>The decisive test writes a committed marker <em>before</em> the recovery
 * boundary and a second committed marker <em>after</em> it, takes a backup,
 * destroys the source database, restores, and observes exactly which survived.
 * That measures committed-data loss, which is what an RPO is — not restore
 * duration.
 */
class RpoValidatorTest {

    /** Requires tooling plus a version-matched PostgreSQL server. */
    static boolean environmentReady() {
        return RecoveryTestEnvironment.ready();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("pass/fail logic")
    class Logic {

        private final RpoValidator validator = new RpoValidator();

        @Test
        @DisplayName("an exact boundary yields zero committed loss and VERIFIED")
        void exactBoundaryIsVerified() {
            Instant boundary = Instant.parse("2026-10-02T12:00:00Z");

            var result = validator.assess("set-1", boundary, boundary.plusSeconds(1800),
                    boundary.minusSeconds(60), boundary.plusSeconds(60),
                    true, false, Duration.ofSeconds(3), Duration.ofHours(1), "local");

            assertThat(result.verified()).isTrue();
            assertThat(result.committedLoss())
                    .as("committed loss is zero when everything inside the boundary survived")
                    .isEqualTo(Duration.ZERO);
            assertThat(result.targetMet()).isTrue();
            assertThat(result.summary()).contains("VERIFIED");
        }

        @Test
        @DisplayName("losing a pre-boundary marker is data loss and fails verification")
        void lostPreBoundaryMarkerFails() {
            Instant boundary = Instant.parse("2026-10-02T12:00:00Z");

            var result = validator.assess("set-1", boundary, boundary.plusSeconds(60),
                    boundary.minusSeconds(60), boundary.plusSeconds(30),
                    false, false, Duration.ofSeconds(3), Duration.ofHours(1), "local");

            assertThat(result.verified())
                    .as("data committed at or before the boundary must not be lost")
                    .isFalse();
            assertThat(result.committedLoss()).isPositive();
            assertThat(result.markers())
                    .anyMatch(m -> m.markerId().equals("BEFORE") && !m.survived());
        }

        @Test
        @DisplayName("a post-boundary marker surviving means the boundary was not real")
        void postBoundarySurvivalInvalidatesTheMeasurement() {
            Instant boundary = Instant.parse("2026-10-02T12:00:00Z");

            var result = validator.assess("set-1", boundary, boundary.plusSeconds(60),
                    boundary.minusSeconds(60), boundary.plusSeconds(30),
                    true, true, Duration.ofSeconds(3), Duration.ofHours(1), "local");

            assertThat(result.verified())
                    .as("seeing data newer than the boundary invalidates the measurement")
                    .isFalse();
            assertThat(result.markers())
                    .anyMatch(m -> m.markerId().equals("AFTER") && m.survived());
        }

        @Test
        @DisplayName("restore duration is recorded but is never the RPO figure")
        void restoreDurationIsNotTheRpo() {
            Instant boundary = Instant.parse("2026-10-02T12:00:00Z");

            var result = validator.assess("set-1", boundary, boundary.plusSeconds(3600),
                    boundary.minusSeconds(10), boundary.plusSeconds(10),
                    true, false, Duration.ofSeconds(2), Duration.ofHours(1), "local");

            assertThat(result.restoreDuration()).isEqualTo(Duration.ofSeconds(2));
            assertThat(result.committedLoss())
                    .as("a 2-second restore does not make the RPO 2 seconds")
                    .isEqualTo(Duration.ZERO);
            assertThat(result.worstCaseLossBound())
                    .as("worst case is bounded by the backup interval")
                    .isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("loss exceeding the approved 1-hour RPO fails the target check")
        void lossBeyondTargetFails() {
            Instant boundary = Instant.parse("2026-10-02T12:00:00Z");

            var result = validator.assess("set-1", boundary,
                    boundary.plus(Duration.ofHours(2)),
                    boundary.minusSeconds(10), boundary.plusSeconds(10),
                    false, false, Duration.ofSeconds(2), Duration.ofHours(1), "local");

            assertThat(result.committedLoss())
                    .isGreaterThan(RpoValidationResult.APPROVED_RPO);
            assertThat(result.targetMet()).isFalse();
        }

        @Test
        @DisplayName("the approved RPO is one hour")
        void approvedRpoIsOneHour() {
            assertThat(RpoValidationResult.APPROVED_RPO).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("every result carries its limitations")
        void limitationsAreMandatory() {
            assertThat(RpoValidationResult.standardLimitations())
                    .anyMatch(l -> l.contains("INTERVAL"))
                    .anyMatch(l -> l.contains("NOT an RPO figure"));
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("end to end: committed loss measured against a real recovery boundary")
    @EnabledIf("com.carbonflow.recovery.validation.RpoValidatorTest#environmentReady")
    void measuresCommittedLossEndToEnd(@TempDir Path temp) throws Exception {
        PostgreSqlBackupTarget server = RecoveryTestEnvironment.matchingServer();
        RpoValidator validator = new RpoValidator();
        String source = "carbonflow_rpo_" + UUID.randomUUID().toString().substring(0, 8);
        String recovery = "carbonflow_rpo_r_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // ---- source database -------------------------------------------
            try (Connection admin = RecoveryTestEnvironment.connect(server, "postgres");
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE \"" + source + "\"");
            }

            Path vault = Files.createDirectories(temp.resolve("vault"));
            String evidence = "RPO EVIDENCE\n";
            Path evidenceFile = vault.resolve("org-a/1_abc.pdf");
            Files.createDirectories(evidenceFile.getParent());
            Files.writeString(evidenceFile, evidence, StandardCharsets.UTF_8);
            String evidenceDigest = hex(MessageDigest.getInstance("SHA-256")
                    .digest(evidence.getBytes(StandardCharsets.UTF_8)));

            String beforeMarkerId;
            try (Connection connection =
                         RecoveryTestEnvironment.connect(server, source);
                 Statement statement = connection.createStatement()) {
                createCarbonFlowTables(statement);
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

                validator.createMarkerTable(connection);
                beforeMarkerId = validator.writeMarker(connection,
                        validator.markerTable(), Instant.now());
            }

            // ---- backup; its boundary is the recovery point ----------------
            var coordinator = new RecoverySetCoordinator(
                    com.carbonflow.recovery.postgres.PostgreSqlBackupService
                            .productionDefaults(),
                    EvidenceVaultBackupService.productionDefaults(),
                    new com.carbonflow.recovery.RecoveryManifestWriter(Clock.systemUTC()),
                    Clock.systemUTC());

            Map<String, String> env = RecoveryTestEnvironment.toolEnvironment();
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
            Instant boundary = backup.manifest().recoveryBoundaryAt();

            // ---- marker AFTER the boundary: must be lost on restore ---------
            String afterMarkerId;
            try (Connection connection =
                         RecoveryTestEnvironment.connect(server, source)) {
                afterMarkerId = validator.writeMarker(connection,
                        validator.markerTable(), Instant.now());
            }

            // ---- qualifying failure: destroy the source database ------------
            try (Connection admin = RecoveryTestEnvironment.connect(server, "postgres");
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE \"" + source + "\"");
            }
            Instant failureAt = Instant.now();

            // ---- restore ------------------------------------------------------
            Instant restoreStart = Instant.now();
            DrillResult drill = new RecoveryDrill(Clock.systemUTC(), env)
                    .run(backup.setDirectory(), server, recovery,
                            temp.resolve("restored-vault"), vault);
            Duration restoreDuration = Duration.between(restoreStart, Instant.now());

            assertThat(drill.passed())
                    .as("the restore must succeed for the RPO to be measurable: %s",
                            drill.findings())
                    .isTrue();

            // ---- observe what survived ---------------------------------------
            boolean beforeSurvived;
            boolean afterSurvived;
            try (Connection restored =
                         RecoveryTestEnvironment.connect(server, recovery)) {
                beforeSurvived = validator.markerSurvived(restored, beforeMarkerId);
                afterSurvived = validator.markerSurvived(restored, afterMarkerId);
                validator.dropMarkerTable(restored);
            }

            var result = validator.assess(backup.backupSetId(), boundary, failureAt,
                    boundary.minusSeconds(1), boundary.plusSeconds(1),
                    beforeSurvived, afterSurvived, restoreDuration,
                    Duration.ofHours(1),
                    "PostgreSQL " + server.host() + ":" + server.port()
                            + ", synthetic dataset, loopback, manual detection");

            System.out.println("RPO MEASUREMENT: " + result.summary());
            System.out.println("  boundary=" + boundary
                    + " beforeSurvived=" + beforeSurvived
                    + " afterSurvived=" + afterSurvived
                    + " committedLoss=" + result.committedLoss()
                    + " restoreDuration=" + restoreDuration
                    + " worstCaseBound=" + result.worstCaseLossBound());

            assertThat(beforeSurvived)
                    .as("the pre-boundary marker is inside the recovery point")
                    .isTrue();
            assertThat(afterSurvived)
                    .as("the post-boundary marker is outside it and must be lost")
                    .isFalse();
            assertThat(result.committedLoss()).isEqualTo(Duration.ZERO);
            assertThat(result.targetMet()).isTrue();
            assertThat(result.limitations()).isNotEmpty();

        } finally {
            RecoveryTestEnvironment.dropDatabase(recovery);
            RecoveryTestEnvironment.dropDatabase(source);
        }
    }

    // ------------------------------------------------------------------

    /** The CarbonFlow table set the drill verifies, as plain DDL. */
    private static void createCarbonFlowTables(Statement statement) throws Exception {
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

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}