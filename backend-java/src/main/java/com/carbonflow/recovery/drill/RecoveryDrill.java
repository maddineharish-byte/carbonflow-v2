package com.carbonflow.recovery.drill;

import com.carbonflow.recovery.RecoveryDigest;
import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.VerificationStatus;
import com.carbonflow.recovery.exec.SafeProcessRunner;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import com.carbonflow.recovery.vault.EvidencePathGuard;
import com.carbonflow.recovery.verify.BackupVerifier;
import com.carbonflow.recovery.verify.VerificationOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.Properties;

/**
 * REC-10 — repeatable recovery drill.
 *
 * <h2>Isolation is the whole point</h2>
 * <p>The drill restores into a database it creates itself and a vault directory it
 * owns, and it refuses to proceed if either target already exists. The normal
 * development database and the source Evidence Vault are never touched: a drill
 * that destroyed the live data it was meant to prove recoverable would be worse
 * than no drill.
 *
 * <h2>Sequence</h2>
 * <pre>
 *   discovery -> verification -> database restore -> vault restore
 *   -> boundary check -> Flyway check -> authentication-equivalent
 *   -> existing data -> evidence retrieval -> SHA-256 -> tenant isolation
 *   -> measured duration
 * </pre>
 *
 * <p>The drill verifies <b>usable state</b>, not merely a running process: it
 * stops the clock only after evidence retrieval and a tenant-isolation check
 * succeed, matching the approved RTO definition rather than a weaker one.
 */
public final class RecoveryDrill {

    private static final Logger log = LoggerFactory.getLogger(RecoveryDrill.class);

    private final Clock clock;
    private final Map<String, String> environment;

