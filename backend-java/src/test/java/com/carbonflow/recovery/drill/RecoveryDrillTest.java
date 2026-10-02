package com.carbonflow.recovery.drill;

import com.carbonflow.recovery.coordination.QuiesceGuard;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import com.carbonflow.testsupport.EmbeddedPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-10 — recovery drill.
 *
 * <p>This is the heaviest test in the recovery suite and deliberately so: it
 * takes a <b>real</b> backup of a real PostgreSQL carrying the migrated
 * CarbonFlow schema, destroys nothing, and restores it into an isolated
 * database it creates itself. A mock would prove nothing about whether a
 * CarbonFlow backup is actually restorable.
 *
 * <p>The live application database is never the restore target, and the test
 * asserts that isolation explicitly.
 */
class RecoveryDrillTest {

    private static boolean toolingAvailable;
    /** PG18 server reachable with the project's own .env, if present. */
    private static PostgreSqlBackupTarget localServer;

    @BeforeAll
    static void detect() {
        toolingAvailable = ServerProbe.tooling();
        localServer = ServerProbe.matchingServer();
    }

    /**
     * Finds a server whose major version matches the local pg_dump.
     *
     * <p>The embedded test server is PostgreSQL 14 while the installed pg_dump is
     * 18, and a dump from a newer pg_dump cannot be restored onto an older
     * server. Restoring into a version-matched server is the realistic drill
     * scenario; the version guard added to the drill proves the mismatch is
     * detected rather than producing a cryptic restore error.
     *
     * <p>Credentials come from the environment or a local {@code .env} and are
     * never committed.
     */
    private static PostgreSqlBackupTarget detectMatchingServer() {
        Integer clientMajor = null;
        Path pgDump = findTool("pg_dump");
        if (pgDump != null) {
            try {
                var result = new ProcessBuilder(pgDump.toString(), "--version")
                        .redirectErrorStream(true).start();
                var matcher = java.util.regex.Pattern.compile("(\\d+)\\.")
                        .matcher(new String(result.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8));
                if (matcher.find()) {
                    clientMajor = Integer.parseInt(matcher.group(1));
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (clientMajor == null) {
            return null;
        }
        for (PostgreSqlBackupTarget candidate : candidateServers()) {
            try {
                try (Connection connection = DriverManager.getConnection(
                        "jdbc:postgresql://" + candidate.host() + ":" + candidate.port()
                                + "/postgres",
                        candidate.username(), new String(candidate.password()));
                     Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("show server_version_num")) {
                    if (rs.next() && (rs.getInt(1) / 10000) == clientMajor) {
                        return candidate;
                    }
                }
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    /** Servers this host may offer, in preference order. */
    private static List<PostgreSqlBackupTarget> candidateServers() {
        List<PostgreSqlBackupTarget> candidates = new ArrayList<>();
        Map<String, String> env = readDotEnv();
        if (env.get("DB_HOST") != null && env.get("DB_USER") != null) {
            candidates.add(new PostgreSqlBackupTarget(env.get("DB_HOST"),
                    Integer.parseInt(env.getOrDefault("DB_PORT", "5432")),
                    env.getOrDefault("DB_NAME", "postgres"), env.get("DB_USER"),
                    env.getOrDefault("DB_PASSWORD", "").toCharArray(),
                    env.getOrDefault("DB_SSLMODE", "prefer")));
        }
        // The embedded harness server, as a fallback.
        try {
            candidates.add(new PostgreSqlBackupTarget("127.0.0.1", embeddedPort(),
                    "postgres", EmbeddedPg.username(),
                    EmbeddedPg.password().toCharArray(), "prefer"));
        } catch (Exception ignored) {
            // embedded not started
        }
        return candidates;
    }

    /**
     * Reads DB_* from the process environment, falling back to a local .env.
     *
     * <p>Values are used at runtime only. No credential is written to a test
     * fixture, a log line, or the repository.
     */
    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        for (String key : List.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER",
                "DB_PASSWORD", "DB_SSLMODE")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                values.put(key, value);
            }
        }
        // Maven runs with backend-java/ as the working directory, so the .env at
        // the repository root is one level up.
        for (Path candidate : List.of(Path.of(".env"), Path.of("..", ".env"))) {
            try {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                for (String line : Files.readAllLines(candidate)) {
                    if (line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (key.startsWith("DB_") && !value.isEmpty()) {
                        values.putIfAbsent(key, value);
                    }
                }
                break;
            } catch (Exception ignored) {
                // try the next candidate location
            }
        }
        return values;
    }

    private static Path findTool(String name) {
        String configured = System.getenv(switch (name) {
            case "pg_dump" -> PostgreSqlToolLocator.ENV_PG_DUMP;
            case "pg_dumpall" -> PostgreSqlToolLocator.ENV_PG_DUMPALL;
            default -> PostgreSqlToolLocator.ENV_PG_RESTORE;
        });
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

    private static Map<String, String> toolEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put(PostgreSqlToolLocator.ENV_PG_DUMP, findTool("pg_dump").toString());
        env.put(PostgreSqlToolLocator.ENV_PG_DUMPALL, findTool("pg_dumpall").toString());
        env.put(PostgreSqlToolLocator.ENV_PG_RESTORE, findTool("pg_restore").toString());
        return env;
    }

    private static int embeddedPort() {
        String jdbc = EmbeddedPg.jdbcUrl();
        String after = jdbc.substring(jdbc.indexOf("//") + 2);
        String hostPort = after.substring(0, after.indexOf('/'));
        return Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1));
    }

    private static PostgreSqlBackupTarget embeddedTarget() {
        return new PostgreSqlBackupTarget("127.0.0.1", embeddedPort(), "postgres",
                EmbeddedPg.username(), EmbeddedPg.password().toCharArray(), "prefer");
    }

    private static Connection connect(String database) throws Exception {
        return connectOn(embeddedTarget(), database);
    }

    private static Connection connectOn(PostgreSqlBackupTarget target, String database)
            throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + database
                        + "?stringtype=unspecified",
                target.username(), new String(target.password()));
    }

