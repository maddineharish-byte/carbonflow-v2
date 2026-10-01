package com.carbonflow.recovery.vault;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Per-file integrity record for a copied Evidence Vault, written as
 * {@code vault-integrity.json} inside the backup set.
 *
 * <h2>Why this exists</h2>
 * <p>The database already stores a SHA-256 for every evidence file
 * ({@code evidence_records.sha256_hash}, {@code evidence_versions.sha256_hash}).
 * That gives CarbonFlow an <b>independent</b> integrity oracle for the vault,
 * which is why the vault can be verified at all without trusting the copy
 * itself. This index records what was copied so REC-06 can compare three
 * independent things:
 *
 * <pre>
 * file on disk   →  index.sha256  →  evidence_records.sha256_hash
 * </pre>
 *
 * <h2>What is deliberately not recorded</h2>
 * <p>No absolute source path, no tenant identifier, no MIME type, no uploader.
 * The index needs enough to verify bytes and nothing more. It is copied and
 * retained for 30 days, so every extra field is extra exposure.
 *
 * @param indexVersion format version of this index
 * @param generatedAt  when the vault copy completed (UTC ISO-8601)
 * @param copiedWith   the copy mechanism used, recorded so the rsync/robocopy
 *                     deviation of 2026-09-30 stays visible rather than hidden
 * @param fileCount    number of files in the copy
 * @param totalBytes   sum of file sizes
 * @param files        one entry per copied file
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"indexVersion", "generatedAt", "copiedWith", "fileCount",
        "totalBytes", "files"})
public record EvidenceVaultIntegrityIndex(String indexVersion, String generatedAt,
                                          String copiedWith, int fileCount,
                                          long totalBytes, List<Entry> files) {

    /** Current index format version. */
    public static final String INDEX_VERSION = "1";

    public EvidenceVaultIntegrityIndex {
        if (indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("indexVersion is required");
        }
        if (files == null) {
            throw new IllegalArgumentException("files is required (may be empty)");
        }
        if (fileCount != files.size()) {
            // A count that disagrees with the list is an integrity problem in
            // the index itself, not a formatting detail.
            throw new IllegalArgumentException(
                    "fileCount (" + fileCount + ") disagrees with files.size() ("
                            + files.size() + ")");
        }
    }

    /**
     * One copied file.
     *
     * @param relativePath path inside the backup set's {@code vault/} directory
     * @param sizeBytes    size of the copied file
     * @param sha256       hex SHA-256 of the copied bytes
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"relativePath", "sizeBytes", "sha256"})
    public record Entry(String relativePath, long sizeBytes, String sha256) {

        public Entry {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath is required");
            }
            if (sha256 == null || !sha256.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "sha256 must be 64 lowercase hex characters");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }
}