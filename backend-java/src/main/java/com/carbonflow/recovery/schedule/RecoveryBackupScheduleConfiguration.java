package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Spring wiring for the automated recovery backup (REC-13).
 *
 * <h2>Disabled by default</h2>
 * <p>{@code carbonflow.recovery.backup.enabled} defaults to {@code false}. A
 * scheduler that began taking backups of whatever database it happened to be
 * pointed at, on application startup, would be a surprise with real
 * consequences. Enabling it is a deliberate operator action and requires the
 * backup root to be configured explicitly.
 *
 * <h2>What this class does not do</h2>
 * <p>It decides when to trigger and assembles the inputs. Every backup, vault,
 * verification, monitoring and retention behaviour lives in the framework-free
 * classes it delegates to, so the same logic is exercised directly by tests
 * without a Spring context.
 *
 * <h2>Credentials</h2>
 * <p>No secret is read into this class.
 * {@link PostgreSqlBackupTarget#fromEnvironment} reads {@code DB_*} from the
 * process environment exactly as the rest of CarbonFlow does, and the password
 * reaches {@code pg_dump} only via {@code PGPASSWORD} in the child process
 * environment.
 */
@Component
@ConditionalOnProperty(name = "carbonflow.recovery.backup.enabled",
        havingValue = "true")
public class RecoveryBackupScheduleConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(RecoveryBackupScheduleConfiguration.class);

    private final RecoveryBackupScheduler scheduler;
    private final EvidenceReferenceReader evidenceReader;
    private final Path backupRoot;
    private final Path vaultRoot;
    private final String copiedWith;
    private final String applicationVersion;
    private final String gitCommit;
    private final List<String> schemaVersions;
    private final int schemaTableCount;

    public RecoveryBackupScheduleConfiguration(
            RecoveryBackupScheduler scheduler,
            JdbcTemplate jdbcTemplate,
            @Value("${carbonflow.recovery.backup.root}") String backupRoot,
            @Value("${carbonflow.evidence.vault-dir}") String vaultRoot,
            @Value("${carbonflow.recovery.backup.copied-with:robocopy}")
            String copiedWith,
            @Value("${carbonflow.recovery.application.version:1.0.0-PRO}")
            String applicationVersion,
            @Value("${carbonflow.recovery.application.git-commit:}") String gitCommit,
            @Value("${carbonflow.recovery.schema.versions:V1,V2,V3,V4,V5,V6,V7,V8}")
            String schemaVersions,
            @Value("${carbonflow.recovery.schema.table-count:38}")
            int schemaTableCount) {

        this.scheduler = scheduler;
        this.evidenceReader = new EvidenceReferenceReader(jdbcTemplate);
        this.backupRoot = Path.of(backupRoot);
        this.vaultRoot = Path.of(vaultRoot);
        this.copiedWith = copiedWith;
        this.applicationVersion = applicationVersion;
        this.gitCommit = gitCommit;
        this.schemaVersions = List.of(schemaVersions.split(","));
        this.schemaTableCount = schemaTableCount;

        log.info("Automated recovery backup is ENABLED: {}", scheduler.config().describe());
    }

    /**
     * The scheduled trigger.
     *
     * <p>Cron expression and zone come from configuration, so the schedule is
     * operator-controlled rather than compiled in.
     */
    @Scheduled(
            cron = "${carbonflow.recovery.backup.cron:0 0 * * * *}",
            zone = "${carbonflow.recovery.backup.zone:UTC}")
    public void scheduledBackup() {
        scheduler.runOnce(buildJob());
    }

    /** Builds the inputs for one cycle. */
    private RecoveryBackupScheduler.Job buildJob() {
        return new RecoveryBackupScheduler.Job(
                backupRoot,
                vaultRoot,
                PostgreSqlBackupTarget.fromEnvironment(System.getenv()),
                Map.copyOf(System.getenv()),
                evidenceReader.readRequiredFiles(),
                gitCommit == null || gitCommit.isBlank()
                        ? RecoveryManifest.Application.withoutGit(applicationVersion)
                        : RecoveryManifest.Application.of(applicationVersion, gitCommit),
                new RecoveryManifest.Schema(schemaVersions, Boolean.TRUE,
                        schemaTableCount),
                copiedWith);
    }

    /** Exposed so REC-14 can trigger an immediate out-of-band run. */
    public RecoveryBackupScheduler scheduler() {
        return scheduler;
    }
}