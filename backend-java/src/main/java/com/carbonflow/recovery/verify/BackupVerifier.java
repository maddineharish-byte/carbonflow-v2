package com.carbonflow.recovery.verify;

import com.carbonflow.recovery.RecoveryDigest;
import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.RecoverySetGuard;
import com.carbonflow.recovery.exec.SafeProcessRunner;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import com.carbonflow.recovery.vault.EvidencePathGuard;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.vault.EvidenceVaultIntegrityIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REC-06 — backup verification.
 *
 * <h2>Existence is not verification</h2>
 * <p>A file being present proves nothing. This class establishes that a set is
 * <em>structurally readable and cryptographically consistent</em>:
 * <ul>
 *   <li>the archive's internal structure parses ({@code pg_restore --list}), which
 *       is the cheapest proof that the dump is a real archive rather than bytes;</li>
 *   <li>every recorded digest matches the bytes on disk;</li>
 *   <li>every vault file in the integrity index exists with the recorded size and
 *       digest;</li>
 *   <li>the manifest itself is internally coherent.</li>
 * </ul>
 *
 * <h2>Two distinct negative states</h2>
 * <p>A check that <b>found a defect</b> yields FAILED. A check that
 * <b>could not run</b> yields UNVERIFIABLE. Neither is a pass, and
 * {@link VerificationOutcome#isUsableForRestore()} is true only for VERIFIED.
 *
 * <h2>Never restores into the live database</h2>
 * <p>{@code pg_restore --list} reads the archive structure without restoring it,
 * so daily verification needs no scratch database. Full restore rehearsal belongs
 * to REC-10/REC-12 and is never performed by this class.
 */
public final class BackupVerifier {

    private final RecoveryManifestWriter manifestWriter;
    private final Map<String, String> environment;

    /**
     * @param manifestWriter reader for the set manifest
     * @param environment    process environment, used to locate {@code pg_restore}
     */
    public BackupVerifier(RecoveryManifestWriter manifestWriter,
                          Map<String, String> environment) {
        this.manifestWriter = manifestWriter;
        this.environment = environment == null ? Map.of() : environment;
    }

    /**
     * Verifies one backup set.
     *
     * @param setDirectory the set to verify
     */
    public VerificationOutcome verify(Path setDirectory) {
        List<VerificationOutcome.Check> checks = new ArrayList<>();

        // ---- 1. manifest ---------------------------------------------------
        RecoveryManifest manifest;
        Path manifestPath = setDirectory.resolve(RecoveryManifestWriter.MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            // No manifest means the set was never finalised: incomplete, not
            // corrupt, and certainly not usable.
            checks.add(VerificationOutcome.Check.unverifiable("manifest.present",
                    "manifest.json is absent, so this set was never finalised "
                            + "(incomplete backup set)"));
            return VerificationOutcome.from(checks);
        }
        try {
            manifest = manifestWriter.fromJson(Files.readString(manifestPath));
            checks.add(VerificationOutcome.Check.passed("manifest.present",
                    "manifest.json parsed"));
        } catch (Exception e) {
            checks.add(VerificationOutcome.Check.failed("manifest.present",
                    "manifest.json could not be parsed: " + e.getMessage()));
            return VerificationOutcome.from(checks);
        }

        checks.add(verifyManifestStructure(manifest));

        // ---- 2. database artefacts ------------------------------------------
        verifyDatabase(checks, setDirectory, manifest);
        // ---- 3. vault --------------------------------------------------------
        verifyVault(checks, setDirectory, manifest);

        return VerificationOutcome.from(checks);
    }

    /** Manifest-internal coherence, independent of the filesystem. */
    private VerificationOutcome.Check verifyManifestStructure(RecoveryManifest manifest) {
        try {
            if (!RecoveryManifest.MANIFEST_VERSION.equals(manifest.manifestVersion())) {
                return VerificationOutcome.Check.failed("manifest.structure",
                        "unexpected manifestVersion '" + manifest.manifestVersion() + "'");
            }
            if (manifest.backupSetId() == null || manifest.backupSetId().isBlank()) {
                return VerificationOutcome.Check.failed("manifest.structure",
                        "backupSetId is missing");
            }
            if (manifest.recoveryBoundaryAt() == null) {
                return VerificationOutcome.Check.failed("manifest.structure",
                        "recoveryBoundaryAt is missing");
            }
            // The boundary must not postdate either snapshot it derives from,
            // and must never be later than manifest creation.
            if (manifest.recoveryBoundaryAt().isAfter(manifest.database().backupCreatedAt())) {
                return VerificationOutcome.Check.failed("manifest.structure",
                        "recoveryBoundaryAt is after databaseSnapshotAt, which is impossible "
                                + "for an earlier-of-the-two boundary");
            }
            if (manifest.recoveryBoundaryAt().isAfter(manifest.evidenceVault().backupCreatedAt())) {
                return VerificationOutcome.Check.failed("manifest.structure",
                        "recoveryBoundaryAt is after vaultSnapshotAt, which is impossible "
                                + "for an earlier-of-the-two boundary");
            }
            if (manifest.schema() == null || manifest.schema().flywayVersions().isEmpty()) {
                return VerificationOutcome.Check.failed("manifest.structure",
                        "schema/Flyway metadata is missing");
            }
            return VerificationOutcome.Check.passed("manifest.structure",
                    "version, set id, boundary and schema metadata are coherent");
        } catch (RuntimeException e) {
            return VerificationOutcome.Check.failed("manifest.structure",
                    "manifest is structurally invalid: " + e.getMessage());
        }
    }

    /**
     * Verifies the database dump and the globals capture.
     *
     * <p>The globals capture is checked exactly as strictly as the dump. A set
     * whose globals file is corrupt would restore an unusable RBAC state, so
     * treating it as optional would defeat the point of requiring it.
     */
    private void verifyDatabase(List<VerificationOutcome.Check> checks, Path setDirectory,
                                RecoveryManifest manifest) {
        RecoveryManifest.Database db = manifest.database();

        Path dump = resolveSetPath(setDirectory, db.backupFile(), checks,
                "database.dump.path");
        if (dump == null) {
            return;
        }
        verifyFile(checks, dump, db.checksum(), null, "database.dump");

        Path globals = resolveSetPath(setDirectory, db.globalsBackupFile(), checks,
                "globals.path");
        if (globals == null) {
            return;
        }
        verifyFile(checks, globals, db.globalsChecksum(), null, "globals.sql");

        // Structural readability: the archive is a real pg_dump custom archive,
        // not merely a file with the right name and size.
        checks.add(verifyArchiveReadable(dump));
    }

    /**
     * {@code pg_restore --list} against the dump.
     *
     * <p>Reports UNVERIFIABLE when the tool cannot be located or run, and FAILED
     * when it runs and rejects the archive. Those are different facts and are
     * kept apart deliberately.
     */
    private VerificationOutcome.Check verifyArchiveReadable(Path dump) {
        Path pgRestore;
        try {
            pgRestore = PostgreSqlToolLocator.resolve("pg_restore",
                    PostgreSqlToolLocator.ENV_PG_RESTORE, "pg_restore", environment);
        } catch (RuntimeException e) {
            return VerificationOutcome.Check.unverifiable("database.archiveReadable",
                    "pg_restore is not available, so archive structure could not be "
                            + "checked: " + e.getMessage());
        }

        try {
            SafeProcessRunner.Result result = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(pgRestore.toString())
                            .arg("--list")
                            .arg(dump.toAbsolutePath().toString())
                            .timeout(Duration.ofMinutes(5)));
            if (result.timedOut()) {
                return VerificationOutcome.Check.unverifiable("database.archiveReadable",
                        "pg_restore --list timed out; structure not determined");
            }
            if (!result.succeeded()) {
                return VerificationOutcome.Check.failed("database.archiveReadable",
                        "pg_restore --list rejected the archive (exit "
                                + result.exitCode() + ")");
            }
            return VerificationOutcome.Check.passed("database.archiveReadable",
                    "archive structure parses as a PostgreSQL custom-format dump");
        } catch (IOException e) {
            return VerificationOutcome.Check.unverifiable("database.archiveReadable",
                    "pg_restore could not be executed: " + e.getMessage());
        }
    }

    /** Verifies every file the integrity index claims to have copied. */
    private void verifyVault(List<VerificationOutcome.Check> checks, Path setDirectory,
                             RecoveryManifest manifest) {
        Path indexPath = setDirectory.resolve(
                EvidenceVaultBackupService.INTEGRITY_INDEX_NAME);
        if (!Files.isRegularFile(indexPath)) {
            checks.add(VerificationOutcome.Check.failed("vault.index",
                    "vault integrity index is missing"));
            return;
        }

        EvidenceVaultIntegrityIndex index;
        try {
            index = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .readValue(Files.readString(indexPath),
                            EvidenceVaultIntegrityIndex.class);
        } catch (Exception e) {
            checks.add(VerificationOutcome.Check.failed("vault.index",
                    "vault integrity index could not be parsed: " + e.getMessage()));
            return;
        }

        // The index's own digest must match the manifest, otherwise the index
        // could have been replaced wholesale.
        verifyFile(checks, indexPath, manifest.evidenceVault().integrityChecksum(),
                null, "vault-integrity.json");

        if (index.files().isEmpty()) {
            checks.add(VerificationOutcome.Check.passed("vault.index",
                    "vault index parsed and lists no files (empty vault)"));
            return;
        }

        Path vaultDirectory = setDirectory.resolve(
                EvidenceVaultBackupService.VAULT_DIR_NAME).toAbsolutePath().normalize();

        int verified = 0;
        int missing = 0;
        int corrupt = 0;

        for (EvidenceVaultIntegrityIndex.Entry entry : index.files()) {
            Path file;
            try {
                // Re-validated here: an index is data, and a tampered index must
                // not be able to send the verifier outside the set.
                file = EvidencePathGuard.resolveInsideSet(vaultDirectory,
                        entry.relativePath());
            } catch (IllegalArgumentException e) {
                corrupt++;
                continue;
            }
            if (!Files.isRegularFile(file)) {
                missing++;
                continue;
            }
            try {
                if (Files.size(file) != entry.sizeBytes()) {
                    corrupt++;
                    continue;
                }
                if (!RecoveryDigest.sha256(file).equals(entry.sha256())) {
                    corrupt++;
                    continue;
                }
                verified++;
            } catch (IOException e) {
                corrupt++;
            }
        }

        if (missing == 0 && corrupt == 0) {
            checks.add(VerificationOutcome.Check.passed("vault.files",
                    verified + " vault file(s) present with matching size and digest"));
        } else {
            checks.add(VerificationOutcome.Check.failed("vault.files",
                    missing + " missing, " + corrupt + " corrupt, " + verified
                            + " intact of " + index.files().size() + " recorded file(s)"));
        }
    }

    /**
     * Existence, non-emptiness and digest for one artefact.
     *
     * <p>A zero-byte artefact is rejected: it is the signature of a process that
     * exited 0 without writing, and would pass every later existence check.
     */
    private void verifyFile(List<VerificationOutcome.Check> checks, Path file,
                            String expectedDigest, Long expectedBytes, String label) {
        if (!Files.isRegularFile(file)) {
            checks.add(VerificationOutcome.Check.failed(label + ".present",
                    label + " is missing"));
            return;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            checks.add(VerificationOutcome.Check.unverifiable(label + ".size",
                    label + " size could not be read"));
            return;
        }
        if (size == 0) {
            checks.add(VerificationOutcome.Check.failed(label + ".nonEmpty",
                    label + " is empty (0 bytes)"));
            return;
        }
        if (expectedBytes != null && expectedBytes > 0 && expectedBytes != size) {
            checks.add(VerificationOutcome.Check.failed(label + ".size",
                    label + " is " + size + " bytes but the manifest records "
                            + expectedBytes));
            return;
        }
        checks.add(VerificationOutcome.Check.passed(label + ".nonEmpty",
                label + " is present and non-empty (" + size + " bytes)"));

        String actual;
        try {
            actual = RecoveryDigest.sha256(file);
        } catch (IOException e) {
            checks.add(VerificationOutcome.Check.unverifiable(label + ".digest",
                    label + " could not be hashed"));
            return;
        }
        if (!actual.equals(expectedDigest)) {
            checks.add(VerificationOutcome.Check.failed(label + ".digest",
                    label + " digest does not match the manifest; the artefact has "
                            + "been modified since the backup"));
            return;
        }
        checks.add(VerificationOutcome.Check.passed(label + ".digest",
                label + " matches its recorded SHA-256"));
    }

    /** Resolves a manifest-recorded relative path inside the set, or records why not. */
    private Path resolveSetPath(Path setDirectory, String relative,
                                List<VerificationOutcome.Check> checks, String label) {
        try {
            // Validated with the same guard the writer used, so a manifest
            // rewritten to contain ../../etc/passwd cannot direct the verifier.
            String safe = RecoverySetGuard.requireSetRelativePath(label, relative);
            Path resolved = setDirectory.resolve(safe).normalize();
            if (!resolved.startsWith(setDirectory.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("path escapes the backup set");
            }
            return resolved;
        } catch (RuntimeException e) {
            checks.add(VerificationOutcome.Check.failed(label,
                    "recorded path is unsafe: " + e.getMessage()));
            return null;
        }
    }
}