package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the evidence files the database says must be recoverable.
 *
 * <p>REC-04 fails a vault backup when the database references a file that is not
 * on disk, so an automated backup needs to know what the database currently
 * references. That list changes as tenants upload, so it must be read at backup
 * time rather than cached.
 *
 * <p>Reads {@code evidence_records} and {@code evidence_versions}. Only the
 * fields needed to copy and verify a file are selected: the absolute
 * {@code storage_path}, the recorded {@code sha256_hash} and
 * {@code file_size_bytes}. No tenant identifier, file name or MIME type is
 * read, so nothing here carries customer content.
 *
 * <p>A failure to read the list is propagated rather than swallowed. Silently
 * backing up "whatever is on disk" when the database cannot be consulted would
 * quietly downgrade the guarantee, because the missing files would not be known.
 */
public class EvidenceReferenceReader {

    private final JdbcTemplate jdbc;

    public EvidenceReferenceReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every evidence file the database references.
     *
     * @return current and versioned evidence, de-duplicated by storage path
     */
    public List<EvidenceVaultBackupService.RequiredFile> readRequiredFiles() {
        List<EvidenceVaultBackupService.RequiredFile> files = new ArrayList<>();

        collect("select storage_path, sha256_hash, file_size_bytes "
                + "from evidence_records", files);
        // A version's bytes may live at a different path from the current record,
        // so both must be recoverable for the record to be restorable in full.
        collect("select storage_path, sha256_hash, file_size_bytes "
                + "from evidence_versions", files);

        // De-duplicate by path: the same current file can be referenced more than
        // once and must not be copied twice.
        List<EvidenceVaultBackupService.RequiredFile> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (EvidenceVaultBackupService.RequiredFile file : files) {
            if (seen.add(file.storagePath())) {
                unique.add(file);
            }
        }
        return unique;
    }

    private void collect(String sql, List<EvidenceVaultBackupService.RequiredFile> into) {
        jdbc.query(sql, rs -> {
            into.add(new EvidenceVaultBackupService.RequiredFile(
                    rs.getString("storage_path"),
                    rs.getString("sha256_hash"),
                    rs.getLong("file_size_bytes")));
        });
    }
}