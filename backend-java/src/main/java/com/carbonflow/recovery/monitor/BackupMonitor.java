package com.carbonflow.recovery.monitor;

import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.RecoverySetNaming;
import com.carbonflow.recovery.VerificationStatus;
import com.carbonflow.recovery.verify.BackupVerifier;
import com.carbonflow.recovery.verify.VerificationOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REC-08 — backup monitoring.
 *
 * <p>Assesses backup health from the backup root plus the outcome of the most
 * recent run, and records a machine-readable status. This is the minimum useful
 * model for the approved monitoring requirement: it establishes whether backups
 * are happening, whether they are fresh enough to satisfy the 1-hour RPO window,
 * and whether they are intact.
 *
 * <h2>Explicit limitation — read before relying on this</h2>
 * <p>Monitoring here <b>detects</b>. It does <b>not notify</b>. There is no email,
 * SMS or pager transport, so a condition discovered at 03:00 is written to a
 * status file and to the log, and nothing more. The approved monitoring
 * requirement is therefore only partially satisfied: detection is implemented,
 * and human notification is a documented integration point that has not been
 * built. Nothing in this class should be described as alerting a human.
 *
 * <h2>Not an RPO compliance statement</h2>
 * <p>{@link BackupHealth#RPO_AT_RISK} describes how much time has passed since
 * the last success. It says nothing about whether an RPO would actually be met,
 * because that depends on where a failure falls relative to the boundary. REC-11
 * measures that; this class never does.
 */
public final class BackupMonitor {

    private final Path backupRoot;
    private final Clock clock;
    private final BackupVerifier verifier;
    private final Map<String, String> environment;

    private final AtomicReference<RunState> lastRun = new AtomicReference<>();
    private final AtomicReference<Instant> runningSince = new AtomicReference<>();

    /** What the coordinator reported for the most recent attempt. */
    public record RunState(boolean success, String backupSetId, Instant at,
                           String failureReason) {
    }

    public BackupMonitor(Path backupRoot, Clock clock, BackupVerifier verifier,
                         Map<String, String> environment) {
        this.backupRoot = backupRoot;
        this.clock = clock;
        this.verifier = verifier;
        this.environment = environment == null ? Map.of() : environment;
    }

    /** Records that a backup attempt has begun. */
    public void recordRunStarted() {
        runningSince.set(clock.instant());
    }

    /** Records the outcome of a backup attempt. */
    public void recordRunFinished(boolean success, String backupSetId, String failureReason) {
        runningSince.set(null);
        lastRun.set(new RunState(success, backupSetId, clock.instant(), failureReason));
    }

    /**
     * Assesses current health.
     *
     * <p>Order matters: a failed most-recent run outranks staleness, because a
     * fresh-looking set that followed a failure is misleading. An unverifiable
     * newest set outranks staleness too, since its age is not trustworthy.
     */
    public BackupHealth.Status assess() {
        Instant now = clock.instant();
        List<String> alerts = new ArrayList<>();

        RunState run = lastRun.get();
        Optional<NewestSet> newest = findNewestSet();

        // 1. A run in progress.
        if (runningSince.get() != null) {
            Duration elapsed = Duration.between(runningSince.get(), now);
            if (elapsed.compareTo(BackupHealth.RPO) < 0) {
                return new BackupHealth.Status(BackupHealth.RUNNING, now, null, elapsed,
                        null, "a backup is currently running", alerts);
            }
            // A run that has exceeded the RPO window is itself a problem.
            alerts.add("BACKUP_OVERRUN: a backup has been running for "
                    + elapsed.toMinutes() + " minutes, beyond the "
                    + BackupHealth.RPO.toMinutes() + "-minute RPO window");
        }

        // 2. The most recent attempt failed.
        if (run != null && !run.success()) {
            return new BackupHealth.Status(BackupHealth.FAILED, now, null, null,
                    run.backupSetId(),
                    "the most recent backup attempt failed: " + run.failureReason(),
                    withAlert(alerts, "BACKUP_FAILED"));
        }

        // 3. Nothing on disk at all.
        if (newest.isEmpty()) {
            return new BackupHealth.Status(BackupHealth.STALE, now, null, null, null,
                    "no backup set exists in the backup root",
                    withAlert(alerts, "BACKUP_MISSING"));
        }

        NewestSet set = newest.get();
        Duration age = Duration.between(set.createdAt(), now);

        // 4. Freshness, evaluated before verification cost.
        if (age.compareTo(BackupHealth.RPO) >= 0) {
            alerts.add("BACKUP_STALE: newest set is " + age.toMinutes()
                    + " minutes old, beyond the " + BackupHealth.RPO.toMinutes()
                    + "-minute RPO window, so the RPO can no longer be satisfied");
            return new BackupHealth.Status(BackupHealth.STALE, now, set.createdAt(), age,
                    set.backupSetId(),
                    "newest backup is older than the RPO window", alerts);
        }
        if (age.compareTo(BackupHealth.RPO_AT_RISK_THRESHOLD) >= 0) {
            alerts.add("RPO_AT_RISK: newest set is " + age.toMinutes()
                    + " minutes old, past the "
                    + BackupHealth.RPO_AT_RISK_THRESHOLD.toMinutes()
                    + "-minute early-warning threshold (the RPO is "
                    + BackupHealth.RPO.toMinutes() + " minutes)");
        }

        // 5. Integrity of the newest set.
        VerificationOutcome outcome;
        try {
            outcome = verifier.verify(set.directory());
        } catch (RuntimeException e) {
            alerts.add("UNVERIFIABLE: verification could not run: " + e.getMessage());
            return new BackupHealth.Status(BackupHealth.UNVERIFIABLE, now, set.createdAt(),
                    age, set.backupSetId(),
                    "verification could not be performed", alerts);
        }

        if (outcome.status() == VerificationStatus.UNVERIFIABLE) {
            alerts.add("UNVERIFIABLE: " + String.join("; ", outcome.findings()));
            return new BackupHealth.Status(BackupHealth.UNVERIFIABLE, now, set.createdAt(),
                    age, set.backupSetId(),
                    "the newest set could not be verified", alerts);
        }
        if (outcome.status() != VerificationStatus.VERIFIED) {
            alerts.add("BACKUP_FAILED: the newest set is intact=false: "
                    + String.join("; ", outcome.findings()));
            return new BackupHealth.Status(BackupHealth.FAILED, now, set.createdAt(), age,
                    set.backupSetId(),
                    "the newest set failed verification: "
                            + String.join("; ", outcome.findings()), alerts);
        }

        return new BackupHealth.Status(BackupHealth.HEALTHY, now, set.createdAt(), age,
                set.backupSetId(), "newest set verified and within the RPO window",
                alerts);
    }

    /** A discovered set directory and the creation instant its manifest records. */
    private record NewestSet(Path directory, String backupSetId, Instant createdAt) {
    }

    /**
     * Finds the newest set by manifest {@code createdAt}.
     *
     * <p>Ordered by the recorded instant rather than the filesystem, matching
     * REC-07's rationale.
     */
    private Optional<NewestSet> findNewestSet() {
        if (!Files.isDirectory(backupRoot)) {
            return Optional.empty();
        }
        RecoveryManifestWriter writer = new RecoveryManifestWriter(Clock.systemUTC());
        NewestSet newest = null;
        try (var entries = Files.list(backupRoot)) {
            for (Path entry : entries.toList()) {
                if (!RecoverySetNaming.isBackupSetDirectory(entry)) {
                    continue;
                }
                if (!Files.isRegularFile(entry.resolve(
                        RecoveryManifestWriter.MANIFEST_FILE_NAME))) {
                    continue;
                }
                try {
                    var manifest = writer.readIfPresent(entry);
                    if (manifest == null) {
                        continue;
                    }
                    if (newest == null
                            || manifest.createdAt().isAfter(newest.createdAt())) {
                        newest = new NewestSet(entry, manifest.backupSetId(),
                                manifest.createdAt());
                    }
                } catch (RuntimeException e) {
                    // An unreadable manifest cannot be dated, so it cannot be
                    // the newest; skip rather than fail the whole assessment.
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(newest);
    }

    private static List<String> withAlert(List<String> alerts, String alert) {
        alerts.add(alert);
        return alerts;
    }

    /**
     * Renders the status as a machine-readable JSON document.
     *
     * <p>A status file is what a human or a future notifier reads. Writing it is
     * <b>not</b> notification — nothing watches this file yet.
     */
    public String toJson(BackupHealth.Status status) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310
                            .JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature
                            .WRITE_DATES_AS_TIMESTAMPS)
                    .enable(com.fasterxml.jackson.databind.SerializationFeature
                            .INDENT_OUTPUT);
            var root = mapper.createObjectNode();
            root.put("health", status.health().name());
            root.put("evaluatedAt", status.evaluatedAt().toString());
            root.put("lastSuccessAt", status.lastSuccessAt() == null
                    ? null : status.lastSuccessAt().toString());
            root.put("ageSeconds", status.age() == null ? null : status.age().toSeconds());
            root.put("backupSetId", status.backupSetId());
            root.put("reason", status.reason());
            root.put("rpoSeconds", BackupHealth.RPO.toSeconds());
            root.put("rpoAtRiskThresholdSeconds",
                    BackupHealth.RPO_AT_RISK_THRESHOLD.toSeconds());
            root.put("notificationDelivered", false);
            root.put("notificationLimitation",
                    "This monitor detects and records state only. It has no email, "
                            + "SMS or pager transport, so no human is notified "
                            + "automatically. Human notification is a documented "
                            + "integration point, not an implemented feature.");
            var alerts = root.putArray("alerts");
            status.alerts().forEach(alerts::add);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"health\":\"" + status.health() + "\",\"error\":\"status "
                    + "could not be rendered\"}";
        }
    }

    /** {@code true} when a notification transport exists. It does not. */
    public boolean hasNotificationTransport() {
        return false;
    }
}