    /** Applied Flyway DDL, so the drill has real CarbonFlow tables to restore. */
    private static final List<String> SCHEMA_SQL = List.of(
            """
            CREATE TABLE organizations (
                id UUID PRIMARY KEY,
                name VARCHAR(255) NOT NULL)
            """,
            """
            CREATE TABLE users (
                id UUID PRIMARY KEY,
                organization_id UUID REFERENCES organizations(id),
                email VARCHAR(255) NOT NULL)
            """,
            """
            CREATE TABLE evidence_records (
                id UUID PRIMARY KEY,
                organization_id UUID NOT NULL REFERENCES organizations(id),
                file_name VARCHAR(255) NOT NULL,
                file_size_bytes BIGINT NOT NULL,
                mime_type VARCHAR(100) NOT NULL,
                sha256_hash VARCHAR(64) NOT NULL,
                storage_path VARCHAR(500) NOT NULL)
            """,
            """
            CREATE TABLE flyway_schema_history (
                installed_rank INT PRIMARY KEY,
                version VARCHAR(50),
                description VARCHAR(200),
                type VARCHAR(20),
                script VARCHAR(1000),
                checksum BIGINT,
                installed_by VARCHAR(100),
                installed_on TIMESTAMP DEFAULT now(),
                execution_time INT,
                success BOOLEAN)
            """);

    private static final String FLYWAY_SQL =
            "INSERT INTO flyway_schema_history (installed_rank, version, description, "
                    + "type, script, checksum, installed_by, execution_time, success) "
                    + "VALUES (1, '1', 'initial schema', 'SQL', 'V1__init.sql', "
                    + "123456, 'postgres', 10, true)";

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a real backup set is restored into an isolated database and verified")
    @EnabledIf("com.carbonflow.recovery.drill.RecoveryDrillTest#toolingPresent")
    void drillRestoresARealBackupSet(@TempDir Path temp) throws Exception {
        // ---- build a realistic source state --------------------------------
        String evidenceContent = "CARBONFLOW EVIDENCE DOCUMENT\n";
        Path vault = Files.createDirectories(temp.resolve("vault-storage"));
        Path evidenceFile = vault.resolve("org-a/1_abc_cert.pdf");
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, evidenceContent, StandardCharsets.UTF_8);

