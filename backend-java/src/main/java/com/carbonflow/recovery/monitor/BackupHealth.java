package com.carbonflow.recovery.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * REC-08 — backup health, derived from what is actually on disk.
 *
 * <h2>Detecting is not notifying</h2>
 * <p>This class produces a state and a reason. It does <b>not</b> send an email,
 * an SMS or a page, and nothing here may be described as though it does. CarbonFlow
 * has no notification transport, so the alert is recorded for a human or a future
 * integration to pick up. The approved monitoring requirement is therefore
 * <b>partially</b> met: detection is implemented, delivery is documented but not
 * implemented.
 *
 * <h2>Thresholds</h2>
 * <p>{@link #RPO} is the approved 1-hour target. {@link #RPO_AT_RISK} is an
 * early-warning at 75% of that window and is <b>not</b> a second objective.
 *
 * <p>Reporting {@code RPO_AT_RISK} is a statement about the clock, never about
 * compliance. An RPO is met only when a failure occurs and the boundary is
 * inside the window — REC-11 measures that.
 */
public enum BackupHealth {

    /** A verified set exists inside the window. */
    HEALTHY,

    /** A backup is executing now. */
    RUNNING,

    /** The most recent attempt failed. */
    FAILED,

    /** The newest set is older than the RPO window. */
    STALE,

    /** Inside the window but past the early-warning threshold. */
    RPO_AT_RISK,

    /** A set exists but its integrity could not be established. */
    UNVERIFIABLE;

    /** The approved RPO, and the window a backup must stay inside. */
    public static final Duration RPO = Duration.ofHours(1);

    /** Early warning at 75% of the RPO window. Not a requirement. */
    public static final Duration RPO_AT_RISK_THRESHOLD = Duration.ofMinutes(45);

    /** A health state plus the evidence behind it. */
    public record Status(BackupHealth health, Instant evaluatedAt, Instant lastSuccessAt,
                         Duration age, String backupSetId, String reason,
                         List<String> alerts) {

        public Status {
            if (health == null) {
                throw new IllegalArgumentException("health is required");
            }
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
        }

        /** {@code true} only for a genuinely healthy, verified, fresh set. */
        public boolean isHealthy() {
            return health == HEALTHY;
        }

        /** Human-readable line for logs and status files. */
        public String summary() {
            return health + (age == null ? "" : " (age " + age.toMinutes() + "m)")
                    + (backupSetId == null ? "" : " set=" + backupSetId)
                    + (reason == null ? "" : ": " + reason);
        }
    }
}