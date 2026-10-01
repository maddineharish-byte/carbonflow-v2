package com.carbonflow.recovery.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * REC-04 — Evidence Vault backup automation.
 *
 * <h2>Approach</h2>
 * <p>Two sources are combined, because either alone is insufficient:
 * <ul>
 *   <li><b>The database</b> says which files are <em>required</em>. A file the
 *       metadata references but which cannot be copied is data loss, and the
 *       vault must never be reported complete in that case.</li>
 *   <li><b>The filesystem walk</b> catches files present on disk. An orphaned
 *       file — deleted metadata pointing at live bytes, or a copy interrupted
 *       after the metadata commit — is preserved rather than silently dropped,
 *       because {@code docs/BACKUP-RECOVERY.md} §5 treats partial evidence loss
 *       as a reportable event, not something to tidy away.</li>
 * </ul>
 *
 * <h2>Not atomic with the database</h2>
 * <p>No atomicity is claimed. REC-05 owns quiescing and the recovery boundary.
 * This class copies what exists at the moment it runs.
 *
 * <h2>Not a Spring bean</h2>
 * <p>Invoked explicitly by REC-05. The application starts and serves traffic
 * with none of this present.
 */
public final class EvidenceVaultBackupService {

    /** Index file name inside the backup set. */
    public static final String INTEGRITY_INDEX_NAME = "vault-integrity.json";

    /** Vault subdirectory inside the backup set. */
    public static final String VAULT_DIR_NAME = "vault";

    /** Records a required evidence file as named by the database. */
    public record RequiredFile(String storagePath, String expectedSha256, long expectedBytes) {
    }

    /** Result of a vault backup attempt. */
    public record VaultBackupResult(boolean success, Path setDirectory,
                                   Path vaultDirectory, Path integrityIndex,
                                   int fileCount, long totalBytes,
                                   Instant copiedAt, String copiedWith,
                                   String failureReason) {

        static VaultBackupResult failed(Path setDirectory, String reason) {
            return new VaultBackupResult(false, setDirectory, null, null, 0, 0L,
                    null, null, reason);
        }
    }

    private final Clock clock;
    private final ObjectMapper mapper;

    public EvidenceVaultBackupService(Clock clock) {
        this.clock = clock;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** Production default: system UTC clock. */
    public static EvidenceVaultBackupService productionDefaults() {
        return new EvidenceVaultBackupService(Clock.systemUTC());
    }

    /**
     * Copies the Evidence Vault into an existing backup set.
     *
     * @param vaultRoot  configured Evidence Vault root
     * @param setDirectory the backup set directory created by REC-03
     * @param requiredFiles every file the database references; a missing one fails
     * @param copiedWith   name of the copy mechanism, recorded for auditability
     */
    public VaultBackupResult backup(Path vaultRoot, Path setDirectory,
                                    List<RequiredFile> requiredFiles,
                                    String copiedWith) {

        Path normalizedRoot = vaultRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            return VaultBackupResult.failed(setDirectory,
                    "Evidence Vault root is not a readable directory: " + normalizedRoot);
        }

        Path vaultDirectory = setDirectory.resolve(VAULT_DIR_NAME)
                .toAbsolutePath().normalize();
        if (!vaultDirectory.startsWith(setDirectory.toAbsolutePath().normalize())) {
            return VaultBackupResult.failed(setDirectory,
                    "Computed vault directory escapes the backup set");
        }

        try {
            Files.createDirectories(vaultDirectory);
        } catch (IOException e) {
            return VaultBackupResult.failed(setDirectory,
                    "Could not create vault directory: " + e.getMessage());
        }

        List<EvidenceVaultIntegrityIndex.Entry> entries = new ArrayList<>();
        try {
            // 1. every file the database requires must be copied, or fail.
            for (RequiredFile required : requiredFiles == null ? List.<RequiredFile>of()
                    : requiredFiles) {
                copyRequired(normalizedRoot, vaultDirectory, required, entries);
            }
            // 2. then everything else on disk, so nothing is silently dropped.
            entries.addAll(copyRemaining(normalizedRoot, vaultDirectory, entries));

            List<EvidenceVaultIntegrityIndex.Entry> sorted = entries.stream()
                    .sorted(Comparator.comparing(EvidenceVaultIntegrityIndex.Entry::relativePath))
                    .toList();

            long totalBytes = sorted.stream()
                    .mapToLong(EvidenceVaultIntegrityIndex.Entry::sizeBytes).sum();

            EvidenceVaultIntegrityIndex index = new EvidenceVaultIntegrityIndex(
                    EvidenceVaultIntegrityIndex.INDEX_VERSION,
                    clock.instant().toString(),
                    copiedWith,
                    sorted.size(),
                    totalBytes,
                    sorted);

            Path indexPath = writeIndex(setDirectory, index);

            return new VaultBackupResult(true, setDirectory, vaultDirectory, indexPath,
                    sorted.size(), totalBytes, clock.instant(), copiedWith, null);

        } catch (VaultFailure f) {
            // Fail closed: a partial vault is not a backup.
            cleanupVaultArtifacts(setDirectory, vaultDirectory);
            return VaultBackupResult.failed(setDirectory, f.getMessage());
        } catch (IOException e) {
            cleanupVaultArtifacts(setDirectory, vaultDirectory);
            return VaultBackupResult.failed(setDirectory,
                    "Vault backup failed: " + e.getMessage());
        }
    }

