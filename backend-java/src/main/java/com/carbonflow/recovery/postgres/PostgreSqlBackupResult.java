package com.carbonflow.recovery.postgres;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Structured metadata for a completed PostgreSQL backup.
 *
 * <p>Shaped so REC-02 can consume it directly, and so REC-05 can pass it to
 * {@code RecoveryManifestWriter} once the Evidence Vault copy exists.
 *
 * <p>A result is only ever produced for a <b>complete</b> set. A partial backup
 * (database dumped, globals failed) is reported through
 * {@link PostgreSqlBackupService.BackupOutcome#failure}, never as a successful
 * result with a missing field.
 *
 * @param backupSetId        UUIDv4 set identity, consistent with REC-02
 * @param setDirectory       directory the artefacts were written to
 * @param databaseDump       absolute path to the custom-format dump
 * @param databaseDumpBytes  size of the dump in bytes
 * @param databaseDumpAt     when the dump completed (UTC)
 * @param databaseDumpSha256 hex SHA-256 of the dump
 * @param globalsDump        absolute path to the {@code --globals-only} capture
 * @param globalsDumpBytes   size of the globals capture
 * @param globalsDumpAt      when the globals capture completed (UTC)
 * @param globalsDumpSha256  hex SHA-256 of the globals capture
 * @param serverVersion      {@code pg_dump} client version, or {@code null}
 *                           when it could not be determined — never fabricated
 */
public record PostgreSqlBackupResult(
        String backupSetId,
        Path setDirectory,
        Path databaseDump,
        long databaseDumpBytes,
        Instant databaseDumpAt,
        String databaseDumpSha256,
        Path globalsDump,
        long globalsDumpBytes,
        Instant globalsDumpAt,
        String globalsDumpSha256,
        String serverVersion) {

    /** File name of the custom-format dump inside the set directory. */
    public static final String DATABASE_DUMP_NAME = "database.dump";

    /** File name of the globals capture inside the set directory. */
    public static final String GLOBALS_DUMP_NAME = "globals.sql";

    public PostgreSqlBackupResult {
        if (backupSetId == null || backupSetId.isBlank()) {
            throw new IllegalArgumentException("backupSetId is required");
        }
        if (databaseDumpSha256 == null || databaseDumpSha256.length() != 64) {
            throw new IllegalArgumentException("databaseDumpSha256 must be SHA-256 hex");
        }
        if (globalsDumpSha256 == null || globalsDumpSha256.length() != 64) {
            throw new IllegalArgumentException("globalsDumpSha256 must be SHA-256 hex");
        }
    }

    /** {@code true} when the client version could not be probed. */
    public boolean serverVersionUnknown() {
        return serverVersion == null || serverVersion.isBlank();
    }

    /**
     * The set-relative path of the dump, as a REC-02 manifest field requires.
     */
    public String databaseDumpRelativePath() {
        return DATABASE_DUMP_NAME;
    }

    /** The set-relative path of the globals capture, as a manifest field requires. */
    public String globalsDumpRelativePath() {
        return GLOBALS_DUMP_NAME;
    }
}