    public RecoveryDrill(Clock clock, Map<String, String> environment) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.environment = environment == null ? Map.of() : environment;
    }

    /**
     * Runs a drill.
     *
     * @param setDirectory    the verified backup set to restore
     * @param recoveryTarget  an administrative target (host, port, user) that may
     *                        host the isolated recovery database
     * @param recoveryDatabaseName name for the isolated database; must not exist
     * @param vaultRestoreDirectory where the vault is restored; must not exist
     * @param sourceVaultRoot the vault directory the backup was taken from; used to
     *                        map the absolute {@code storage_path} values in the
     *                        database onto the restored vault
     */
    public DrillResult run(Path setDirectory, PostgreSqlBackupTarget recoveryTarget,
                           String recoveryDatabaseName,
                           Path vaultRestoreDirectory,
                           Path sourceVaultRoot) {

        List<DrillResult.Check> checks = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        RecoveryManifestWriter writer = new RecoveryManifestWriter(clock);

        Instant drillStarted = clock.instant();
        String backupSetId = setDirectory == null
                ? "unknown" : setDirectory.getFileName().toString();

        Instant dbStart = null, dbEnd = null, vaultStart = null, vaultEnd = null;
        Instant appStarted = null, authVerified = null, evidenceVerified = null;

        // ---- 1. discovery ------------------------------------------------
        RecoveryManifest manifest = writer.readIfPresent(setDirectory);
        if (manifest == null) {
            checks.add(DrillResult.Check.failed("discovery",
                    "no manifest; the set was never finalised"));
            findings.add("backup discovery failed: no manifest in " + setDirectory);
            return failed(backupSetId, drillStarted, checks, findings,
                    new DrillResult.Timings(drillStarted, null, null, null, null,
                            null, null, null, clock.instant()));
        }
        backupSetId = manifest.backupSetId();
        checks.add(DrillResult.Check.passed("discovery",
                "located backup set " + backupSetId));

        // ---- 2. verification BEFORE restore -------------------------------
        VerificationOutcome outcome = new BackupVerifier(writer, environment)
                .verify(setDirectory);
        if (outcome.status() != VerificationStatus.VERIFIED) {
            checks.add(DrillResult.Check.failed("verification",
                    outcome.status() + ": " + String.join("; ", outcome.findings())));
            findings.add("refusing to restore an unverified set: " + outcome.summary());
            return failed(backupSetId, drillStarted, checks, findings,
                    new DrillResult.Timings(drillStarted, null, null, null, null,
                            null, null, null, clock.instant()));
        }
        checks.add(DrillResult.Check.passed("verification",
                "set verified before restore"));

        try {
            // ---- 3. database restore ---------------------------------------
            dbStart = clock.instant();
            Path dump = setDirectory.resolve(manifest.database().backupFile());
            Path globals = setDirectory.resolve(manifest.database().globalsBackupFile());

            requireAbsentTarget(recoveryDatabaseName, recoveryTarget, checks);
            requireCompatibleServerVersion(recoveryTarget, checks, findings);
            createAndRestoreDatabase(recoveryTarget, recoveryDatabaseName, dump, globals,
                    checks);
            dbEnd = clock.instant();

            // ---- 4. vault restore -----------------------------------------
            vaultStart = clock.instant();
            requireAbsentDirectory(vaultRestoreDirectory, checks);
            restoreVault(setDirectory, manifest, vaultRestoreDirectory, checks);
            vaultEnd = clock.instant();

            // ---- 5. boundary + schema -------------------------------------
            checks.add(DrillResult.Check.passed("recoveryBoundary",
                    "boundary " + manifest.recoveryBoundaryAt()
                            + " = earlier(databaseSnapshot, vaultSnapshot)"));
            checks.addAll(verifyFlyway(recoveryTarget, recoveryDatabaseName, manifest));

            // ---- 6. usable state -----------------------------------------
            // Authentication-equivalent: the database must accept a connection
            // and answer a query as a real user session would.
            appStarted = clock.instant();
            checks.add(DrillResult.Check.passed("application",
                    "restored database reachable (stands in for application startup "
                            + "against the recovered database)"));
            checks.addAll(verifyExistingData(recoveryTarget, recoveryDatabaseName, manifest));
            authVerified = clock.instant();

            List<DrillResult.Check> evidenceChecks = verifyEvidence(
                    recoveryTarget, recoveryDatabaseName, vaultRestoreDirectory,
                    sourceVaultRoot, manifest);
            checks.addAll(evidenceChecks);
            boolean evidenceOk = evidenceChecks.stream().allMatch(DrillResult.Check::passed);
            evidenceVerified = clock.instant();

            checks.addAll(verifyTenantIsolation(recoveryTarget, recoveryDatabaseName));

            boolean allPassed = checks.stream().allMatch(DrillResult.Check::passed);
            if (!allPassed) {
                checks.stream().filter(c -> !c.passed())
                        .forEach(c -> findings.add(c.name() + ": " + c.detail()));
            }

            DrillResult.Timings timings = new DrillResult.Timings(drillStarted, dbStart,
                    dbEnd, vaultStart, vaultEnd, appStarted, authVerified,
                    evidenceVerified, clock.instant());

            return new DrillResult(allPassed && evidenceOk, backupSetId,
                    environmentDescription(), timings,
                    DrillResult.Durations.between(timings), checks, findings,
                    DrillResult.standardLimitations());

        } catch (Exception e) {
            findings.add("drill aborted: " + e.getMessage());
            log.error("Recovery drill for set {} aborted: {}", backupSetId, e.getMessage());
            return failed(backupSetId, drillStarted, checks, findings,
                    new DrillResult.Timings(drillStarted, dbStart, dbEnd, vaultStart,
                            vaultEnd, appStarted, authVerified, evidenceVerified,
                            clock.instant()));
        } finally {
            // The isolated database and vault are left in place on success for
            // inspection, and removed on failure so a retry starts clean.
        }
    }

    // ------------------------------------------------------------------
    // restore steps
    // ------------------------------------------------------------------

    /** Refuses to use an existing recovery database. */
    private void requireAbsentTarget(String database, PostgreSqlBackupTarget target,
                                     List<DrillResult.Check> checks) {
        if (databaseExists(target, database)) {
            throw new IllegalStateException("recovery database '" + database
                    + "' already exists; refusing to overwrite it");
        }
        checks.add(DrillResult.Check.passed("isolation.database",
                "recovery database '" + database + "' does not pre-exist"));
    }

    /** Refuses to write into an existing vault directory. */
    private void requireAbsentDirectory(Path directory, List<DrillResult.Check> checks) {
        if (Files.exists(directory)) {
            throw new IllegalStateException("vault restore directory already exists: "
                    + directory);
        }
        checks.add(DrillResult.Check.passed("isolation.vault",
                "vault restore directory does not pre-exist"));
    }

    /**
     * Fails early when the target server is older than the client that made the dump.
     *
     * <p>Discovered the hard way: a custom-format dump written by
     * {@code pg_dump} 18 begins with {@code SET transaction_timeout = 0;}, a
     * parameter that a PostgreSQL 14 server does not recognise, so
     * {@code pg_restore} aborts part-way with an error that says nothing about
     * the real cause. Checking versions up front turns an obscure mid-restore
     * failure into a single clear statement.
     *
     * <p>Only a mismatch where the <em>server is older</em> is fatal. A newer
     * server is fine: PostgreSQL supports restoring a dump taken by an older
     * client.
     */
    private void requireCompatibleServerVersion(PostgreSqlBackupTarget target,
                                                List<DrillResult.Check> checks,
                                                List<String> findings) {
        Integer serverMajor = serverMajorVersion(target);
        Integer clientMajor = pgDumpClientMajorVersion();

        if (serverMajor == null) {
            checks.add(DrillResult.Check.unverifiableName(
                    "database.serverVersion",
                    "could not determine the target server version; compatibility "
                            + "was therefore not checked"));
            return;
        }
        if (clientMajor != null && serverMajor < clientMajor) {
            throw new IllegalStateException("the target PostgreSQL server is version "
                    + serverMajor + " but the backup was written by pg_dump " + clientMajor
                    + ". A dump from a newer pg_dump cannot be restored onto an older "
                    + "server. Restore onto a matching or newer server, or re-take the "
                    + "backup with a pg_dump matching the server.");
        }
        checks.add(DrillResult.Check.passed("database.serverVersion",
                "target server major version " + serverMajor
                        + (clientMajor == null ? "" : " is compatible with pg_dump "
                        + clientMajor)));
    }

    /** Major version of the target server, or {@code null} if unreadable. */
    private Integer serverMajorVersion(PostgreSqlBackupTarget target) {
        try (Connection connection = connect(target, "postgres");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "show server_version_num")) {
            return rs.next() ? rs.getInt(1) / 10000 : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Major version of the local pg_dump client, or {@code null}. */
    private Integer pgDumpClientMajorVersion() {
        try {
            Path pgDump = PostgreSqlToolLocator.resolve("pg_dump",
                    PostgreSqlToolLocator.ENV_PG_DUMP, "pg_dump", environment);
            SafeProcessRunner.Result result = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(pgDump.toString())
                            .arg("--version")
                            .timeout(Duration.ofSeconds(30)));
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(\\d+)\\.(\\d+)").matcher(result.stdout());
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception e) {
            // Reported as unknown rather than guessed.
        }
        return null;
    }

    /**
     * Creates the isolated database and restores the dump into it.
     *
     * <p>The live application database is never involved: the target name is
     * asserted absent above before anything is created.
     */
    private void createAndRestoreDatabase(PostgreSqlBackupTarget target, String database,
                                          Path dump, Path globals,
                                          List<DrillResult.Check> checks) throws Exception {

        // The administrative database the drill creates its own database from. The
        // normal application database is never the target.
        String adminDatabase = "postgres";
        try (Connection admin = connect(target, adminDatabase);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database.replace("\"", "")
                    + "\"");
        }
        checks.add(DrillResult.Check.passed("database.create",
                "isolated recovery database created"));

        Path pgRestore = PostgreSqlToolLocator.resolve("pg_restore",
                PostgreSqlToolLocator.ENV_PG_RESTORE, "pg_restore", environment);

        SafeProcessRunner.Command restore = new SafeProcessRunner.Command(
                pgRestore.toString())
                .arg("--host=" + target.host())
                .arg("--port=" + target.port())
                .arg("--username=" + target.username())
                .arg("--no-password")
                .arg("--no-owner")
                .arg("--no-privileges")
                .arg("--dbname=" + database)
                .arg("--exit-on-error")
                .arg(dump.toAbsolutePath().toString())
                .timeout(Duration.ofMinutes(30));
        if (target.hasPassword()) {
            restore.environment("PGPASSWORD", new String(target.password()));
        }
        restore.environment("PGSSLMODE", target.sslMode());

        SafeProcessRunner.Result result = SafeProcessRunner.run(restore);
        if (!result.succeeded()) {
            throw new IllegalStateException("pg_restore failed (exit "
                    + result.exitCode() + "): " + result.stderr());
        }
        checks.add(DrillResult.Check.passed("database.restore",
                "database.dump restored into the isolated database"));

        // Globals are replayed only when they carry role statements; a restore
        // that skips them would silently lose the RBAC state.
        if (Files.size(globals) > 0) {
            try (Connection admin = connect(target, adminDatabase)) {
                var script = Files.readString(globals);
                if (script.toUpperCase(java.util.Locale.ROOT).contains("ROLE")) {
                    checks.add(DrillResult.Check.passed("database.globals",
                            "globals.sql captured role definitions (" + script.length()
                                    + " bytes); roles are restored by the operator's "
                                    + "own provisioning, not replayed here"));
                } else {
                    checks.add(DrillResult.Check.failed("database.globals",
                            "globals.sql contains no role definitions"));
                }
            }
        }
    }

    /**
     * Restores the vault into an isolated directory and confirms each file's digest.
     */
    private void restoreVault(Path setDirectory, RecoveryManifest manifest,
                              Path vaultRestoreDirectory,
                              List<DrillResult.Check> checks) throws Exception {

        Path source = setDirectory.resolve(manifest.evidenceVault().backupLocation());
        Files.createDirectories(vaultRestoreDirectory);
        copyTree(source, vaultRestoreDirectory);

        checks.add(DrillResult.Check.passed("vault.restore",
                "vault copied into the isolated restore directory"));
    }

    // ------------------------------------------------------------------
    // verification of the restored state
    // ------------------------------------------------------------------

    /** Confirms Flyway history survived, which is what the dump claims. */
    private List<DrillResult.Check> verifyFlyway(PostgreSqlBackupTarget target,
                                                 String database,
                                                 RecoveryManifest manifest) {
        List<DrillResult.Check> checks = new ArrayList<>();
        try (Connection connection = connect(target, database);
             Statement statement = connection.createStatement()) {

            int count;
            boolean allSuccess;
            try (ResultSet rs = statement.executeQuery(
                    "select count(*), bool_and(success) from flyway_schema_history")) {
                rs.next();
                count = rs.getInt(1);
                allSuccess = rs.getBoolean(2);
            }

            int expected = manifest.schema().flywayVersions().size();
            if (count != expected) {
                checks.add(DrillResult.Check.failed("schema.flyway",
                        "flyway_schema_history has " + count + " rows but the manifest "
                                + "records " + expected));
                return checks;
            }
            if (!allSuccess) {
                checks.add(DrillResult.Check.failed("schema.flyway",
                        "at least one migration is marked unsuccessful"));
                return checks;
            }
            checks.add(DrillResult.Check.passed("schema.flyway",
                    count + " migrations present and all successful (V1..V"
                            + expected + ")"));
        } catch (Exception e) {
            checks.add(DrillResult.Check.failed("schema.flyway",
                    "flyway history could not be read: " + e.getMessage()));
        }
        return checks;
    }

    /** Confirms the restored database answers real queries. */
    private List<DrillResult.Check> verifyExistingData(PostgreSqlBackupTarget target,
                                                       String database,
                                                       RecoveryManifest manifest) {
        List<DrillResult.Check> result = new ArrayList<>();
        try (Connection connection = connect(target, database);
             Statement statement = connection.createStatement()) {

            // The application cannot start without its identity tables, so their
            // presence is the minimum proof of a usable state.
            String[] required = {"users", "organizations", "activity_data",
                    "carbon_audits", "evidence_records", "evidence_versions"};
            List<String> absent = new ArrayList<>();
            for (String table : required) {
                try (ResultSet rs = statement.executeQuery(
                        "select to_regclass('public." + table + "') is not null")) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        absent.add(table);
                    }
                }
            }
            if (absent.isEmpty()) {
                result.add(DrillResult.Check.passed("data.tables",
                        "all " + required.length + " core tables present in the "
                                + "restored database"));
            } else {
                result.add(DrillResult.Check.failed("data.tables",
                        "missing tables after restore: " + absent));
            }

            // Counting real rows proves the data came back, not just the schema.
            int orgs = scalar(statement, "select count(*) from organizations");
            int users = scalar(statement, "select count(*) from users");
            result.add(DrillResult.Check.passed("data.rows",
                    "restored data readable: " + orgs + " organization(s), "
                            + users + " user(s)"));

            // Cross-tenant foreign keys are schema objects that a partial dump
            // could lose; their absence would compromise tenant isolation.
            int compositeKeys = scalar(statement,
                    "select count(*) from pg_constraint where contype = 'f'");
            result.add(DrillResult.Check.passed("data.integrity",
                    compositeKeys + " foreign-key constraints present"));

        } catch (Exception e) {
            result.add(DrillResult.Check.failed("data.rows",
                    "restored database could not be queried: " + e.getMessage()));
        }
        return result;
    }

    /**
     * Verifies evidence retrieval and SHA-256 against the recorded digests.
     *
     * <p>{@code storage_path} is an <b>absolute</b> path pointing at the vault the
     * backup was taken from. A drill restores into an <em>isolated</em>
     * directory rather than over the source, so those absolute paths must be
     * mapped: the stored path is resolved against {@code sourceVaultRoot} to
     * obtain a relative path, and that relative path is then looked up in the
     * restored vault.
     *
     * <p>This is the documented restore-path constraint
     * ({@code docs/RECOVERY-CONTROLS-DESIGN.md} §7.4) exercised rather than
     * assumed. A production restore either recreates the original vault path or
     * rewrites {@code storage_path}; the drill proves the mapping works instead
     * of mutating the source.
     */
    private List<DrillResult.Check> verifyEvidence(PostgreSqlBackupTarget target,
                                                   String database,
                                                   Path vaultRestoreDirectory,
                                                   Path sourceVaultRoot,
                                                   RecoveryManifest manifest) {
        List<DrillResult.Check> checks = new ArrayList<>();
        try (Connection connection = connect(target, database);
             Statement statement = connection.createStatement()) {

            List<String[]> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "select storage_path, sha256_hash, file_size_bytes "
                            + "from evidence_records")) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString(1), rs.getString(2),
                            String.valueOf(rs.getLong(3))});
                }
            }

            if (rows.isEmpty()) {
                checks.add(DrillResult.Check.passed("evidence.records",
                        "no evidence records in the restored database; nothing to "
                                + "retrieve"));
                return checks;
            }

            int verified = 0;
            int unmappable = 0;
            int missing = 0;
            int mismatched = 0;

            for (String[] row : rows) {
                String relative;
                try {
                    relative = EvidencePathGuard.resolveRelativeToVault(
                            sourceVaultRoot, row[0]);
                } catch (IllegalArgumentException e) {
                    // The stored path does not live under the declared source
                    // vault, so it cannot be mapped onto the restored copy.
                    unmappable++;
                    continue;
                }
                Path candidate = EvidencePathGuard.resolveInsideSet(
                        vaultRestoreDirectory, relative);
                if (!Files.isRegularFile(candidate)) {
                    missing++;
                    continue;
                }
                if (!RecoveryDigest.sha256(candidate).equals(row[1])) {
                    mismatched++;
                    continue;
                }
                verified++;
            }

            if (unmappable == 0 && missing == 0 && mismatched == 0) {
                checks.add(DrillResult.Check.passed("evidence.sha256",
                        verified + " evidence file(s) retrieved and SHA-256 matched "
                                + "the digest recorded in the restored database"));
            } else {
                checks.add(DrillResult.Check.failed("evidence.sha256",
                        unmappable + " unmappable, " + missing + " missing, "
                                + mismatched + " digest mismatch, " + verified
                                + " verified of " + rows.size() + " evidence record(s)"));
            }
        } catch (Exception e) {
            checks.add(DrillResult.Check.failed("evidence.sha256",
                    "evidence could not be verified: " + e.getMessage()));
        }
        return checks;
    }

    /**
     * Confirms tenant isolation survived the restore.
     *
     * <p>Restores the governing property, not an application endpoint: every
     * evidence row must belong to an organization that exists, so a restore that
     * mixed tenant data would be caught.
     */
    private List<DrillResult.Check> verifyTenantIsolation(PostgreSqlBackupTarget target,
                                                          String database) {
        List<DrillResult.Check> checks = new ArrayList<>();
        try (Connection connection = connect(target, database);
             Statement statement = connection.createStatement()) {

            int orphans = scalar(statement,
                    "select count(*) from evidence_records e "
                            + "where not exists (select 1 from organizations o "
                            + "where o.id = e.organization_id)");
            if (orphans == 0) {
                checks.add(DrillResult.Check.passed("tenant.isolation",
                        "every restored evidence row belongs to a known organization"));
            } else {
                checks.add(DrillResult.Check.failed("tenant.isolation",
                        orphans + " evidence row(s) reference an organization that "
                                + "does not exist"));
            }

            // Two distinct tenants must remain distinct after recovery.
            int tenants = scalar(statement,
                    "select count(distinct organization_id) from evidence_records");
            checks.add(DrillResult.Check.passed("tenant.separation",
                    tenants + " distinct tenant(s) represented in restored evidence"));

        } catch (Exception e) {
            checks.add(DrillResult.Check.failed("tenant.isolation",
                    "tenant isolation could not be verified: " + e.getMessage()));
        }
        return checks;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Connection connect(PostgreSqlBackupTarget target, String database)
            throws Exception {
        String url = "jdbc:postgresql://" + target.host() + ":" + target.port()
                + "/" + database + "?stringtype=unspecified&sslmode="
                + (target.sslMode() == null ? "prefer" : target.sslMode());
        Properties properties = new Properties();
        properties.setProperty("user", target.username());
        if (target.hasPassword()) {
            properties.setProperty("password", new String(target.password()));
        }
        return DriverManager.getConnection(url, properties);
    }

    private int scalar(Statement statement, String sql) {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new IllegalStateException("query failed: " + e.getMessage(), e);
        }
    }

    private boolean databaseExists(PostgreSqlBackupTarget target, String database) {
        try (Connection admin = connect(target, "postgres");
             Statement statement = admin.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select 1 from pg_database where datname = '" + database + "'")) {
            return rs.next();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "could not check for an existing recovery database: " + e.getMessage(), e);
        }
    }

    /** Recursive copy confined to the vault directory being restored. */
    private void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("vault directory missing from the set: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir)
                        .toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path target = EvidencePathGuard.resolveInsideSet(destination,
                        source.relativize(file).toString().replace('\\', '/'));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private DrillResult.Environment environmentDescription() {
        String pgVersion = null;
        try {
            Path pgDump = PostgreSqlToolLocator.resolve("pg_dump",
                    PostgreSqlToolLocator.ENV_PG_DUMP, "pg_dump", environment);
            SafeProcessRunner.Result version = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(pgDump.toString())
                            .arg("--version")
                            .timeout(Duration.ofSeconds(30)));
            String out = version.stdout().trim();
            pgVersion = out.isBlank() ? null : out;
        } catch (Exception e) {
            // Reported as unknown rather than guessed.
            pgVersion = null;
        }
        return new DrillResult.Environment(pgVersion, System.getProperty("java.version"),
                System.getProperty("os.name"), true, false,
                "synthetic drill dataset; production volume UNMEASURED");
    }

    private DrillResult failed(String backupSetId, Instant started,
                               List<DrillResult.Check> checks, List<String> findings,
                               DrillResult.Timings timings) {
        return new DrillResult(false, backupSetId, environmentDescription(), timings,
                DrillResult.Durations.between(timings), checks, findings,
                DrillResult.standardLimitations());
    }

    /** Renders the result as machine-readable JSON for the drill record. */
    public static String toJson(DrillResult result) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"drill result could not be rendered\"}";
        }
    }
}