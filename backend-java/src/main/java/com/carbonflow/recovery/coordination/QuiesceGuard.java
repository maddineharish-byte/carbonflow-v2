package com.carbonflow.recovery.coordination;

/**
 * Controls application writes for the duration of a backup.
 *
 * <h2>What this is for</h2>
 * <p>PostgreSQL and the Evidence Vault share no transaction coordinator. During
 * a backup an evidence upload could commit its database row while the vault copy
 * had already passed the file's location, producing a manifest that references
 * bytes the set does not contain.
 *
 * <p>{@code docs/RECOVERY-CONTROLS-DESIGN.md} §5.2 resolves this by quiescing
 * the application for the vault copy: no writes, so the copy is complete.
 *
 * <h2>Honest limitations — read before relying on this</h2>
 * <p>The application is a single instance, so quiescing is achievable. CarbonFlow
 * nevertheless has <b>no maintenance-mode flag today</b>, and this interface
 * does not invent one:
 * <ul>
 *   <li>The production implementation supplied by REC-05's caller is a
 *       <b>no-op</b> unless an operator provides a real quiesce mechanism.</li>
 *   <li>With a no-op, the backup is <b>not</b> quiesced. The recovery boundary
 *       is then the earlier of the two snapshot instants, and a concurrent upload
 *       may legitimately land outside the set. That is recorded honestly in the
 *       set rather than hidden.</li>
 * </ul>
 *
 * <p>A no-op is provided so the mechanism is explicit and testable rather than
 * implied by the absence of a call. Implementing a genuine maintenance mode
 * would require modifying application request handling, which is out of scope
 * for the recovery phases and would touch business logic that must stay frozen.
 *
 * <p>No distributed lock, no database-wide destructive lock, and no fake
 * quiescence is introduced. {@link #isEffective()} exists precisely so the
 * coordinator can refuse to describe an unquiesced backup as fully consistent.
 */
public interface QuiesceGuard extends AutoCloseable {

    /**
     * Begins controlling writes.
     *
     * @return a guard to hold for the duration; closing it restores normal writes
     */
    QuiesceGuard begin();

    /**
     * Whether this guard actually prevents concurrent writes.
     *
     * <p>{@code false} for a no-op. The coordinator records this so a set that
     * was not quiesced is distinguishable from one that was.
     */
    boolean isEffective();

    /** Restores normal operation. Must be idempotent and must not throw. */
    @Override
    void close();

    /**
     * A guard that controls nothing.
     *
     * <p>Explicit rather than a silent default: {@link #isEffective()} returns
     * {@code false} so no caller can mistake it for real quiescence.
     */
    final class NoOp implements QuiesceGuard {

        @Override
        public QuiesceGuard begin() {
            return this;
        }

        @Override
        public boolean isEffective() {
            return false;
        }

        @Override
        public void close() {
            // nothing to restore
        }
    }
}