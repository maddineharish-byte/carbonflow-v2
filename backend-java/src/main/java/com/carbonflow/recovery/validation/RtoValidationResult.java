package com.carbonflow.recovery.validation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Result of an RTO measurement.
 *
 * <h2>When the clock starts and stops</h2>
 * <p>Start is the instant a <b>qualifying failure is formally declared</b> — not
 * the moment an engineer notices it, and not a convenient time chosen to flatter
 * the number. Stop is the instant <b>all</b> approved usable-state conditions are
 * verified, which includes evidence retrieval and tenant isolation. Stopping at
 * "health is UP" would measure a weaker property than the approved RTO
 * describes, which is exactly the error the 2026-09-30 drill record documents
 * (66.4 s to health versus 177.9 s to verified usability).
 *
 * <h2>What a measurement here does not establish</h2>
 * <p>Detection in this validation is <b>manual</b>. The approved RTO clock starts
 * at the failure, so an outage that nobody notices would consume budget invisibly.
 * A measured duration therefore describes the recovery <em>procedure</em>, not the
 * detection-plus-recovery service. This is stated in {@link #limitations()} and
 * never elided.
 */
public record RtoValidationResult(boolean verified, String backupSetId,
                                  Instant failureDeclaredAt, Instant recoveryStartedAt,
                                  Instant databaseRestoredAt, Instant vaultRestoredAt,
                                  Instant applicationStartedAt,
                                  Instant authenticationVerifiedAt,
                                  Instant usableStateVerifiedAt,
                                  Duration totalRecovery, boolean targetMet,
                                  List<String> phasesCompleted, List<String> limitations,
                                  String environment) {

    /** The approved project RTO. */
    public static final Duration APPROVED_RTO = Duration.ofHours(4);

    /**
     * Statements every measurement carries.
     *
     * <p>A local recovery duration is evidence about a procedure on one host and
     * one dataset. It is not a production RTO, and it says nothing about
     * detection time, high availability, or disaster recovery.
     */
    public static List<String> standardLimitations() {
        return List.of(
                "Detection during this validation was MANUAL: the failure was "
                        + "declared by the operator running the test. The approved "
                        + "RTO clock starts at the qualifying failure, so automated "
                        + "monitoring (NOT IMPLEMENTED) is a prerequisite for any "
                        + "production RTO claim.",
                "Measured on a synthetic dataset over loopback against a single "
                        + "PostgreSQL instance. Recovery at production data volume "
                        + "is UNMEASURED.",
                "No high availability or failover was exercised; none exists.",
                "This is NOT a production disaster recovery demonstration and NOT "
                        + "an SLA measurement.",
                "Project-level validation of a procedure. It is not evidence that "
                        + "CarbonFlow meets a contractual recovery commitment.");
    }

    /** {@code true} when the whole recovery fitted inside the approved budget. */
    public boolean withinApprovedBudget() {
        return totalRecovery != null
                && totalRecovery.compareTo(APPROVED_RTO) <= 0;
    }

    /** Human-readable summary. Never a prediction, only a measurement. */
    public String summary() {
        return (verified ? "VERIFIED" : "NOT VERIFIED")
                + " measured=" + (totalRecovery == null ? "n/a"
                        : totalRecovery.toSeconds() + "s")
                + " approvedBudget=" + APPROVED_RTO.toSeconds() + "s"
                + " targetMet=" + targetMet;
    }
}