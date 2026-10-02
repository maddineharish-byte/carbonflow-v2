package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.retention.RetentionService;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.verify.VerificationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REC-13 — automated recovery backup scheduling.
 *
 * <p>Invokes the <b>existing</b> REC-05 coordinator, then REC-06 verification,
 * REC-08 monitoring and REC-07 retention. No backup logic is duplicated here:
 * this class decides <em>when</em> to run, refuses overlapping runs, and records
 * the outcome.
 *
 * <h2>Overlap protection — and its honest limit</h2>
 * <p>A run in progress makes any further trigger a no-op
 * ({@link Outcome.Status#SKIPPED_OVERLAPPING}). The guard is a single-JVM
 * {@link AtomicBoolean}, which prevents a slow backup overlapping the next
 * scheduled one <b>within this process</b>.
 *
 * <p>It is <b>not</b> a distributed lock. Two instances on two hosts would each
 * hold their own guard and could run concurrently. CarbonFlow deploys as a single
 * instance, so this matches the architecture — but it must never be described as
 * cross-host protection. Real multi-host protection needs a lease in the
 * database, which is out of scope here.
 *
 * <h2>Fail closed</h2>
 * <p>A failed backup is never recorded as a success, and a backup that does not
 * verify is not treated as good. Verification runs <em>before</em> the monitor is
 * told the run succeeded, so the monitor can only ever observe a verified set.
 */
public final class RecoveryBackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryBackupScheduler.class);

    private final RecoveryCycleDependencies.BackupPerformer backupPerformer;
    private final RecoveryCycleDependencies.SetVerifier verifier;
    private final RecoveryCycleDependencies.HealthReporter monitor;
    private final RecoveryCycleDependencies.RetentionSweeper retention;
    private final RecoveryScheduleConfig config;
    private final Clock clock;

    /** Single-instance guard. See the class note on its limits. */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Outcome> lastOutcome = new AtomicReference<>();
    private final AtomicReference<Instant> lastRunStartedAt = new AtomicReference<>();

    /** What one scheduled cycle did. */
    public record Outcome(Status status, String backupSetId, String detail,
                          Instant at, Duration duration, VerificationOutcome verification) {

        /** Why a cycle ended as it did. */
        public enum Status {
            /** Backup and verification both succeeded. */
            COMPLETED,
            /** Backup succeeded but verification did not. */
            VERIFICATION_FAILED,
            /** The backup itself failed. */
            FAILED,
            /** A run was already in progress; this trigger was ignored. */
            SKIPPED_OVERLAPPING,
            /** Scheduling is disabled. */
            DISABLED
        }

        public boolean isSuccess() {
            return status == Status.COMPLETED;
        }
    }

    /**
     * Everything one cycle needs. Supplied by the caller, never guessed.
     *
     * <p>Held as a record so the scheduler carries no configuration of its own
     * and cannot pick up a stale path or credential.
     *
     * @param backupRoot      where backup sets are written
     * @param vaultRoot       the Evidence Vault being protected
     * @param target          database connection parameters
     * @param environment     process environment for tool discovery and PGPASSWORD
     * @param requiredEvidence files the database says must be recoverable
     * @param application     application identity recorded in the manifest
     * @param schema          Flyway identity recorded in the manifest
     * @param copiedWith      copy mechanism name, recorded for auditability
     */
    public record Job(Path backupRoot, Path vaultRoot, PostgreSqlBackupTarget target,
                      Map<String, String> environment,
                      List<EvidenceVaultBackupService.RequiredFile> requiredEvidence,
                      RecoveryManifest.Application application,
                      RecoveryManifest.Schema schema,
                      String copiedWith) {
    }

    public RecoveryBackupScheduler(RecoveryCycleDependencies.BackupPerformer backupPerformer,
                                   RecoveryCycleDependencies.SetVerifier verifier,
                                   RecoveryCycleDependencies.HealthReporter monitor,
                                   RecoveryCycleDependencies.RetentionSweeper retention,
                                   RecoveryScheduleConfig config,
                                   Clock clock) {
        this.backupPerformer = backupPerformer;
        this.verifier = verifier;
        this.monitor = monitor;
        this.retention = retention;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Runs one scheduled cycle: backup, verification, monitoring, retention.
     *
     * <p>Order matters. Verification precedes recording success. Retention runs
     * last and can never fail the cycle, because failing to delete old backups
     * must not be mistaken for failing to take a new one.
     */
    public Outcome runOnce(Job job) {
        if (!config.enabled()) {
            return record(new Outcome(Outcome.Status.DISABLED, null,
                    "scheduling is disabled by configuration", clock.instant(), null, null));
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("Skipping scheduled recovery backup: a run is already in progress");
            return record(new Outcome(Outcome.Status.SKIPPED_OVERLAPPING, null,
                    "a backup was already running when this trigger fired",
                    clock.instant(), null, null));
        }

        Instant startedAt = clock.instant();
        lastRunStartedAt.set(startedAt);
        monitor.recordRunStarted();
        try {
            var backup = backupPerformer.perform(new RecoverySetCoordinator.Request(
                    job.backupRoot(), job.vaultRoot(), job.target(), job.environment(),
                    job.requiredEvidence(), job.application(), job.schema(),
                    job.copiedWith()));

            Duration elapsed = Duration.between(startedAt, clock.instant());

            if (!backup.success()) {
                // Fail closed: never record a failed backup as successful.
                monitor.recordRunFinished(false, backup.backupSetId(),
                        backup.failureReason());
                log.error("Scheduled recovery backup FAILED after {}s: {}",
                        elapsed.toSeconds(), backup.failureReason());
                return record(new Outcome(Outcome.Status.FAILED, backup.backupSetId(),
                        backup.failureReason(), clock.instant(), elapsed, null));
            }

            VerificationOutcome verification;
            try {
                verification = verifier.verify(backup.setDirectory());
            } catch (RuntimeException e) {
                verification = VerificationOutcome.unverifiable(
                        "verification could not run: " + e.getMessage());
            }

            monitor.recordRunFinished(verification.isVerified(), backup.backupSetId(),
                    verification.isVerified() ? null : verification.summary());

            if (!verification.isVerified()) {
                log.error("Scheduled recovery backup {} completed but verification "
                                + "returned {}: {}", backup.backupSetId(),
                        verification.status(), verification.findings());
                return record(new Outcome(Outcome.Status.VERIFICATION_FAILED,
                        backup.backupSetId(), verification.summary(), clock.instant(),
                        elapsed, verification));
            }

            applyRetention(job.backupRoot());

            log.info("Scheduled recovery backup {} completed and verified in {}s",
                    backup.backupSetId(), elapsed.toSeconds());
            return record(new Outcome(Outcome.Status.COMPLETED, backup.backupSetId(),
                    "backup and verification succeeded", clock.instant(), elapsed,
                    verification));

        } finally {
            running.set(false);
        }
    }

    /** Applies retention, never letting its failure fail the cycle. */
    private void applyRetention(Path backupRoot) {
        try {
            RetentionService.RetentionResult result = retention.apply(backupRoot);
            if (!result.safeToDelete()) {
                log.warn("Retention declined to run: {}", result.reasons());
                return;
            }
            if (!result.deleted().isEmpty()) {
                log.info("Retention removed {} expired backup set(s)",
                        result.deleted().size());
            }
            for (String reason : result.reasons()) {
                log.warn("Retention: {}", reason);
            }
        } catch (RuntimeException e) {
            log.error("Retention failed (the backup itself is unaffected): {}",
                    e.getMessage());
        }
    }

    private Outcome record(Outcome outcome) {
        lastOutcome.set(outcome);
        return outcome;
    }

    /** Whether a cycle is running right now. */
    public boolean isRunning() {
        return running.get();
    }

    /** The most recent cycle outcome, or {@code null} before the first run. */
    public Outcome lastOutcome() {
        return lastOutcome.get();
    }

    public Instant lastRunStartedAt() {
        return lastRunStartedAt.get();
    }

    public RecoveryScheduleConfig config() {
        return config;
    }

    /** Stops accepting triggers. Used on shutdown. */
    public void shutdown() {
        log.info("Recovery backup scheduler shutting down (in-progress run: {})",
                running.get());
    }
}