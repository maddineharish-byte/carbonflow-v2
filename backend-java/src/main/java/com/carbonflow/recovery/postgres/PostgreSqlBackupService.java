package com.carbonflow.recovery.postgres;

import com.carbonflow.recovery.exec.SafeProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * REC-03 — PostgreSQL backup automation.
 *
 * <h2>What this produces</h2>
 * <p>A backup set directory containing exactly two artefacts:
 * <pre>
 * &lt;backupRoot&gt;/&lt;backupSetId&gt;/
 *     database.dump     pg_dump --format=custom
 *     globals.sql       pg_dumpall --globals-only
 * </pre>
 * plus structured metadata carrying size, timestamps and SHA-256 for each.
 *
 * <h2>Both artefacts are mandatory</h2>
 * <p>Roles, {@code permissions} and {@code role_permissions} live outside the
 * database dump but are read at runtime by the frozen 9-role × 44-permission
 * matrix ({@code docs/BACKUP-RECOVERY.md} §2.2). A set containing only
 * {@code database.dump} would restore an application that cannot authorise
 * anything, so a globals failure fails the whole operation rather than
 * producing a partial set.
 *
 * <h2>What this does not do</h2>
 * <p>No Evidence Vault copy, no quiescing, no recovery-boundary coordination, no
 * manifest write, no retention, no monitoring, no encryption, no verification,
 * no RTO/RPO measurement. This is a primitive for REC-05 to coordinate and for
 * REC-06 to verify.
 *
 * <h2>Not atomic with the vault</h2>
 * <p>The database and the Evidence Vault share no transaction coordinator. This
 * class makes no atomicity claim whatsoever, and the {@code databaseDumpAt}
 * timestamp it records exists precisely so REC-05 can compute the conservative
 * earlier-of-the-two recovery boundary.
 *
 * <h2>Not a Spring bean</h2>
 * <p>No {@code @Component}: recovery tooling is invoked explicitly by an operator
 * or a later phase, and the application must start and serve traffic with none of
 * this present.
 */
