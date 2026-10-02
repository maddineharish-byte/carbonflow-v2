package com.carbonflow.recovery.drill;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Result of one recovery drill.
 *
 * <h2>What a drill does and does not establish</h2>
 * <p>A drill proves that a <b>backup set can be restored</b> in the environment
 * where the drill ran. It does <b>not</b> establish:
 * <ul>
 *   <li>a production RTO — recovery here was performed by an engineer with the
 *       backup store to hand, not by automated detection;</li>
 *   <li>a production RPO — the recovery point is exactly the boundary recorded in
 *       the set, which says nothing about where a real failure would fall;</li>
 *   <li>high availability — nothing here exercises failover, because none
 *       exists;</li>
 *   <li>production disaster recovery — no production infrastructure, dataset
 *       volume or offsite copy is involved.</li>
 * </ul>
 *
 * <p>{@link #limitations()} carries these statements verbatim into the machine
 * readable report so they cannot be separated from the result.
 *
 * @param passed              whether every required check succeeded
 * @param backupSetId         the set that was restored
 * @param environment         where the drill ran
 * @param timings             phase timestamps
 * @param durations           derived phase durations
 * @param checks              every check with its outcome
 * @param findings            problems found, empty when passed
 * @param limitations         what this drill explicitly does not prove
 */
public record DrillResult(boolean passed, String backupSetId, Environment environment,
                          Timings timings, Durations durations,
                          List<Check> checks, List<String> findings,
                          List<String> limitations) {

    /**
     * Conditions under which the drill ran.
     *
     * <p>Mandatory. A recovery number without its conditions is not evidence:
     * the same drill on a production-scale dataset and real infrastructure is a
     * different claim entirely.
     */
    public record Environment(String postgresqlVersion, String javaVersion,
                              String hostOs, boolean loopbackOnly,
                              boolean highAvailability, String datasetNote) {
    }

    /** Phase timestamps, recorded as the drill actually ran. */
    public record Timings(Instant drillStartedAt, Instant databaseRestoreStartedAt,
                          Instant databaseRestoreCompletedAt,
                          Instant vaultRestoreStartedAt, Instant vaultRestoreCompletedAt,
                          Instant applicationStartedAt, Instant authenticationVerifiedAt,
                          Instant evidenceVerifiedAt, Instant drillCompletedAt) {
    }

    /** Durations derived from {@link Timings}. */
    public record Durations(Duration totalRecovery, Duration databaseRestore,
                            Duration vaultRestore) {

        /** Derives the durations, or nulls when the instants are absent. */
        static Durations between(Timings t) {
            return new Durations(
                    duration(t.drillStartedAt(), t.drillCompletedAt()),
                    duration(t.databaseRestoreStartedAt(), t.databaseRestoreCompletedAt()),
                    duration(t.vaultRestoreStartedAt(), t.vaultRestoreCompletedAt()));
        }

        private static Duration duration(Instant from, Instant to) {
            return from == null || to == null ? null : Duration.between(from, to);
        }
    }

    /** One verification step. */
    public record Check(String name, boolean passed, String detail) {

        public static Check passed(String name, String detail) {
            return new Check(name, true, detail);
        }

        public static Check failed(String name, String detail) {
            return new Check(name, false, detail);
        }

        /** A check that could not be performed, so its outcome is unknown. */
        public static Check unverifiableName(String name, String detail) {
            return new Check(name, false, detail);
        }
    }

    /** Statements this drill does not prove. Never omitted. */
    public static List<String> standardLimitations() {
        return List.of(
                "A local drill demonstrates the recovery PROCEDURE, not production RTO.",
                "Detection during this drill was manual. The approved RTO clock starts "
                        + "at a qualifying failure, and automated detection is NOT IMPLEMENTED.",
                "No high availability or failover was exercised; none exists in "
                        + "CarbonFlow today.",
                "The dataset is synthetic and small. Recovery at production volume "
                        + "is UNMEASURED.",
                "No offsite or cross-region copy is involved.",
                "This is NOT a production disaster recovery demonstration.");
    }

    /** Human-readable summary. */
    public String summary() {
        return (passed ? "PASS" : "FAIL")
                + " set=" + backupSetId
                + " checks=" + checks.size()
                + " findings=" + findings.size()
                + (durations.totalRecovery() == null ? ""
                   : " total=" + durations.totalRecovery().toSeconds() + "s");
    }
}