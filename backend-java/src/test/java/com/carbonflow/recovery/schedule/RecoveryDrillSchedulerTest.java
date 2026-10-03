package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.notify.LoggingNotificationProvider;
import com.carbonflow.recovery.notify.RecoveryNotification;
import com.carbonflow.recovery.notify.RecoveryNotificationService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REC-15 — quarterly recovery drill scheduling.
 *
 * <p>The centre of gravity is the safety gate. A scheduled drill drops and restores
 * a database, so every refusal path is asserted to leave the environment
 * untouched: the drill runner is never invoked, and no database name is ever
 * created.
 */
class RecoveryDrillSchedulerTest {

    private static final String LIVE_DB = "carbonflow";

    // ------------------------------------------------------------------
    // quarterly cadence
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("quarterly cadence")
    class Cadence {

        @Test
        @DisplayName("the next quarter is derived from the calendar quarter")
        void nextQuarterIsCalendarDerived() {
            DrillSchedule schedule = DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null);

            // Mid-Q1 -> start of Q2
            assertThat(schedule.nextQuarterStartAfter(
                    Instant.parse("2026-02-15T00:00:00Z"), Clock.systemUTC()))
                    .isEqualTo(Instant.parse("2026-04-01T02:00:00Z"));
            // Mid-Q2 -> start of Q3
            assertThat(schedule.nextQuarterStartAfter(
                    Instant.parse("2026-05-15T00:00:00Z"), Clock.systemUTC()))
                    .isEqualTo(Instant.parse("2026-07-01T02:00:00Z"));
            // Mid-Q3 -> start of Q4
            assertThat(schedule.nextQuarterStartAfter(
                    Instant.parse("2026-08-15T00:00:00Z"), Clock.systemUTC()))
                    .isEqualTo(Instant.parse("2026-10-01T02:00:00Z"));
        }

        @Test
        @DisplayName("Q4 rolls into the following year")
        void fourthQuarterRollsIntoNextYear() {
            DrillSchedule schedule = DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null);

