package com.carbonflow.recovery.coordination;

import com.carbonflow.recovery.RecoveryDigest;
import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.VerificationStatus;
import com.carbonflow.recovery.postgres.PostgreSqlBackupResult;
import com.carbonflow.recovery.postgres.PostgreSqlBackupService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.vault.EvidenceVaultIntegrityIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REC-05 — coordinated recovery boundary.
 *
 * <h2>Sequence</h2>
 * <pre>
 *   begin quiesce
 *     → database backup      (databaseSnapshotAt)
 *     → vault backup         (vaultSnapshotAt)
 *     → boundary = earlier(databaseSnapshotAt, vaultSnapshotAt)
 *     → manifest             (written LAST)
 *   end quiesce
 * </pre>
 *
 * <h2>No atomicity is claimed</h2>
 * <p>PostgreSQL and the filesystem share no transaction coordinator. This class
 * produces a <b>coordinated snapshot</b>, not an atomic one. The recovery
 * boundary is deliberately the <b>earlier</b> of the two snapshot instants, so a
 * set can never claim to contain more than the database actually had.
 *
 * <h2>Manifest last is a completeness signal</h2>
 * <p>Because the manifest is written only after every other artefact exists, a
 * set without a manifest is by definition incomplete. That is <b>not</b>
 * cryptographic immutability — a later editor can still change the file — and it
 * is not described as such.
 *
 * <h2>Verification state</h2>
 * <p>The manifest is written with {@link VerificationStatus#PENDING}. REC-06 owns
 * verification; this class performs none, and must never write VERIFIED.
 */
public final class RecoverySetCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RecoverySetCoordinator.class);

    private final PostgreSqlBackupService databaseBackup;
    private final EvidenceVaultBackupService vaultBackup;
    private final RecoveryManifestWriter manifestWriter;
    private final Clock clock;

    public RecoverySetCoordinator(PostgreSqlBackupService databaseBackup,
                                  EvidenceVaultBackupService vaultBackup,
                                  RecoveryManifestWriter manifestWriter,
                                  Clock clock) {
        this.databaseBackup = databaseBackup;
        this.vaultBackup = vaultBackup;
        this.manifestWriter = manifestWriter;
        this.clock = clock;
    }

    /** The completed set. */
    public record CoordinatedBackupResult(boolean success, String backupSetId,
                                          Path setDirectory, RecoveryManifest manifest,
                                          boolean quiesced, String failureReason) {

        static CoordinatedBackupResult failed(String backupSetId, Path setDirectory,
                                              String reason) {
            return new CoordinatedBackupResult(false, backupSetId, setDirectory, null,
                    false, reason);
        }
    }

    /** Inputs for one coordinated backup. */
    public record Request(Path backupRoot, Path vaultRoot,
                          PostgreSqlBackupTarget target, Map<String, String> environment,
                          List<EvidenceVaultBackupService.RequiredFile> requiredEvidence,
                          RecoveryManifest.Application application,
                          RecoveryManifest.Schema schema,
                          String copiedWith) {
    }

    /**
     * Runs the full coordinated backup.
     *
     * <p>Any failure at any stage fails the whole set. A partial set is left on
     * disk without a manifest, which is exactly how an incomplete set is
     * identified — deliberately not deleted here, because REC-07 owns retention
     * and REC-06 may still need to inspect what went wrong.
     */
    public CoordinatedBackupResult run(Request request, QuiesceGuard quiesce) {
        // Placeholder until REC-03 returns the identity of the set it created.
        // Every component must reference ONE set id, so the database backup's
        // id is adopted rather than a second one being minted here: the set
        // directory name, the manifest and any verifier must all agree.
        String backupSetId = "not-yet-created";

        QuiesceGuard guard = quiesce == null ? new QuiesceGuard.NoOp() : quiesce.begin();
        try {
            // ---- 1. database ------------------------------------------------
            var dbOutcome = databaseBackup.backup(request.backupRoot(), request.target(),
                    request.environment());
            if (!dbOutcome.success()) {
                return CoordinatedBackupResult.failed(backupSetId, null,
                        "database backup failed: " + dbOutcome.failureReason());
            }
            PostgreSqlBackupResult db = dbOutcome.result();
            // Adopt the set identity created with the directory, so the manifest
            // describes the set that actually exists on disk.
            backupSetId = db.backupSetId();
            Instant databaseSnapshotAt = db.databaseDumpAt();

            // ---- 2. vault ---------------------------------------------------
            var vaultOutcome = vaultBackup.backup(request.vaultRoot(), db.setDirectory(),
                    request.requiredEvidence(), request.copiedWith());
            if (!vaultOutcome.success()) {
                return CoordinatedBackupResult.failed(backupSetId, db.setDirectory(),
                        "vault backup failed: " + vaultOutcome.failureReason());
            }
            Instant vaultSnapshotAt = vaultOutcome.copiedAt();

            // ---- 3. boundary = EARLIER of the two ---------------------------
            // Never the manifest creation time: by then both snapshots are in
            // the past and claiming that instant would overstate freshness.
            Instant recoveryBoundaryAt = earlier(databaseSnapshotAt, vaultSnapshotAt);

            // ---- 4. verify the checksums we are about to record --------------
            // Cheap, and it means the manifest never records a digest that does
            // not match the bytes on disk.
            String dbDigest = RecoveryDigest.sha256(db.databaseDump());
            String globalsDigest = RecoveryDigest.sha256(db.globalsDump());
            String indexDigest = RecoveryDigest.sha256(vaultOutcome.integrityIndex());

            EvidenceVaultIntegrityIndex index =
                    vaultBackup.readIndex(db.setDirectory());

            // ---- 5. manifest LAST -------------------------------------------
            RecoveryManifest manifest = new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION,
                    backupSetId,
                    clock.instant(),
                    recoveryBoundaryAt,
                    request.application(),
                    new RecoveryManifest.Database(
                            db.databaseDumpRelativePath(),
                            RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM,
                            databaseSnapshotAt,
                            RecoveryManifest.CHECKSUM_ALGORITHM,
                            dbDigest,
                            db.globalsDumpRelativePath(),
                            globalsDigest),
                    new RecoveryManifest.EvidenceVault(
                            EvidenceVaultBackupService.VAULT_DIR_NAME,
                            vaultSnapshotAt,
                            (long) index.fileCount(),
                            index.totalBytes(),
                            RecoveryManifest.CHECKSUM_ALGORITHM,
                            EvidenceVaultBackupService.INTEGRITY_INDEX_NAME,
                            indexDigest,
                            index.copiedWith()),
                    request.schema(),
                    // PENDING always. REC-06 verifies; this layer must not.
                    new RecoveryManifest.Verification(VerificationStatus.PENDING, null, null),
                    buildNotes(guard.isEffective(), databaseSnapshotAt, vaultSnapshotAt));

            Path manifestPath = manifestWriter.write(manifest, db.setDirectory());

            log.info("Coordinated recovery set {} complete: boundary {}, "
                            + "database snapshot {}, vault snapshot {}, quiesced={}",
                    backupSetId, recoveryBoundaryAt, databaseSnapshotAt, vaultSnapshotAt,
                    guard.isEffective());

            return new CoordinatedBackupResult(true, backupSetId, db.setDirectory(),
                    manifest, guard.isEffective(), null);

        } catch (Exception e) {
            log.error("Coordinated recovery set {} failed: {}", backupSetId, e.getMessage());
            return CoordinatedBackupResult.failed(backupSetId, null,
                    "coordinated backup failed: " + e.getMessage());
        } finally {
            // Always released, even on failure: leaving writes blocked would turn
            // a backup problem into an outage.
            guard.close();
        }
    }

    /**
     * The earlier of two instants.
     *
     * <p>Package-visible for direct testing. Choosing the earlier instant is the
     * conservative reading: anything outside the boundary is by definition not
     * committed data, so the set can never over-claim completeness.
     */
    static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * Records how the set was produced, including honest limitations.
     *
     * <p>An unquiesced set is marked as such in the manifest itself rather than
     * being silently treated as consistent.
     */
    private static List<String> buildNotes(boolean quiesced,
                                           Instant databaseSnapshotAt,
                                           Instant vaultSnapshotAt) {
        List<String> notes = new java.util.ArrayList<>();
        notes.add("Database and Evidence Vault are separate stores with no shared "
                + "transaction coordinator; this set is a coordinated snapshot, "
                + "NOT an atomic database/filesystem backup.");
        notes.add("Recovery boundary is the earlier of databaseSnapshotAt ("
                + databaseSnapshotAt + ") and vaultSnapshotAt (" + vaultSnapshotAt + ").");
        notes.add(quiesced
                ? "Writes were quiesced for the duration of the backup."
                : "WARNING: writes were NOT quiesced (no-op guard). A concurrent "
                        + "evidence upload may fall outside this set. Treat the "
                        + "boundary as best-effort, not a strict guarantee.");
        notes.add("Verification status is PENDING: no integrity verification has "
                + "been performed on this set.");
        return List.copyOf(notes);
    }
}