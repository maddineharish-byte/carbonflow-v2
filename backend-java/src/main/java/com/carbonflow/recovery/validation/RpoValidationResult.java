package com.carbonflow.recovery.validation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Result of an RPO measurement.
 *
 * <h2>RPO is not restore duration</h2>
 * <p>The Recovery Point Objective bounds how much <b>committed data</b> may be
 * lost, measured against the declared recovery boundary. It has nothing to do
 * with how long a restore takes: a restore can be instant and still lose two
 * hours of data if the boundary is two hours old. {@link #committedLossSeconds}
 * is therefore the measured quantity, and {@link #restoreDuration} is recorded
 * separately so the two can never be confused.
 *
 * <h2>What a pass means</h2>
 * <p>Committed loss of zero proves that everything committed at or before the
 * boundary survived. It does <b>not</b> prove the RPO is met in general: the
 * measurement is taken at one point in the backup cycle. Worst-case loss is
 * bounded by the backup interval, not by the measured value, and
 * {@link #worstCaseLossBoundSeconds} records that honestly.
 */
public record RpoValidationResult(boolean verified, String backupSetId,
                                  Instant recoveryBoundaryAt, Instant failureDeclaredAt,
                                  Duration committedLoss, Duration worstCaseLossBound,
                                  Duration restoreDuration, boolean targetMet,
                                  List<MarkerOutcome> markers, List<String> limitations,
                                  String environment) {

    /**
     * One identifiable marker row written before or after the boundary.
     *
     * @param markerId  the value written into the data
     * @param writtenAt when it was committed
     * @param sideOfBoundary whether it fell before or after the boundary
     * @param survived  whether it was present after the restore
     * @param correctlyLost whether its absence was the expected outcome
     */
    public record MarkerOutcome(String markerId, Instant writtenAt,
                                String sideOfBoundary, boolean survived,
                                boolean correctlyLost) {

        /** A marker that should have survived and did. */
        public static MarkerOutcome retained(String id, Instant at) {
            return new MarkerOutcome(id, at, "BEFORE_BOUNDARY", true, false);
        }

        /**
         * A marker written after the boundary and therefore expected to be lost.
         *
         * <p>Losing it is correct behaviour, not a failure. Only a marker
         * committed <em>at or before</em> the boundary being lost would indicate
         * data loss inside the objective.
         */
        public static MarkerOutcome lostAfterBoundary(String id, Instant at) {
            return new MarkerOutcome(id, at, "AFTER_BOUNDARY", false, true);
        }
    }

    /** The approved project RPO. */
    public static final Duration APPROVED_RPO = Duration.ofHours(1);

    /**
     * Every result carries these limitations.
     *
     * <p>An RPO measurement on one host, one dataset and one point in the backup
     * cycle is evidence, not a guarantee.
     */
    public static List<String> standardLimitations() {
        return List.of(
                "RPO is bounded by the backup INTERVAL, not by this measurement. "
                        + "Worst-case loss occurs for a failure immediately before "
                        + "the next scheduled backup.",
                "This measures committed-data loss at one point in the backup "
                        + "cycle on a synthetic local dataset.",
                "The measured boundary loss is NOT evidence that the RPO is met in "
                        + "production, and says nothing about detection time.",
                "Restore duration is recorded separately and is NOT an RPO figure.");
    }

    /** Human-readable summary. */
    public String summary() {
        return (verified ? "VERIFIED" : "NOT VERIFIED")
                + " boundary=" + recoveryBoundaryAt
                + " committedLoss=" + (committedLoss == null ? "n/a"
                        : committedLoss.toSeconds() + "s")
                + " targetMet=" + targetMet;
    }
}