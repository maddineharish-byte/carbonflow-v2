package com.carbonflow.recovery.vault;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Resolves and validates Evidence Vault paths for backup.
 *
 * <h2>Why this exists</h2>
 * <p>{@code evidence_records.storage_path} holds an <b>absolute</b> path written
 * by {@code EvidenceStorageService} as {@code target.toString()}. Feeding those
 * values straight into a file copy is a filesystem-read primitive driven by
 * database content: anyone able to influence a row could point the backup at
 * {@code /etc/shadow} or {@code ../../..} and have the operator's process copy
 * it into a backup archive.
 *
 * <p>The application itself already defends against this on the read path
 * ({@code EvidenceStorageService.isPathInside}). Backup must defend
 * independently: it is a different process, often running with different
 * privileges, and the containment rule must not be inherited by hope.
 *
 * <p>The only permitted chain is:
 * <pre>
 * database storage_path
 *     → resolved absolute path
 *     → proven inside the configured vault root
 *     → re-expressed as a vault-relative path
 *     → used as the backup destination
 * </pre>
 */
public final class EvidencePathGuard {

    private EvidencePathGuard() {
    }

    /**
     * Resolves a database {@code storage_path} to a vault-relative path.
     *
     * <p>Fails closed. A path that is absent, absolute-but-outside, relative to
     * an unexpected base, or that escapes after normalisation is rejected rather
     * than clamped, because a clamped path would silently back up the wrong
     * bytes and report success.
     *
     * @param vaultRoot the configured Evidence Vault root
     * @param storagePath the raw {@code storage_path} column value
     * @return the vault-relative path, using {@code /} separators
     * @throws IllegalArgumentException if the path is unsafe or outside the root
     */
    public static String resolveRelativeToVault(Path vaultRoot, String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storage_path is blank");
        }
        Path root = vaultRoot.toAbsolutePath().normalize();
        Path candidate = Paths.get(storagePath.trim()).toAbsolutePath().normalize();

        if (!candidate.startsWith(root)) {
            // Deliberately does not echo the resolved path: an operator-facing
            // message must not become a channel for probing arbitrary locations.
            throw new IllegalArgumentException(
                    "Evidence storage_path resolves outside the configured Evidence "
                            + "Vault root; refusing to read it");
        }
        Path relative = root.relativize(candidate);
        if (relative.toString().isEmpty()) {
            throw new IllegalArgumentException(
                    "Evidence storage_path resolves to the vault root itself");
        }

        String normalised = relative.toString().replace('\\', '/');
        for (String segment : normalised.split("/")) {
            String lowered = segment.toLowerCase(Locale.ROOT);
            if ("..".equals(lowered) || ".".equals(lowered)) {
                throw new IllegalArgumentException(
                        "Evidence path contains a traversal segment");
            }
        }
        return normalised;
    }

    /**
     * Rejects a destination that would leave the backup set.
     *
     * <p>Defence in depth on the write side: even a validated relative path is
     * re-checked against the set directory after joining, so a future bug that
     * introduced an unvalidated segment could not write outside the set.
     *
     * @param setDirectory the backup set directory
     * @param relativePath a previously validated relative path
     * @return the resolved destination, proven inside {@code setDirectory}
     */
    public static Path resolveInsideSet(Path setDirectory, String relativePath) {
        Path base = setDirectory.toAbsolutePath().normalize();
        Path destination = base.resolve(relativePath).normalize();
        if (!destination.startsWith(base) || destination.equals(base)) {
            throw new IllegalArgumentException(
                    "Backup destination escapes the backup set directory");
        }
        return destination;
    }
}