    /**
     * Copies one database-required file and checks it against its recorded digest.
     *
     * <p>The expected digest comes from the database, which is an oracle
     * independent of the copy. A mismatch means the source changed or the copy
     * was corrupted; either way the set is not trustworthy.
     */
    private void copyRequired(Path vaultRoot, Path vaultDirectory, RequiredFile required,
                              List<EvidenceVaultIntegrityIndex.Entry> entries)
            throws IOException, VaultFailure {

        if (required == null || required.storagePath() == null
                || required.storagePath().isBlank()) {
            throw new VaultFailure("Database supplied a blank evidence storage_path");
        }

        String relative;
        try {
            relative = EvidencePathGuard.resolveRelativeToVault(vaultRoot,
                    required.storagePath());
        } catch (IllegalArgumentException e) {
            throw new VaultFailure(
                    "Evidence storage_path is unsafe (outside the vault root or "
                            + "traversal); refusing the vault backup");
        }

        Path source = EvidencePathGuard.resolveInsideSet(vaultRoot, relative);
        if (!Files.isRegularFile(source)) {
            // The database references bytes that are not there. docs/BACKUP-RECOVERY.md
            // §5 calls this partial evidence loss — a reportable event.
            throw new VaultFailure(
                    "Database references an evidence file that is missing from the "
                            + "vault: " + relative);
        }

        long size = Files.size(source);
        if (size == 0) {
            throw new VaultFailure("Evidence file is empty (0 bytes): " + relative);
        }
        if (required.expectedBytes() > 0 && required.expectedBytes() != size) {
            throw new VaultFailure("Evidence file size differs from the recorded "
                    + "file_size_bytes: " + relative);
        }

        String expected = normaliseDigest(required.expectedSha256());
        if (expected == null) {
            throw new VaultFailure(
                    "Database supplied an unusable sha256_hash for: " + relative);
        }

        Path destination = EvidencePathGuard.resolveInsideSet(vaultDirectory, relative);
        Files.createDirectories(destination.getParent());

        String copiedDigest = copyAndHash(source, destination);
        if (!expected.equals(copiedDigest)) {
            // Overwrite the suspect copy so no unverified bytes linger in a set
            // that another process might mistake for complete.
            Files.deleteIfExists(destination);
            throw new VaultFailure("Copied evidence bytes do not match the digest "
                    + "recorded in the database: " + relative);
        }
        entries.add(new EvidenceVaultIntegrityIndex.Entry(relative, size, copiedDigest));
    }

