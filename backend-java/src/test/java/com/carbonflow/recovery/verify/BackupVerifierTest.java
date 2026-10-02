package com.carbonflow.recovery.verify;

import com.carbonflow.recovery.RecoveryDigest;
import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.VerificationStatus;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-06 — backup verification.
 *
 * <p>The centre of gravity is the corruption suite. A verifier that has never
 * been observed to <em>fail</em> has not been tested, so every corruption case
 * here asserts that verification reports the specific defect rather than merely
 * returning something non-VERIFIED.
 *
 * <p>Sets are assembled as real files with real digests. Where {@code pg_restore}
 * is unavailable the archive-structure check reports UNVERIFIABLE — which is
 * itself a tested and correct outcome — and the assertions account for that.
 */
class BackupVerifierTest {

    private static boolean pgRestoreAvailable;
    private static Path pgRestorePath;

    @BeforeAll
    static void detect() {
        pgRestorePath = findTool("pg_restore");
        pgRestoreAvailable = pgRestorePath != null;
    }

    private static Path findTool(String name) {
        String configured = System.getenv(PostgreSqlToolLocator.ENV_PG_RESTORE);
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                for (String candidate : new String[]{name, name + ".exe"}) {
                    Path p = Path.of(dir).resolve(candidate);
                    if (Files.isRegularFile(p)) {
                        return p;
                    }
                }
            }
        }
        Path pgRoot = Path.of("C:\\Program Files\\PostgreSQL");
        if (Files.isDirectory(pgRoot)) {
            try (var versions = Files.list(pgRoot)) {
                return versions.map(v -> v.resolve("bin").resolve(name + ".exe"))
                        .filter(Files::isRegularFile).findFirst().orElse(null);
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (pgRestoreAvailable) {
            env.put(PostgreSqlToolLocator.ENV_PG_RESTORE, pgRestorePath.toString());
        }
        return env;
    }

    private BackupVerifier verifier() {
        return new BackupVerifier(new RecoveryManifestWriter(Clock.systemUTC()),
                environment());
    }

    /**
     * A real {@code pg_dump --format=custom} archive, produced once per run
     * against the hermetic embedded server.
     *
     * <p>Using a genuine archive is what makes the happy path meaningful: a
     * placeholder would be rejected by {@code pg_restore --list}, so a set built
     * from one could never verify and the test would prove nothing about REC-06's
     * ability to accept a good set. Returns {@code null} when tooling is absent,
     * in which case archive-dependent assertions are skipped.
     */
    private static Path realArchive;

    private static synchronized Path realArchive() throws Exception {
        if (realArchive != null) {
            return realArchive;
        }
        if (!pgRestoreAvailable || findTool("pg_dump") == null) {
            return null;
        }
        String jdbc = com.carbonflow.testsupport.EmbeddedPg.jdbcUrl();
        String afterProtocol = jdbc.substring(jdbc.indexOf("//") + 2);
        String hostPort = afterProtocol.substring(0, afterProtocol.indexOf('/'));
        int port = Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1));

        var target = new com.carbonflow.recovery.postgres.PostgreSqlBackupTarget(
                "127.0.0.1", port, "postgres",
                com.carbonflow.testsupport.EmbeddedPg.username(),
                com.carbonflow.testsupport.EmbeddedPg.password().toCharArray(), "prefer");

        Path root = Files.createTempDirectory("carbonflow-verify-archive");
        var service = com.carbonflow.recovery.postgres.PostgreSqlBackupService
                .productionDefaults();
        Map<String, String> env = new HashMap<>();
        env.put(PostgreSqlToolLocator.ENV_PG_DUMP,
                findTool("pg_dump").toString());
        env.put(PostgreSqlToolLocator.ENV_PG_DUMPALL,
                findTool("pg_dumpall").toString());

        var outcome = service.backup(root, target, env);
        if (!outcome.success()) {
            return null;
        }
        realArchive = outcome.result().databaseDump();
        return realArchive;
    }

    /**
     * Builds a complete, self-consistent set on disk.
     *
     * <p>Every size and digest is computed from the actual bytes, and the
     * recovery boundary is the earlier of the two snapshot instants, so the
     * fixture is internally coherent exactly as a real set must be.
     */
    private Path buildValidSet(Path root) throws Exception {
        Path set = Files.createDirectories(root.resolve(
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));

        Path archive = realArchive();
        if (archive != null) {
            Files.copy(archive, set.resolve("database.dump"));
        } else {
            Files.writeString(set.resolve("database.dump"),
                    "PGDMP-placeholder", StandardCharsets.UTF_8);
        }
        Files.writeString(set.resolve("globals.sql"),
                "-- PostgreSQL database cluster dump\nCREATE ROLE postgres;\n",
                StandardCharsets.UTF_8);

        Path vaultFile = set.resolve("vault/org-a/1_abc_cert.pdf");
        Files.createDirectories(vaultFile.getParent());
        Files.writeString(vaultFile, "CARBONFLOW EVIDENCE\n", StandardCharsets.UTF_8);
        long vaultBytes = Files.size(vaultFile);

        Path indexPath = set.resolve("vault-integrity.json");
        Files.writeString(indexPath, """
                {
                  "indexVersion" : "1",
                  "generatedAt" : "2026-10-01T16:00:00Z",
                  "copiedWith" : "robocopy",
                  "fileCount" : 1,
                  "totalBytes" : %d,
                  "files" : [ {
                    "relativePath" : "org-a/1_abc_cert.pdf",
                    "sizeBytes" : %d,
                    "sha256" : "%s"
                  } ]
                }
                """.formatted(vaultBytes, vaultBytes, digest(vaultFile)),
                StandardCharsets.UTF_8);

        // databaseSnapshotAt 16:00:03, vaultSnapshotAt 16:00:08, so the
        // earlier-of-two boundary is 16:00:03.
        Instant databaseSnapshotAt = Instant.parse("2026-10-01T16:00:03Z");
        Instant vaultSnapshotAt = Instant.parse("2026-10-01T16:00:08Z");

        RecoveryManifest manifest = new RecoveryManifest(
                RecoveryManifest.MANIFEST_VERSION,
                set.getFileName().toString(),
                Instant.parse("2026-10-01T16:00:12Z"),
                databaseSnapshotAt,   // earlier of the two
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Database("database.dump",
                        RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM,
                        databaseSnapshotAt,
                        RecoveryManifest.CHECKSUM_ALGORITHM,
                        digest(set.resolve("database.dump")),
                        "globals.sql", digest(set.resolve("globals.sql"))),
                new RecoveryManifest.EvidenceVault("vault",
                        vaultSnapshotAt, 1L, vaultBytes,
                        RecoveryManifest.CHECKSUM_ALGORITHM,
                        "vault-integrity.json", digest(indexPath), "robocopy"),
                new RecoveryManifest.Schema(
                        List.of("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8"),
                        Boolean.TRUE, 38),
                RecoveryManifest.Verification.pending(),
                List.of("test set"));
        new RecoveryManifestWriter(Clock.systemUTC()).write(manifest, set);
        return set;
    }

    private String digest(Path file) throws IOException {
        return RecoveryDigest.sha256(file);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a valid set")
    class Valid {

        @Test
        @DisplayName("all checks pass and the set is usable for restore")
        void validSetVerifies(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);

            VerificationOutcome outcome = verifier().verify(set);

            if (realArchive() == null) {
                // Without real tooling the archive-structure check cannot run, so
                // UNVERIFIABLE is the correct and only honest result.
                assertThat(outcome.status()).isEqualTo(VerificationStatus.UNVERIFIABLE);
                return;
            }
            assertThat(outcome.status())
                    .as("findings: %s", outcome.findings())
                    .isEqualTo(VerificationStatus.VERIFIED);
            assertThat(outcome.isUsableForRestore()).isTrue();
            assertThat(outcome.findings()).isEmpty();
            assertThat(outcome.checks()).allSatisfy(c ->
                    assertThat(c.ran()).as("%s must actually run", c.name()).isTrue());
        }

        @Test
        @DisplayName("the archive-structure check is honest when pg_restore is absent")
        void archiveCheckIsHonestAboutMissingTooling(@TempDir Path temp) throws Exception {
            // Run with an environment that cannot resolve pg_restore at all.
            Path set = buildValidSet(temp);
            BackupVerifier blind = new BackupVerifier(
                    new RecoveryManifestWriter(Clock.systemUTC()),
                    Map.of("PATH", ""));

            VerificationOutcome outcome = blind.verify(set);

            assertThat(outcome.status())
                    .as("no tooling means no verdict, not a pass")
                    .isEqualTo(VerificationStatus.UNVERIFIABLE);
            assertThat(outcome.findings())
                    .anyMatch(f -> f.contains("pg_restore"));
            assertThat(outcome.isUsableForRestore()).isFalse();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("corruption must always be detected")
    class Corruption {

        @Test
        @DisplayName("a modified database.dump is detected")
        void modifiedDatabaseDumpIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.writeString(set.resolve("database.dump"),
                    "PGDMP-TAMPERED", StandardCharsets.UTF_8);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .anyMatch(f -> f.contains("database.dump.digest"));
        }

        @Test
        @DisplayName("a modified globals.sql is detected")
        void modifiedGlobalsIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.writeString(set.resolve("globals.sql"),
                    "-- tampered\nDROP ROLE postgres;\n", StandardCharsets.UTF_8);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .as("corrupt globals would restore an unusable RBAC state")
                    .anyMatch(f -> f.contains("globals.sql.digest"));
        }

        @Test
        @DisplayName("a deleted database dump is detected")
        void deletedDumpIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.delete(set.resolve("database.dump"));

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .anyMatch(f -> f.contains("database.dump.present"));
        }

        @Test
        @DisplayName("an empty database dump is detected")
        void emptyDumpIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.write(set.resolve("database.dump"), new byte[0]);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .as("a zero-byte dump must never pass an existence check")
                    .anyMatch(f -> f.contains("nonEmpty"));
        }

        @Test
        @DisplayName("a modified evidence file is detected")
        void modifiedEvidenceFileIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.writeString(set.resolve("vault/org-a/1_abc_cert.pdf"),
                    "TAMPERED EVIDENCE\n", StandardCharsets.UTF_8);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .as("altered audit evidence must be caught by its digest")
                    .anyMatch(f -> f.contains("vault.files"));
        }

        @Test
        @DisplayName("a deleted evidence file is detected")
        void deletedEvidenceFileIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.delete(set.resolve("vault/org-a/1_abc_cert.pdf"));

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .anyMatch(f -> f.contains("vault.files"));
        }

        @Test
        @DisplayName("a truncated evidence file is detected")
        void truncatedEvidenceFileIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.write(set.resolve("vault/org-a/1_abc_cert.pdf"), new byte[0]);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings()).anyMatch(f -> f.contains("vault.files"));
        }

        @Test
        @DisplayName("a modified integrity index is detected")
        void modifiedIntegrityIndexIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            String json = Files.readString(set.resolve("vault-integrity.json"));
            Files.writeString(set.resolve("vault-integrity.json"),
                    json.replace("robocopy", "rsync    "), StandardCharsets.UTF_8);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings())
                    .as("an index could otherwise be swapped wholesale")
                    .anyMatch(f -> f.contains("vault-integrity.json.digest"));
        }

        @Test
        @DisplayName("a removed integrity index is detected")
        void removedIntegrityIndexIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.delete(set.resolve("vault-integrity.json"));

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings()).anyMatch(f -> f.contains("vault.index"));
        }

        @Test
        @DisplayName("an unparseable manifest is detected")
        void unparseableManifestIsDetected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.writeString(set.resolve("manifest.json"),
                    "{ this is not json", StandardCharsets.UTF_8);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings()).anyMatch(f -> f.contains("manifest.present"));
        }

        @Test
        @DisplayName("an absent manifest makes the set UNVERIFIABLE, not verified")
        void absentManifestIsUnverifiable(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            Files.delete(set.resolve("manifest.json"));

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.UNVERIFIABLE);
            assertThat(outcome.isUsableForRestore()).isFalse();
            assertThat(outcome.findings())
                    .anyMatch(f -> f.contains("never finalised"));
        }

        @Test
        @DisplayName("an index path escaping the set is refused")
        void escapingIndexPathIsRefused(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            String json = Files.readString(set.resolve("vault-integrity.json"));
            Files.writeString(set.resolve("vault-integrity.json"),
                    json.replace("\"org-a/1_abc_cert.pdf\"", "\"../../etc/passwd\""),
                    StandardCharsets.UTF_8);
            // Re-sign the index so only the path rule can catch it.
            RecoveryManifest manifest = new RecoveryManifestWriter(Clock.systemUTC())
                    .readIfPresent(set);
            RecoveryManifest updated = new RecoveryManifest(
                    manifest.manifestVersion(), manifest.backupSetId(),
                    manifest.createdAt(), manifest.recoveryBoundaryAt(),
                    manifest.application(), manifest.database(),
                    new RecoveryManifest.EvidenceVault("vault",
                            manifest.evidenceVault().backupCreatedAt(),
                            manifest.evidenceVault().fileCount(),
                            manifest.evidenceVault().totalBytes(),
                            manifest.evidenceVault().checksumAlgorithm(),
                            "vault-integrity.json",
                            digest(set.resolve("vault-integrity.json")),
                            manifest.evidenceVault().copiedWith()),
                    manifest.schema(), manifest.verification(), manifest.notes());
            new RecoveryManifestWriter(Clock.systemUTC()).write(updated, set);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings()).anyMatch(f -> f.contains("vault.files"));
        }

        @Test
        @DisplayName("a manifest with an impossible boundary is rejected")
        void impossibleBoundaryIsRejected(@TempDir Path temp) throws Exception {
            Path set = buildValidSet(temp);
            RecoveryManifest original =
                    new RecoveryManifestWriter(Clock.systemUTC()).readIfPresent(set);

            // Boundary AFTER both snapshots is impossible for an earlier-of-two rule.
            RecoveryManifest broken = new RecoveryManifest(
                    original.manifestVersion(), original.backupSetId(),
                    original.createdAt(),
                    Instant.parse("2026-10-01T17:00:00Z"),
                    original.application(), original.database(), original.evidenceVault(),
                    original.schema(), original.verification(), original.notes());
            new RecoveryManifestWriter(Clock.systemUTC()).write(broken, set);

            VerificationOutcome outcome = verifier().verify(set);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
            assertThat(outcome.findings()).anyMatch(f -> f.contains("impossible"));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("state derivation")
    class States {

        @Test
        @DisplayName("all passing checks yield VERIFIED")
        void allPassingYieldsVerified() {
            VerificationOutcome outcome = VerificationOutcome.from(List.of(
                    VerificationOutcome.Check.passed("a", "ok"),
                    VerificationOutcome.Check.passed("b", "ok")));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.VERIFIED);
        }

        @Test
        @DisplayName("a failed check yields FAILED even if others could not run")
        void failureOutranksUnverifiable() {
            // A definite defect is the stronger statement and must not be
            // downgraded to "we could not tell".
            VerificationOutcome outcome = VerificationOutcome.from(List.of(
                    VerificationOutcome.Check.failed("a", "broken"),
                    VerificationOutcome.Check.unverifiable("b", "no tooling")));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.FAILED);
        }

        @Test
        @DisplayName("an unrunnable check yields UNVERIFIABLE, never VERIFIED")
        void unrunnableYieldsUnverifiable() {
            VerificationOutcome outcome = VerificationOutcome.from(List.of(
                    VerificationOutcome.Check.passed("a", "ok"),
                    VerificationOutcome.Check.unverifiable("b", "no tooling")));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.UNVERIFIABLE);
            assertThat(outcome.isUsableForRestore())
                    .as("cannot-check is not checked-and-fine")
                    .isFalse();
        }

        @Test
        @DisplayName("the state is derived, never supplied by the caller")
        void stateCannotBeForged() {
            // There is deliberately no way to construct a VERIFIED outcome from
            // failing checks.
            VerificationOutcome outcome = VerificationOutcome.from(List.of(
                    VerificationOutcome.Check.failed("only", "no")));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.VERIFIED);
            assertThat(outcome.isVerified()).isFalse();
        }
    }
}