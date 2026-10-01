package com.carbonflow.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable, machine-readable identity of one CarbonFlow recovery set.
 *
 * <p>A recovery set is the unit that the approved requirements talk about: the
 * PostgreSQL backup, the Evidence Vault copy, their checksums and the boundary
 * timestamp that ties them together. This record is what lets REC-03 through
 * REC-10 refer to that same set without re-deriving anything.
 *
 * <h2>Why this lives outside PostgreSQL</h2>
 * <p>The manifest is written <em>after</em> the database dump. If it were stored
 * in the database it would be absent precisely when a restore is needed — and
 * would be lost with the instance whose failure prompted the restore. It is a
 * plain file inside the backup set, which is also what
 * {@code docs/BACKUP-RECOVERY.md} §2.5 implies by describing ordering between
 * the database and the other set members.
 *
 * <h2>What this type does not claim</h2>
 * <ul>
 *   <li>It does not verify anything. {@link VerificationStatus#PENDING} is the
 *       only status this layer can produce; {@code VERIFIED} means REC-06
 *       actually ran the checks.</li>
 *   <li>It does not create backups. The database and vault metadata are
 *       <em>supplied</em> by REC-03 and REC-04.</li>
 *   <li>It does not provide cryptographic immutability. Writing the file last
 *       makes an <em>incomplete</em> set unidentifiable, which is a useful
 *       property, but it is a completeness signal, not a tamper-evidence
 *       guarantee.</li>
 *   <li>It does not make the two snapshots atomic. The database and the vault
 *       share no transaction coordinator; see {@link #recoveryBoundaryAt()}.</li>
 * </ul>
 *
 * <h2>Time</h2>
 * <p>Every timestamp is an {@link Instant} serialised as UTC ISO-8601 with a
 * trailing {@code Z} (e.g. {@code 2026-10-01T14:00:03Z}). No local zone, locale
 * or geographic offset is stored or assumed, so a set written on one host is
 * readable on another and nothing here depends on where it was produced.
 *
 * <h2>Serialisation</h2>
 * <p>{@code @JsonPropertyOrder} fixes key order and Jackson's
 * {@code SORT_PROPERTIES_ALPHABETICALLY} behaviour is not used, so identical
 * metadata always yields byte-identical JSON. That determinism is what lets a
 * later phase hash the manifest itself. It is <em>not</em> a claim that the
 * file cannot be altered.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "manifestVersion",
        "backupSetId",
        "createdAt",
        "recoveryBoundaryAt",
        "application",
        "database",
        "evidenceVault",
        "schema",
        "verification",
        "notes"
})
public record RecoveryManifest(

        /** Format version of this document, not the application version. */
        String manifestVersion,

        /** Collision-resistant set identity; see {@link #newBackupSetId()}. */
        String backupSetId,

        /** When the manifest itself was finalised (UTC, ISO-8601). */
        Instant createdAt,

        /**
         * The recovery boundary: the earlier of the database snapshot instant
         * and the vault copy instant, per {@code docs/RECOVERY-CONTROLS-DESIGN.md}
         * §5.4.
         *
         * <p>Choosing the earlier instant is the conservative reading: anything
         * outside the boundary is by definition not committed data, so a set can
         * never claim to contain more than the database actually had.
         */
        Instant recoveryBoundaryAt,

        Application application,
        Database database,
        EvidenceVault evidenceVault,
        Schema schema,
        Verification verification,
        List<String> notes) {

    /** Current manifest format version. Bump on any breaking shape change. */
    public static final String MANIFEST_VERSION = "1";

    /** Application name as built by this repository. */
    public static final String APPLICATION_NAME = "CarbonFlow";

    /** The only checksum algorithm in use; never invent a second. */
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    /** {@code pg_dump --format=custom}, the format REC-03 will produce. */
    public static final String BACKUP_FORMAT_POSTGRESQL_CUSTOM = "postgresql-custom";

    public RecoveryManifest {
        if (manifestVersion == null || manifestVersion.isBlank()) {
            throw ManifestValidationException.invalid("manifestVersion", "is required");
        }
        if (backupSetId == null || backupSetId.isBlank()) {
            throw ManifestValidationException.invalid("backupSetId", "is required");
        }
        if (createdAt == null) {
            throw ManifestValidationException.invalid("createdAt", "is required");
        }
        if (recoveryBoundaryAt == null) {
            throw ManifestValidationException.invalid("recoveryBoundaryAt", "is required");
        }
        if (application == null) {
            throw ManifestValidationException.invalid("application", "is required");
        }
        if (database == null) {
            throw ManifestValidationException.invalid("database", "is required");
        }
        if (evidenceVault == null) {
            throw ManifestValidationException.invalid("evidenceVault", "is required");
        }
        if (schema == null) {
            throw ManifestValidationException.invalid("schema", "is required");
        }
        // A manifest with no verification block at all is worse than one that
        // says PENDING: absence would read as "not considered" rather than
        // "considered and not yet checked".
        verification = verification == null
                ? new Verification(VerificationStatus.PENDING, null, null)
                : verification;
    }

    /**
     * Generates a set identifier.
     *
     * <p>A random UUIDv4 rather than a timestamp or counter. Two backup jobs on
     * two hosts must be able to create sets without coordinating, and a
     * timestamp-derived id would also collide for two runs inside the same
     * hour — which is the exact interval the approved schedule uses.
     */
    public static String newBackupSetId() {
        return UUID.randomUUID().toString();
    }

    /** Identity of the application build that produced the set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "version", "gitCommit", "gitCommitKnown"})
    public record Application(String name, String version, String gitCommit,
                              Boolean gitCommitKnown) {

        public Application {
            if (name == null || name.isBlank()) {
                throw ManifestValidationException.invalid("application.name", "is required");
            }
        }

        /** Convenience for the known case, where the revision is available. */
        public static Application of(String version, String gitCommit) {
            return new Application(APPLICATION_NAME, version, gitCommit,
                    gitCommit != null && !gitCommit.isBlank());
        }

        /**
         * Application build with the revision explicitly unknown.
         *
         * <p>Used when Git metadata is unavailable — a jar copied to a restore
         * host has no {@code .git} directory. The manifest then records the
         * absence honestly instead of fabricating a hash or failing the backup.
         */
        public static Application withoutGit(String version) {
            return new Application(APPLICATION_NAME, version, null, Boolean.FALSE);
        }
    }

    /** Identity of the PostgreSQL backup within the set. Supplied by REC-03. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"backupFile", "backupFormat", "backupCreatedAt",
            "checksumAlgorithm", "checksum", "globalsBackupFile", "globalsChecksum"})
    public record Database(String backupFile, String backupFormat, Instant backupCreatedAt,
                           String checksumAlgorithm, String checksum,
                           String globalsBackupFile, String globalsChecksum) {

        public Database {
            backupFile = RecoverySetGuard.requireSetRelativePath("database.backupFile", backupFile);
            if (backupFormat == null || backupFormat.isBlank()) {
                throw ManifestValidationException.invalid("database.backupFormat", "is required");
            }
            if (backupCreatedAt == null) {
                throw ManifestValidationException.invalid("database.backupCreatedAt", "is required");
            }
            if (!CHECKSUM_ALGORITHM.equals(checksumAlgorithm)) {
                throw ManifestValidationException.invalid("database.checksumAlgorithm",
                        "must be " + CHECKSUM_ALGORITHM);
            }
            checksum = ManifestValidationException.requireSha256Hex("database.checksum", checksum);

            // The globals capture is mandatory, not optional. Roles, permissions
            // and role_permissions live outside the database dump but are read
            // at runtime (docs/BACKUP-RECOVERY.md §2.2), so a set without it
            // would restore an unusable RBAC state.
            globalsBackupFile = RecoverySetGuard.requireSetRelativePath(
                    "database.globalsBackupFile", globalsBackupFile);
            globalsChecksum = ManifestValidationException.requireSha256Hex(
                    "database.globalsChecksum", globalsChecksum);
        }
    }

    /** Identity of the Evidence Vault copy within the set. Supplied by REC-04. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"backupLocation", "backupCreatedAt", "fileCount", "totalBytes",
            "checksumAlgorithm", "integrityIndex", "integrityChecksum", "copiedWith"})
    public record EvidenceVault(String backupLocation, Instant backupCreatedAt,
                                Long fileCount, Long totalBytes, String checksumAlgorithm,
                                String integrityIndex, String integrityChecksum,
                                String copiedWith) {

        public EvidenceVault {
            backupLocation = RecoverySetGuard.requireSetRelativePath(
                    "evidenceVault.backupLocation", backupLocation);
            if (backupCreatedAt == null) {
                throw ManifestValidationException.invalid("evidenceVault.backupCreatedAt",
                        "is required");
            }
            if (fileCount != null && fileCount < 0) {
                throw ManifestValidationException.invalid("evidenceVault.fileCount",
                        "must not be negative");
            }
            if (totalBytes != null && totalBytes < 0) {
                throw ManifestValidationException.invalid("evidenceVault.totalBytes",
                        "must not be negative");
            }
            if (!CHECKSUM_ALGORITHM.equals(checksumAlgorithm)) {
                throw ManifestValidationException.invalid("evidenceVault.checksumAlgorithm",
                        "must be " + CHECKSUM_ALGORITHM);
            }
            // Integrity metadata is required, not optional: the vault carries the
            // same RTO/RPO as the database, so an unverified copy must be
            // identifiable as such rather than merely absent.
            integrityIndex = RecoverySetGuard.requireSetRelativePath(
                    "evidenceVault.integrityIndex", integrityIndex);
            integrityChecksum = ManifestValidationException.requireSha256Hex(
                    "evidenceVault.integrityChecksum", integrityChecksum);
        }
    }

    /**
     * Schema identity of the restored database.
     *
     * <p>Records the Flyway state observed at backup time so a restore can be
     * reasoned about. Supplied by the caller from
     * {@code flyway_schema_history}; this layer never queries the database,
     * which is what allows the manifest to be written when PostgreSQL is down.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"flywayVersions", "flywayAllSuccessful", "expectedTableCount"})
    public record Schema(List<String> flywayVersions, Boolean flywayAllSuccessful,
                         Integer expectedTableCount) {

        public Schema {
            if (flywayVersions == null || flywayVersions.isEmpty()) {
                throw ManifestValidationException.invalid("schema.flywayVersions",
                        "is required (expected V1..V8)");
            }
            for (String version : flywayVersions) {
                if (version == null || version.isBlank()) {
                    throw ManifestValidationException.invalid("schema.flywayVersions",
                            "must not contain a blank entry");
                }
            }
            if (expectedTableCount != null && expectedTableCount <= 0) {
                throw ManifestValidationException.invalid("schema.expectedTableCount",
                        "must be positive");
            }
        }

        /** The frozen migration set, for the current release candidate. */
        public static Schema ofFrozenBaseline(List<String> versions) {
            return new Schema(versions, Boolean.TRUE, 38);
        }
    }

    /**
     * Verification state of the set.
     *
     * <p>REC-02 writes {@link VerificationStatus#PENDING} and nothing else.
     * A manifest that had merely been written is not a verified set, and
     * claiming otherwise here would manufacture exactly the false assurance the
     * recovery documentation exists to prevent.
     *
     * @param status        lifecycle state
     * @param verifiedAt    when REC-06 completed, or {@code null} while PENDING
     * @param reason        why the set failed or became unverifiable
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"status", "verifiedAt", "reason"})
    public record Verification(VerificationStatus status, Instant verifiedAt, String reason) {

        public Verification {
            if (status == null) {
                throw ManifestValidationException.invalid("verification.status", "is required");
            }
            // Only the two *negative* terminal states must be explainable. An
            // operator reading this at 3am needs to know why a set cannot be
            // used; VERIFIED needs no excuse, and demanding one would force
            // REC-06 to invent filler text for a set that is simply fine.
            boolean negative = status == VerificationStatus.FAILED
                    || status == VerificationStatus.UNVERIFIABLE;
            if (negative && (reason == null || reason.isBlank())) {
                throw ManifestValidationException.invalid("verification.reason",
                        "is required for status " + status);
            }
            // PENDING must not carry a reason, or a re-run would make an
            // unchecked set look like it had already failed once.
            if (status == VerificationStatus.PENDING && reason != null && !reason.isBlank()) {
                throw ManifestValidationException.invalid("verification.reason",
                        "must be empty while status is PENDING");
            }
            // VERIFIED is only meaningful with the time it was established;
            // "verified, but when?" is not a record anyone can act on.
            if (status == VerificationStatus.VERIFIED && verifiedAt == null) {
                throw ManifestValidationException.invalid("verification.verifiedAt",
                        "is required for status VERIFIED");
            }
        }

        /** The only state this layer produces. */
        public static Verification pending() {
            return new Verification(VerificationStatus.PENDING, null, null);
        }
    }
}