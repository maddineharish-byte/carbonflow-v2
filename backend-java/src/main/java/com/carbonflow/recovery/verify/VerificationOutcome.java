package com.carbonflow.recovery.verify;

import com.carbonflow.recovery.VerificationStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of verifying one backup set.
 *
 * <p>The four states are kept strictly distinct because collapsing them is how a
 * recovery programme starts lying to itself:
 *
 * <ul>
 *   <li>{@link VerificationStatus#VERIFIED} — every required check passed.</li>
 *   <li>{@link VerificationStatus#FAILED} — a check ran and found a defect.</li>
 *   <li>{@link VerificationStatus#UNVERIFIABLE} — a required check
 *       <b>could not run</b>. Not a pass, and explicitly not the same as
 *       FAILED: "we could not tell" and "we found it broken" are different
 *       facts, and an unusable set must never read as merely suspicious.</li>
 *   <li>{@link VerificationStatus#PENDING} — verification has not been run.</li>
 * </ul>
 *
 * @param status  overall verdict
 * @param checks  every check attempted, in order
 * @param findings operator-readable problems; empty only when VERIFIED
 */
public record VerificationOutcome(VerificationStatus status, List<Check> checks,
                                  List<String> findings) {

    /**
     * One check.
     *
     * @param name    what was checked
     * @param passed  whether it passed
     * @param ran     whether it could be executed at all
     * @param detail  operator-facing detail; never contains a credential
     */
    public record Check(String name, boolean passed, boolean ran, String detail) {

        /** A check that ran and passed. */
        public static Check passed(String name, String detail) {
            return new Check(name, true, true, detail);
        }

        /** A check that ran and found a defect. */
        public static Check failed(String name, String detail) {
            return new Check(name, false, true, detail);
        }

        /** A check that could not be run. */
        public static Check unverifiable(String name, String detail) {
            return new Check(name, false, false, detail);
        }
    }

    public VerificationOutcome {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        checks = checks == null ? List.of() : List.copyOf(checks);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * Builds an outcome from a set of checks.
     *
     * <p>The state is derived, never supplied by the caller. Deriving it here is
     * what makes it impossible to report VERIFIED while a check silently failed.
     */
    public static VerificationOutcome from(List<Check> checks) {
        List<Check> all = new ArrayList<>(checks);

        boolean anyUnverifiable = all.stream().anyMatch(c -> !c.ran());
        boolean anyFailed = all.stream().anyMatch(c -> c.ran() && !c.passed());

        List<String> findings = all.stream()
                .filter(c -> !c.passed())
                .map(c -> c.name() + ": " + c.detail())
                .toList();

        VerificationStatus status;
        if (anyFailed) {
            // A definite defect outranks an inability to check: the set is known
            // broken, whatever else could not be determined.
            status = VerificationStatus.FAILED;
        } else if (anyUnverifiable) {
            status = VerificationStatus.UNVERIFIABLE;
        } else {
            status = VerificationStatus.VERIFIED;
        }
        return new VerificationOutcome(status, all, findings);
    }

    /** Convenience for "no manifest / nothing to verify". */
    public static VerificationOutcome unverifiable(String reason) {
        return new VerificationOutcome(VerificationStatus.UNVERIFIABLE, List.of(),
                List.of(reason));
    }

    /** {@code true} only when every required check passed. */
    public boolean isVerified() {
        return status == VerificationStatus.VERIFIED;
    }

    /**
     * Whether this set may be used for a restore.
     *
     * <p>Only VERIFIED. UNVERIFIABLE is <b>not</b> usable — a set whose
     * integrity could not be established is not a set that passed.
     */
    public boolean isUsableForRestore() {
        return isVerified();
    }

    /** Human-readable summary for logs and drill reports. */
    public String summary() {
        return status + " (" + checks.size() + " checks, " + findings.size() + " findings)";
    }
}