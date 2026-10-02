package com.carbonflow.recovery.monitor;

import com.carbonflow.recovery.RecoveryDigest;
import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import com.carbonflow.recovery.verify.BackupVerifier;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-08 — monitoring.
 *
 * <p>The clock is the interesting input, so it is injected: staleness and the
 * RPO-at-risk warning are driven by an explicit {@code now} rather than by real
 * elapsed time. That makes every threshold testable exactly at, just below and
 * just above the boundary.
 */
class BackupMonitorTest {

    private static Path pgRestore;

    @BeforeAll
    static void detect() {
        String configured = System.getenv(PostgreSqlToolLocator.ENV_PG_RESTORE);
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            pgRestore = Path.of(configured);
            return;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                for (String candidate : new String[]{"pg_restore", "pg_restore.exe"}) {
                    Path p = Path.of(dir).resolve(candidate);
                    if (Files.isRegularFile(p)) {
                        pgRestore = p;
                        return;
                    }
                }
            }
        }
        Path pgRoot = Path.of("C:\\Program Files\\PostgreSQL");
        if (Files.isDirectory(pgRoot)) {
            try (var versions = Files.list(pgRoot)) {
                pgRestore = versions
                        .map(v -> v.resolve("bin").resolve("pg_restore.exe"))
                        .filter(Files::isRegularFile).findFirst().orElse(null);
            } catch (IOException ignored) {
                pgRestore = null;
            }
        }
    }

    private BackupMonitor monitorAt(Path root, Instant now) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (pgRestore != null) {
            env.put(PostgreSqlToolLocator.ENV_PG_RESTORE, pgRestore.toString());
        }
        return new BackupMonitor(root, Clock.fixed(now, ZoneOffset.UTC),
                new BackupVerifier(new RecoveryManifestWriter(Clock.systemUTC()), env), env);
    }

    /**
     * A real {@code pg_dump --format=custom} archive, produced once per run.
     *
     * <p>Needed because the monitor's HEALTHY path requires verification to
     * succeed, and verification runs {@code pg_restore --list}. A placeholder
     * file is correctly rejected by that tool, so a fixture built from one could
     * never reach HEALTHY and the test would assert nothing.
     */
    private static Path realArchive;

    private static synchronized Path realArchive() throws Exception {
        if (realArchive != null) {
            return realArchive;
        }
        Path pgDump = findTool("pg_dump");
        if (pgRestore == null || pgDump == null) {
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

        Map<String, String> env = new HashMap<>();
        env.put(PostgreSqlToolLocator.ENV_PG_DUMP, pgDump.toString());
        env.put(PostgreSqlToolLocator.ENV_PG_DUMPALL,
                findTool("pg_dumpall") == null ? "" : findTool("pg_dumpall").toString());

        var outcome = com.carbonflow.recovery.postgres.PostgreSqlBackupService
                .productionDefaults().backup(
                        Files.createTempDirectory("carbonflow-monitor-archive"), target, env);
        realArchive = outcome.success() ? outcome.result().databaseDump() : null;
        return realArchive;
    }

    private static Path findTool(String name) {
        String configured = System.getenv(PostgreSqlToolLocator.ENV_PG_DUMP);
        if (configured != null) {
            String f = Path.of(configured).getFileName().toString();
            String bare = f.endsWith(".exe") ? f.substring(0, f.length() - 4) : f;
            if (bare.equals(name)) {
                return Path.of(configured);
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

    /** Writes a set whose manifest records {@code createdAt}. */
    private Path createSet(Path root, Instant createdAt) throws Exception {
        Path set = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));

        Path archive = realArchive();
        if (archive != null) {
            Files.copy(archive, set.resolve("database.dump"));
        } else {
            Files.writeString(set.resolve("database.dump"), "PGDMP",
                    StandardCharsets.UTF_8);
        }
        Files.writeString(set.resolve("globals.sql"), "-- globals",
                StandardCharsets.UTF_8);

        Path indexPath = set.resolve("vault-integrity.json");
        Files.writeString(indexPath, """
                {
                  "indexVersion" : "1",
                  "generatedAt" : "2026-10-01T00:00:00Z",
                  "copiedWith" : "robocopy",
                  "fileCount" : 0,
                  "totalBytes" : 0,
                  "files" : [ ]
                }
                """, StandardCharsets.UTF_8);

        RecoveryManifest manifest = new RecoveryManifest(
                RecoveryManifest.MANIFEST_VERSION, set.getFileName().toString(), createdAt,
                createdAt, RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Database("database.dump",
                        RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM, createdAt,
                        RecoveryManifest.CHECKSUM_ALGORITHM,
                        RecoveryDigest.sha256(set.resolve("database.dump")),
                        "globals.sql",
                        RecoveryDigest.sha256(set.resolve("globals.sql"))),
                new RecoveryManifest.EvidenceVault("vault", createdAt, 0L, 0L,
                        RecoveryManifest.CHECKSUM_ALGORITHM,
                        "vault-integrity.json",
                        RecoveryDigest.sha256(indexPath), "robocopy"),
                new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                RecoveryManifest.Verification.pending(), List.of());
        new RecoveryManifestWriter(Clock.systemUTC()).write(manifest, set);
        return set;
    }

    private static Instant now() {
        return Instant.parse("2026-10-02T12:00:00Z");
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("freshness thresholds")
    class Freshness {

        @Test
        @DisplayName("no backup at all is STALE with a BACKUP_MISSING alert")
        void noBackupIsStale(@TempDir Path temp) {
            BackupHealth.Status status = monitorAt(temp, now()).assess();

            assertThat(status.health()).isEqualTo(BackupHealth.STALE);
            assertThat(status.alerts())
                    .anyMatch(a -> a.contains("BACKUP_MISSING"));
        }

        @Test
        @DisplayName("a 10-minute-old verified set is HEALTHY")
        void freshSetIsHealthy(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(10)));

            BackupHealth.Status status = monitorAt(root, now()).assess();

            assertThat(status.alerts()).isEmpty();
            assertThat(status.health()).isEqualTo(BackupHealth.HEALTHY);
            assertThat(status.isHealthy()).isTrue();
            assertThat(status.age()).isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("just below the early-warning threshold is still HEALTHY")
        void justBelowWarningThresholdIsHealthy(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(44)));

            BackupHealth.Status status = monitorAt(root, now()).assess();

            assertThat(status.health()).isEqualTo(BackupHealth.HEALTHY);
            assertThat(status.alerts()).isEmpty();
        }

        @Test
        @DisplayName("exactly at the early-warning threshold raises RPO_AT_RISK")
        void exactlyAtWarningThresholdWarns(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(BackupHealth.RPO_AT_RISK_THRESHOLD));

            BackupHealth.Status status = monitorAt(root, now()).assess();

            assertThat(status.alerts())
                    .anyMatch(a -> a.contains("RPO_AT_RISK"));
            // Still inside the RPO window, so not yet STALE.
            assertThat(status.health()).isEqualTo(BackupHealth.HEALTHY);
        }

        @Test
        @DisplayName("just above the early-warning threshold warns")
        void justAboveWarningThresholdWarns(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(46)));

            assertThat(monitorAt(root, now()).assess().alerts())
                    .anyMatch(a -> a.contains("RPO_AT_RISK"));
        }

        @Test
        @DisplayName("exactly at the RPO window is STALE")
        void exactlyAtRpoIsStale(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(BackupHealth.RPO));

            BackupHealth.Status status = monitorAt(root, now()).assess();

            assertThat(status.health()).isEqualTo(BackupHealth.STALE);
            assertThat(status.alerts()).anyMatch(a -> a.contains("BACKUP_STALE"));
        }

        @Test
        @DisplayName("well beyond the RPO window is STALE")
        void beyondRpoIsStale(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofHours(5)));

            assertThat(monitorAt(root, now()).assess().health())
                    .isEqualTo(BackupHealth.STALE);
        }

        @Test
        @DisplayName("the approved RPO is one hour and the warning is 45 minutes")
        void thresholdsMatchTheApprovedRequirement() {
            assertThat(BackupHealth.RPO).isEqualTo(Duration.ofHours(1));
            assertThat(BackupHealth.RPO_AT_RISK_THRESHOLD)
                    .isEqualTo(Duration.ofMinutes(45));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("run state")
    class RunState {

        @Test
        @DisplayName("a run in progress reports RUNNING")
        void runInProgressIsRunning(@TempDir Path temp) {
            BackupMonitor monitor = monitorAt(temp, now());
            monitor.recordRunStarted();

            assertThat(monitor.assess().health()).isEqualTo(BackupHealth.RUNNING);
        }

        @Test
        @DisplayName("a failed most-recent run reports FAILED even with a fresh set present")
        void failedRunOutranksFreshness(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(1)));

            BackupMonitor monitor = monitorAt(root, now());
            monitor.recordRunFinished(false, "some-set", "vault backup failed");

            BackupHealth.Status status = monitor.assess();

            assertThat(status.health())
                    .as("a fresh-looking set after a failure is misleading")
                    .isEqualTo(BackupHealth.FAILED);
            assertThat(status.alerts()).anyMatch(a -> a.contains("BACKUP_FAILED"));
            assertThat(status.reason()).contains("vault backup failed");
        }

        @Test
        @DisplayName("a successful run clears the failure state")
        void successfulRunClearsFailure(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(1)));

            BackupMonitor monitor = monitorAt(root, now());
            monitor.recordRunFinished(false, "set-a", "boom");
            monitor.recordRunFinished(true, "set-b", null);

            assertThat(monitor.assess().health()).isEqualTo(BackupHealth.HEALTHY);
        }

        @Test
        @DisplayName("a run that overruns the RPO window raises BACKUP_OVERRUN")
        void overrunRunWarns(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(1)));

            // A run starts now, and 90 minutes later has still not finished. A
            // mutable clock is required: with a fixed clock the elapsed time is
            // always zero and the overrun can never be observed.
            MutableClock clock = new MutableClock(now());
            BackupMonitor monitor = new BackupMonitor(root, clock,
                    new BackupVerifier(new RecoveryManifestWriter(Clock.systemUTC()),
                            Map.of()), Map.of());

            monitor.recordRunStarted();
            assertThat(monitor.assess().health())
                    .as("a run inside the window is RUNNING")
                    .isEqualTo(BackupHealth.RUNNING);

            clock.current = now().plus(Duration.ofMinutes(90));
            BackupHealth.Status status = monitor.assess();

            assertThat(status.alerts())
                    .anyMatch(a -> a.contains("BACKUP_OVERRUN"));
        }

        /** A clock the test advances, so a run can genuinely start earlier. */
        private static final class MutableClock extends Clock {
            private Instant current;

            MutableClock(Instant start) {
                this.current = start;
            }

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return current;
            }
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        @DisplayName("a set whose dump was tampered with reports FAILED")
        void tamperedSetFails(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path set = createSet(root, now().minus(Duration.ofMinutes(5)));
            Files.writeString(set.resolve("database.dump"), "TAMPERED",
                    StandardCharsets.UTF_8);

            BackupHealth.Status status = monitorAt(root, now()).assess();

            assertThat(status.health()).isEqualTo(BackupHealth.FAILED);
        }

        @Test
        @DisplayName("a set with no manifest is not treated as a healthy set")
        void setWithoutManifestIsNotHealthy(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path set = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
            Files.writeString(set.resolve("database.dump"), "PGDMP",
                    StandardCharsets.UTF_8);

            // findNewestSet skips manifest-less directories, so the monitor sees
            // no set at all rather than trusting one.
            assertThat(monitorAt(root, now()).assess().health())
                    .isEqualTo(BackupHealth.STALE);
            assertThat(set).exists();
        }

        @Test
        @DisplayName("verification that cannot run reports UNVERIFIABLE")
        void unverifiableIsItsOwnState(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, now().minus(Duration.ofMinutes(5)));

            // No tooling at all: the archive check cannot run.
            BackupMonitor blind = new BackupMonitor(root, Clock.fixed(now(), ZoneOffset.UTC),
                    new BackupVerifier(new RecoveryManifestWriter(Clock.systemUTC()),
                            Map.of("PATH", "")), Map.of("PATH", ""));

            assertThat(blind.assess().health()).isEqualTo(BackupHealth.UNVERIFIABLE);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("notification honesty")
    class Notification {

        @Test
        @DisplayName("the monitor admits it cannot notify a human")
        void noNotificationTransportIsClaimed(@TempDir Path temp) {
            BackupMonitor monitor = monitorAt(temp, now());

            assertThat(monitor.hasNotificationTransport()).isFalse();
        }

        @Test
        @DisplayName("the status document states that no human is notified")
        void statusDocumentStatesTheLimitation(@TempDir Path temp) {
            BackupMonitor monitor = monitorAt(temp, now());

            String json = monitor.toJson(monitor.assess());

            assertThat(json).contains("\"notificationDelivered\" : false");
            assertThat(json)
                    .as("detecting must not be described as notifying")
                    .contains("no email")
                    .contains("integration point");
        }

        @Test
        @DisplayName("the status document carries the state, age and thresholds")
        void statusDocumentIsMachineReadable(@TempDir Path temp) {
            BackupMonitor monitor = monitorAt(temp, now());

            String json = monitor.toJson(monitor.assess());

            assertThat(json).contains("\"health\"").contains("\"evaluatedAt\"")
                    .contains("\"rpoSeconds\" : 3600")
                    .contains("\"rpoAtRiskThresholdSeconds\" : 2700")
                    .contains("\"alerts\"");
        }
    }
}