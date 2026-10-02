package com.carbonflow.recovery.coordination;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.VerificationStatus;
import com.carbonflow.recovery.postgres.PostgreSqlBackupService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import com.carbonflow.recovery.vault.EvidencePathGuard;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.testsupport.EmbeddedPg;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-05 — coordinated recovery boundary.
 *
 * <p>The end-to-end tests run a <b>real</b> {@code pg_dump} against the
 * repository's hermetic {@link EmbeddedPg} server, because the property under
 * test is that the assembled set is genuinely coherent — a mocked database
 * backup would prove only that the mocks were called in order.
 *
 * <p>Where {@code pg_dump} is unavailable the end-to-end assertions are skipped
 * rather than faked; the pure boundary-arithmetic tests always run.
 */
class RecoverySetCoordinatorTest {

    private static boolean toolingAvailable;

    @BeforeAll
    static void detectTooling() {
        toolingAvailable = findTool("pg_dump") != null && findTool("pg_dumpall") != null;
    }

    private static Path findTool(String name) {
        for (String var : new String[]{PostgreSqlToolLocator.ENV_PG_DUMP,
                PostgreSqlToolLocator.ENV_PG_DUMPALL}) {
            String configured = System.getenv(var);
            if (configured != null) {
                String file = Path.of(configured).getFileName().toString();
                String bare = file.endsWith(".exe") ? file.substring(0, file.length() - 4) : file;
                if (bare.equals(name)) {
                    return Path.of(configured);
                }
            }
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
        // The PostgreSQL install layout on the dev host — discovery only in the
        // test, never in production code.
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

    private static Map<String, String> toolEnvironment() {
        Path dump = findTool("pg_dump");
        Path dumpAll = findTool("pg_dumpall");
        Map<String, String> env = new java.util.HashMap<>();
        env.put(PostgreSqlToolLocator.ENV_PG_DUMP,
                dump != null ? dump.toString() : "absent-pg_dump");
        env.put(PostgreSqlToolLocator.ENV_PG_DUMPALL,
                dumpAll != null ? dumpAll.toString() : "absent-pg_dumpall");
        return env;
    }

    private RecoverySetCoordinator coordinator() {
        return new RecoverySetCoordinator(
                PostgreSqlBackupService.productionDefaults(),
                EvidenceVaultBackupService.productionDefaults(),
                new RecoveryManifestWriter(Clock.systemUTC()),
                Clock.systemUTC());
    }

    private PostgreSqlBackupTarget embeddedTarget() {
        String jdbc = EmbeddedPg.jdbcUrl();
        String afterProtocol = jdbc.substring(jdbc.indexOf("//") + 2);
        String hostPort = afterProtocol.substring(0, afterProtocol.indexOf('/'));
        int port = Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1));
        return new PostgreSqlBackupTarget("127.0.0.1", port, "postgres",
                EmbeddedPg.username(), EmbeddedPg.password().toCharArray(), "prefer");
    }

