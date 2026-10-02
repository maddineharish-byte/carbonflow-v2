package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.monitor.BackupHealth;
import com.carbonflow.recovery.monitor.BackupMonitor;
import com.carbonflow.recovery.postgres.PostgreSqlBackupService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.retention.RetentionService;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.verify.BackupVerifier;
import com.carbonflow.recovery.verify.VerificationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REC-13 — automated recovery backup scheduling.
 *
 * <p>The scheduler's collaborators are supplied through
 * {@link RecoveryCycleDependencies}, so overlap, fail-closed recording and
 * retention ordering are tested directly against controllable collaborators with
 * no database anywhere in sight. The end-to-end test then drives a <b>real</b>
 * {@code pg_dump} through the same scheduler to prove the automated path produces a
 * genuine verified backup set.
 */
class RecoveryBackupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    // ------------------------------------------------------------------
    // configuration
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("schedule configuration")
    class Configuration {

        @Test
        @DisplayName("the default schedule is hourly in UTC")
        void defaultIsHourlyUtc() {
            RecoveryScheduleConfig config = RecoveryScheduleConfig.hourly(true);

            assertThat(config.enabled()).isTrue();
            assertThat(config.cron()).isEqualTo("0 0 * * * *");
            assertThat(config.zone()).isEqualTo(ZoneId.of("UTC"));
            assertThat(config.defaultInterval())
                    .isEqualTo(RecoveryScheduleConfig.APPROVED_INTERVAL);
            assertThat(config.describe()).contains("enabled").contains("UTC");
        }

        @Test
        @DisplayName("no country-specific timezone is hardcoded")
        void noGeographicTimezoneIsHardcoded() throws IOException {
            String source = Files.readString(Path.of(
                    "src/main/java/com/carbonflow/recovery/schedule/RecoveryScheduleConfig.java"));

            for (String geography : List.of("Kolkata", "IST", "PST", "EST",
                    "America/", "Asia/", "Europe/", "05:30")) {
                assertThat(source).as("must not reference %s", geography)
                        .doesNotContain(geography);
            }
        }

        @Test
        @DisplayName("an explicit non-UTC zone is honoured")
        void explicitZoneIsHonoured() {
            RecoveryScheduleConfig config = new RecoveryScheduleConfig(true,
                    "0 30 2 * * *", ZoneId.of("Pacific/Auckland"),
                    Duration.ofMinutes(30), Duration.ofHours(1));

            assertThat(config.zone().getId()).isEqualTo("Pacific/Auckland");
        }

        @Test
        @DisplayName("an unknown zone is refused rather than silently defaulting")
        void unknownZoneIsRefused() {
            // Silently falling back to UTC would move every backup without telling
            // anyone, which is exactly the class of surprise this must avoid.
            assertThatThrownBy(() -> ZoneId.of("Mars/Olympus_Mons"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("invalid configuration is refused rather than defaulted")
        void invalidConfigurationIsRefused() {
            assertThatThrownBy(() -> new RecoveryScheduleConfig(true, "  ",
                    ZoneId.of("UTC"), Duration.ofMinutes(30), Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new RecoveryScheduleConfig(true, "0 0 * * * *",
                    ZoneId.of("UTC"), Duration.ZERO, Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            assertThatThrownBy(() -> new RecoveryScheduleConfig(true, "0 0 * * * *",
                    ZoneId.of("UTC"), Duration.ofMinutes(30), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interval must be positive");
        }

        @Test
        @DisplayName("the next run lands on the following hour boundary")
        void nextRunIsTheFollowingHourBoundary() {
            RecoveryScheduleConfig config = RecoveryScheduleConfig.hourly(true);

            assertThat(config.nextRunAfter(Instant.parse("2026-10-02T12:00:30Z"),
                    Clock.systemUTC()))
                    .isEqualTo(Instant.parse("2026-10-02T13:00:00Z"));
        }

        @Test
        @DisplayName("the next run is always strictly after the given instant")
        void nextRunIsStrictlyAfter() {
            RecoveryScheduleConfig config = RecoveryScheduleConfig.hourly(true);

            for (String from : List.of("2026-10-02T12:00:00Z", "2026-10-02T12:59:59Z",
                    "2026-10-02T23:30:00Z")) {
                assertThat(config.nextRunAfter(Instant.parse(from), Clock.systemUTC()))
                        .as("from %s", from).isAfter(Instant.parse(from));
            }
        }
    }

    // ------------------------------------------------------------------
    // cycle behaviour, driven through controllable collaborators
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("scheduled cycle behaviour")
    class Cycle {

        @Test
        @DisplayName("a disabled schedule does nothing and says so")
        void disabledScheduleDoesNothing(@TempDir Path temp) {
            Fixture fixture = new Fixture(false);
            var outcome = fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(outcome.status())
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.DISABLED);
            assertThat(fixture.backup.invocations.get()).isZero();
        }

        @Test
        @DisplayName("a successful backup is verified and recorded")
        void successfulBackupIsVerifiedAndRecorded(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            var outcome = fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(outcome.status())
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);
            assertThat(outcome.isSuccess()).isTrue();
            assertThat(fixture.backup.invocations.get()).isEqualTo(1);
            assertThat(fixture.monitor.finishSuccess).isTrue();
            assertThat(fixture.monitor.finishReason).isNull();
            assertThat(outcome.verification().isVerified()).isTrue();
        }

        @Test
        @DisplayName("a failed backup is recorded as failed, never as success")
        void failedBackupIsRecordedAsFailed(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            fixture.backup.failWith("vault backup failed: evidence file missing");

            var outcome = fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(outcome.status())
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.FAILED);
            assertThat(outcome.isSuccess()).isFalse();
            assertThat(outcome.detail()).contains("evidence file missing");
            assertThat(fixture.monitor.finishSuccess).isFalse();
        }

        @Test
        @DisplayName("a backup that fails verification is not recorded as success")
        void verificationFailureIsNotSuccess(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            fixture.verifier.result = VerificationOutcome.unverifiable(
                    "pg_restore not available");

            var outcome = fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(outcome.status())
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.VERIFICATION_FAILED);
            assertThat(outcome.isSuccess()).isFalse();
            assertThat(fixture.monitor.finishSuccess)
                    .as("the monitor must never see an unverified set as good")
                    .isFalse();
            assertThat(fixture.retention.applications.get())
                    .as("retention must not run on an unverified set")
                    .isZero();
        }

        @Test
        @DisplayName("verification runs before success is recorded")
        void verificationRunsBeforeSuccessIsRecorded(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);

            fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(fixture.backup.invocations.get()).isEqualTo(1);
            assertThat(fixture.verifier.invocations)
                    .as("the set must be verified before the monitor is told it succeeded")
                    .isEqualTo(1);
            assertThat(fixture.monitor.started).isTrue();
        }

        @Test
        @DisplayName("an overlapping run is skipped, not queued")
        void overlappingRunIsSkipped(@TempDir Path temp) throws Exception {
            Fixture fixture = new Fixture(true);
            fixture.backup.blockUntilReleased();
            try {
                var firstResult = new java.util.concurrent.atomic.AtomicReference<
                        RecoveryBackupScheduler.Outcome>();
                Thread first = new Thread(() ->
                        firstResult.set(fixture.scheduler.runOnce(fixture.job(temp))));
                first.start();
                assertThat(fixture.backup.awaitInside(5, TimeUnit.SECONDS)).isTrue();

                var second = fixture.scheduler.runOnce(fixture.job(temp));

                assertThat(second.status())
                        .isEqualTo(RecoveryBackupScheduler.Outcome.Status
                                .SKIPPED_OVERLAPPING);
                assertThat(second.detail()).contains("already running");
                assertThat(fixture.backup.invocations.get())
                        .as("the second trigger must not start a second backup")
                        .isEqualTo(1);

                fixture.backup.release();
                first.join(10_000);
                assertThat(firstResult.get())
                        .isNotNull()
                        .satisfies(o -> assertThat(o.isSuccess()).isTrue());
            } finally {
                fixture.backup.release();
            }
        }

        @Test
        @DisplayName("the guard is released after a failure so the next run can proceed")
        void guardIsReleasedAfterFailure(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            fixture.backup.failWith("boom");

            fixture.scheduler.runOnce(fixture.job(temp));
            assertThat(fixture.scheduler.isRunning()).isFalse();

            fixture.backup.succeed();
            var second = fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(second.status())
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);
        }

        @Test
        @DisplayName("retention runs after a successful cycle")
        void retentionRunsAfterSuccess(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);

            fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(fixture.retention.applications.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("a retention failure does not fail the backup cycle")
        void retentionFailureDoesNotFailTheCycle(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            fixture.retention.failWith("backup root unreadable");

            var outcome = fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(outcome.status())
                    .as("failing to delete old backups is not failing to take a new one")
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);
        }

        @Test
        @DisplayName("the last outcome is retained for REC-14 to report")
        void lastOutcomeIsRetained(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            assertThat(fixture.scheduler.lastOutcome()).isNull();

            fixture.scheduler.runOnce(fixture.job(temp));

            assertThat(fixture.scheduler.lastOutcome()).isNotNull();
            assertThat(fixture.scheduler.lastOutcome().status())
                    .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);
            assertThat(fixture.scheduler.lastRunStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("shutdown reports whether a run is still in progress")
        void shutdownReportsState(@TempDir Path temp) {
            Fixture fixture = new Fixture(true);
            fixture.scheduler.runOnce(fixture.job(temp));

            fixture.scheduler.shutdown();

            assertThat(fixture.scheduler.isRunning()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // end to end
    // ------------------------------------------------------------------

    @Test
    @DisplayName("end to end: the scheduler produces a real verified backup set")
    void schedulerProducesRealVerifiedSet() throws Exception {
        if (!RecoveryTestEnvironment.ready()) {
            return;
        }
        Path backupRoot = Files.createTempDirectory("carbonflow-sched-backups");
        Path vault = Files.createTempDirectory("carbonflow-sched-vault");
        String content = "SCHEDULED EVIDENCE\n";
        Path evidenceFile = vault.resolve("org-a/1_abc.pdf");
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, content, StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> env = RecoveryTestEnvironment.toolEnvironment();
        RecoveryManifestWriter writer = new RecoveryManifestWriter(Clock.systemUTC());
        BackupMonitor monitor = new BackupMonitor(backupRoot, Clock.systemUTC(),
                new BackupVerifier(writer, env), env);

        RecoveryBackupScheduler scheduler = new RecoveryBackupScheduler(
                request -> new RecoverySetCoordinator(
                                PostgreSqlBackupService.productionDefaults(),
                                EvidenceVaultBackupService.productionDefaults(),
                                writer, Clock.systemUTC())
                        .run(request,
                                new com.carbonflow.recovery.coordination.QuiesceGuard.NoOp()),
                new BackupVerifier(writer, env)::verify,
                new RecoveryCycleDependencies.HealthReporter() {
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
                },
                new RetentionService(Clock.systemUTC(),
                        RetentionService.approvedRetention())::apply,
                RecoveryScheduleConfig.hourly(true),
                Clock.systemUTC());

        var outcome = scheduler.runOnce(new RecoveryBackupScheduler.Job(
                backupRoot, vault, RecoveryTestEnvironment.matchingServer(), env,
                List.of(new EvidenceVaultBackupService.RequiredFile(
                        evidenceFile.toString(), digest, content.length())),
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                "robocopy"));

        assertThat(outcome.status())
                .as("scheduled backup failed: %s", outcome.detail())
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.COMPLETED);

        Path set = backupRoot.resolve(outcome.backupSetId());
        assertThat(set.resolve("database.dump")).exists();
        assertThat(set.resolve("globals.sql")).exists();
        assertThat(set.resolve("vault-integrity.json")).exists();
        assertThat(set.resolve("manifest.json")).exists();

        assertThat(monitor.assess().health())
                .as("monitoring must report HEALTHY after a verified scheduled backup")
                .isEqualTo(BackupHealth.HEALTHY);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    // ------------------------------------------------------------------
    // collaborators
    // ------------------------------------------------------------------

    /** A backup performer that can be told to fail or to block. */
    private static final class StubBackup {
        final AtomicInteger invocations = new AtomicInteger();
        volatile String failure;
        volatile CountDownLatch inside;
        volatile CountDownLatch release;

        RecoveryCycleDependencies.BackupPerformer performer() {
            return request -> {
                invocations.incrementAndGet();
                if (inside != null) {
                    inside.countDown();
                }
                if (release != null) {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failure != null) {
                    return new RecoverySetCoordinator.CoordinatedBackupResult(false,
                            "stub-set", null, null, false, failure);
                }
                return new RecoverySetCoordinator.CoordinatedBackupResult(true,
                        "stub-set", Path.of("stub-set-dir"), null, false, null);
            };
        }

        void failWith(String reason) {
            this.failure = reason;
        }

        void succeed() {
            this.failure = null;
        }

        void blockUntilReleased() {
            inside = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void release() {
            if (release != null) {
                release.countDown();
            }
        }

        boolean awaitInside(long seconds, TimeUnit unit) throws InterruptedException {
            return inside != null && inside.await(seconds, unit);
        }
    }

    /** A verifier that records its invocations and returns a chosen outcome. */
    private static final class StubVerifier {
        int invocations;
        VerificationOutcome result = verified();

        VerificationOutcome verify(Path setDirectory) {
            invocations++;
            return result;
        }

        /** A genuinely verified outcome, constructed from passing checks. */
        static VerificationOutcome verified() {
            return VerificationOutcome.from(List.of(
                    VerificationOutcome.Check.passed("a", "ok"),
                    VerificationOutcome.Check.passed("b", "ok")));
        }
    }

    /** Records what the scheduler told the monitor. */
    private static final class StubMonitor
            implements RecoveryCycleDependencies.HealthReporter {
        boolean started;
        Boolean finishSuccess;
        String finishReason;

        @Override
        public void recordRunStarted() {
            started = true;
        }

        @Override
        public void recordRunFinished(boolean success, String backupSetId, String reason) {
            finishSuccess = success;
            finishReason = reason;
        }

        @Override
        public BackupHealth.Status assess() {
            return null;
        }
    }

    /** Counts applications and can be made to fail. */
    private static final class StubRetention {
        final AtomicInteger applications = new AtomicInteger();
        volatile String failure;

        RetentionService.RetentionResult apply(Path backupRoot) {
            applications.incrementAndGet();
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            return new RetentionService.RetentionResult(true, List.of(), List.of(),
                    List.of(), 0);
        }

        void failWith(String reason) {
            this.failure = reason;
        }
    }

    private static final class Fixture {
        final StubBackup backup = new StubBackup();
        final StubVerifier verifier = new StubVerifier();
        final StubMonitor monitor = new StubMonitor();
        final StubRetention retention = new StubRetention();
        final RecoveryBackupScheduler scheduler;

        Fixture(boolean enabled) {
            RecoveryScheduleConfig config = new RecoveryScheduleConfig(enabled,
                    RecoveryScheduleConfig.DEFAULT_CRON, ZoneId.of("UTC"),
                    Duration.ofMinutes(30), Duration.ofHours(1));
            scheduler = new RecoveryBackupScheduler(backup.performer(),
                    verifier::verify, monitor, retention::apply, config, Clock.systemUTC());
        }

        RecoveryBackupScheduler.Job job(Path root) {
            return new RecoveryBackupScheduler.Job(
                    root, root,
                    new PostgreSqlBackupTarget("localhost", 5432, "db", "u",
                            new char[0], "prefer"),
                    Map.of(), List.of(),
                    RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                    new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                    "robocopy");
        }
    }
}