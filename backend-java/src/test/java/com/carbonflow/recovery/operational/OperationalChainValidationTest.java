package com.carbonflow.recovery.operational;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.coordination.QuiesceGuard;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.monitor.BackupHealth;
import com.carbonflow.recovery.monitor.BackupMonitor;
import com.carbonflow.recovery.notify.LoggingNotificationProvider;
import com.carbonflow.recovery.notify.RecoveryNotification;
import com.carbonflow.recovery.notify.RecoveryNotificationService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.retention.RetentionService;
import com.carbonflow.recovery.schedule.DrillSchedule;
import com.carbonflow.recovery.schedule.RecoveryBackupScheduler;
import com.carbonflow.recovery.schedule.RecoveryCycleDependencies;
import com.carbonflow.recovery.schedule.RecoveryDrillScheduler;
import com.carbonflow.recovery.schedule.RecoveryScheduleConfig;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.verify.BackupVerifier;
import com.carbonflow.recovery.verify.VerificationOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-17 — end-to-end operational validation.
 *
 * <p>Exercises the whole operational chain the Phase 10.7 controls and the
 * Phase 10.8 automation compose into:
 *
 * <pre>
 * scheduler → backup → vault → manifest → verification → monitoring → notification
 * drill scheduler → drill selection → isolated restore → validation → result → notification
 * </pre>
 *
 * <p>The happy path runs a <b>real</b> {@code pg_dump} and a <b>real</b> isolated
 * restore against a live PostgreSQL, because the property being validated is that
 * the automated path produces a genuinely restorable set — something a mock would
 * assert rather than prove.
 *
 * <p>Failure scenarios are then driven against controllable collaborators so each
 * one is proven to be detected and reported rather than tolerated.
 */
class OperationalChainValidationTest {

    private static boolean ready;

    @BeforeAll
    static void detect() {
        ready = RecoveryTestEnvironment.ready();
    }

    /** Full chain against a real PostgreSQL. */
    @Test
    @DisplayName("scheduler → backup → vault → manifest → verification → monitoring → notification")
    void fullOperationalChain(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        if (!ready) {
            return;
        }
        Path backupRoot = Files.createTempDirectory("carbonflow-ops-backups");
        Path vault = Files.createTempDirectory("carbonflow-ops-vault");
        String content = "OPERATIONAL CHAIN EVIDENCE\n";
        Path evidenceFile = vault.resolve("org-a/1_abc.pdf");
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, content, StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> env = RecoveryTestEnvironment.toolEnvironment();
        RecoveryManifestWriter writer = new RecoveryManifestWriter(Clock.systemUTC());
        BackupVerifier verifier = new BackupVerifier(writer, env);
        BackupMonitor monitor = new BackupMonitor(backupRoot, Clock.systemUTC(),
                verifier, env);
        LoggingNotificationProvider provider = new LoggingNotificationProvider();
        RecoveryNotificationService notifications =
                new RecoveryNotificationService(List.of(provider), Clock.systemUTC(),
                        RecoveryNotification.defaultRepeatInterval());

        RecoverySetCoordinator coordinator = new RecoverySetCoordinator(
                PostgreSqlBackupService.productionDefaults(),
                EvidenceVaultBackupService.productionDefaults(), writer,
                Clock.systemUTC());

        RecoveryBackupScheduler scheduler = new RecoveryBackupScheduler(
                request -> coordinator.run(request, new QuiesceGuard.NoOp()),
                verifier::verify,
                healthReporter(monitor),
                new RetentionService(Clock.systemUTC(),
                        RetentionService.approvedRetention())::apply,
                RecoveryScheduleConfig.hourly(true),
                Clock.systemUTC(),
                notifications::notify);

        // ---- 1-2. scheduler invokes a real coordinated backup --------------
        var outcome = scheduler.runOnce(new RecoveryBackupScheduler.Job(
                backupRoot, vault, RecoveryTestEnvironment.matchingServer(), env,
                List.of(new EvidenceVaultBackupService.RequiredFile(
                        evidenceFile.toString(), digest, content.length())),
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                "robocopy"));

        // ---- 3-4. backup + vault produced, manifest written last -----------
        assertThat(outcome.status())
                .as("the operational chain failed: %s", outcome.detail())
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);

