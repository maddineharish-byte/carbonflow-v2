package com.carbonflow.perf;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.coordination.QuiesceGuard;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.postgres.PostgreSqlBackupService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.retention.RetentionService;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.verify.BackupVerifier;
import com.carbonflow.recovery.verify.VerificationOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.10 — recovery operation timings, measured separately from every other
 * measurement in this phase.
 *
 * <h2>Why recovery is measured on its own</h2>
 * <p>Backup, verification, restore and drill are not request paths. They have no
 * users waiting on them, they are not bounded by an SLA expressed in
 * milliseconds, and — the reason this matters most — <b>a measured drill
 * duration is not an RTO</b> and a measured backup interval is not an RPO.
 * {@link DrillResult} already carries its own limitations verbatim and this test
 * copies them into the report, so the durations cannot be quoted without the
 * statement of what they do not establish.
 *
 * <h2>What is timed</h2>
 * <ul>
 *   <li>{@code pg_dump} against each synthetic dataset scale, so backup duration
 *       and dump size are known as functions of data volume rather than guessed;</li>
 *   <li>the coordinated backup set (database dump + evidence vault + manifest)
 *       as one operator-visible operation;</li>
 *   <li>backup verification, which is CPU and disk bound over the whole set;</li>
 *   <li>the evidence vault backup on its own, at two vault sizes;</li>
 *   <li>a full drill — restore the dump into a fresh database, restore the vault,
 *       re-hash every evidence file, re-run tenant isolation;</li>
 *   <li>retention pruning.</li>
 * </ul>
 *
 * <h2>Run separately, on purpose</h2>
 * <p>{@code mvn verify} runs the project's existing recovery tests, which create
 * and drop their own source and recovery databases. This measurement writes real
 * data to the configured server and is therefore opt-in, and it is run in its
 * own Maven invocation so it never competes for resources with the API
 * measurements.
 */
@EnabledIfSystemProperty(named = "carbonflow.perf", matches = "true",
        disabledReason = "Recovery measurement is opt-in; run with -Dcarbonflow.perf=true")
class PerfRecoveryTest {

    /** True only when pg_dump/pg_restore resolve and a version-matched server exists. */
    static boolean toolingPresent() {
        return RecoveryTestEnvironment.ready();
    }

