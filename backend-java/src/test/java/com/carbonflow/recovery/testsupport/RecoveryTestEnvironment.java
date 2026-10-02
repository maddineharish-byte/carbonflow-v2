package com.carbonflow.recovery.testsupport;

import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import com.carbonflow.recovery.exec.SafeProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host discovery shared by the recovery drill and validation tests.
 *
 * <p>Discovery lives here because three suites need the same answers: the
 * PostgreSQL client tools, a server whose major version matches the local
 * {@code pg_dump}, and the tool environment. Each re-deriving that would let the
 * suites disagree about what counts as a usable environment.
 *
 * <p>Credentials are read from the process environment or a local {@code .env}
 * at <b>runtime</b> only. No credential is written to a fixture, a log line, or
 * the repository, and none appears in a failure message.
 */
public final class RecoveryTestEnvironment {

    private static Boolean tooling;
    private static PostgreSqlBackupTarget matched;

    private RecoveryTestEnvironment() {
    }

    /** True when {@code pg_dump}, {@code pg_dumpall} and {@code pg_restore} resolve. */
    public static synchronized boolean toolingAvailable() {
        if (tooling == null) {
            tooling = findTool("pg_dump") != null && findTool("pg_dumpall") != null
                    && findTool("pg_restore") != null;
            matched = tooling ? findMatchingServer() : null;
        }
        return tooling;
    }

    /** True when tooling and a version-matched server are both available. */
    public static boolean ready() {
        return toolingAvailable() && matchingServer() != null;
    }

    /** A server whose major version matches {@code pg_dump}, or {@code null}. */
    public static synchronized PostgreSqlBackupTarget matchingServer() {
        toolingAvailable();
        return matched;
    }

    public static PostgreSqlBackupTarget targetFor(String database) {
        PostgreSqlBackupTarget server = matchingServer();
        return new PostgreSqlBackupTarget(server.host(), server.port(), database,
                server.username(), server.password(), server.sslMode());
    }

    public static Connection connect(PostgreSqlBackupTarget target, String database)
            throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + database,
                target.username(), new String(target.password()));
    }

    public static void dropDatabase(String name) {
        PostgreSqlBackupTarget server = matchingServer();
        if (server == null) {
            return;
        }
        try (Connection connection = connect(server, "postgres");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + name + "\"");
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    /** Process environment with every PostgreSQL tool path pinned. */
    public static Map<String, String> toolEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put(PostgreSqlToolLocator.ENV_PG_DUMP, findTool("pg_dump").toString());
        env.put(PostgreSqlToolLocator.ENV_PG_DUMPALL, findTool("pg_dumpall").toString());
        env.put(PostgreSqlToolLocator.ENV_PG_RESTORE, findTool("pg_restore").toString());
        return env;
    }

    // ------------------------------------------------------------------

    public static Path findTool(String name) {
        String envVariable = switch (name) {
            case "pg_dump" -> PostgreSqlToolLocator.ENV_PG_DUMP;
            case "pg_dumpall" -> PostgreSqlToolLocator.ENV_PG_DUMPALL;
            default -> PostgreSqlToolLocator.ENV_PG_RESTORE;
        };
        String configured = System.getenv(envVariable);
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                for (String candidate : new String[]{name, name + ".exe"}) {
                    Path candidatePath = Path.of(dir).resolve(candidate);
                    if (Files.isRegularFile(candidatePath)) {
                        return candidatePath;
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

    /**
     * A server whose major version matches {@code pg_dump}.
     *
     * <p>A dump written by a newer {@code pg_dump} cannot be restored onto an
     * older server: PostgreSQL 18 emits {@code SET transaction_timeout = 0},
     * which a PostgreSQL 14 server rejects mid-restore with an error that gives
     * no hint of the cause. Matching versions is the realistic drill scenario.
     */
    private static PostgreSqlBackupTarget findMatchingServer() {
        Integer clientMajor = majorVersion(findTool("pg_dump"));
        if (clientMajor == null) {
            return null;
        }
        for (PostgreSqlBackupTarget candidate : candidateServers()) {
            try {
                try (Connection connection = connect(candidate, "postgres");
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
        try {
            candidates.add(new PostgreSqlBackupTarget("127.0.0.1",
                    embeddedPort(), "postgres",
                    com.carbonflow.testsupport.EmbeddedPg.username(),
                    com.carbonflow.testsupport.EmbeddedPg.password().toCharArray(),
                    "prefer"));
        } catch (Exception ignored) {
            // embedded server not started
        }
        return candidates;
    }

    private static int embeddedPort() {
        String jdbc = com.carbonflow.testsupport.EmbeddedPg.jdbcUrl();
        String after = jdbc.substring(jdbc.indexOf("//") + 2);
        String hostPort = after.substring(0, after.indexOf('/'));
        return Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1));
    }

    private static Integer majorVersion(Path tool) {
        if (tool == null) {
            return null;
        }
        try {
            SafeProcessRunner.Result result = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(tool.toString())
                            .arg("--version").timeout(Duration.ofSeconds(30)));
            var matcher = java.util.regex.Pattern.compile("(\\d+)\\.")
                    .matcher(result.stdout());
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Git's bundled gpg needs a POSIX-style home directory path. */
    public static String toPosix(String windowsPath) {
        String value = windowsPath.replace('\\', '/');
        if (value.length() > 2 && value.charAt(1) == ':') {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
        }
        return value;
    }

    /** Reads {@code DB_*} from the environment, falling back to a local .env. */
    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        for (String key : List.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER",
                "DB_PASSWORD", "DB_SSLMODE")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                values.put(key, value);
            }
        }
        // Maven runs with backend-java/ as the working directory.
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
                // try the next location
            }
        }
        return values;
    }
}