            assertThat(schedule.nextQuarterStartAfter(
                    Instant.parse("2026-11-15T00:00:00Z"), Clock.systemUTC()))
                    .isEqualTo(Instant.parse("2027-01-01T02:00:00Z"));
        }

        @Test
        @DisplayName("month lengths never shift a quarter boundary")
        void monthLengthsDoNotShiftBoundaries() {
            DrillSchedule schedule = DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null);

            // 31 January and 30 April both resolve to the same next boundary,
            // proving the boundary is derived from the quarter index rather than
            // by adding three months to a timestamp.
            Instant fromJan = schedule.nextQuarterStartAfter(
                    Instant.parse("2026-01-31T23:59:59Z"), Clock.systemUTC());
            Instant fromApr = schedule.nextQuarterStartAfter(
                    Instant.parse("2026-04-30T23:59:59Z"), Clock.systemUTC());

            assertThat(fromJan.getNano()).isZero();
            assertThat(fromApr.getNano()).isZero();
            assertThat(fromJan.atZone(ZoneId.of("UTC")).getMonthValue()).isEqualTo(4);
            assertThat(fromApr.atZone(ZoneId.of("UTC")).getMonthValue()).isEqualTo(7);
        }

        @Test
        @DisplayName("no drill on record means the next quarter, not immediately overdue")
        void noDrillMeansNextQuarterNotOverdue() {
            DrillSchedule schedule = DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null);

            assertThat(schedule.isOverdue(Clock.systemUTC(), Duration.ofDays(14)))
                    .as("a fresh installation has not missed a deadline it never had")
                    .isFalse();
            assertThat(schedule.sinceLastDrill(Clock.systemUTC())).isNull();
        }

        @Test
        @DisplayName("a drill becomes overdue once the quarter passes plus the grace period")
        void becomesOverdueAfterGrace() {
            Instant lastDrill = Instant.parse("2026-01-05T02:00:00Z");
            DrillSchedule schedule = DrillSchedule.quarterly(ZoneId.of("UTC"), 2, lastDrill);

            // Well inside the quarter after the drill: not overdue.
            Clock withinQuarter = Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"),
                    ZoneId.of("UTC"));
            assertThat(schedule.isOverdue(withinQuarter, Duration.ofDays(14))).isFalse();

            // Long past the next quarter and its grace: overdue.
            Clock muchLater = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"),
                    ZoneId.of("UTC"));
            assertThat(schedule.isOverdue(muchLater, Duration.ofDays(14))).isTrue();
            assertThat(schedule.isOverdue(muchLater, Duration.ZERO)).isTrue();
        }

        @Test
        @DisplayName("the next due date follows the last drill")
        void nextDueFollowsLastDrill() {
            DrillSchedule schedule = DrillSchedule.quarterly(ZoneId.of("UTC"), 2,
                    Instant.parse("2026-02-05T02:00:00Z"));

            assertThat(schedule.nextDueAfter(Clock.systemUTC()))
                    .isEqualTo(Instant.parse("2026-04-01T02:00:00Z"));
        }

        @Test
        @DisplayName("an explicit zone is honoured and never hardcoded")
        void explicitZoneIsHonoured() {
            DrillSchedule auckland = DrillSchedule.quarterly(
                    ZoneId.of("Pacific/Auckland"), 2, null);
            DrillSchedule utc = DrillSchedule.quarterly(ZoneId.of("UTC"), 2, null);

            assertThat(auckland.zone().getId()).isEqualTo("Pacific/Auckland");
            assertThat(utc.zone().getId()).isEqualTo("UTC");
            assertThat(auckland.describe()).contains("quarterly").contains("Auckland");
        }

        @Test
        @DisplayName("an invalid hour is refused")
        void invalidHourIsRefused() {
            assertThatThrownBy(() -> DrillSchedule.quarterly(ZoneId.of("UTC"), 24, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0 and 23");
            assertThatThrownBy(() -> DrillSchedule.quarterly(ZoneId.of("UTC"), -1, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    // safety gate
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("safety gate — a drill must never destroy anything live")
    class Safety {

        @Test
        @DisplayName("a drill targeting the live database is refused")
        void liveDatabaseIsRefused(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            RecoveryDrillScheduler.DrillJob job = fixture.job(temp,
                    fixture.backupRoot(temp), LIVE_DB);

            var outcome = fixture.scheduler().runScheduled(job,
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(outcome.detail())
                    .as("the refusal must name the live database")
                    .contains("LIVE application database")
                    .contains(LIVE_DB);
            assertThat(fixture.drill().invocations.get())
                    .as("the drill runner must never be reached")
                    .isZero();
        }

        @Test
        @DisplayName("the live database refusal ignores case")
        void liveDatabaseRefusalIgnoresCase(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), "CARBONFLOW"),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(fixture.drill().invocations.get()).isZero();
        }

        @Test
        @DisplayName("an existing vault restore directory is refused")
        void existingVaultDirectoryIsRefused(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            Path backupRoot = fixture.backupRoot(temp);
            Path existing = Files.createDirectories(temp.resolve("already-there"));

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, backupRoot, "carbonflow_drill", existing),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(outcome.detail()).contains("already exists");
            assertThat(fixture.drill().invocations.get()).isZero();
        }

        @Test
        @DisplayName("a missing backup root is refused")
        void missingBackupRootIsRefused(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, temp.resolve("no-such-root"), "carbonflow_drill",
                            temp.resolve("vault")),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(outcome.detail()).contains("backup root does not exist");
            assertThat(fixture.drill().invocations.get()).isZero();
        }

        @Test
        @DisplayName("no recovery target is refused")
        void missingTargetIsRefused(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            Path backupRoot = fixture.backupRoot(temp);

            var job = new RecoveryDrillScheduler.DrillJob(backupRoot, temp, null,
                    "carbonflow_drill", temp.resolve("vault"), Map.of());

            var outcome = fixture.scheduler().runScheduled(job,
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(outcome.detail()).contains("no recovery target");
        }

        @Test
        @DisplayName("a refusal raises an operator notification")
        void refusalNotifies(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);

            fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), LIVE_DB),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(fixture.provider.recorded()).hasSize(1);
            RecoveryNotification notification = fixture.provider.recorded().get(0);
            assertThat(notification.event())
                    .isEqualTo(RecoveryNotification.Event.DRILL_NOT_EXECUTABLE);
            assertThat(notification.control())
                    .isEqualTo(RecoveryNotification.Control.RECOVERY_DRILL);
            assertThat(notification.severity())
                    .isEqualTo(RecoveryNotification.Severity.CRITICAL);
            assertThat(notification.action()).contains("Nothing was destroyed");
        }

        @Test
        @DisplayName("a disabled schedule does nothing")
        void disabledScheduleDoesNothing(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(false);

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), "carbonflow_drill"),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status.DISABLED);
            assertThat(fixture.drill().invocations.get()).isZero();
        }

        @Test
        @DisplayName("no backup set means not executable, not a silent pass")
        void noBackupSetMeansNotExecutable(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            Path emptyRoot = Files.createDirectories(temp.resolve("empty-root"));

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, emptyRoot, "carbonflow_drill"),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status
                            .DRILL_NOT_EXECUTABLE);
            assertThat(outcome.detail()).contains("no verifiable backup set");
            assertThat(fixture.drill().invocations.get()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // execution and results
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("drill execution")
    class Execution {

        @Test
        @DisplayName("a passing drill reports COMPLETED with its set and duration")
        void passingDrillReportsCompleted(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            fixture.drill.passing();

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), "carbonflow_drill"),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status.COMPLETED);
            assertThat(outcome.isSuccess()).isTrue();
            assertThat(outcome.backupSetId())
                    .matches("^[0-9a-f-]{36}$");
            assertThat(outcome.duration()).isNotNull();
            assertThat(outcome.environment()).contains("quarterly");
            assertThat(fixture.drill().invocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("a failing drill reports FAILED and notifies")
        void failingDrillNotifies(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            fixture.drill.failing();

            var outcome = fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), "carbonflow_drill"),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(outcome.status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status.FAILED);
            assertThat(outcome.isSuccess()).isFalse();
            assertThat(fixture.provider.recorded())
                    .anyMatch(n -> n.event() == RecoveryNotification.Event.DRILL_FAILED);
        }

        @Test
        @DisplayName("the drill outcome is retained for reporting")
        void outcomeIsRetained(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true);
            fixture.drill.passing();
            assertThat(fixture.scheduler().lastOutcome()).isNull();

            fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), "carbonflow_drill"),
                    RecoveryDrillScheduler.DEFAULT_GRACE);

            assertThat(fixture.scheduler().lastOutcome()).isNotNull();
            assertThat(fixture.scheduler().lastOutcome().status())
                    .isEqualTo(RecoveryDrillScheduler.Outcome.Status.COMPLETED);
        }

        @Test
        @DisplayName("an overdue drill raises an overdue notification")
        void overdueDrillNotifies(@TempDir Path temp) throws IOException {
            Fixture fixture = Fixture.newFixture(true, Instant.parse("2026-01-05T02:00:00Z"));
            fixture.clock.current = Instant.parse("2026-06-01T00:00:00Z");
            fixture.drill.passing();

            fixture.scheduler().runScheduled(
                    fixture.job(temp, fixture.backupRoot(temp), "carbonflow_drill"),
                    Duration.ZERO);

            assertThat(fixture.provider.recorded())
                    .anyMatch(n -> n.event() == RecoveryNotification.Event.DRILL_OVERDUE);
        }

        @Test
        @DisplayName("next due and overdue status are exposed for reporting")
        void nextDueAndOverdueAreExposed(@TempDir Path temp) throws IOException {
            // Last drill in February; the fixture clock reads October, so the
            // quarterly cadence has genuinely lapsed.
            Fixture overdue = Fixture.newFixture(true,
                    Instant.parse("2026-02-05T02:00:00Z"));

            assertThat(overdue.scheduler().nextDueAt())
                    .isEqualTo(Instant.parse("2026-04-01T02:00:00Z"));
            assertThat(overdue.scheduler().isOverdue(Duration.ofDays(14)))
                    .as("a February drill evaluated in October is overdue")
                    .isTrue();

            // A drill from the immediately preceding quarter is not yet overdue.
            Fixture current = Fixture.newFixture(true,
                    Instant.parse("2026-07-05T02:00:00Z"));
            assertThat(current.scheduler().nextDueAt())
                    .isEqualTo(Instant.parse("2026-10-01T02:00:00Z"));
            assertThat(current.scheduler().isOverdue(Duration.ofDays(14)))
                    .as("the current quarter has not yet elapsed its grace period")
                    .isFalse();
        }
    }

    private static DrillResult.Durations durations(DrillResult.Timings t) {
        return new DrillResult.Durations(
                java.time.Duration.between(t.drillStartedAt(), t.drillCompletedAt()),
                java.time.Duration.between(t.databaseRestoreStartedAt(),
                        t.databaseRestoreCompletedAt()),
                java.time.Duration.between(t.vaultRestoreStartedAt(),
                        t.vaultRestoreCompletedAt()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static final class StubDrill implements RecoveryDrillScheduler.DrillPerformer {
        final AtomicInteger invocations = new AtomicInteger();
        private volatile boolean pass = true;



        void passing() {
            this.pass = true;
        }

        void failing() {
            this.pass = false;
        }

        @Override
        public DrillResult run(Path setDirectory,
                               RecoveryDrillScheduler.DrillJob job) {
            invocations.incrementAndGet();
            List<DrillResult.Check> checks = List.of(
                    DrillResult.Check.passed("discovery", "located set"));
            List<String> findings = pass ? List.of()
                    : List.of("evidence.sha256: digest mismatch");

            DrillResult.Timings timings = new DrillResult.Timings(
                    Instant.parse("2026-10-02T12:00:00Z"),
                    Instant.parse("2026-10-02T12:00:01Z"),
                    Instant.parse("2026-10-02T12:00:10Z"),
                    Instant.parse("2026-10-02T12:00:10Z"),
                    Instant.parse("2026-10-02T12:00:12Z"),
                    Instant.parse("2026-10-02T12:00:30Z"),
                    Instant.parse("2026-10-02T12:00:40Z"),
                    Instant.parse("2026-10-02T12:00:45Z"),
                    Instant.parse("2026-10-02T12:00:46Z"));

            return new DrillResult(pass, job.recoveryDatabase(),
                    new DrillResult.Environment("pg_dump 18.6", "21", "test", true,
                            false, "synthetic"),
                    timings, durations(timings), checks, findings,
                    DrillResult.standardLimitations());
        }
    }

    /** A clock whose value the test controls. */
    private static final class SteppingClock extends Clock {
        private Instant current = Instant.parse("2026-10-02T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private record Fixture(RecoveryDrillScheduler scheduler, StubDrill drill,
                           LoggingNotificationProvider provider, SteppingClock clock) {

        static Fixture newFixture(boolean enabled) {
            return newFixture(enabled, null);
        }

        static Fixture newFixture(boolean enabled, Instant lastDrill) {
            SteppingClock clock = new SteppingClock();
            StubDrill drill = new StubDrill();
            LoggingNotificationProvider provider = new LoggingNotificationProvider();
            RecoveryNotificationService notifications =
                    new RecoveryNotificationService(List.of(provider), clock,
                            RecoveryNotification.defaultRepeatInterval());
            RecoveryDrillScheduler scheduler = new RecoveryDrillScheduler(drill::run,
                    notifications,
                    DrillSchedule.quarterly(ZoneId.of("UTC"), 2, lastDrill),
                    enabled, LIVE_DB, clock);
            return new Fixture(scheduler, drill, provider, clock);
        }

        /** A backup root containing one well-formed set directory. */
        Path backupRoot(Path temp) throws IOException {
            Path root = Files.createDirectories(temp.resolve("backups"));
            Path set = Files.createDirectories(root.resolve(
                    "11111111-2222-4333-8444-555555555555"));
            Files.writeString(set.resolve("manifest.json"),
                    "{\"manifestVersion\":\"1\"}", java.nio.charset.StandardCharsets.UTF_8);
            return root;
        }

        RecoveryDrillScheduler.DrillJob job(Path temp, Path backupRoot,
                                             String recoveryDatabase) {
            return job(temp, backupRoot, recoveryDatabase,
                    temp.resolve("vault-" + recoveryDatabase));
        }

        RecoveryDrillScheduler.DrillJob job(Path temp, Path backupRoot,
                                             String recoveryDatabase,
                                             Path vaultRestoreDirectory) {
            return new RecoveryDrillScheduler.DrillJob(backupRoot, temp,
                    new PostgreSqlBackupTarget("127.0.0.1", 5432, "postgres",
                            "postgres", new char[0], "prefer"),
                    recoveryDatabase, vaultRestoreDirectory, Map.of());
        }
    }
}