    @Test
    @EnabledIf("com.carbonflow.perf.PerfRecoveryTest#toolingPresent")
    @DisplayName("Phase 10.10 recovery operation timings")
    void measureRecovery(@TempDir Path temp) throws Exception {
        PostgreSqlBackupTarget server = RecoveryTestEnvironment.matchingServer();
        Map<String, String> environment = RecoveryTestEnvironment.toolEnvironment();

        PerfReport report = new PerfReport();
        report.context("measuredAt", Instant.now().toString())
                .context("javaVersion", System.getProperty("java.version"))
                .context("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .context("availableProcessors", Runtime.getRuntime().availableProcessors())
                .context("postgresqlClient", RecoveryTestEnvironment.findTool("pg_dump").toString())
                .context("serverVersion", serverVersion(server))
                .context("harness", "src/test/java/com/carbonflow/perf/PerfRecoveryTest");

        List<String> created = new ArrayList<>();
        try {
            // ---- Database dump cost as a function of data volume ----------
            for (PerfScale scale : PerfScale.values()) {
                String database = "carbonflow_perfsrc_" + scale.label;
                created.add(database);
                long seededRows = seedDatabase(server, database, scale);

                report.record("recovery-dataset", scale.label, "database", database, null);
                report.record("recovery-dataset", scale.label, "seeded_rows", seededRows, "rows");

                Path dumpRoot = Files.createDirectories(
                        temp.resolve("dumproot-" + scale.label));
                long started = System.nanoTime();
                var outcome = PostgreSqlBackupService.productionDefaults()
                        .backup(dumpRoot, target(server, database), environment);
                long elapsed = System.nanoTime() - started;

                assertThat(outcome.success())
                        .as("pg_dump must succeed to be measured: %s", outcome.result())
                        .isTrue();
                Path setDirectory = dumpRoot.resolve(outcome.backupSetId());
                Path dump = setDirectory.resolve("database.dump");
                assertThat(dump).exists();

                report.record("pg-dump", scale.label, "duration",
                        PerfStats.fmt(elapsed / 1_000_000.0), "ms");
                report.record("pg-dump", scale.label, "dump_bytes",
                        Files.size(dump), "bytes");
                report.record("pg-dump", scale.label, "rows",
                        rowsIn(server, database), "rows");
                report.record("pg-dump", scale.label, "bytes_per_row",
                        rowsIn(server, database) <= 0 ? 0
                                : Files.size(dump) / rowsIn(server, database), "bytes/row");
                report.record("pg-dump", scale.label, "set_id", outcome.backupSetId(), null);
            }

            // ---- Evidence vault backup at two sizes -----------------------
            measureVault(report, temp, server, environment);

            // ---- Coordinated set, verification, drill, retention ----------
            String sourceDatabase = "carbonflow_perfsrc_small";
            Path vault = Files.createDirectories(temp.resolve("vault"));
            List<EvidenceVaultBackupService.RequiredFile> evidence =
                    writeVault(vault, PerfScale.SMALL.evidenceRecords, 64 * 1024);
            // Appoint the seeded evidence metadata row-for-row at the real vault
            // files, keeping storage_path, file_name, size and sha256 mutually
            // consistent. Without this, the drill maps the seeded (pseudo)
            // storage_path values outside the vault root and cannot verify.
            pinEvidenceToVault(server, sourceDatabase, vault, evidence);

            Path backupRoot = Files.createDirectories(temp.resolve("backups"));
            RecoverySetCoordinator coordinator = new RecoverySetCoordinator(
                    PostgreSqlBackupService.productionDefaults(),
                    EvidenceVaultBackupService.productionDefaults(),
                    new com.carbonflow.recovery.RecoveryManifestWriter(Clock.systemUTC()),
                    Clock.systemUTC());

            long started = System.nanoTime();
            RecoverySetCoordinator.CoordinatedBackupResult coordinated = coordinator.run(
                    new RecoverySetCoordinator.Request(
                            backupRoot, vault, target(server, sourceDatabase), environment,
                            evidence,
                            RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                            new RecoveryManifest.Schema(List.of("V1", "V2", "V3", "V4", "V5",
                                    "V6", "V7", "V8", "V9", "V10"), Boolean.TRUE, 38),
                            "robocopy"),
                    new QuiesceGuard.NoOp());
            long coordinatedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(coordinated.success())
                    .as("coordinated backup must succeed: %s", coordinated.failureReason())
                    .isTrue();
            report.record("recovery-set", "database + vault + manifest", "duration",
                    coordinatedMillis, "ms");
            report.record("recovery-set", "database + vault + manifest", "set_bytes",
                    directoryBytes(coordinated.setDirectory()), "bytes");

            VerificationOutcome verification =
                    new BackupVerifier(new com.carbonflow.recovery.RecoveryManifestWriter(
                            Clock.systemUTC()), Map.of())
                            .verify(coordinated.setDirectory());
            report.record("recovery-verify", "coordinated set", "status",
                    verification.status().name(), null);
            report.record("recovery-verify", "coordinated set", "checks",
                    verification.checks().size(), "checks");
            report.record("recovery-verify", "coordinated set", "findings",
                    verification.findings().size(), "findings");

            String recoveryDatabase = "carbonflow_perfrec_" + UUID.randomUUID()
                    .toString().substring(0, 8);
            created.add(recoveryDatabase);
            long drillStarted = System.nanoTime();
            DrillResult drill = new RecoveryDrill(Clock.systemUTC(), environment)
                    .run(coordinated.setDirectory(), server, recoveryDatabase,
                            temp.resolve("restored-vault"), vault);
            long drillMillis = (System.nanoTime() - drillStarted) / 1_000_000;

            report.record("recovery-drill", "restore into a fresh database", "passed",
                    drill.passed(), null);
            report.record("recovery-drill", "restore into a fresh database", "wall_clock",
                    drillMillis, "ms");
            report.record("recovery-drill", "restore into a fresh database", "checks",
                    drill.checks().size(), "checks");
            if (drill.durations() != null && drill.durations().databaseRestore() != null) {
                report.record("recovery-drill", "restore into a fresh database",
                        "database_restore", drill.durations().databaseRestore().toMillis(),
                        "ms");
            }
            if (drill.durations() != null && drill.durations().vaultRestore() != null) {
                report.record("recovery-drill", "restore into a fresh database",
                        "vault_restore", drill.durations().vaultRestore().toMillis(), "ms");
            }
            if (drill.durations() != null && drill.durations().totalRecovery() != null) {
                report.record("recovery-drill", "restore into a fresh database",
                        "total_recovery", drill.durations().totalRecovery().toMillis(), "ms");
            }
            // The limitations travel with the number, always.
            for (String limitation : drill.limitations()) {
                report.record("recovery-drill", "limitations", "limitation", limitation, null);
            }
            assertThat(drill.passed())
                    .as("the measured drill must genuinely pass: %s", drill.findings())
                    .isTrue();

            RetentionService.RetentionResult retention =
                    new RetentionService(Clock.systemUTC(), Duration.ofDays(30))
                            .apply(backupRoot);
            report.record("recovery-retention", "30-day window", "safe_to_delete",
                    retention.safeToDelete(), null);
            report.record("recovery-retention", "30-day window", "deleted",
                    retention.deleted().size(), "sets");
        } finally {
            for (String database : created) {
                dropDatabase(server, database);
            }
        }

        report.write(Path.of("target", "perf"), "perf-recovery");
    }

    /**
     * Evidence vault backup at two vault sizes.
     *
     * <p>Two points rather than a sweep: the small vault is dominated by
     * per-file overhead, the larger one by throughput. Both files are real, on
     * real disk, with real SHA-256 digests computed over them — the vault backup
     * re-hashes every file, so a synthetic file would measure the same thing,
     * but a file that exists is the only way the copy cost is honest.
     */
    private void measureVault(PerfReport report, Path temp, PostgreSqlBackupTarget server,
                              Map<String, String> environment) throws Exception {
        for (int[] shape : new int[][]{{20, 8 * 1024}, {200, 256 * 1024}}) {
            int files = shape[0];
            int size = shape[1];
            String label = files + " files x " + (size / 1024) + " KiB";
            Path vault = Files.createDirectories(temp.resolve("vault-" + files + "-" + size));
            List<EvidenceVaultBackupService.RequiredFile> required = writeVault(vault, files, size);
            Path setDirectory = Files.createDirectories(
                    temp.resolve("vaultset-" + files + "-" + size));

            long started = System.nanoTime();
            EvidenceVaultBackupService.VaultBackupResult result =
                    EvidenceVaultBackupService.productionDefaults()
                            .backup(vault, setDirectory, required, "robocopy");
            long elapsed = System.nanoTime() - started;

            assertThat(result.success())
                    .as("vault backup must succeed to be measured: %s", result.failureReason())
                    .isTrue();
            report.record("evidence-vault-backup", label, "duration",
                    PerfStats.fmt(elapsed / 1_000_000.0), "ms");
            report.record("evidence-vault-backup", label, "source_bytes",
                    result.totalBytes(), "bytes");
            report.record("evidence-vault-backup", label, "files",
                    result.fileCount(), "files");
            report.record("evidence-vault-backup", label, "copied_bytes",
                    directoryBytes(result.setDirectory()), "bytes");
            report.record("evidence-vault-backup", label, "throughput_mb_per_s",
                    result.totalBytes() / (1024.0 * 1024.0) / (elapsed / 1_000_000_000.0),
                    "MiB/s");
        }
    }

    /** Writes {@code files} evidence files of {@code size} bytes with real digests. */
    private List<EvidenceVaultBackupService.RequiredFile> writeVault(Path vault, int files,
                                                                     int size)
            throws Exception {
        // Deterministic incompressible-ish content: zeros would compress into
        // nothing on some file systems and would flatter the copy measurement.
        Random random = new Random(files * 31L + size);
        List<EvidenceVaultBackupService.RequiredFile> required = new ArrayList<>();
        for (int i = 1; i <= files; i++) {
            Path file = vault.resolve("org-perf/1_" + i + "_evidence.bin");
            Files.createDirectories(file.getParent());
            byte[] content = new byte[size];
            random.nextBytes(content);
            Files.write(file, content);
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(content));
            required.add(new EvidenceVaultBackupService.RequiredFile(
                    file.toAbsolutePath().toString(), digest, content.length));
        }
        return required;
    }

