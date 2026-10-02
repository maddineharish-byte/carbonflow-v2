package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.retention.RetentionService;
import com.carbonflow.recovery.verify.BackupVerifier;
import com.carbonflow.recovery.verify.VerificationOutcome;

import java.nio.file.Path;

/**
 * The collaborators {@link RecoveryBackupScheduler} depends on, as narrow
 * contracts.
 *
 * <h2>Why this seam exists</h2>
 * <p>The scheduler's real collaborators — {@link RecoverySetCoordinator},
 * {@link BackupVerifier}, the monitor and {@link RetentionService} — are all
 * {@code final} classes that do real work against a live database and the
 * filesystem. Substituting them in a test would mean either weakening them to be
 * subclassable or faking the thing under test.
 *
 * <p>Declaring the four contracts here keeps the scheduler honest and testable:
 * production passes the real classes, and a test can supply a collaborator that
 * fails, blocks, or reports a specific state without a database anywhere in sight.
 * The behaviour under test — overlap refusal, fail-closed recording,
 * verification-before-success, retention ordering — is policy, not I/O, and
 * policy deserves to be testable directly.
 *
 * <p>Every contract is implemented by an existing production class, so this adds
 * no behaviour and no alternative implementation.
 */
public final class RecoveryCycleDependencies {

    private RecoveryCycleDependencies() {
    }

    /** Performs one coordinated backup (REC-05). */
    @FunctionalInterface
    public interface BackupPerformer {
        RecoverySetCoordinator.CoordinatedBackupResult perform(
                RecoverySetCoordinator.Request request);
    }

    /** Verifies a completed set (REC-06). */
    @FunctionalInterface
    public interface SetVerifier {
        VerificationOutcome verify(Path setDirectory);
    }

    /**
     * Records and reports backup health (REC-08).
     *
     * <p>Separate write and read methods because the scheduler only writes, while
     * tests and REC-14 read.
     */
    public interface HealthReporter {

        void recordRunStarted();

        void recordRunFinished(boolean success, String backupSetId, String reason);

        /** Current health, for assessment. */
        com.carbonflow.recovery.monitor.BackupHealth.Status assess();
    }

    /** Applies retention (REC-07). */
    @FunctionalInterface
    public interface RetentionSweeper {
        RetentionService.RetentionResult apply(Path backupRoot);
    }
}