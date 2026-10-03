package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.monitor.BackupMonitor;
import com.carbonflow.recovery.notify.LoggingNotificationProvider;
import com.carbonflow.recovery.notify.RecoveryNotification;
import com.carbonflow.recovery.notify.RecoveryNotificationService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupService;
import com.carbonflow.recovery.retention.RetentionService;
import com.carbonflow.recovery.vault.EvidenceVaultBackupService;
import com.carbonflow.recovery.verify.BackupVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Wires the REC-13 scheduler and its collaborating controls.
 *
 * <p>Every bean here is conditional on {@code carbonflow.recovery.backup.enabled}.
 * When that is {@code false} — the default — none of this graph exists, so the
 * application starts and serves traffic with recovery automation entirely absent
 * and the request path carries no dependency on it.
 *
 * <p>Configuration is validated eagerly at startup. A misconfigured recovery
 * schedule fails fast rather than silently running on a default, because a backup
 * that quietly writes somewhere unintended is worse than a backup that refuses to
 * start.
 */
@Configuration
@ConditionalOnProperty(name = "carbonflow.recovery.backup.enabled",
        havingValue = "true")
public class RecoverySchedulerConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(RecoverySchedulerConfiguration.class);

    @Bean
    public RecoveryScheduleConfig recoveryScheduleConfig(
            @Value("${carbonflow.recovery.backup.cron:0 0 * * * *}") String cron,
            @Value("${carbonflow.recovery.backup.zone:UTC}") String zone,
            @Value("${carbonflow.recovery.backup.timeout:PT30M}")
            Duration timeout,
            @Value("${carbonflow.recovery.backup.interval:PT1H}")
            Duration interval) {

        RecoveryScheduleConfig config;
        try {
            // Validated at startup: an unknown zone must fail here, not silently
            // fall back to UTC where a DST or zone-id change would quietly move
            // every backup.
            config = new RecoveryScheduleConfig(true, cron, ZoneId.of(zone), timeout,
                    interval);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid recovery backup schedule configuration: " + e.getMessage(), e);
        }
        log.info("Recovery backup schedule configured: {}", config.describe());
        return config;
    }

    @Bean
    public PostgreSqlBackupService recoveryPostgresBackupService() {
        return PostgreSqlBackupService.productionDefaults();
    }

    @Bean
    public EvidenceVaultBackupService recoveryVaultBackupService() {
        return EvidenceVaultBackupService.productionDefaults();
    }

    @Bean
    public RecoveryManifestWriter recoveryManifestWriter() {
        return new RecoveryManifestWriter(Clock.systemUTC());
    }

    @Bean
    public RecoverySetCoordinator recoverySetCoordinator(
            PostgreSqlBackupService databaseBackup,
            EvidenceVaultBackupService vaultBackup,
            RecoveryManifestWriter manifestWriter) {
        return new RecoverySetCoordinator(databaseBackup, vaultBackup, manifestWriter,
                Clock.systemUTC());
    }

    @Bean
    public BackupVerifier recoveryBackupVerifier(RecoveryManifestWriter writer) {
        // Tool discovery reads the process environment at verification time so a
        // freshly installed client is picked up without a rebuild.
        return new BackupVerifier(writer, Map.copyOf(System.getenv()));
    }

    @Bean
    public BackupMonitor recoveryBackupMonitor(
            @Value("${carbonflow.recovery.backup.root}") String backupRoot,
            RecoveryManifestWriter writer) {
        return new BackupMonitor(Path.of(backupRoot), Clock.systemUTC(),
                new BackupVerifier(writer, Map.copyOf(System.getenv())),
                Map.copyOf(System.getenv()));
    }

    @Bean
    public RetentionService recoveryRetentionService() {
        return new RetentionService(Clock.systemUTC(),
                RetentionService.approvedRetention());
    }

    @Bean
    public RecoveryNotificationService recoveryNotificationService() {
        // Provider-neutral. The shipped provider writes a structured notification
        // to the application log; it does NOT reach a human, and
        // hasOutOfBandDelivery() reports false so no status can imply otherwise.
        return new RecoveryNotificationService(
                List.of(new LoggingNotificationProvider()), Clock.systemUTC(),
                RecoveryNotification.defaultRepeatInterval());
    }

    @Bean
    public DrillSchedule recoveryDrillSchedule(
            @Value("${carbonflow.recovery.drill.zone:UTC}") String zone,
            @Value("${carbonflow.recovery.drill.hour:2}") int hour,
            @Value("${carbonflow.recovery.drill.last-run:}") String lastRun) {
        // last-run is empty on a fresh installation, meaning no drill has ever
        // been performed. That is recorded as "never" rather than defaulted to a
        // fabricated date, which would make a drill appear to have happened.
        Instant lastDrillAt = lastRun == null || lastRun.isBlank()
                ? null : Instant.parse(lastRun);
        try {
            return DrillSchedule.quarterly(ZoneId.of(zone), hour, lastDrillAt);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid recovery drill schedule configuration: " + e.getMessage(), e);
        }
    }

    @Bean
    public RecoveryDrillScheduler recoveryDrillScheduler(
            RecoverySetCoordinator coordinator,
            BackupVerifier verifier,
            RecoveryManifestWriter manifestWriter,
            RecoveryDrillSchedulerConfig config,
            DrillSchedule drillSchedule,
            ObjectProvider<RecoveryNotificationService> notifications,
            @Value("${carbonflow.recovery.drill.enabled:false}") boolean enabled,
            @Value("${carbonflow.recovery.drill.recovery-database:}")
            String recoveryDatabase,
            @Value("${carbonflow.recovery.drill.target-host:}") String targetHost,
            @Value("${carbonflow.recovery.drill.target-port:5432}") int targetPort,
            @Value("${carbonflow.recovery.drill.target-user:}") String targetUser,
            @Value("${carbonflow.recovery.drill.target-password:}")
            String targetPassword,
            @Value("${carbonflow.recovery.drill.target-database:postgres}")
            String targetDatabase,
            @Value("${carbonflow.recovery.drill.grace-days:14}") int graceDays,
            @Value("${carbonflow.recovery.backup.root}") String backupRoot,
            @Value("${carbonflow.evidence.vault-dir}") String vaultRoot) {

        RecoveryNotificationService notifier = notifications.getIfAvailable();

        RecoveryDrill drill = new RecoveryDrill(Clock.systemUTC(),
                Map.copyOf(System.getenv()));

        RecoveryDrillScheduler.DrillPerformer performer =
                (setDirectory, job) -> drill.run(setDirectory,
                        job.recoveryTarget(), job.recoveryDatabase(),
                        job.vaultRestoreDirectory(), job.sourceVaultRoot());

        // The live database name is passed in so the safety gate can refuse it.
        // The drill environment is only constructed when an operator has actually
        // configured one; otherwise every precondition fails and the drill is
        // refused with DRILL_NOT_EXECUTABLE rather than guessing a target.
        RecoveryDrillScheduler.DrillJob job = targetHost == null
                || targetHost.isBlank()
                ? new RecoveryDrillScheduler.DrillJob(Path.of(backupRoot), Path.of(vaultRoot),
                null, recoveryDatabase, null, Map.of())
                : new RecoveryDrillScheduler.DrillJob(Path.of(backupRoot), Path.of(vaultRoot),
                new com.carbonflow.recovery.postgres.PostgreSqlBackupTarget(
                        targetHost, targetPort, targetDatabase, targetUser,
                        targetPassword.toCharArray(), "prefer"),
                recoveryDatabase,
                recoveryDatabase == null || recoveryDatabase.isBlank() ? null
                        : Path.of(vaultRoot).resolve(".drill-restore").resolve(recoveryDatabase),
                Map.copyOf(System.getenv()));

        log.info("Recovery drill scheduler: enabled={} schedule={} grace={}d "
                        + "recoveryDatabase={}", enabled, drillSchedule.describe(),
                graceDays, recoveryDatabase == null || recoveryDatabase.isBlank()
                        ? "<not configured>" : recoveryDatabase);

        return new RecoveryDrillScheduler(performer, notifier, drillSchedule, enabled,
                config.liveDatabaseName(), Clock.systemUTC());
    }

    @Bean
    public RecoveryDrillSchedulerConfig recoveryDrillSchedulerConfig(
            @Value("${spring.datasource.url}") String datasourceUrl) {
        // The live database name is taken from the application's own JDBC URL so
        // the safety gate compares against the database the application actually
        // uses, rather than a separately configured value that could drift.
        return new RecoveryDrillSchedulerConfig(
                liveDatabaseNameFrom(datasourceUrl));
    }

    /**
     * Extracts the database name from a JDBC URL.
     *
     * <p>Falls back to {@code carbonflow} rather than an empty string, so the
     * safety gate always has something to refuse against. A missing name must not
     * become a name that matches nothing.
     */
    static String liveDatabaseNameFrom(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "carbonflow";
        }
        int lastSlash = jdbcUrl.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == jdbcUrl.length() - 1) {
            return "carbonflow";
        }
        String remainder = jdbcUrl.substring(lastSlash + 1);
        int query = remainder.indexOf('?');
        if (query >= 0) {
            remainder = remainder.substring(0, query);
        }
        return remainder.isBlank() ? "carbonflow" : remainder;
    }

    /** Live database name, used by the drill safety gate to refuse a live target. */
    public static final class RecoveryDrillSchedulerConfig {
        private final String liveDatabaseName;

        public RecoveryDrillSchedulerConfig(String liveDatabaseName) {
            this.liveDatabaseName = liveDatabaseName;
        }

        public String liveDatabaseName() {
            return liveDatabaseName;
        }
    }

    @Bean
    public RecoveryBackupScheduler recoveryBackupScheduler(
            RecoverySetCoordinator coordinator,
            BackupVerifier verifier,
            BackupMonitor monitor,
            RetentionService retention,
            RecoveryScheduleConfig config,
            ObjectProvider<RecoveryNotificationService> notifications) {
        RecoveryNotificationService notifier = notifications.getIfAvailable();
        if (notifier != null) {
            log.info("Recovery notifications enabled with provider(s) {} "
                            + "(out-of-band delivery to a human: {})",
                    notifier.providerNames(), notifier.hasOutOfBandDelivery());
        } else {
            log.warn("Recovery notifications are not configured; backup failures "
                    + "will be logged only");
        }

        // The real production classes are adapted to the scheduler's narrow
        // contracts. The coordinator is invoked with the no-op quiesce guard, which
        // is recorded honestly in the manifest: writes are not being blocked
        // during an automated backup.
        // Cross-host exclusion via a PostgreSQL advisory lock. The lease falls back
        // to "proceed on local guard only" if the database cannot be reached, which
        // is logged loudly rather than silently assumed, because refusing to back
        // up during a database outage would be worse.
        com.carbonflow.recovery.postgres.PostgreSqlBackupTarget leaseTarget =
                com.carbonflow.recovery.postgres.PostgreSqlBackupTarget
                        .fromEnvironment(System.getenv());
        log.info("Cross-host backup exclusion: PostgreSQL advisory lock on {}",
                leaseTarget.host() + ":" + leaseTarget.port());

        return new RecoveryBackupScheduler(
                request -> coordinator.run(request,
                        new com.carbonflow.recovery.coordination.QuiesceGuard.NoOp()),
                verifier::verify,
                new RecoveryCycleDependencies.HealthReporter() {
                    @Override
                    public void recordRunStarted() {
                        monitor.recordRunStarted();
                    }

                    @Override
                    public void recordRunFinished(boolean success, String backupSetId,
                                                  String reason) {
                        monitor.recordRunFinished(success, backupSetId, reason);
                    }

                    @Override
                    public com.carbonflow.recovery.monitor.BackupHealth.Status assess() {
                        return monitor.assess();
                    }
                },
                retention::apply,
                config,
                Clock.systemUTC(),
                notifier == null ? null
                        : notification -> notifier.notify(notification),
                () -> PostgreSqlBackupLease.tryAcquireOrProceed(leaseTarget.host(),
                        leaseTarget.port(), "postgres", leaseTarget.username(),
                        leaseTarget.password()));
    }
}