    /**
     * Creates, migrates and seeds one disposable database.
     *
     * <p>Migration runs the repository's own {@code db/migration} files with
     * Flyway — the same files the application applies — so the dumped database
     * has the schema a real deployment has, at exactly V1..V8.
     */
    private long seedDatabase(PostgreSqlBackupTarget server, String database,
                              PerfScale scale) throws Exception {
        dropDatabase(server, database);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + server.host() + ":" + server.port() + "/postgres",
                server.username(), new String(server.password()));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
        }

        String url = "jdbc:postgresql://" + server.host() + ":" + server.port() + "/"
                + database + "?stringtype=unspecified";
        Flyway.configure()
                .dataSource(url, server.username(), new String(server.password()))
                .locations("filesystem:../db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("6")
                .load()
                .migrate();

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(url);
        dataSource.setUsername(server.username());
        dataSource.setPassword(new String(server.password()));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String namespace = "carbonflow-perf-recovery:" + scale.label;
        String organizationId = PerfDataset.id(namespace, "org", 0);
        String userId = PerfDataset.id(namespace, "user", 0);
        jdbc.update("INSERT INTO organizations (id, name, tax_id, country, industry, status) "
                + "VALUES (?, ?, ?, 'GB', 'PERF-MEASUREMENT', 'ACTIVE')",
                organizationId, "Perf Tenant " + scale.label, "PERF-" + scale.label);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, is_active) "
                        + "VALUES (?, ?, ?, 'Perf Owner', TRUE)",
                userId, "perf-" + scale.label + "@carbonflow.invalid",
                "{noop}not-a-real-hash-measurement-only");
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, "
                        + "role_id, is_active) VALUES (?, ?, ?, ?, TRUE)",
                PerfDataset.id(namespace, "membership", 0), organizationId, userId,
                "11111111-1111-1111-1111-111111111101");

        PerfDataset.seed(jdbc, scale, organizationId, userId, namespace);
        PerfDataset.verify(jdbc, scale, organizationId);
        return rowsIn(server, database);
    }

    /**
     * Rewrites the source database's seeded evidence metadata row-for-row so
     * it points at the real vault files, with mutually consistent name, size,
     * and SHA-256.
     *
     * <p>{@code PerfDataset} seeds {@code evidence_records} with the pseudo path
     * {@code perf/<org>/evidence-N.csv} and a synthetic digest purely to create a
     * deterministic volume of rows.  For an actual recovery drill, however, the
     * stored {@code storage_path} must resolve under the vault root the drill
     * will guard against — otherwise the evidence integrity check is
     * unfalsifiable.  This pins the rows (ordered by {@code created_at, id}) to
     * the real files created by {@link #writeVault}.
     */
    private void pinEvidenceToVault(PostgreSqlBackupTarget server, String database,
                                    Path vaultRoot,
                                    List<EvidenceVaultBackupService.RequiredFile> vaultFiles)
            throws Exception {
        List<String> ids = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + server.host() + ":" + server.port() + "/" + database,
                server.username(), new String(server.password()));
             var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT id FROM evidence_records ORDER BY created_at, id")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        assertThat(ids.size()).as("evidence rows to pin for the drill")
                .isEqualTo(vaultFiles.size());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + server.host() + ":" + server.port() + "/" + database,
                server.username(), new String(server.password()));
             var ps = connection.prepareStatement(
                     "UPDATE evidence_records SET storage_path = ?, file_name = ?, "
                             + "file_size_bytes = ?, sha256_hash = ? WHERE id = ?::uuid")) {
            for (int i = 0; i < ids.size(); i++) {
                EvidenceVaultBackupService.RequiredFile file = vaultFiles.get(i);
                Path p = Path.of(file.storagePath());
                ps.setString(1, p.toAbsolutePath().toString());
                ps.setString(2, p.getFileName().toString());
                ps.setLong(3, file.expectedBytes());
                ps.setString(4, file.expectedSha256());
                ps.setString(5, ids.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private PostgreSqlBackupTarget target(PostgreSqlBackupTarget server, String database) {
        return new PostgreSqlBackupTarget(server.host(), server.port(), database,
                server.username(), server.password(), server.sslMode());
    }

    private long rowsIn(PostgreSqlBackupTarget server, String database) {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + server.host() + ":" + server.port() + "/" + database,
                server.username(), new String(server.password()));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT (SELECT count(*) FROM activity_data) "
                             + "+ (SELECT count(*) FROM emission_records) "
                             + "+ (SELECT count(*) FROM calculations) "
                             + "+ (SELECT count(*) FROM facilities)")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            return -1;
        }
    }

    private String serverVersion(PostgreSqlBackupTarget server) {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + server.host() + ":" + server.port() + "/postgres",
                server.username(), new String(server.password()));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW server_version")) {
            rs.next();
            return rs.getString(1);
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    private static void dropDatabase(PostgreSqlBackupTarget server, String database) {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + server.host() + ":" + server.port() + "/postgres",
                server.username(), new String(server.password()));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
        } catch (Exception ignored) {
            // best-effort cleanup; a leftover database is visible, not silent
        }
    }

    private static long directoryBytes(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
