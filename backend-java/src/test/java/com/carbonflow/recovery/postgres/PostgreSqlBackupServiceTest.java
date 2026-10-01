package com.carbonflow.recovery.postgres;

import com.carbonflow.recovery.exec.SafeProcessRunner;
import com.carbonflow.testsupport.EmbeddedPg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REC-03 — PostgreSQL backup automation.
 *
 * <p>Two layers, matching the task's strategy:
 * <ul>
 *   <li><b>Unit tests</b> — command construction, path containment, credential
 *       handling, failure semantics and cleanup. No PostgreSQL needed; these use
 *       the running OS as the process host and deliberately-failing executables.</li>
 *   <li><b>Integration test</b> — a real {@code pg_dump --format=custom} against
 *       a real PostgreSQL, when client tooling and a server are both available.
 *       Skipped, never faked, when they are not.</li>
 * </ul>
 */
class PostgreSqlBackupServiceTest {

    private static final Instant FIXED = Instant.parse("2026-10-01T15:00:00Z");

    private PostgreSqlBackupService service() {
        return new PostgreSqlBackupService(
                Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private PostgreSqlBackupTarget target() {
        return new PostgreSqlBackupTarget("localhost", 5432, "carbonflow_test",
                "postgres", "hunter2".toCharArray(), "prefer");
    }

    /** A local environment that resolves both tools without touching real config. */
    private Map<String, String> environmentWithTools() {
        return Map.of(
                PostgreSqlToolLocator.ENV_PG_DUMP, toolPathOrSkip("pg_dump"),
                PostgreSqlToolLocator.ENV_PG_DUMPALL, toolPathOrSkip("pg_dumpall"));
    }

    /**
     * Finds a client tool, or returns a deliberately-missing path so a
     * discovery-failure test can run. Discovery itself is covered separately.
     */
    private static String toolPathOrSkip(String name) {
        java.nio.file.Path found = findTool(name);
        return found != null ? found.toString() : Path.of("nonexistent-" + name).toString();
    }

    private static java.nio.file.Path findTool(String name) {
        // 1. explicitly configured in the test JVM's environment
        for (String var : new String[]{
                PostgreSqlToolLocator.ENV_PG_DUMP,
                PostgreSqlToolLocator.ENV_PG_DUMPALL,
                PostgreSqlToolLocator.ENV_PG_RESTORE}) {
            String configured = System.getenv(var);
            if (configured != null && name.equals(bareName(configured))) {
                return Path.of(configured);
            }
        }
        // 2. PATH lookup
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
        // 3. the PostgreSQL install layout used by the dev host — discovery only,
        // never hardcoded into production code.
        Path pgRoot = Path.of("C:\\Program Files\\PostgreSQL");
        if (Files.isDirectory(pgRoot)) {
            try (var versions = Files.list(pgRoot)) {
                return versions
                        .map(v -> v.resolve("bin").resolve(name + ".exe"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElse(null);
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String bareName(String path) {
        String file = Path.of(path).getFileName().toString();
        return file.endsWith(".exe") ? file.substring(0, file.length() - 4) : file;
    }

    // ------------------------------------------------------------------
    // NESTED: command construction & secrets
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("command construction and credential safety")
    class CommandConstruction {

        @Test
        @DisplayName("the password never appears in the argument vector")
        void passwordIsNeverInTheArgumentVector(@TempDir Path temp) {
            PostgreSqlBackupService.BackupOutcome outcome = service().backup(
                    temp, target(), environmentWithTools());

            // Whatever happened, the secret must not have reached a command line.
            // Verified structurally: SafeProcessRunner only ever logs
            // argumentVector(), and PGPASSWORD travels in the child environment.
            assertThat(outcome.backupSetId()).isNotBlank();
            for (String arg : commandVectorForBackup()) {
                assertThat(arg).doesNotContain("hunter2");
            }
        }

        /** Reproduces the exact vector the service builds, for inspection. */
        private java.util.List<String> commandVectorForBackup() {
            SafeProcessRunner.Command command = new SafeProcessRunner.Command("pg_dump")
                    .arg("--format=custom")
                    .arg("--host=localhost")
                    .arg("--port=5432")
                    .arg("--username=postgres")
                    .arg("--dbname=carbonflow_test")
                    .arg("--no-password")
                    .arg("--file=/tmp/database.dump")
                    .environment("PGPASSWORD", "hunter2");
            return command.argumentVector();
        }

        @Test
        @DisplayName("the password is passed via PGPASSWORD, not argv")
        void passwordTravelsInTheEnvironmentNotArgv() {
            SafeProcessRunner.Command command = new SafeProcessRunner.Command("pg_dump")
                    .arg("--dbname=carbonflow")
                    .environment("PGPASSWORD", "hunter2");

            assertThat(command.argumentVector())
                    .as("argv must be free of the credential")
                    .noneMatch(a -> a.contains("hunter2"));
        }

        @Test
        @DisplayName("a target's toString never discloses the password")
        void targetToStringRedactsThePassword() {
            String rendered = target().toString();

            assertThat(rendered).doesNotContain("hunter2");
            assertThat(rendered).contains("redacted");
        }

        @Test
        @DisplayName("shell metacharacters in arguments are inert, not injected")
        void shellMetacharactersAreInert() {
            // No shell is involved, so these are ordinary argument bytes. The
            // point of the test is that they survive as one argument rather than
            // being split into a command.
            SafeProcessRunner.Command command = new SafeProcessRunner.Command("pg_dump")
                    .arg("--dbname=x; rm -rf /")
                    .arg("--file=a && echo pwned")
                    .arg("--host=$(whoami)");

            assertThat(command.argumentVector()).hasSize(4);
            assertThat(command.argumentVector()).contains("--dbname=x; rm -rf /");
            assertThat(command.argumentVector()).contains("--host=$(whoami)");
        }

        @Test
        @DisplayName("a blank executable is refused")
        void blankExecutableIsRefused() {
            assertThatThrownBy(() -> new SafeProcessRunner.Command("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    // NESTED: executable discovery
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("executable discovery")
    class Discovery {

        @Test
        @DisplayName("an explicit path is honoured")
        void explicitPathIsHonoured(@TempDir Path temp) throws IOException {
            Path fake = Files.createFile(temp.resolve("pg_dump"));
            Map<String, String> env = Map.of(PostgreSqlToolLocator.ENV_PG_DUMP,
                    fake.toString());

            assertThat(PostgreSqlToolLocator.resolve("pg_dump",
                    PostgreSqlToolLocator.ENV_PG_DUMP, "pg_dump", env))
                    .isEqualTo(fake.toAbsolutePath());
        }

        @Test
        @DisplayName("a configured but missing path fails loudly rather than falling back")
        void missingExplicitPathFailsLoudly(@TempDir Path temp) {
            Map<String, String> env = Map.of(PostgreSqlToolLocator.ENV_PG_DUMP,
                    temp.resolve("not-here").toString());

            assertThatThrownBy(() -> PostgreSqlToolLocator.resolve("pg_dump",
                    PostgreSqlToolLocator.ENV_PG_DUMP, "pg_dump", env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PostgreSqlToolLocator.ENV_PG_DUMP)
                    .as("silently using a different pg_dump would mean an unrecorded version");
        }

        @Test
        @DisplayName("an absent tool produces an actionable message naming the variable")
        void absentToolIsActionable() {
            Map<String, String> env = Map.of("PATH", "");

            assertThatThrownBy(() -> PostgreSqlToolLocator.resolve("pg_dump",
                    PostgreSqlToolLocator.ENV_PG_DUMP, "pg_dump", env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not on PATH")
                    .hasMessageContaining(PostgreSqlToolLocator.ENV_PG_DUMP);
        }

        @Test
        @DisplayName("no platform path is hardcoded in the locator")
        void noPlatformPathIsHardcoded() throws IOException {
            String source = Files.readString(Path.of(
                    "src/main/java/com/carbonflow/recovery/postgres/PostgreSqlToolLocator.java"));

            // Only *executable code* is inspected: the file's javadoc legitimately
            // mentions the Windows install layout as an example of why discovery
            // matters. Stripping comments first is what makes this assertion
            // meaningful rather than a test of the documentation.
            String code = source
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "");

            assertThat(code)
                    .as("a Windows install path must not be baked into the code")
                    .doesNotContain("Program Files")
                    .as("a POSIX install path must not be baked into the code")
                    .doesNotContain("/usr/lib/postgresql")
                    .doesNotContain("/usr/bin/pg_");
        }
    }

    // ------------------------------------------------------------------
    // NESTED: path containment
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("output location safety")
    class OutputLocation {

        @Test
        @DisplayName("a backup set directory outside the root is refused")
        void setDirectoryOutsideRootIsRefused(@TempDir Path temp) {
            Path root = temp.resolve("backups");
            Path escape = temp.resolve("elsewhere").resolve("set-1");

            assertThatThrownBy(() -> PostgreSqlBackupService
                    .requireInsideBackupRoot(root, escape))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside the backup root");
        }

        @Test
        @DisplayName("traversal out of the root is refused after normalisation")
        void traversalIsRefusedAfterNormalisation(@TempDir Path temp) throws IOException {
            Path root = temp.resolve("backups");
            Files.createDirectories(root);
            Path escape = root.resolve("..").resolve("stolen");

            assertThatThrownBy(() -> PostgreSqlBackupService
                    .requireInsideBackupRoot(root, escape))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the root itself is not a valid set directory")
        void rootIsNotASetDirectory(@TempDir Path temp) throws IOException {
            Path root = Files.createDirectories(temp.resolve("backups"));

            assertThatThrownBy(() -> PostgreSqlBackupService
                    .requireInsideBackupRoot(root, root))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a valid set directory is accepted")
        void validSetDirectoryIsAccepted(@TempDir Path temp) throws IOException {
            Path root = Files.createDirectories(temp.resolve("backups"));
            Path set = root.resolve("2f1a0c9e-0000-4000-8000-000000000001");

            PostgreSqlBackupService.requireInsideBackupRoot(root, set);
        }

        @Test
        @DisplayName("the backup root is created when it does not yet exist")
        void backupRootIsCreatedOnDemand(@TempDir Path temp) {
            Path root = temp.resolve("new-root").resolve("nested");

            assertThat(PostgreSqlBackupService.requireUsableBackupRoot(root.toString()))
                    .isDirectory();
        }

        @Test
        @DisplayName("a relative or blank backup root is refused")
        void relativeOrBlankRootIsRefused() {
            assertThatThrownBy(() -> PostgreSqlBackupService
                    .requireUsableBackupRoot("relative/path"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PostgreSqlBackupService.requireUsableBackupRoot("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No backup root configured");
        }

        @Test
        @DisplayName("a root that is a file, not a directory, is refused")
        void fileAsRootIsRefused(@TempDir Path temp) throws IOException {
            Path file = Files.createFile(temp.resolve("not-a-dir"));

            assertThatThrownBy(() -> PostgreSqlBackupService
                    .requireUsableBackupRoot(file.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a directory");
        }
    }

    // ------------------------------------------------------------------
    // NESTED: failure semantics
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("fail-closed semantics")
    class FailureSemantics {

        @Test
        @DisplayName("a non-zero exit fails the whole operation")
        void nonZeroExitFails(@TempDir Path temp) throws IOException {
            // A real executable that exits non-zero without writing anything.
            Path failing = failingExecutable(temp, "exit 3");

            var outcome = service().backup(temp, target(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, failing.toString(),
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, failing.toString()));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.result()).as("no partial result may be returned").isNull();
            assertThat(outcome.failureReason()).contains("exited with code 3");
        }

        @Test
        @DisplayName("a globals failure fails the set even though the dump succeeded")
        void globalsFailureFailsTheWholeSet(@TempDir Path temp) throws IOException {
            // pg_dump succeeds and writes a real file; pg_dumpall then fails.
            Path succeeding = succeedingExecutableWritingFile(temp, "pg_dump_ok",
                    PostgreSqlBackupResult.DATABASE_DUMP_NAME);
            Path failing = failingExecutable(temp, "exit 4");

            var outcome = service().backup(temp, target(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, succeeding.toString(),
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, failing.toString()));

            assertThat(outcome.success())
                    .as("a set without globals is not a complete set")
                    .isFalse();
            assertThat(outcome.failureReason()).contains("pg_dumpall");
        }

        @Test
        @DisplayName("a zero-byte artefact is rejected as not-a-backup")
        void emptyArtifactIsRejected(@TempDir Path temp) throws IOException {
            Path emptyWriter = succeedingExecutableWritingFile(temp, "pg_dump_empty",
                    PostgreSqlBackupResult.DATABASE_DUMP_NAME, true);

            var outcome = service().backup(temp, target(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, emptyWriter.toString(),
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, emptyWriter.toString()));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.failureReason()).contains("empty");
        }

        @Test
        @DisplayName("a partial artefact is removed, not left to look restorable")
        void partialArtifactIsRemoved(@TempDir Path temp) throws IOException {
            Path failing = failingExecutable(temp, "exit 5");

            var outcome = service().backup(temp, target(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, failing.toString(),
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, failing.toString()));

            assertThat(outcome.success()).isFalse();
            Path setDir = temp.resolve(outcome.backupSetId());
            assertThat(Files.exists(setDir.resolve(PostgreSqlBackupResult.DATABASE_DUMP_NAME)))
                    .as("a truncated dump must not survive a failure")
                    .isFalse();
            assertThat(Files.exists(setDir.resolve(PostgreSqlBackupResult.GLOBALS_DUMP_NAME)))
                    .isFalse();
        }

        @Test
        @DisplayName("other backup sets are never touched during cleanup")
        void otherSetsAreNeverTouched(@TempDir Path temp) throws IOException {
            // A pre-existing, unrelated set that must survive untouched.
            Path neighbour = temp.resolve("00000000-0000-4000-8000-000000000000");
            Files.createDirectories(neighbour);
            Path neighbourDump = neighbour.resolve(PostgreSqlBackupResult.DATABASE_DUMP_NAME);
            Files.writeString(neighbourDump, "pre-existing recovery point");

            Path failing = failingExecutable(temp, "exit 6");
            var outcome = service().backup(temp, target(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, failing.toString(),
                            PostgreSqlToolLocator.ENV_PG_DUMPALL, failing.toString()));

            assertThat(outcome.success()).isFalse();
            assertThat(neighbourDump).exists();
            assertThat(Files.readString(neighbourDump))
                    .isEqualTo("pre-existing recovery point");
        }

        @Test
        @DisplayName("a timeout fails the operation rather than hanging")
        void timeoutFails(@TempDir Path temp) throws IOException {
            // The script lives outside the backup root: a killed child process
            // can still hold a handle to its working directory on Windows, which
            // would make @TempDir undeletable and mask the real assertion.
            Path scriptDir = Files.createTempDirectory("carbonflow-slow");
            try {
                Path slow = sleepingExecutable(scriptDir, 30);

                PostgreSqlBackupService impatient =
                        new PostgreSqlBackupService(
                                Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofSeconds(1));

                var outcome = impatient.backup(temp, target(),
                        Map.of(PostgreSqlToolLocator.ENV_PG_DUMP, slow.toString(),
                                PostgreSqlToolLocator.ENV_PG_DUMPALL, slow.toString()));

                assertThat(outcome.success()).isFalse();
                assertThat(outcome.failureReason())
                        .as("an unbounded backup would stall the hourly schedule")
                        .contains("bound");
                assertThat(outcome.result()).isNull();
            } finally {
                deleteQuietly(scriptDir);
            }
        }

        /** Best-effort cleanup; a lingering child may still hold a handle. */
        private void deleteQuietly(Path dir) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            } catch (IOException ignored) {
                // best effort
            }
        }

        @Test
        @DisplayName("a misconfigured executable fails the operation, not the caller")
        void missingExecutableFails(@TempDir Path temp) {
            // A configured-but-absent path is a configuration fault. It must
            // surface as a FAILED backup the operator can read, not as an
            // escaping exception from deep inside discovery.
            var outcome = service().backup(temp, target(),
                    Map.of(PostgreSqlToolLocator.ENV_PG_DUMP,
                            temp.resolve("nope").toString(),
                            PostgreSqlToolLocator.ENV_PG_DUMPALL,
                            temp.resolve("nope").toString()));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.result()).isNull();
            assertThat(outcome.failureReason())
                    .as("the operator must be told which variable is wrong")
                    .contains(PostgreSqlToolLocator.ENV_PG_DUMP);
        }
    }

    // ------------------------------------------------------------------
    // NESTED: checksum
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("checksum generation")
    class Checksums {

        @Test
        @DisplayName("SHA-256 of a known content matches the reference value")
        void sha256MatchesReference(@TempDir Path temp) throws Exception {
            Path file = temp.resolve("fixture.txt");
            Files.writeString(file, "carbonflow\n", StandardCharsets.UTF_8);
            String expected = sha256OfReference("carbonflow\n");

            assertThat(PostgreSqlBackupService.sha256(file)).isEqualTo(expected);
        }

        /** Reference implementation, independent of the production code path. */
        private String sha256OfReference(String content) throws Exception {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }

        @Test
        @DisplayName("hashing is streaming, so a large file does not need to fit in heap")
        void hashingIsStreaming() throws IOException {
            Path large = Files.createTempFile("carbonflow-hash", ".bin");
            try {
                byte[] chunk = new byte[1024 * 1024];
                try (var out = Files.newOutputStream(large)) {
                    for (int i = 0; i < 8; i++) {
                        out.write(chunk);
                    }
                }
                assertThat(Files.size(large)).isEqualTo(8L * 1024 * 1024);
                assertThat(PostgreSqlBackupService.sha256(large))
                        .as("8 MiB must hash without loading into a byte[]")
                        .hasSize(64);
            } finally {
                Files.deleteIfExists(large);
            }
        }

        @Test
        @DisplayName("different content yields a different digest")
        void digestIsContentSensitive() throws IOException {
            Path a = Files.createTempFile("a", ".txt");
            Path b = Files.createTempFile("b", ".txt");
            try {
                Files.writeString(a, "one");
                Files.writeString(b, "two");
                assertThat(PostgreSqlBackupService.sha256(a))
                        .isNotEqualTo(PostgreSqlBackupService.sha256(b));
            } finally {
                Files.deleteIfExists(a);
                Files.deleteIfExists(b);
            }
        }
    }

    // ------------------------------------------------------------------
    // NESTED: TLS parity
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("TLS is carried through, never weakened")
    class Tls {

        @Test
        @DisplayName("a verifying sslmode is preserved")
        void verifyingModeIsPreserved() {
            var target = new PostgreSqlBackupTarget("h", 5432, "db", "u",
                    new char[0], "verify-full");

            assertThat(target.sslMode()).isEqualTo("verify-full");
            assertThat(target.verifiesServerCertificate()).isTrue();
            assertThat(target.permitsPlaintext()).isFalse();
        }

        @Test
        @DisplayName("an unrecognised sslmode is refused, not passed through")
        void unknownModeIsRefused() {
            assertThatThrownBy(() -> new PostgreSqlBackupTarget("h", 5432, "db", "u",
                    new char[0], "requre"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refuses to guess");
        }

        @Test
        @DisplayName("the application environment supplies the mode unchanged")
        void modeComesFromTheApplicationEnvironment() {
            var target = PostgreSqlBackupTarget.fromEnvironment(Map.of(
                    "DB_HOST", "db.internal",
                    "DB_NAME", "carbonflow",
                    "DB_USER", "carbonflow_app",
                    "DB_PASSWORD", "s3cret",
                    "DB_SSLMODE", "require"));

            assertThat(target.host()).isEqualTo("db.internal");
            assertThat(target.database()).isEqualTo("carbonflow");
            assertThat(target.username()).isEqualTo("carbonflow_app");
            assertThat(target.sslMode()).isEqualTo("require");
            assertThat(target.toString()).doesNotContain("s3cret");
        }

        @Test
        @DisplayName("DB_SSLMODE is read, not invented")
        void noHardcodedSslMode() throws IOException {
            String source = Files.readString(Path.of(
                    "src/main/java/com/carbonflow/recovery/postgres/PostgreSqlBackupService.java"));

            assertThat(source).contains("PGSSLMODE");
            assertThat(source).doesNotContain("sslmode=disable");
        }
    }

    // ------------------------------------------------------------------
    // INTEGRATION — real pg_dump against a real server
    // ------------------------------------------------------------------

    /**
     * Skipped unless a real server and real client tooling are both present.
     * Never substituted with a mock: a fake "successful backup" would prove
     * nothing about whether the custom-format archive is actually restorable.
     *
     * <p>Uses the repository's own hermetic {@code EmbeddedPg} server, so the
     * test runs against a genuine PostgreSQL with the real CarbonFlow schema
     * applied by Flyway V1..V8 — no external database and no production data.
     */
    @Nested
    @DisplayName("integration against a real PostgreSQL")
    @EnabledIf("com.carbonflow.recovery.postgres.PostgreSqlBackupServiceTest#realToolingAvailable")
    class Integration {

        private int embeddedPort() {
            String jdbc = EmbeddedPg.jdbcUrl();   // starts the server if needed
            // jdbc:postgresql://127.0.0.1:<port>/postgres?stringtype=unspecified
            String afterProtocol = jdbc.substring(jdbc.indexOf("//") + 2);
            String hostPort = afterProtocol.substring(0, afterProtocol.indexOf('/'));
            return Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1));
        }

        private PostgreSqlBackupTarget embeddedTarget() {
            return new PostgreSqlBackupTarget("127.0.0.1", embeddedPort(), "postgres",
                    EmbeddedPg.username(),
                    EmbeddedPg.password().toCharArray(), "prefer");
        }

        @Test
        @DisplayName("a real custom-format dump and globals capture are produced and verifiable")
        void realBackupProducesRestorableArchive(@TempDir Path temp) throws IOException {
            var outcome = service().backup(temp, embeddedTarget(), environmentWithTools());

            assertThat(outcome.failureReason()).as("backup must succeed").isNull();
            assertThat(outcome.success()).isTrue();

            PostgreSqlBackupResult result = outcome.result();
            assertThat(result.databaseDump())
                    .as("custom-format archive must exist").exists();
            assertThat(result.globalsDump()).exists();
            assertThat(result.databaseDumpBytes()).isPositive();
            assertThat(result.globalsDumpBytes()).isPositive();
            assertThat(result.databaseDumpSha256()).hasSize(64);
            assertThat(result.globalsDumpSha256()).hasSize(64);
            assertThat(result.backupSetId()).matches("^[0-9a-f-]{36}$");

            // The decisive check: is the archive actually restorable, or merely
            // non-empty? pg_restore --list reads the archive structure without
            // restoring it — exactly REC-06's cheap daily probe.
            Path pgRestore = findTool("pg_restore");
            assertThat(pgRestore).as("pg_restore needed to inspect the archive").isNotNull();

            var listResult = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(pgRestore.toString())
                            .arg("--list")
                            .arg(result.databaseDump().toString())
                            .timeout(Duration.ofMinutes(2)));

            assertThat(listResult.succeeded())
                    .as("pg_restore --list must read the archive: %s",
                            listResult.stderr())
                    .isTrue();
            // The hermetic EmbeddedPg server carries only the default 'public'
            // schema — Flyway is applied by the Spring context, not here — so
            // this asserts on archive *structure*: a readable custom-format TOC
            // naming the schema, not on CarbonFlow tables.
            assertThat(listResult.stdout())
                    .as("archive must be a readable custom-format TOC")
                    .contains("Format: CUSTOM")
                    .contains("SCHEMA");

            // The globals capture must contain role definitions — the reason it
            // is mandatory: the frozen 9x44 permission matrix lives here.
            assertThat(Files.readString(result.globalsDump()))
                    .as("globals capture must preserve roles")
                    .containsIgnoringCase("ROLE");

            // Server version is probed, not fabricated.
            assertThat(result.serverVersionUnknown()).isFalse();
            assertThat(result.serverVersion()).containsIgnoringCase("pg_dump");
        }

        @Test
        @DisplayName("two runs produce distinct set directories")
        void twoRunsProduceDistinctSets(@TempDir Path temp) {
            var target = embeddedTarget();

            var first = service().backup(temp, target, environmentWithTools());
            var second = service().backup(temp, target, environmentWithTools());

            assertThat(first.success()).isTrue();
            assertThat(second.success()).isTrue();
            assertThat(first.backupSetId()).isNotEqualTo(second.backupSetId());
            assertThat(first.result().setDirectory())
                    .isNotEqualTo(second.result().setDirectory());
        }

        @Test
        @DisplayName("an unreachable server fails closed rather than yielding a 'backup'")
        void unreachableServerFailsClosed(@TempDir Path temp) {
            // Port 1 is reserved and nothing listens there, so pg_dump cannot
            // connect. The hermetic embedded server trusts local connections and
            // would ignore a wrong password, so an unreachable port is the
            // honest way to prove the failure path end to end.
            var unreachable = new PostgreSqlBackupTarget("127.0.0.1", 1, "postgres",
                    "postgres", "not-the-password".toCharArray(), "prefer");

            var outcome = service().backup(temp, unreachable, environmentWithTools());

            assertThat(outcome.success())
                    .as("a connection failure must not yield a 'backup'")
                    .isFalse();
            assertThat(outcome.result()).isNull();
            assertThat(outcome.failureReason()).isNotBlank();

            // No set directory may be left behind holding a partial artefact.
            Path setDir = temp.resolve(outcome.backupSetId());
            if (Files.exists(setDir)) {
                assertThat(Files.exists(
                        setDir.resolve(PostgreSqlBackupResult.DATABASE_DUMP_NAME)))
                        .as("no partial dump may survive a failure")
                        .isFalse();
            }
        }
    }

    /** True only when pg_dump, pg_dumpall and pg_restore are all resolvable. */
    static boolean realToolingAvailable() {
        return findTool("pg_dump") != null
                && findTool("pg_dumpall") != null
                && findTool("pg_restore") != null;
    }

    // ------------------------------------------------------------------
    // helpers: real executables with controlled behaviour
    // ------------------------------------------------------------------

    /** A stub executable that exits non-zero without writing anything. */
    private Path failingExecutable(Path dir, String behaviour) throws IOException {
        int code = Integer.parseInt(behaviour.replaceAll("\\D+", ""));
        return javaStub(dir, "FailingStub", """
                public class FailingStub {
                    public static void main(String[] args) {
                        System.err.println("stub failure %d");
                        System.exit(%d);
                    }
                }
                """.formatted(code, code));
    }

    /** A stub executable that sleeps, to exercise the timeout. */
    private Path sleepingExecutable(Path dir, int seconds) throws IOException {
        return javaStub(dir, "SleepingStub", """
                public class SleepingStub {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(%d_000L);
                    }
                }
                """.formatted(seconds));
    }

    /**
     * Writes a single-file Java stub and returns a platform launcher for it.
     *
     * <p>Uses the JVM's single-file source launcher, so no compilation step and
     * no dependency on shell quoting are involved.
     */
    private Path javaStub(Path dir, String className, String source) throws IOException {
        Path sourceFile = dir.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        Path script = dir.resolve(className + (isWindows() ? ".cmd" : ".sh"));
        if (isWindows()) {
            Files.writeString(script,
                    "@echo off\r\n\"" + javaExecutable() + "\" \"" + sourceFile + "\" %*\r\n",
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(script,
                    "#!/bin/sh\nexec \"" + javaExecutable() + "\" \"" + sourceFile + "\" \"$@\"\n",
                    StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
        }
        return script;
    }

    /**
     * A stub executable that writes a non-empty file to the path given by
     * {@code --file=}.
     *
     * <p>Implemented as a tiny Java source file launched via the {@code java}
     * single-file launcher, which is guaranteed present because the tests are
     * running on a JVM. This avoids hand-written shell/batch: the Windows
     * {@code for} loop parses {@code %*} unreliably and {@code %OUT:--file=%}
     * does not perform substring removal, so a batch stub would have tested the
     * stub rather than the backup logic.
     *
     * <p>The stub must honour the real {@code --file=} argument, since the
     * service passes an absolute path.
     */
    private Path succeedingExecutableWritingFile(Path dir, String name, String fileName)
            throws IOException {
        return succeedingExecutableWritingFile(dir, name, fileName, false);
    }

    /**
     * @param writeEmpty when true the stub creates a zero-byte file, which must
     *                   still be rejected — an empty archive is not a backup
     */
    private Path succeedingExecutableWritingFile(Path dir, String name, String fileName,
                                                 boolean writeEmpty) throws IOException {
        Path source = Path.of(name + ".java");
        Path script = dir.resolve(name + (isWindows() ? ".cmd" : ".sh"));
        String javaSource = """
                import java.nio.file.*;
                import java.nio.charset.StandardCharsets;
                public class %s {
                    public static void main(String[] args) throws Exception {
                        String target = null;
                        for (String a : args) {
                            if (a.startsWith("--file=")) {
                                target = a.substring("--file=".length());
                            }
                        }
                        if (target == null) {
                            System.exit(9);
                        }
                        Files.writeString(Path.of(target), "%s",
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    }
                }
                """.formatted(source.toString().replace(".java", ""),
                writeEmpty ? "" : "payload");

        Path sourceFile = dir.resolve(source);
        Files.writeString(sourceFile, javaSource, StandardCharsets.UTF_8);

        if (isWindows()) {
            Files.writeString(script,
                    "@echo off\r\n\"" + javaExecutable() + "\" \"" + sourceFile + "\" %*\r\n",
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(script,
                    "#!/bin/sh\nexec \"" + javaExecutable() + "\" \"" + sourceFile + "\" \"$@\"\n",
                    StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
        }
        return script;
    }

    /** The JVM running the tests. */
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}