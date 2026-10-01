package com.carbonflow.recovery.vault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REC-04 — Evidence Vault backup automation.
 *
 * <p>Pure unit tests over a temporary directory tree standing in for the vault.
 * No PostgreSQL, no Spring context, and — critically — no CarbonFlow
 * application instance, because this class must be usable when the database is
 * unreachable.
 */
class EvidenceVaultBackupServiceTest {

    private static final Instant FIXED = Instant.parse("2026-10-01T16:00:00Z");

    private EvidenceVaultBackupService service() {
        return new EvidenceVaultBackupService(
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private String sha256Of(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private Path writeVaultFile(Path vaultRoot, String relative, String content)
            throws IOException {
        Path file = vaultRoot.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("single and multiple files")
    class Copying {

        @Test
        @DisplayName("one evidence file is copied with its digest")
        void singleFileIsCopied(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            String content = "CARBONFLOW EVIDENCE\n";
            writeVaultFile(vault, "org-a/1_abc_doc.pdf", content);

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/1_abc_doc.pdf").toString(),
                            sha256Of(content), content.length())),
                    "robocopy");

            assertThat(result.success()).isTrue();
            assertThat(result.fileCount()).isEqualTo(1);
            assertThat(result.totalBytes()).isEqualTo(content.length());
            assertThat(Files.readString(set.resolve("vault/org-a/1_abc_doc.pdf")))
                    .isEqualTo(content);
        }

        @Test
        @DisplayName("nested tenant paths are preserved")
        void nestedPathsArePreserved(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/deep/nested/f1.txt", "one");
            writeVaultFile(vault, "org-b/f2.txt", "two");

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/deep/nested/f1.txt").toString(),
                            sha256Of("one"), 3L),
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-b/f2.txt").toString(),
                            sha256Of("two"), 3L)), "robocopy");

            assertThat(result.success()).isTrue();
            assertThat(result.fileCount()).isEqualTo(2);
            assertThat(Files.exists(set.resolve("vault/org-a/deep/nested/f1.txt"))).isTrue();
            assertThat(Files.exists(set.resolve("vault/org-b/f2.txt"))).isTrue();
        }

        @Test
        @DisplayName("a file present on disk but absent from the database is still preserved")
        void orphanedFilesArePreserved(@TempDir Path temp) throws Exception {
            // Losing audit bytes because a metadata row was deleted would be
            // silent data loss. The file is preserved even though no row names it.
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/known.txt", "known");
            writeVaultFile(vault, "org-a/orphan.txt", "orphaned");

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/known.txt").toString(),
                            sha256Of("known"), 5L)), "robocopy");

            assertThat(result.success()).isTrue();
            assertThat(result.fileCount())
                    .as("an unreferenced file must not be silently dropped")
                    .isEqualTo(2);
            assertThat(Files.readString(set.resolve("vault/org-a/orphan.txt")))
                    .isEqualTo("orphaned");
        }

        @Test
        @DisplayName("an empty vault produces a valid empty index")
        void emptyVaultIsValid(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));

            var result = service().backup(vault, set, List.of(), "robocopy");

            assertThat(result.success()).isTrue();
            assertThat(result.fileCount()).isZero();
            var index = service().readIndex(set);
            assertThat(index.files()).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("integrity index")
    class Integrity {

        @Test
        @DisplayName("the index records path, size and digest for every file")
        void indexRecordsEveryFile(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/f1.txt", "alpha");
            writeVaultFile(vault, "org-b/f2.txt", "beta");

            var result = service().backup(vault, set, List.of(), "robocopy");
            assertThat(result.success()).isTrue();

            var index = service().readIndex(set);
            assertThat(index.indexVersion())
                    .isEqualTo(EvidenceVaultIntegrityIndex.INDEX_VERSION);
            assertThat(index.copiedWith())
                    .as("the rsync/robocopy deviation must stay visible")
                    .isEqualTo("robocopy");
            assertThat(index.files()).hasSize(2);
            assertThat(index.files())
                    .extracting(EvidenceVaultIntegrityIndex.Entry::relativePath)
                    .containsExactly("org-a/f1.txt", "org-b/f2.txt");
            assertThat(index.files())
                    .extracting(EvidenceVaultIntegrityIndex.Entry::sha256)
                    .containsExactlyInAnyOrder(sha256Of("alpha"), sha256Of("beta"));
            assertThat(index.totalBytes()).isEqualTo(9);
        }

        @Test
        @DisplayName("the index contains no absolute paths or tenant identifiers")
        void indexLeaksNothing(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-secret/f1.txt", "alpha");

            service().backup(vault, set, List.of(), "robocopy");
            String json = Files.readString(set.resolve("vault-integrity.json"));

            assertThat(json)
                    .as("an absolute source path would leak host layout")
                    .doesNotContain(temp.toAbsolutePath().toString());
            assertThat(json).contains("org-secret/f1.txt");
        }

        @Test
        @DisplayName("a large file is copied and hashed without loading it into memory")
        void largeFileIsStreamed(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            Files.createDirectories(vault.resolve("org-a"));
            Path large = vault.resolve("org-a/large.bin");
            byte[] chunk = new byte[1024 * 1024];
            try (var out = Files.newOutputStream(large)) {
                for (int i = 0; i < 5; i++) {
                    out.write(chunk);
                }
            }

            var result = service().backup(vault, set, List.of(), "robocopy");

            assertThat(result.success()).isTrue();
            assertThat(result.totalBytes()).isEqualTo(5L * 1024 * 1024);
            assertThat(Files.size(set.resolve("vault/org-a/large.bin")))
                    .isEqualTo(5L * 1024 * 1024);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("path safety — the database must not become a read primitive")
    class PathSafety {

        @Test
        @DisplayName("a traversal storage_path is refused")
        void traversalIsRefused(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            "../../../../etc/passwd", null, 0L)), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason())
                    .as("database content must never drive a read outside the vault")
                    .contains("unsafe");
        }

        @Test
        @DisplayName("an absolute path outside the vault is refused")
        void absolutePathOutsideVaultIsRefused(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            Path secret = temp.resolve("secret.txt");
            Files.writeString(secret, "TOP SECRET");

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            secret.toString(), null, 0L)), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("unsafe");
            assertThat(Files.exists(set.resolve("vault")))
                    .as("no vault directory may survive a refused path")
                    .isFalse();
        }

        @Test
        @DisplayName("the guard itself rejects escapes directly")
        void guardRejectsEscapes(@TempDir Path vault) throws IOException {
            Files.createDirectories(vault);
            assertThatThrownBy(() -> EvidencePathGuard.resolveRelativeToVault(
                    vault.resolve("vault"), vault.resolve("../outside").toString()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a destination that escapes the set is refused")
        void destinationEscapeIsRefused(@TempDir Path temp) throws IOException {
            Path set = temp.resolve("set");
            assertThatThrownBy(() -> EvidencePathGuard.resolveInsideSet(
                    set, "../escaped.txt"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a blank storage_path is refused")
        void blankStoragePathIsRefused(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile("   ", null, 0L)),
                    "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("blank");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("fail-closed behaviour")
    class Failure {

        @Test
        @DisplayName("a database-referenced file that is missing fails the backup")
        void missingRequiredFileFails(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/gone.txt").toString(),
                            "a".repeat(64), 5L)), "robocopy");

            assertThat(result.success())
                    .as("metadata referencing missing bytes is reportable data loss")
                    .isFalse();
            assertThat(result.failureReason()).contains("missing");
        }

        @Test
        @DisplayName("an empty required file fails the backup")
        void emptyRequiredFileFails(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            Files.createDirectories(vault.resolve("org-a"));
            Files.createFile(vault.resolve("org-a/empty.txt"));

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/empty.txt").toString(),
                            sha256Of(""), 0L)), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("empty");
        }

        @Test
        @DisplayName("a digest mismatch fails the backup and discards the copy")
        void digestMismatchFails(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/f.txt", "real content");

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/f.txt").toString(),
                            "b".repeat(64), 12L)), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("do not match");
            assertThat(Files.exists(set.resolve("vault/org-a/f.txt")))
                    .as("unverified bytes must not be left in the set")
                    .isFalse();
        }

        @Test
        @DisplayName("a recorded size mismatch fails the backup")
        void sizeMismatchFails(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/f.txt", "twelve chars");

            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/f.txt").toString(),
                            sha256Of("twelve chars"), 999L)), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("size differs");
        }

        @Test
        @DisplayName("a missing vault root fails rather than producing an empty set")
        void missingVaultRootFails(@TempDir Path temp) throws Exception {
            Path set = Files.createDirectories(temp.resolve("set"));

            var result = service().backup(temp.resolve("no-such-vault"), set,
                    List.of(), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("not a readable directory");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("partial cleanup")
    class Cleanup {

        @Test
        @DisplayName("a failed vault backup leaves no vault directory or index")
        void partialVaultIsRemoved(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/ok.txt", "fine");
            writeVaultFile(vault, "org-a/bad.txt", "corrupt");

            // Second file's digest is wrong, so the copy aborts after the first
            // has already been written.
            var result = service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/ok.txt").toString(),
                            sha256Of("fine"), 4L),
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("org-a/bad.txt").toString(),
                            "c".repeat(64), 7L)), "robocopy");

            assertThat(result.success()).isFalse();
            assertThat(Files.exists(set.resolve("vault")))
                    .as("a partial vault must not survive as if complete")
                    .isFalse();
            assertThat(Files.exists(set.resolve("vault-integrity.json"))).isFalse();
        }

        @Test
        @DisplayName("the database artefacts written by REC-03 are preserved")
        void databaseArtefactsSurviveVaultFailure(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            Path dump = set.resolve("database.dump");
            Path globals = set.resolve("globals.sql");
            Files.writeString(dump, "PGDMP", StandardCharsets.UTF_8);
            Files.writeString(globals, "-- globals", StandardCharsets.UTF_8);

            service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("nope.txt").toString(), "d".repeat(64), 1L)),
                    "robocopy");

            assertThat(Files.exists(dump))
                    .as("cleanup must be scoped to REC-04 artefacts only")
                    .isTrue();
            assertThat(Files.exists(globals)).isTrue();
        }

        @Test
        @DisplayName("neighbouring backup sets are never touched")
        void neighbouringSetsAreUntouched(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path root = Files.createDirectories(temp.resolve("root"));
            Path neighbour = root.resolve("00000000-0000-4000-8000-000000000000");
            Files.createDirectories(neighbour.resolve("vault"));
            Path neighbourFile = neighbour.resolve("vault/keep.txt");
            Files.writeString(neighbourFile, "precious", StandardCharsets.UTF_8);

            Path set = Files.createDirectories(root.resolve(
                    "11111111-1111-4111-8111-111111111111"));
            service().backup(vault, set, List.of(
                    new EvidenceVaultBackupService.RequiredFile(
                            vault.resolve("missing.txt").toString(), "e".repeat(64), 1L)),
                    "robocopy");

            assertThat(neighbourFile).exists();
            assertThat(Files.readString(neighbourFile)).isEqualTo("precious");
        }

        @Test
        @DisplayName("no temporary index file is left behind")
        void noTemporaryFileRemains(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path set = Files.createDirectories(temp.resolve("set"));
            writeVaultFile(vault, "org-a/f.txt", "x");

            service().backup(vault, set, List.of(), "robocopy");

            assertThat(Files.exists(set.resolve("vault-integrity.json.tmp"))).isFalse();
        }
    }
}