    private String sha256Of(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private RecoverySetCoordinator.Request request(Path backupRoot, Path vaultRoot,
                                                   List<EvidenceVaultBackupService.RequiredFile> files) {
        return new RecoverySetCoordinator.Request(
                backupRoot, vaultRoot, embeddedTarget(), toolEnvironment(), files,
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Schema(
                        List.of("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8"),
                        Boolean.TRUE, 38),
                "robocopy");
    }

    /** A quiesce guard that records whether it was closed. */
    private static final class RecordingGuard implements QuiesceGuard {
        boolean closed;
        private final boolean effective;

        RecordingGuard(boolean effective) {
            this.effective = effective;
        }

        @Override
        public QuiesceGuard begin() {
            return this;
        }

        @Override
        public boolean isEffective() {
            return effective;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // ------------------------------------------------------------------
    // Pure logic — always runs
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("boundary arithmetic")
    class Boundary {

        @Test
        @DisplayName("the boundary is the earlier of the two snapshot instants")
        void boundaryIsTheEarlierInstant() {
            Instant db = Instant.parse("2026-10-01T10:00:05Z");
            Instant vault = Instant.parse("2026-10-01T10:00:17Z");

            assertThat(RecoverySetCoordinator.earlier(db, vault))
                    .as("the example in the design: 10:00:05, not 10:00:17")
                    .isEqualTo(db);
            assertThat(RecoverySetCoordinator.earlier(vault, db)).isEqualTo(db);
        }

        @Test
        @DisplayName("identical instants are handled")
        void equalInstantsAreHandled() {
            Instant same = Instant.parse("2026-10-01T10:00:05Z");
            assertThat(RecoverySetCoordinator.earlier(same, same)).isEqualTo(same);
        }

        @Test
        @DisplayName("the no-op quiesce guard reports that it is not effective")
        void noOpGuardIsNotEffective() {
            QuiesceGuard guard = new QuiesceGuard.NoOp();

            assertThat(guard.begin().isEffective())
                    .as("a no-op must never be mistaken for real quiescence")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    // End to end — needs real pg_dump
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("end to end against a real PostgreSQL")
    @org.junit.jupiter.api.condition.EnabledIf(
            "com.carbonflow.recovery.coordination.RecoverySetCoordinatorTest#toolingPresent")
    class EndToEnd {

        @Test
        @DisplayName("a complete set contains every artefact and a last-written manifest")
        void completeSetContainsEverything(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path root = Files.createDirectories(temp.resolve("root"));
            String content = "CARBONFLOW EVIDENCE DRILL\n";
            Path evidence = vault.resolve("org-a/1_abc_cert.pdf");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, content, StandardCharsets.UTF_8);

            RecordingGuard guard = new RecordingGuard(true);
            var result = coordinator().run(
                    request(root, vault, List.of(
                            new EvidenceVaultBackupService.RequiredFile(
                                    evidence.toString(), sha256Of(content),
                                    content.length()))),
                    guard);

            assertThat(result.failureReason()).as("backup must succeed").isNull();
            assertThat(result.success()).isTrue();
            assertThat(guard.closed).as("quiesce must always be released").isTrue();

            Path set = result.setDirectory();
            assertThat(set.resolve("database.dump")).exists();
            assertThat(set.resolve("globals.sql")).exists();
            assertThat(set.resolve("vault/org-a/1_abc_cert.pdf")).exists();
            assertThat(set.resolve("vault-integrity.json")).exists();
            assertThat(set.resolve("manifest.json"))
                    .as("the manifest is the last artefact written")
                    .exists();
        }

        @Test
        @DisplayName("the manifest records the earlier of the two snapshot instants")
        void manifestRecordsTheEarlierBoundary(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var result = coordinator().run(request(root, vault, List.of()),
                    new RecordingGuard(true));

            assertThat(result.success()).isTrue();
            RecoveryManifest manifest = result.manifest();
            assertThat(manifest.recoveryBoundaryAt())
                    .as("boundary = min(databaseSnapshotAt, vaultSnapshotAt)")
                    .isBeforeOrEqualTo(manifest.database().backupCreatedAt());
            assertThat(manifest.recoveryBoundaryAt())
                    .isBeforeOrEqualTo(manifest.evidenceVault().backupCreatedAt());
            assertThat(manifest.recoveryBoundaryAt())
                    .as("never the manifest creation time")
                    .isBeforeOrEqualTo(manifest.createdAt());
        }

        @Test
        @DisplayName("the manifest is PENDING, never VERIFIED")
        void manifestIsPendingNotVerified(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var result = coordinator().run(request(root, vault, List.of()),
                    new RecordingGuard(true));

            assertThat(result.manifest().verification().status())
                    .as("REC-05 performs no verification; REC-06 owns that")
                    .isEqualTo(VerificationStatus.PENDING);
        }

        @Test
        @DisplayName("the manifest notes that this is not an atomic backup")
        void manifestRecordsTheNonAtomicity(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var result = coordinator().run(request(root, vault, List.of()),
                    new RecordingGuard(true));

            assertThat(result.manifest().notes())
                    .as("no atomic DB/filesystem claim may be implied")
                    .anyMatch(n -> n.contains("NOT an atomic"));
        }

        @Test
        @DisplayName("an unquiesced backup says so in the manifest")
        void unquiescedBackupIsDeclared(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var result = coordinator().run(request(root, vault, List.of()),
                    new QuiesceGuard.NoOp());

            assertThat(result.success()).isTrue();
            assertThat(result.quiesced()).isFalse();
            assertThat(result.manifest().notes())
                    .as("an unquiesced set must not read as consistent")
                    .anyMatch(n -> n.contains("were NOT quiesced"));
        }

        @Test
        @DisplayName("one backupSetId binds the database, vault and manifest")
        void oneSetIdBindsEveryComponent(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var result = coordinator().run(request(root, vault, List.of()),
                    new RecordingGuard(true));

            // The set directory name and the manifest identity must agree, or a
            // verifier could not tie the artefacts to one another.
            assertThat(result.setDirectory().getFileName().toString())
                    .isEqualTo(result.backupSetId());
            assertThat(result.manifest().backupSetId())
                    .isEqualTo(result.backupSetId());
        }

        @Test
        @DisplayName("a vault failure fails the whole set and writes no manifest")
        void vaultFailureFailsTheWholeSet(@TempDir Path temp) throws Exception {
            Path vault = Files.createDirectories(temp.resolve("vault"));
            Path root = Files.createDirectories(temp.resolve("root"));

            var result = coordinator().run(request(root, vault, List.of(
                            new EvidenceVaultBackupService.RequiredFile(
                                    vault.resolve("org-a/missing.txt").toString(),
                                    "a".repeat(64), 10L))),
                    new RecordingGuard(true));

            assertThat(result.success()).isFalse();
            assertThat(result.manifest()).isNull();
            assertThat(result.failureReason()).contains("vault backup failed");

            // No manifest anywhere in the set: incompleteness is self-evident.
            try (var walk = Files.walk(root)) {
                assertThat(walk.filter(p -> p.getFileName().toString()
                                .equals("manifest.json")).count())
                        .as("a failed set must never carry a manifest")
                        .isZero();
            }
        }

        @Test
        @DisplayName("a database failure fails the whole set and writes no manifest")
        void databaseFailureFailsTheWholeSet(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var request = new RecoverySetCoordinator.Request(
                    root, vault, embeddedTarget(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, "absent-tool",
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, "absent-tool"),
                    List.of(),
                    RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                    new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                    "robocopy");

            var result = coordinator().run(request, new RecordingGuard(true));

            assertThat(result.success()).isFalse();
            assertThat(result.manifest()).isNull();
            assertThat(result.failureReason()).contains("database backup failed");
        }

        @Test
        @DisplayName("quiesce is released even when the backup fails")
        void quiesceIsReleasedOnFailure(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            RecordingGuard guard = new RecordingGuard(true);
            var request = new RecoverySetCoordinator.Request(
                    root, vault, embeddedTarget(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, "absent-tool",
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, "absent-tool"),
                    List.of(),
                    RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                    new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                    "robocopy");

            coordinator().run(request, guard);

            assertThat(guard.closed)
                    .as("leaving writes blocked would turn a backup fault into an outage")
                    .isTrue();
        }

        @Test
        @DisplayName("the vault copy is contained inside the set directory")
        void vaultCopyIsContained(@TempDir Path temp) {
            Path vault = temp.resolve("vault");
            Path root = temp.resolve("root");
            assertThat(vault.toFile().mkdirs()).isTrue();
            assertThat(root.toFile().mkdirs()).isTrue();

            var result = coordinator().run(request(root, vault, List.of()),
                    new RecordingGuard(true));

            Path vaultDir = result.setDirectory().resolve("vault")
                    .toAbsolutePath().normalize();
            Path resolved = EvidencePathGuard.resolveInsideSet(vaultDir, "org-a/x.txt");
            assertThat(resolved.toString())
                    .as("a vault destination must stay inside the set")
                    .startsWith(vaultDir.toString());
        }
    }

    /** Used by {@code @EnabledIf}. */
    static boolean toolingPresent() {
        return toolingAvailable;
    }
}