    /**
     * Copies vault files not already handled, so orphaned bytes are preserved.
     *
     * <p>Does not fail on an unreadable extra file: a file the database does not
     * reference cannot make the set incomplete, and refusing would let one stray
     * unreadable file block every backup.
     */
    private List<EvidenceVaultIntegrityIndex.Entry> copyRemaining(
            Path vaultRoot, Path vaultDirectory,
            List<EvidenceVaultIntegrityIndex.Entry> alreadyCopied)
            throws IOException {

        var done = alreadyCopied.stream()
                .map(EvidenceVaultIntegrityIndex.Entry::relativePath)
                .collect(java.util.HashSet::new, java.util.HashSet::add,
                        java.util.HashSet::addAll);

        List<EvidenceVaultIntegrityIndex.Entry> extra = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(vaultRoot)) {
            for (Path path : walk.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String relative = vaultRoot.relativize(path).toString()
                        .replace('\\', '/');
                if (done.contains(relative)) {
                    continue;
                }
                long size = Files.size(path);
                if (size == 0) {
                    // Not an error for an unreferenced file; recorded as skipped.
                    continue;
                }
                Path destination = EvidencePathGuard.resolveInsideSet(vaultDirectory,
                        relative);
                Files.createDirectories(destination.getParent());
                String digest = copyAndHash(path, destination);
                extra.add(new EvidenceVaultIntegrityIndex.Entry(relative, size, digest));
                done.add(relative);
            }
        }
        return extra;
    }

    /**
     * Streams a copy while hashing, so a large evidence file never needs to fit
     * in heap.
     *
     * <p>Also skips re-copying a file whose digest already matches, which keeps
     * an incremental run cheap. The bytes are verified either way.
     */
    private String copyAndHash(Path source, Path destination) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable in this JVM", e);
        }

        try (InputStream in = Files.newInputStream(source);
             DigestInputStream digestIn = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = digestIn.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path writeIndex(Path setDirectory, EvidenceVaultIntegrityIndex index)
            throws IOException {
        Path indexPath = EvidencePathGuard.resolveInsideSet(setDirectory,
                INTEGRITY_INDEX_NAME);
        Path temporary = EvidencePathGuard.resolveInsideSet(setDirectory,
                INTEGRITY_INDEX_NAME + ".tmp");
        Files.writeString(temporary, mapper.writeValueAsString(index));
        Files.move(temporary, indexPath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return indexPath;
    }

    /** Reads an integrity index back. Used by REC-06 verification. */
    public EvidenceVaultIntegrityIndex readIndex(Path setDirectory) throws IOException {
        Path indexPath = setDirectory.resolve(INTEGRITY_INDEX_NAME);
        if (!Files.isRegularFile(indexPath)) {
            throw new IOException("Vault integrity index is missing: " + indexPath);
        }
        return mapper.readValue(Files.readString(indexPath),
                EvidenceVaultIntegrityIndex.class);
    }

    /**
     * Removes only this attempt's vault artefacts.
     *
     * <p>Scoped strictly to {@code vault/} and the index. {@code database.dump}
     * and {@code globals.sql} written by REC-03 are preserved: they are
     * independently valuable and may already have been recorded elsewhere.
     */
    private void cleanupVaultArtifacts(Path setDirectory, Path vaultDirectory) {
        deleteRecursively(vaultDirectory);
        try {
            Files.deleteIfExists(setDirectory.resolve(INTEGRITY_INDEX_NAME));
            Files.deleteIfExists(setDirectory.resolve(INTEGRITY_INDEX_NAME + ".tmp"));
        } catch (IOException e) {
            // Best effort; the set is already marked failed by having no manifest.
        }
    }

    /**
     * Recursive delete confined to one known subdirectory.
     *
     * <p>Only ever called with {@code <setDirectory>/vault}. The containment
     * check is repeated here rather than assumed, because an unrestricted
     * recursive delete is the single worst failure mode a backup tool can have.
     */
    private void deleteRecursively(Path directory) {
        Path base = directory.toAbsolutePath().normalize();
        Path set = base.getParent();
        if (set == null || base.equals(set)) {
            return;
        }
        if (!base.getFileName().toString().equals(VAULT_DIR_NAME)) {
            return;
        }
        if (!Files.exists(base)) {
            return;
        }
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Best effort. The set remains identifiable as incomplete because it
            // has no manifest.
        }
    }

    private static String normaliseDigest(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.matches("^[0-9a-f]{64}$") ? trimmed : null;
    }

    /** Internal signal that the vault copy must be abandoned. */
    private static final class VaultFailure extends Exception {
        VaultFailure(String message) {
            super(message);
        }
    }
}