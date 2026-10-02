package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoveryManifestWriter;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
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
                        : notification -> notifier.notify(notification));
    }
}