package com.carbonflow.recovery;

/**
 * Lifecycle state of a recovery set's integrity checking.
 *
 * <p>REC-02 records this state; it does not advance it. Only REC-06 (backup
 * verification) may move a set out of {@link #PENDING}.
 *
 * <p>The ordering below is the failure-first ordering: a set that has been
 * checked and found broken must sort below one that has never been checked,
 * so that {@code max(status)} over a set of states cannot quietly report
 * {@link #PENDING} as if it were a clean result.
 */
public enum VerificationStatus {

    /**
     * Written by the manifest writer and never advanced by it.
     *
     * <p>A manifest that has merely been <em>written</em> has not been checked.
     * Anything that could be tested for must therefore be reported here rather
     * than as {@link #VERIFIED}.
     */
    PENDING,

    /** REC-06 confirmed every integrity check passed. Not writable by REC-02. */
    VERIFIED,

    /** A check ran and found the set broken. */
    FAILED,

    /**
     * Integrity could not be established at all — a missing artefact, an
     * unparseable manifest, or a check that could not run.
     *
     * <p>Distinct from {@link #FAILED}: "broken" and "unknown" must never be
     * collapsed, because a set that cannot be checked is not a set that passed.
     */
    UNVERIFIABLE
}