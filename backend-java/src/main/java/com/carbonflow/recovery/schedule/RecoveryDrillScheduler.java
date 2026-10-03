package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoverySetNaming;
import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.notify.RecoveryNotification;
import com.carbonflow.recovery.notify.RecoveryNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REC-15 — scheduled quarterly recovery drill.
 *
 * <h2>The safety gate — this is the point of the class</h2>
 * <p>A drill drops and restores a database. Running that on a schedule against
 * the wrong target would be catastrophic, so a drill executes only when
 * <b>every</b> one of these holds:
 * <ol>
 *   <li>scheduling is explicitly enabled;</li>
 *   <li>a recovery target is explicitly configured;</li>
 *   <li>the recovery database name differs from the live application database;</li>
 *   <li>the recovery database does not already exist;</li>
 *   <li>the vault restore directory does not already exist;</li>
 *   <li>the named backup set actually exists.</li>
 * </ol>
 * <p>If any check fails the outcome is {@link Outcome.Status#DRILL_NOT_EXECUTABLE}
 * and <b>nothing is destroyed</b>; an operator notification is raised. There is no
 * "force" flag and no override: a mechanism that can be told to skip its own safety
 * checks is not a safety check.
 *
 * <p>The live application database is refused by name, not merely by luck. The
 * configured {@code DB_NAME} is never an acceptable drill target.
 */
public final class RecoveryDrillScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryDrillScheduler.class);

    /** Default lateness allowed before a quarterly drill counts as overdue. */
    public static final Duration DEFAULT_GRACE = Duration.ofDays(14);

    /**
     * Runs one drill.
     *
     * <p>A narrow contract rather than the {@code final} {@link RecoveryDrill}
     * class, so the scheduler's policy — the safety gate, overdue detection,
     * result recording, notification — can be tested directly without a live
     * database. The real drill is adapted into this contract in production, and
     * the safety gate is what actually prevents a destructive run; the
     * abstraction does not weaken it.
     */
    @FunctionalInterface
    public interface DrillPerformer {
        /**
         * Runs the drill for a specific, already-resolved backup set.
         *
         * @param setDirectory the backup set to restore
         * @param job          the drill environment
         */
        DrillResult run(Path setDirectory, RecoveryDrillScheduler.DrillJob job);
    }

    private final DrillPerformer drill;
    private final RecoveryNotificationService notifications;
    private final DrillSchedule schedule;
    private final Clock clock;
    private final boolean enabled;
    private final String liveDatabaseName;

    private final AtomicReference<Outcome> lastOutcome = new AtomicReference<>();

    /** What one scheduled drill attempt did. */
    public record Outcome(Status status, String backupSetId, String detail,
                          Instant at, Duration duration, DrillResult drillResult,
                          String environment) {

        /** Why a drill attempt ended as it did. */
        public enum Status {
            /** The drill ran and every check passed. */
            COMPLETED,
            /** The drill ran and at least one check failed. */
            FAILED,
            /** Refused: a safety precondition was not met. Nothing was destroyed. */
            DRILL_NOT_EXECUTABLE,
            /** Scheduling is disabled. */
            DISABLED
        }

        public boolean isSuccess() {
            return status == Status.COMPLETED;
        }
    }

    /**
     * Everything a drill execution needs.
     *
     * @param backupRoot          where backup sets live, for discovery
     * @param sourceVaultRoot     the vault the backup was taken from
     * @param recoveryTarget      an administrative target for the isolated database
     * @param recoveryDatabase    the isolated database name to create
     * @param vaultRestoreDirectory an isolated directory to restore the vault into
     * @param environmentVariables process environment for tool discovery
     */
    public record DrillJob(Path backupRoot, Path sourceVaultRoot,
                           com.carbonflow.recovery.postgres.PostgreSqlBackupTarget
                                   recoveryTarget,
                           String recoveryDatabase, Path vaultRestoreDirectory,
                           java.util.Map<String, String> environmentVariables) {
    }

    public RecoveryDrillScheduler(DrillPerformer drill,
                                  RecoveryNotificationService notifications,
                                  DrillSchedule schedule, boolean enabled,
                                  String liveDatabaseName, Clock clock) {
        this.drill = drill;
        this.notifications = notifications;
        this.schedule = schedule;
        this.enabled = enabled;
        this.liveDatabaseName = liveDatabaseName;
        this.clock = clock;
    }

    /**
     * Runs the scheduled drill if it is due, the schedule is enabled, and every
     * safety precondition holds.
     *
     * @param job    the drill environment
     * @param grace  lateness allowed before reporting overdue
     */
    public Outcome runScheduled(DrillJob job, Duration grace) {
        if (!enabled) {
            return record(new Outcome(Outcome.Status.DISABLED, null,
                    "drill scheduling is disabled by configuration", clock.instant(),
                    null, null, null));
        }

        if (schedule.isOverdue(clock, grace)) {
            notify(RecoveryNotification.Event.DRILL_OVERDUE, null,
                    "Last drill was " + schedule.lastDrillAt()
                            + "; the quarterly cadence is overdue by more than "
                            + grace.toDays() + " days.",
                    "Run the recovery drill now and record the result.");
        }

        // Safety gate. Any failure here means nothing is destroyed.
        String refusal = checkSafety(job);
        if (refusal != null) {
            log.error("Scheduled recovery drill REFUSED ({}): {}", job.recoveryDatabase(),
                    refusal);
            notify(RecoveryNotification.Event.DRILL_NOT_EXECUTABLE, null, refusal,
                    "Configure an isolated recovery database and vault directory, "
                            + "then rerun the drill. Nothing was destroyed.");
            return record(new Outcome(Outcome.Status.DRILL_NOT_EXECUTABLE, null,
                    refusal, clock.instant(), null, null, null));
        }

        String backupSetId = newestVerifiedSetId(job);
        if (backupSetId == null) {
            String detail = "no verifiable backup set was found under " + job.backupRoot();
            notify(RecoveryNotification.Event.DRILL_NOT_EXECUTABLE, null, detail,
                    "Run and verify a backup before attempting a drill.");
            return record(new Outcome(Outcome.Status.DRILL_NOT_EXECUTABLE, null,
                    detail, clock.instant(), null, null, null));
        }

        Instant startedAt = clock.instant();
        DrillResult result = drill.run(job.backupRoot().resolve(backupSetId), job);
        Duration elapsed = Duration.between(startedAt, clock.instant());

        if (!result.passed()) {
            notify(RecoveryNotification.Event.DRILL_FAILED, backupSetId,
                    "Drill failed: " + String.join("; ", result.findings()),
                    "Investigate the findings, restore the isolated environment, then "
                            + "rerun the drill.");
            return record(new Outcome(Outcome.Status.FAILED, backupSetId,
                    String.join("; ", result.findings()), clock.instant(), elapsed,
                    result, environment()));
        }

        log.info("Scheduled recovery drill of set {} passed in {}s", backupSetId,
                elapsed.toSeconds());
        return record(new Outcome(Outcome.Status.COMPLETED, backupSetId,
                "all drill checks passed", clock.instant(), elapsed, result,
                environment()));
    }

    /**
     * The safety gate.
     *
     * @return {@code null} when the drill may run, otherwise why it must not
     */
    private String checkSafety(DrillJob job) {
        if (job.recoveryTarget() == null) {
            return "no recovery target is configured";
        }
        if (job.recoveryDatabase() == null || job.recoveryDatabase().isBlank()) {
            return "no recovery database name is configured";
        }
        // The critical refusal: never create, drop or restore the live database.
        if (job.recoveryDatabase().equalsIgnoreCase(liveDatabaseName)) {
            return "the configured recovery database is the LIVE application database "
                    + "(" + liveDatabaseName + "); a drill must never target it";
        }
        if (job.vaultRestoreDirectory() == null) {
            return "no vault restore directory is configured";
        }
        if (java.nio.file.Files.exists(job.vaultRestoreDirectory())) {
            return "the vault restore directory already exists ("
                    + job.vaultRestoreDirectory() + "); refusing to overwrite it";
        }
        if (job.backupRoot() == null
                || !java.nio.file.Files.isDirectory(job.backupRoot())) {
            return "the backup root does not exist: " + job.backupRoot();
        }
        return null;
    }

    /** The newest backup set directory name, or {@code null} if none exists. */
    private String newestVerifiedSetId(DrillJob job) {
        try (var entries = java.nio.file.Files.list(job.backupRoot())) {
            return entries
                    .filter(RecoverySetNaming::isBackupSetDirectory)
                    .filter(p -> java.nio.file.Files.isRegularFile(
                            p.resolve(com.carbonflow.recovery.RecoveryManifestWriter
                                    .MANIFEST_FILE_NAME)))
                    .max((a, b) -> Long.compare(modifiedMillis(a), modifiedMillis(b)))
                    .map(p -> p.getFileName().toString())
                    .orElse(null);
        } catch (java.io.IOException e) {
            log.error("Could not list backup root {}: {}", job.backupRoot(), e.getMessage());
            return null;
        }
    }

    /** Last-modified time in milliseconds; unreadable paths sort oldest. */
    private static long modifiedMillis(Path path) {
        try {
            return java.nio.file.Files.getLastModifiedTime(path).toMillis();
        } catch (java.io.IOException e) {
            return Long.MIN_VALUE;
        }
    }

    /** Raises an operator notification, never letting it break the scheduler. */
    private void notify(RecoveryNotification.Event event, String subjectId,
                         String detail, String action) {
        if (notifications == null) {
            return;
        }
        try {
            notifications.notify(new RecoveryNotification(
                    RecoveryNotification.Severity.CRITICAL,
                    RecoveryNotification.Control.RECOVERY_DRILL,
                    event, detail, subjectId, clock.instant(),
                    RecoveryNotification.token(event.name()), action));
        } catch (RuntimeException e) {
            log.error("Failed to raise drill notification: {}", e.getMessage());
        }
    }

    private Outcome record(Outcome outcome) {
        lastOutcome.set(outcome);
        return outcome;
    }

    /** The most recent drill outcome, or {@code null} before the first attempt. */
    public Outcome lastOutcome() {
        return lastOutcome.get();
    }

    /** When the next drill is due. */
    public Instant nextDueAt() {
        return schedule.nextDueAfter(clock);
    }

    /** Whether the quarterly cadence is currently overdue. */
    public boolean isOverdue(Duration grace) {
        return schedule.isOverdue(clock, grace);
    }

    public DrillSchedule schedule() {
        return schedule;
    }

    private String environment() {
        return "zone=" + schedule.zone().getId() + " cadence=quarterly";
    }
}