public final class PostgreSqlBackupService {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlBackupService.class);

    private final Clock clock;
    private final Duration defaultTimeout;

    /**
     * @param clock   injected so metadata timestamps are reproducible in tests
     * @param defaultTimeout bound on each external command
     */
    public PostgreSqlBackupService(Clock clock, Duration defaultTimeout) {
        this.clock = clock;
        this.defaultTimeout = defaultTimeout;
    }

    /** Production default: system UTC clock, 30-minute bound per command. */
    public static PostgreSqlBackupService productionDefaults() {
        return new PostgreSqlBackupService(Clock.systemUTC(), Duration.ofMinutes(30));
    }

    /**
     * Validates and normalises the configured backup root.
     *
     * <p>Called by whoever constructs the service (an operator entry point or a
     * later phase), because the root is a configuration concern while this class
     * is the mechanism. It is exposed rather than assumed so a caller cannot
     * accidentally pass an unvalidated path.
     *
     * <p>Only an absolute, existing-or-creatable directory is accepted. A
     * relative path is refused because it would resolve against whatever
     * working directory the backup process happened to inherit, which differs
     * between a shell, a service manager and a container.
     *
     * @param configured configured root path
     * @return the normalised absolute root
     */
    public static Path requireUsableBackupRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(
                    "No backup root configured. Set the backup root to an absolute "
                            + "directory on durable storage before running a backup.");
        }
        // Test the raw input before absolutising. Calling toAbsolutePath() first would
        // make every path absolute and the check could never fail — which is
        // precisely the bug a relative backup root causes: the same
        // configuration would resolve differently under a shell, a service
        // manager and a container.
        Path raw = Path.of(configured.trim());
        if (!raw.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Backup root must be an absolute path, was: '" + configured
                            + "'. A relative path would resolve against the working "
                            + "directory of whatever invoked the backup.");
        }
        Path root = raw.normalize();
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "Backup root exists but is not a directory: " + root);
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Backup root could not be created: " + root + " ("
                            + e.getMessage() + ")", e);
        }
        if (!Files.isWritable(root)) {
            throw new IllegalArgumentException("Backup root is not writable: " + root);
        }
        return root;
    }

    /**
     * Confirms {@code setDirectory} lies inside {@code backupRoot}.
     *
     * <p>Defence in depth for REC-05, which will pass paths derived from
     * configuration. A resolved-and-normalised containment check is the only
     * reliable way to be sure a constructed path has not escaped via traversal.
     *
     * @throws IllegalArgumentException if the set directory is outside the root
     */
    public static void requireInsideBackupRoot(Path backupRoot, Path setDirectory) {
        Path root = backupRoot.toAbsolutePath().normalize();
        Path candidate = setDirectory.toAbsolutePath().normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new IllegalArgumentException(
                    "Backup set directory " + candidate + " is outside the backup root "
                            + root);
        }
    }

    /**
     * Outcome of a backup attempt.
     *
     * <p>Either a complete {@link PostgreSqlBackupResult} or a failure with a
     * reason. There is deliberately no "partial success" state.
     *
     * @param success      whether a complete set was produced
     * @param result       the set metadata, or {@code null} on failure
     * @param backupSetId  the set identity attempted, for log correlation
     * @param failureReason operator-readable cause, or {@code null} on success
     */
    public record BackupOutcome(boolean success, PostgreSqlBackupResult result,
                                String backupSetId, String failureReason) {

        static BackupOutcome ok(PostgreSqlBackupResult result) {
            return new BackupOutcome(true, result, result.backupSetId(), null);
        }

        static BackupOutcome failed(String backupSetId, String reason) {
            return new BackupOutcome(false, null, backupSetId, reason);
        }
    }

    /**
     * Executes a complete database backup into {@code backupRoot}.
     *
     * <p>The set directory is derived from a fresh UUIDv4, so an existing set is
     * never reused or overwritten. No caller-supplied directory name is accepted.
     *
     * @param backupRoot  pre-validated root; sets are created beneath it
     * @param target      connection parameters
     * @param environment process environment, for tool discovery and PGPASSWORD
     */
    public BackupOutcome backup(Path backupRoot, PostgreSqlBackupTarget target,
                                Map<String, String> environment) {
        String backupSetId = UUID.randomUUID().toString();
        try {
            return performBackup(backupRoot, target, environment, backupSetId);
        } catch (ToolNotFound | IOException e) {
            // Configuration and start-up faults become a readable FAILED
            // outcome rather than an escaping exception. An operator running a
            // scheduled backup needs a status they can act on, not a stack trace
            // from inside tool discovery.
            // The message never contains a credential: the password travels via
            // PGPASSWORD only, and these exceptions name variables and paths.
            log.error("PostgreSQL backup set {} could not start: {}",
                    backupSetId, e.getMessage());
            return BackupOutcome.failed(backupSetId, e.getMessage());
        }
    }

    /** Raised when a required client executable cannot be resolved. */
    private static final class ToolNotFound extends RuntimeException {
        ToolNotFound(String message) {
            super(message);
        }
    }

    private BackupOutcome performBackup(Path backupRoot, PostgreSqlBackupTarget target,
                                        Map<String, String> environment,
                                        String backupSetId) throws IOException, ToolNotFound {

        Path pgDump = resolveTool("pg_dump", PostgreSqlToolLocator.ENV_PG_DUMP,
                "pg_dump", environment);
        Path pgDumpAll = resolveTool("pg_dumpall", PostgreSqlToolLocator.ENV_PG_DUMPALL,
                "pg_dumpall", environment);

        // Logged, not trusted: the redacted toString omits the password.
        log.info("Starting PostgreSQL backup set {} against {} using pg_dump at {}",
                backupSetId, target, pgDump);

        Path setDirectory = backupRoot.resolve(backupSetId);
        requireInsideBackupRoot(backupRoot, setDirectory);
        if (Files.exists(setDirectory)) {
            // UUIDv4 collision is implausible, but overwriting a set would destroy
            // an existing recovery point, so it is refused rather than assumed away.
            throw new IOException("Refusing to reuse existing backup set directory "
                    + setDirectory);
        }
        Files.createDirectories(setDirectory);

        Path databaseDump = setDirectory.resolve(PostgreSqlBackupResult.DATABASE_DUMP_NAME);
        Path globalsDump = setDirectory.resolve(PostgreSqlBackupResult.GLOBALS_DUMP_NAME);

        // Note: the child process is deliberately NOT given setDirectory as its
        // working directory. Every output path is absolute, so there is no need
        // to chdir, and a backup child that is killed on timeout would otherwise
        // keep a handle on its own CWD — leaving a set directory that cannot be
        // removed or retried on Windows, long after the process is gone.

        boolean databaseWritten = false;
        boolean globalsWritten = false;
        try {
            // ---- 1. database dump (custom format) ------------------------
            SafeProcessRunner.Result dbRun = runPgDump(pgDump, target, environment,
                    databaseDump);
            requireSuccess(dbRun, "pg_dump", databaseDump, backupSetId);
            requireNonEmptyArtifact(databaseDump, "database.dump", backupSetId);
            databaseWritten = true;

            // ---- 2. globals capture (mandatory) ---------------------------
            SafeProcessRunner.Result globalsRun = runPgDumpAll(pgDumpAll, target,
                    environment, globalsDump);
            requireSuccess(globalsRun, "pg_dumpall", globalsDump, backupSetId);
            requireNonEmptyArtifact(globalsDump, "globals.sql", backupSetId);
            globalsWritten = true;

            // ---- 3. checksums (streaming) --------------------------------
            String databaseSha = sha256(databaseDump);
            String globalsSha = sha256(globalsDump);
            long databaseBytes = Files.size(databaseDump);
            long globalsBytes = Files.size(globalsDump);

            Instant completedAt = clock.instant();

            PostgreSqlBackupResult result = new PostgreSqlBackupResult(
                    backupSetId,
                    setDirectory,
                    databaseDump,
                    databaseBytes,
                    completedAt,
                    databaseSha,
                    globalsDump,
                    globalsBytes,
                    completedAt,
                    globalsSha,
                    probeClientVersion(pgDump, environment));

            log.info("PostgreSQL backup set {} complete: database.dump {} bytes, "
                            + "globals.sql {} bytes", backupSetId, databaseBytes, globalsBytes);
            return BackupOutcome.ok(result);

        } catch (BackupFailure failure) {
            // Fail closed. Anything written by this attempt is removed, because a
            // truncated dump is worse than no dump: it looks restorable and is not.
            quarantinePartial(setDirectory, databaseDump, globalsDump,
                    databaseWritten, globalsWritten);
            return BackupOutcome.failed(backupSetId, failure.getMessage());
        }
    }

    /**
     * Resolves a client tool, converting the locator's configuration fault into a
     * {@link ToolNotFound} so it surfaces as a failed backup.
     */
    private static Path resolveTool(String name, String variable, String bareName,
                                    Map<String, String> environment) {
        try {
            return PostgreSqlToolLocator.resolve(name, variable, bareName, environment);
        } catch (IllegalStateException e) {
            throw new ToolNotFound(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // pg_dump
    // ------------------------------------------------------------------

    /**
     * Runs {@code pg_dump --format=custom}.
     *
     * <p>Argument vector, never a shell string. The password is supplied via the
     * {@code PGPASSWORD} environment variable of the child process, so it does
     * not appear in the process table — {@code docs/BACKUP-RECOVERY.md} §2.1
     * explicitly requires this over a command-line argument.
     */
    private SafeProcessRunner.Result runPgDump(Path executable,
                                               PostgreSqlBackupTarget target,
                                               Map<String, String> environment,
                                               Path outputFile)
            throws IOException, BackupFailure {

        SafeProcessRunner.Command command = new SafeProcessRunner.Command(
                executable.toString())
                .arg("--format=custom")
                .arg("--host=" + target.host())
                .arg("--port=" + target.port())
                .arg("--username=" + target.username())
                .arg("--dbname=" + target.database())
                .arg("--no-password")          // fail rather than block on a prompt
                .arg("--file=" + outputFile.toAbsolutePath())
                .timeout(defaultTimeout);

        if (target.hasPassword()) {
            command.environment("PGPASSWORD", new String(target.password()));
        }
        // Carry the application's TLS mode through to libpq unchanged. pg_dump
        // reads PGSSLMODE from the environment, which is how the backup honours
        // the same transport security the application uses — never weaker, and
        // never disabled to make a backup succeed.
        command.environment("PGSSLMODE", target.sslMode());

        log.debug("Executing pg_dump with argument vector {}", command.argumentVector());
        return SafeProcessRunner.run(command);
    }

    /**
     * {@code pg_dumpall --globals-only}.
     *
     * <p>{@code --clean} is deliberately <em>not</em> passed: the capture is
     * intended to be replayable as-is during a restore, and generating DROP
     * statements for pre-existing roles would make it destructive if applied
     * carelessly.
     */
    private SafeProcessRunner.Result runPgDumpAll(Path executable,
                                                  PostgreSqlBackupTarget target,
                                                  Map<String, String> environment,
                                                  Path outputFile)
            throws IOException, BackupFailure {

        SafeProcessRunner.Command command = new SafeProcessRunner.Command(
                executable.toString())
                .arg("--globals-only")
                .arg("--host=" + target.host())
                .arg("--port=" + target.port())
                .arg("--username=" + target.username())
                .arg("--no-password")
                .arg("--file=" + outputFile.toAbsolutePath())
                .timeout(defaultTimeout);

        if (target.hasPassword()) {
            command.environment("PGPASSWORD", new String(target.password()));
        }
        command.environment("PGSSLMODE", target.sslMode());

        log.debug("Executing pg_dumpall with argument vector {}", command.argumentVector());
        return SafeProcessRunner.run(command);
    }

    // ------------------------------------------------------------------
    // failure semantics
    // ------------------------------------------------------------------

    /** Internal signal that an attempt must be abandoned and cleaned up. */
    private static final class BackupFailure extends Exception {
        BackupFailure(String message) {
            super(message);
        }
    }

    /**
     * Fails on any non-zero exit, timeout, or unusable stderr.
     *
     * <p>stderr is not treated as fatal on its own — {@code pg_dump} emits
     * benign NOTICE lines — but it is logged so an operator can see why a
     * borderline run behaved as it did.
     */
    private void requireSuccess(SafeProcessRunner.Result run, String tool,
                                Path expectedOutput, String backupSetId)
            throws BackupFailure {

        if (run.timedOut()) {
            throw new BackupFailure(tool + " exceeded the " + defaultTimeout.toMinutes()
                    + "-minute bound and was terminated; no output was accepted");
        }
        if (!run.succeeded()) {
            throw new BackupFailure(tool + " exited with code " + run.exitCode()
                    + ": " + summarise(run.stderr()));
        }
        if (!run.stderr().isBlank()) {
            log.warn("{} reported diagnostics for backup set {}: {}",
                    tool, backupSetId, summarise(run.stderr()));
        }
    }

    /**
     * Rejects a missing or zero-byte artefact.
     *
     * <p>A zero-byte dump is the signature of a process that exited 0 without
     * writing — for example after writing to an unwritable path. Accepting it
     * would produce a set that passes every later existence check and restores
     * to nothing.
     */
    private void requireNonEmptyArtifact(Path artefact, String label, String backupSetId)
            throws BackupFailure {
        if (!Files.isRegularFile(artefact)) {
            throw new BackupFailure(label + " was not created for backup set "
                    + backupSetId);
        }
        long size;
        try {
            size = Files.size(artefact);
        } catch (IOException e) {
            throw new BackupFailure(label + " could not be measured for backup set "
                    + backupSetId);
        }
        if (size == 0) {
            throw new BackupFailure(label + " is empty (0 bytes) for backup set "
                    + backupSetId + "; an empty dump is not a backup");
        }
    }

    /**
     * Removes artefacts written by the failed attempt.
     *
     * <p>Scoped strictly to this attempt's own files. There is no recursive
     * delete anywhere in this class: a broad delete here could destroy every
     * other recovery set on the host, which is the single worst failure mode a
     * backup tool can have. A partially written dump is removed rather than kept,
     * because a truncated file looks restorable and is not.
     */
    private void quarantinePartial(Path setDirectory, Path databaseDump, Path globalsDump,
                                   boolean databaseWritten, boolean globalsWritten) {
        deleteIfPresent(databaseDump);
        deleteIfPresent(globalsDump);
        try {
            // Remove the now-empty set directory, but only if it is empty.
            try (var entries = Files.list(setDirectory)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(setDirectory);
                    return;
                }
            }
            log.warn("Backup set directory {} retained: it contains files this "
                    + "attempt did not create", setDirectory);
        } catch (IOException e) {
            log.warn("Could not tidy backup set directory {}: {}",
                    setDirectory, e.getMessage());
        }
    }

    private void deleteIfPresent(Path path) {
        try {
            if (Files.exists(path)) {
                Files.deleteIfExists(path);
                log.warn("Removed incomplete backup artefact {}", path.getFileName());
            }
        } catch (IOException e) {
            log.error("Could not remove incomplete artefact {}: {}", path, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // integrity + version
    // ------------------------------------------------------------------

    /**
     * Streams the file through SHA-256.
     *
     * <p>Streaming rather than {@code Files.readAllBytes}: a backup may be
     * gigabytes, and loading one into heap to hash it would be a second outage
     * waiting to happen.
     */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable in this JVM", e);
        }
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestStream = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (digestStream.read(buffer) != -1) {
                // reading is the hashing
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Probes the {@code pg_dump --version} string.
     *
     * <p>Recorded because {@code docs/BACKUP-RECOVERY.md} §2.3 notes that
     * version identity matters when restoring. For a custom-format logical dump
     * the client version is informational, so a failure to read it is reported
     * as {@code null} rather than guessed — an invented version string in a
     * recovery record is worse than an acknowledged gap.
     */
    private String probeClientVersion(Path pgDump, Map<String, String> environment) {
        try {
            SafeProcessRunner.Result result = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(pgDump.toString())
                            .arg("--version")
                            .timeout(Duration.ofSeconds(30))
                            .environment(Map.of()));
            if (result.succeeded() && !result.stdout().isBlank()) {
                return result.stdout().trim();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not probe pg_dump version: {}", e.getMessage());
        }
        return null;
    }

    /** Trims captured stderr for an operator-facing message. */
    private static String summarise(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "(no diagnostics)";
        }
        String trimmed = stderr.strip();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) + "…" : trimmed;
    }
}