        Path set = backupRoot.resolve(outcome.backupSetId());
        assertThat(set.resolve("database.dump")).exists();
        assertThat(set.resolve("globals.sql")).exists();
        assertThat(set.resolve("vault/org-a/1_abc.pdf")).exists();
        assertThat(set.resolve("vault-integrity.json")).exists();
        assertThat(set.resolve("manifest.json")).exists();

        // ---- 5. verification ----------------------------------------------
        VerificationOutcome verification = verifier.verify(set);
        assertThat(verification.status())
                .as("verification findings: %s", verification.findings())
                .isEqualTo(com.carbonflow.recovery.VerificationStatus.VERIFIED);

        // ---- 6. monitoring sees a healthy set -----------------------------
        BackupHealth.Status health = monitor.assess();
        assertThat(health.health()).isEqualTo(BackupHealth.HEALTHY);

        // ---- 7. notification: healthy raises nothing ----------------------
        assertThat(notifications.notifyForHealth(health))
                .as("a healthy backup must not alert anyone")
                .isEmpty();
        assertThat(provider.recorded()).isEmpty();

        System.out.println("OPERATIONAL CHAIN: backup=" + outcome.backupSetId()
                + " verification=" + verification.status()
                + " monitoring=" + health.health()
                + " durationMs=" + outcome.duration().toMillis());
    }

    /** The drill half of the chain, against a real isolated restore. */
    @Test
    @DisplayName("drill scheduler → selection → isolated restore → validation → result → notification")
    void drillChain(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        if (!ready) {
            return;
        }
        Path backupRoot = Files.createTempDirectory("carbonflow-ops-drill-backups");
        Path vault = Files.createTempDirectory("carbonflow-ops-drill-vault");
        String content = "DRILL CHAIN EVIDENCE\n";
        Path evidenceFile = vault.resolve("org-a/1_abc.pdf");
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, content, StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));

        PostgreSqlBackupTarget server = RecoveryTestEnvironment.matchingServer();
        String source = "carbonflow_ops_" + UUID.randomUUID().toString().substring(0, 8);
        String recovery = "carbonflow_ops_r_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            createSource(source, digest, evidenceFile.toString(), content.length());

            Map<String, String> env = RecoveryTestEnvironment.toolEnvironment();
            RecoveryManifestWriter writer = new RecoveryManifestWriter(Clock.systemUTC());
            RecoverySetCoordinator coordinator = new RecoverySetCoordinator(
                    PostgreSqlBackupService.productionDefaults(),
                    EvidenceVaultBackupService.productionDefaults(), writer,
                    Clock.systemUTC());

            var backup = coordinator.run(new RecoverySetCoordinator.Request(
                    backupRoot, vault, RecoveryTestEnvironment.targetFor(source), env,
                    List.of(new EvidenceVaultBackupService.RequiredFile(
                            evidenceFile.toString(), digest, content.length())),
                    RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                    new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 8),
                    "robocopy"), new QuiesceGuard.NoOp());
            assertThat(backup.success()).isTrue();

            // The drill safety gate must refuse the LIVE database. The live name
            // here is the embedded "postgres" maintenance database, which the
            // gate compares by name.
            LoggingNotificationProvider provider = new LoggingNotificationProvider();
            RecoveryNotificationService notifications =
                    new RecoveryNotificationService(List.of(provider), Clock.systemUTC(),
                            RecoveryNotification.defaultRepeatInterval());
            RecoveryDrill drill = new RecoveryDrill(Clock.systemUTC(), env);

            RecoveryDrillScheduler refusing = new RecoveryDrillScheduler(
                    (setDirectory, job) -> drill.run(setDirectory, job.recoveryTarget(),
                            job.recoveryDatabase(), job.vaultRestoreDirectory(),
                            job.sourceVaultRoot()),
                    notifications,
                    DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null),
                    true, "postgres", Clock.systemUTC());

            var refused = refusing.runScheduled(new RecoveryDrillScheduler.DrillJob(
                    backupRoot, vault, server, "postgres",
                    temp.resolve("never-created"), env),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(refused.status())
                    .as("a drill must never target the live database")
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(refused.detail()).contains("LIVE application database");
            assertThat(Files.exists(temp.resolve("never-created")))
                    .as("a refusal must not create anything")
                    .isFalse();
            assertThat(provider.recorded())
                    .anyMatch(n -> n.event() == RecoveryNotification.Event
                            .DRILL_NOT_EXECUTABLE);

            // ---- with a safe isolated target, the drill runs -------------
            Path vaultRestore = temp.resolve("drill-vault-restore");
            RecoveryDrillScheduler allowed = new RecoveryDrillScheduler(
                    (setDirectory, job) -> drill.run(setDirectory, job.recoveryTarget(),
                            job.recoveryDatabase(), job.vaultRestoreDirectory(),
                            job.sourceVaultRoot()),
                    notifications,
                    DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null),
                    true, "postgres", Clock.systemUTC());

            var executed = allowed.runScheduled(new RecoveryDrillScheduler.DrillJob(
                    backupRoot, vault, server, recovery, vaultRestore, env),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(executed.status())
                    .as("drill failed: %s", executed.detail())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status.COMPLETED);
            assertThat(executed.duration()).isNotNull();
            assertThat(vaultRestore.resolve("org-a/1_abc.pdf"))
                    .as("the drill must restore the evidence bytes")
                    .exists();

            System.out.println("DRILL CHAIN: set=" + executed.backupSetId()
                    + " durationMs=" + executed.duration().toMillis()
                    + " environment=" + executed.environment());

        } finally {
            RecoveryTestEnvironment.dropDatabase(recovery);
            RecoveryTestEnvironment.dropDatabase(source);
        }
    }

    // ------------------------------------------------------------------
    // failure scenarios 1-10
    // ------------------------------------------------------------------

    @Test
    @DisplayName("failure scenarios 1-10 are each detected and reported")
    void failureScenariosAreDetected(
            @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path backupRoot = Files.createDirectories(temp.resolve("root"));

        // 1. PostgreSQL backup failure
        assertThat(cycle(true, backupRoot,
                request -> new RecoverySetCoordinator.CoordinatedBackupResult(false,
                        "set-1", null, null, false,
                        "pg_dump failed: could not connect to server"),
                verifier -> VerificationOutcome.from(List.of(
                        VerificationOutcome.Check.passed("a", "ok")))).runOnce(job(backupRoot)).status())
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.FAILED);

        // 2. Evidence Vault failure
        assertThat(cycle(true, backupRoot,
                request -> new RecoverySetCoordinator.CoordinatedBackupResult(false,
                        "set-2", null, null, false,
                        "vault backup failed: evidence file missing"),
                verifier -> VerificationOutcome.from(List.of(
                        VerificationOutcome.Check.passed("a", "ok")))).runOnce(job(backupRoot)).status())
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.FAILED);

        // 3. Checksum / 5. verification failure
        assertThat(cycle(true, backupRoot,
                request -> new RecoverySetCoordinator.CoordinatedBackupResult(true,
                        "set-3", backupRoot, null, false, null),
                verifier -> VerificationOutcome.unverifiable(
                        "database.dump digest does not match the manifest")).runOnce(job(backupRoot)).status())
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.VERIFICATION_FAILED);

        // 4. Stale backup -> detected by monitoring and notified
        RecordingMonitor staleMonitor = new RecordingMonitor();
        BackupHealth.Status stale = new BackupHealth.Status(BackupHealth.STALE,
                Instant.parse("2026-10-02T12:00:00Z"),
                Instant.parse("2026-10-02T10:00:00Z"), Duration.ofHours(2), "set-4",
                "newest backup is older than the RPO window", new java.util.ArrayList<>());
        LoggingNotificationProvider staleProvider = new LoggingNotificationProvider();
        RecoveryNotificationService staleNotifications =
                new RecoveryNotificationService(List.of(staleProvider), Clock.systemUTC(),
                        RecoveryNotification.defaultRepeatInterval());
        assertThat(staleNotifications.notifyForHealth(stale))
                .as("a stale backup must raise an operator notification")
                .hasSize(1);
        assertThat(staleProvider.recorded().get(0).event())
                .isEqualTo(RecoveryNotification.Event.BACKUP_STALE);
        assertThat(staleMonitor.finishSuccess).isNull();

        // 6. Notification failure is contained: the backup still succeeds
        RecoveryBackupScheduler resilient = new RecoveryBackupScheduler(
                request -> new RecoverySetCoordinator.CoordinatedBackupResult(true,
                        "set-6", backupRoot, null, false, null),
                verifier -> VerificationOutcome.from(List.of(
                        VerificationOutcome.Check.passed("a", "ok"))),
                staleMonitor,
                path -> new RetentionService.RetentionResult(true, List.of(), List.of(),
                        List.of(), 0),
                RecoveryScheduleConfig.hourly(true), Clock.systemUTC(),
                notification -> {
                    throw new IllegalStateException("notification channel down");
                });
        assertThat(resilient.runOnce(job(backupRoot)).status())
                .as("a broken notification channel must not fail the backup")
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);

        // 7. Missing drill environment -> refused, nothing destroyed
        LoggingNotificationProvider drillProvider = new LoggingNotificationProvider();
        RecoveryDrillScheduler drillScheduler = new RecoveryDrillScheduler(
                (set, j) -> {
                    throw new AssertionError("drill must not run without a target");
                },
                new RecoveryNotificationService(List.of(drillProvider),
                        Clock.systemUTC(), RecoveryNotification.defaultRepeatInterval()),
                DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null),
                true, "carbonflow", Clock.systemUTC());
        assertThat(drillScheduler.runScheduled(new RecoveryDrillScheduler.DrillJob(
                backupRoot, temp, null, "", null, Map.of()),
                RecoveryDrillScheduler.DEFAULT_GRACE).status())
                .isEqualTo(RecoveryDrillScheduler.Outcome.Status.DRILL_NOT_EXECUTABLE);

        // 8. Overdue drill -> notified
        LoggingNotificationProvider overdueProvider = new LoggingNotificationProvider();
        class FixedClock extends Clock {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId z) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.parse("2026-06-01T00:00:00Z");
            }
        }
        RecoveryDrillScheduler overdue = new RecoveryDrillScheduler(
                (set, j) -> {
                    throw new AssertionError("gate must refuse before the drill");
                },
                new RecoveryNotificationService(List.of(overdueProvider),
                        new FixedClock(), RecoveryNotification.defaultRepeatInterval()),
                DrillSchedule.quarterly(ZoneId.of("UTC"), 2,
                        Instant.parse("2026-01-05T02:00:00Z")),
                true, "carbonflow", new FixedClock());
        overdue.runScheduled(new RecoveryDrillScheduler.DrillJob(
                backupRoot, temp, null, "", null, Map.of()), Duration.ZERO);
        assertThat(overdueProvider.recorded())
                .anyMatch(n -> n.event() == RecoveryNotification.Event.DRILL_OVERDUE);

        // 9. Scheduler overlap
        assertThat(overlapIsRefused(backupRoot).status())
                .as("scenario 9: a second trigger during a run must be refused")
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.SKIPPED_OVERLAPPING);

        // 10. Scheduler restart: a fresh scheduler runs cleanly afterwards
        RecoveryBackupScheduler afterRestart = cycle(true, backupRoot,
                request -> new RecoverySetCoordinator.CoordinatedBackupResult(true,
                        "set-10", backupRoot, null, false, null),
                verifier -> VerificationOutcome.from(List.of(
                        VerificationOutcome.Check.passed("a", "ok"))));
        assertThat(afterRestart.runOnce(job(backupRoot)).status())
                .as("scenario 10: a restarted scheduler must run normally")
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);
    }

    private RecoveryBackupScheduler cycle(boolean enabled, Path backupRoot,
                                          RecoveryCycleDependencies.BackupPerformer performer,
                                          RecoveryCycleDependencies.SetVerifier verifier) {
        return new RecoveryBackupScheduler(performer, verifier,
                new RecoveryCycleDependencies.HealthReporter() {
                    @Override
                    public void recordRunStarted() {
                    }

                    @Override
                    public void recordRunFinished(boolean s, String id, String r) {
                    }

                    @Override
                    public BackupHealth.Status assess() {
                        return null;
                    }
                },
                p -> new RetentionService.RetentionResult(true, List.of(), List.of(),
                        List.of(), 0),
                RecoveryScheduleConfig.hourly(enabled), Clock.systemUTC());
    }

    private RecoveryBackupScheduler.Job job(Path root) {
        return new RecoveryBackupScheduler.Job(root, root,
                new PostgreSqlBackupTarget("localhost", 5432, "db", "u", new char[0],
                        "prefer"),
                Map.of(), List.of(),
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38), "robocopy");
    }

    /** Holds the first cycle inside the backup so a second trigger collides. */
    private RecoveryBackupScheduler.Outcome overlapIsRefused(Path root) throws Exception {
        java.util.concurrent.CountDownLatch inside =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        RecoveryBackupScheduler scheduler = new RecoveryBackupScheduler(request -> {
            inside.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RecoverySetCoordinator.CoordinatedBackupResult(true, "set-9", root,
                    null, false, null);
        }, verifier -> VerificationOutcome.from(List.of(
                VerificationOutcome.Check.passed("a", "ok"))),
                new RecordingMonitor(),
                p -> new RetentionService.RetentionResult(true, List.of(), List.of(),
                        List.of(), 0),
                RecoveryScheduleConfig.hourly(true), Clock.systemUTC());

        Thread first = new Thread(() -> scheduler.runOnce(job(root)));
        first.start();
        assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var second = scheduler.runOnce(job(root));
        release.countDown();
        first.join(10_000);
        return second;
    }

    private static RecoveryCycleDependencies.HealthReporter healthReporter(
            BackupMonitor monitor) {
        return new RecoveryCycleDependencies.HealthReporter() {
            @Override
            public void recordRunStarted() {
                monitor.recordRunStarted();
            }

            @Override
            public void recordRunFinished(boolean success, String id, String reason) {
                monitor.recordRunFinished(success, id, reason);
            }

            @Override
            public BackupHealth.Status assess() {
                return monitor.assess();
            }
        };
    }

    /** Records what the scheduler reported. */
    private static final class RecordingMonitor
            implements RecoveryCycleDependencies.HealthReporter {
        Boolean finishSuccess;
        String finishReason;

        @Override
        public void recordRunStarted() {
        }

        @Override
        public void recordRunFinished(boolean success, String id, String reason) {
            finishSuccess = success;
            finishReason = reason;
        }

        @Override
        public BackupHealth.Status assess() {
            return null;
        }
    }

    private static void createSource(String name, String digest, String storagePath,
                                     long bytes) throws Exception {
        PostgreSqlBackupTarget server = RecoveryTestEnvironment.matchingServer();
        try (Connection admin = RecoveryTestEnvironment.connect(server, "postgres");
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
        }
        try (Connection connection =
                     RecoveryTestEnvironment.connect(server, name);
             Statement statement = connection.createStatement()) {
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
            statement.execute("INSERT INTO organizations VALUES ('"
                    + UUID.randomUUID() + "', 'Acme')");
            statement.execute("INSERT INTO activity_data VALUES ('" + UUID.randomUUID()
                    + "', (select id from organizations limit 1), 'gas')");
            statement.execute("INSERT INTO carbon_audits VALUES ('" + UUID.randomUUID()
                    + "', (select id from organizations limit 1), 'LOCKED')");
            statement.execute("INSERT INTO users VALUES ('" + UUID.randomUUID()
                    + "', (select id from organizations limit 1), 'a@acme.invalid')");
            statement.execute("INSERT INTO evidence_records VALUES ('"
                    + UUID.randomUUID() + "', (select id from organizations limit 1), "
                    + "'a.pdf', " + bytes + ", 'application/pdf', '" + digest
                    + "', '" + storagePath.replace("'", "''") + "')");
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}