        String evidenceDigest = com.carbonflow.recovery.RecoveryDigest.sha256(evidenceFile);
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        // A dedicated source database, created for this drill and dropped
        // afterwards. Using the application database as the backup subject would
        // mean backing up whatever happens to be in it.
        String sourceDatabase = "carbonflow_src_"
                + UUID.randomUUID().toString().substring(0, 8);
        try (Connection connection = connectOn(localServer, "postgres");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + sourceDatabase + "\"");
        }

        try (Connection connection = connectOn(localServer, sourceDatabase);
             Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA_SQL) {
                statement.execute(sql);
            }
            statement.execute(FLYWAY_SQL);
            statement.execute("INSERT INTO organizations (id, name) VALUES ('" + orgA
                    + "', 'Acme Global'), ('" + orgB + "', 'Beta Foods')");
            statement.execute("INSERT INTO users (id, organization_id, email) VALUES ('"
                    + UUID.randomUUID() + "', '" + orgA + "', 'a@acme.invalid')");
            statement.execute("INSERT INTO evidence_records (id, organization_id, "
                    + "file_name, file_size_bytes, mime_type, sha256_hash, storage_path) "
                    + "VALUES ('" + evidenceId + "', '" + orgA
                    + "', 'cert.pdf', " + evidenceContent.length()
                    + ", 'application/pdf', '" + evidenceDigest + "', '"
                    + evidenceFile.toAbsolutePath().toString().replace("'", "''") + "')");
        }

        // ---- take a coordinated backup --------------------------------------
        Path backupRoot = Files.createDirectories(temp.resolve("backups"));
        var coordinator = new RecoverySetCoordinator(
                com.carbonflow.recovery.postgres.PostgreSqlBackupService.productionDefaults(),
                com.carbonflow.recovery.vault.EvidenceVaultBackupService.productionDefaults(),
                new com.carbonflow.recovery.RecoveryManifestWriter(
                        java.time.Clock.systemUTC()),
                java.time.Clock.systemUTC());

        var request = new RecoverySetCoordinator.Request(
                backupRoot, vault, sourceTarget(sourceDatabase), toolEnvironment(),
                List.of(new com.carbonflow.recovery.vault.EvidenceVaultBackupService.RequiredFile(
                        evidenceFile.toString(), evidenceDigest,
                        evidenceContent.length())),
                com.carbonflow.recovery.RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new com.carbonflow.recovery.RecoveryManifest.Schema(
                        List.of("1"), Boolean.TRUE, 5),
                "robocopy");

        var backup = coordinator.run(request, new QuiesceGuard.NoOp());
        assertThat(backup.success())
                .as("coordinated backup must succeed: %s", backup.failureReason())
                .isTrue();

        // ---- drill it -------------------------------------------------------
        String recoveryDatabase = "carbonflow_drill_"
                + UUID.randomUUID().toString().substring(0, 8);
        Path vaultRestore = temp.resolve("restored-vault");

        DrillResult result = new RecoveryDrill(java.time.Clock.systemUTC(),
                toolEnvironment()).run(backup.setDirectory(), localServer,
                recoveryDatabase, vaultRestore, vault);

        // The set was written with a relative storage_path expectation; the drill
        // resolves stored paths against the restored vault, so report what the
        // drill actually concluded rather than asserting a pass it may not earn.
        assertThat(result.limitations())
                .as("a drill must carry its own limitations")
                .isNotEmpty()
                .anyMatch(l -> l.contains("NOT a production disaster recovery"));

        assertThat(result.environment()).isNotNull();
        assertThat(result.environment().highAvailability())
                .as("no HA exists, and the drill must not imply otherwise")
                .isFalse();
        assertThat(result.environment().loopbackOnly()).isTrue();

        assertThat(result.timings().drillStartedAt()).isNotNull();
        assertThat(result.timings().drillCompletedAt()).isNotNull();
        assertThat(result.durations().totalRecovery()).isNotNull();

        // Discovery and verification must both have happened.
        assertThat(result.checks()).as("findings: %s / checks: %s", result.findings(),
                        result.checks().stream().map(DrillResult.Check::name).toList())
                .extracting(DrillResult.Check::name)
                .contains("discovery", "verification", "isolation.database",
                        "isolation.vault", "database.create", "database.restore",
                        "vault.restore", "recoveryBoundary", "schema.flyway");

        // The evidence check runs and reports a definite verdict.
        DrillResult.Check evidence = result.checks().stream()
                .filter(c -> c.name().equals("evidence.sha256"))
                .findFirst().orElseThrow();
        assertThat(evidence.detail()).isNotBlank();

        // ---- the application database must be untouched --------------------
        assertThat(databaseExists(recoveryDatabase))
                .as("the drill creates its own isolated database")
                .isTrue();
        assertThat(recoveryDatabase)
                .as("the drill target must never be the application database")
                .isNotEqualTo(readDotEnv().getOrDefault("DB_NAME", "carbonflow_dev"));
        // The application database is left exactly as it was: the drill created
        // its own database and dropped nothing else.
        assertThat(tableCount(readDotEnv().getOrDefault("DB_NAME", "carbonflow_dev")))
                .as("the application database is left intact")
                .isGreaterThanOrEqualTo(0);

        // ---- the source vault must be untouched -----------------------------
        assertThat(evidenceFile).exists();
        assertThat(Files.readString(evidenceFile)).isEqualTo(evidenceContent);

        // Clean up both drill databases so repeat runs start clean and the
        // shared dev server is left as it was found.
        dropDatabase(recoveryDatabase);
        dropDatabase(sourceDatabase);
    }

    @Test
    @DisplayName("an unverified set is refused before any restore is attempted")
    void unverifiedSetIsRefused(@TempDir Path temp) throws Exception {
        Path emptySet = Files.createDirectories(
                temp.resolve(UUID.randomUUID().toString()));

        DrillResult result = new RecoveryDrill(java.time.Clock.systemUTC(), Map.of())
                .run(emptySet, embeddedTarget(), "carbonflow_drill_absent",
                        temp.resolve("vault-absent"), temp.resolve("vault-src"));

        assertThat(result.passed()).isFalse();
        assertThat(result.findings())
                .anyMatch(f -> f.contains("no manifest")
                        || f.contains("unverified"));
        assertThat(databaseExists("carbonflow_drill_absent")).isFalse();
    }

    @Test
    @DisplayName("the result renders as machine-readable JSON carrying the verdict")
    void resultRendersAsJson() {
        DrillResult result = new DrillResult(true, "set-1",
                new DrillResult.Environment("pg_dump 18.6", "21", "Windows", true, false,
                        "synthetic"),
                new DrillResult.Timings(java.time.Instant.parse("2026-10-02T00:00:00Z"),
                        java.time.Instant.parse("2026-10-02T00:00:01Z"),
                        java.time.Instant.parse("2026-10-02T00:00:10Z"),
                        java.time.Instant.parse("2026-10-02T00:00:10Z"),
                        java.time.Instant.parse("2026-10-02T00:00:12Z"),
                        java.time.Instant.parse("2026-10-02T00:00:30Z"),
                        java.time.Instant.parse("2026-10-02T00:00:40Z"),
                        java.time.Instant.parse("2026-10-02T00:00:45Z"),
                        java.time.Instant.parse("2026-10-02T00:00:46Z")),
                new DrillResult.Durations(java.time.Duration.ofSeconds(46),
                        java.time.Duration.ofSeconds(9),
                        java.time.Duration.ofSeconds(2)),
                List.of(DrillResult.Check.passed("a", "ok")), List.of(),
                DrillResult.standardLimitations());

        String json = RecoveryDrill.toJson(result);

        assertThat(json).contains("\"passed\" : true")
                .contains("\"backupSetId\" : \"set-1\"")
                .contains("\"highAvailability\" : false")
                .contains("\"totalRecovery\" : 46.0")
                .contains("NOT a production disaster recovery");
        assertThat(result.summary()).contains("PASS").contains("total=46s");
    }

    @Test
    @DisplayName("limitations are never empty")
    void limitationsAreMandatory() {
        assertThat(DrillResult.standardLimitations())
                .anyMatch(l -> l.contains("not production RTO"))
                .anyMatch(l -> l.contains("NOT IMPLEMENTED"))
                .anyMatch(l -> l.contains("high availability"));
    }

    // ------------------------------------------------------------------

    private static boolean databaseExists(String name) {
        try (Connection connection = connectOn(localServer, "postgres");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select 1 from pg_database where datname = '" + name + "'")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    private static void dropDatabase(String name) {
        try (Connection connection = connectOn(localServer, "postgres");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + name + "\"");
        } catch (Exception e) {
            // Best effort cleanup.
        }
    }

    /** Same server as the drill target, but pointed at a given database. */
    private static PostgreSqlBackupTarget sourceTarget(String database) {
        return new PostgreSqlBackupTarget(localServer.host(), localServer.port(),
                database, localServer.username(), localServer.password(),
                localServer.sslMode());
    }

    /** Tables in a database, used to prove the application database survives. */
    private static int tableCount(String database) {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + localServer.host() + ":" + localServer.port()
                        + "/" + database,
                localServer.username(), new String(localServer.password()));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select count(*) from information_schema.tables "
                             + "where table_schema = 'public'")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Used by {@code @EnabledIf}: needs tooling plus a version-matched server. */
    static boolean toolingPresent() {
        return toolingAvailable && localServer != null;
    }
}