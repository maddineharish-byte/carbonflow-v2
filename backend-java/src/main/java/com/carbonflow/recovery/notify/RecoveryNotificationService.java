package com.carbonflow.recovery.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REC-14 — turns recovery conditions into operator-visible notifications.
 *
 * <h2>What this is, honestly</h2>
 * <p>CarbonFlow has no email, SMS or pager integration, and none is invented
 * here. This service <b>detects</b> a condition, decides whether it is worth
 * telling someone about, and <b>hands it to a provider</b>.
 *
 * <p>The provider actually shipped is a structured log writer, so a notification
 * lands in the application log at {@code WARN} or {@code ERROR} where an operator
 * or a log shipper can find it. That is a real channel, but it is not delivery to
 * a human: nobody is emailed or paged. {@link #hasOutOfBandDelivery()} reports
 * {@code false} so no report can imply otherwise.
 *
 * <h2>Deduplication</h2>
 * <p>An unresolved condition repeats as long as it persists — every health
 * assessment would otherwise re-raise it. The first occurrence is delivered; a
 * repeat within {@link #repeatInterval()} is suppressed and counted; after the
 * interval it is delivered again so a long outage is not forgotten.
 *
 * <p>The key is control plus event, <b>not</b> the subject, so one broken backup
 * is one condition rather than a new alert per assessment. A condition that
 * <em>resolves</em> is forgotten, so the next occurrence alerts again rather than
 * being suppressed for ever by an old timestamp.
 *
 * <h2>Provider failure</h2>
 * <p>A provider that throws is caught, counted and logged. A notification channel
 * being broken must never take the backup scheduler down with it, and must never
 * make a backup look failed.
 */
public final class RecoveryNotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(RecoveryNotificationService.class);

    private final List<Provider> providers;
    private final Clock clock;
    private final Duration repeatInterval;
    private final Map<String, Instant> lastDelivered = new ConcurrentHashMap<>();
    private final Map<String, Integer> suppressedCounts = new ConcurrentHashMap<>();
    private final Deque<RecoveryNotification> delivered =
            new ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicLong providerFailures =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * A delivery channel.
     *
     * <p>Provider-neutral by design: a log writer ships now, and an email or pager
     * provider can be added later without changing this service.
     */
    public interface Provider {
        /** Delivers the notification. May throw; failures are contained here. */
        void deliver(RecoveryNotification notification);

        /** A short name, for logging and for the health report. */
        String name();

        /**
         * Whether this provider reaches a human outside the application log.
         *
         * <p>The shipped log provider returns {@code false}, which is what keeps
         * this codebase from ever claiming a notification was delivered to a
         * person. An email or pager provider would return {@code true}.
         */
        default boolean deliversOutOfBand() {
            return false;
        }
    }

    /** Counters describing what the service did. */
    public record Stats(long delivered, long suppressed, long providerFailures,
                        boolean outOfBandDelivery) {
    }

    public RecoveryNotificationService(List<Provider> providers, Clock clock,
                                       Duration repeatInterval) {
        this.providers = List.copyOf(providers);
        this.clock = clock;
        this.repeatInterval = repeatInterval == null || repeatInterval.isNegative()
                ? RecoveryNotification.defaultRepeatInterval() : repeatInterval;
    }

    /**
     * Raises a notification, applying deduplication.
     *
     * @return {@code true} if it was delivered, {@code false} if suppressed
     */
    public boolean notify(RecoveryNotification notification) {
        String key = notification.dedupKey();
        Instant now = clock.instant();

        Instant previous = lastDelivered.get(key);
        if (previous != null && now.isBefore(previous.plus(repeatInterval))) {
            suppressedCounts.merge(key, 1, Integer::sum);
            log.debug("Suppressed repeat recovery notification for {} (still within "
                    + "the {} repeat window)", key, repeatInterval);
            return false;
        }

        boolean anyDelivered = false;
        for (Provider provider : providers) {
            try {
                provider.deliver(notification);
                anyDelivered = true;
            } catch (RuntimeException e) {
                // A broken channel must not propagate: the caller is usually the
                // backup scheduler, and a notification failure there must not
                // abort or misreport a backup.
                providerFailures.incrementAndGet();
                log.error("Recovery notification provider '{}' failed: {}",
                        provider.name(), e.getMessage());
            }
        }

        lastDelivered.put(key, now);
        if (anyDelivered) {
            synchronized (delivered) {
                delivered.addLast(notification);
                // Bound the history so a long-running process cannot grow it
                // without limit.
                while (delivered.size() > 200) {
                    delivered.removeFirst();
                }
            }
            log.warn("Recovery notification: {}", notification.toMessage());
        }
        return anyDelivered;
    }

    /**
     * Forgets a condition because it has resolved.
     *
     * <p>Called when health returns to normal, so a recurrence alerts again
     * instead of being suppressed by a stale timestamp from an earlier incident.
     */
    public void clear(RecoveryNotification.Control control,
                      RecoveryNotification.Event event) {
        String key = control.name() + "/" + event.name();
        if (lastDelivered.remove(key) != null) {
            log.info("Recovery condition resolved; alerting re-armed for {}", key);
        }
    }

    /**
     * Raises notifications for whatever the monitor currently reports.
     *
     * <p>The mapping from health state to event is explicit, so an operator can see
     * exactly which condition produces which alert, and a state with no
     * notification (HEALTHY, RUNNING) produces none at all.
     *
     * @return the notifications that were delivered
     */
    public List<RecoveryNotification> notifyForHealth(
            com.carbonflow.recovery.monitor.BackupHealth.Status status) {

        List<RecoveryNotification> sent = new java.util.ArrayList<>();
        RecoveryNotification.Control control =
                RecoveryNotification.Control.RECOVERY_MONITORING;

        switch (status.health()) {
            case HEALTHY, RUNNING -> {
                // Nothing wrong: clear anything previously raised for these
                // conditions so a recurrence alerts again.
                clear(control, RecoveryNotification.Event.BACKUP_FAILED);
                clear(control, RecoveryNotification.Event.BACKUP_STALE);
                clear(control, RecoveryNotification.Event.RPO_AT_RISK);
                clear(control, RecoveryNotification.Event.BACKUP_MISSING);
                return sent;
            }
            case STALE -> {
                boolean missing = status.backupSetId() == null;
                RecoveryNotification.Event event = missing
                        ? RecoveryNotification.Event.BACKUP_MISSING
                        : RecoveryNotification.Event.BACKUP_STALE;
                RecoveryNotification notification = new RecoveryNotification(
                        RecoveryNotification.Severity.CRITICAL, control, event,
                        status.reason(), status.backupSetId(), status.evaluatedAt(),
                        RecoveryNotification.token(status.health().name()),
                        "Run a backup immediately, then confirm the scheduler is "
                                + "still triggering.");
                if (notify(notification)) {
                    sent.add(notification);
                }
            }
            case RPO_AT_RISK -> {
                RecoveryNotification notification = new RecoveryNotification(
                        RecoveryNotification.Severity.WARNING, control,
                        RecoveryNotification.Event.RPO_AT_RISK,
                        "Newest backup is " + (status.age() == null ? "unknown"
                                : status.age().toMinutes() + " minutes")
                                + " old; the approved RPO window is 60 minutes.",
                        status.backupSetId(), status.evaluatedAt(),
                        RecoveryNotification.token(status.health().name()),
                        "Investigate why the last backup is late. The RPO can still "
                                + "be met, but there is little margin.");
                if (notify(notification)) {
                    sent.add(notification);
                }
            }
            case FAILED -> {
                RecoveryNotification notification = new RecoveryNotification(
                        RecoveryNotification.Severity.CRITICAL, control,
                        RecoveryNotification.Event.BACKUP_FAILED,
                        status.reason(), status.backupSetId(), status.evaluatedAt(),
                        RecoveryNotification.token(status.health().name()),
                        "Inspect the failed backup's logs, correct the cause, then "
                                + "rerun the backup and verify the new set.");
                if (notify(notification)) {
                    sent.add(notification);
                }
            }
            case UNVERIFIABLE -> {
                RecoveryNotification notification = new RecoveryNotification(
                        RecoveryNotification.Severity.CRITICAL, control,
                        RecoveryNotification.Event.BACKUP_UNVERIFIABLE,
                        status.reason(), status.backupSetId(), status.evaluatedAt(),
                        RecoveryNotification.token(status.health().name()),
                        "Do NOT restore from this set. Determine whether a check "
                                + "could not run, or whether the artefacts are damaged.");
                if (notify(notification)) {
                    sent.add(notification);
                }
            }
        }
        return sent;
    }

    /** Whether any provider delivers outside the application log. */
    public boolean hasOutOfBandDelivery() {
        return providers.stream().anyMatch(Provider::deliversOutOfBand);
    }

    /** Recent delivered notifications, newest last. */
    public List<RecoveryNotification> recentDeliveries() {
        synchronized (delivered) {
            return List.copyOf(delivered);
        }
    }

    public Stats stats() {
        long suppressed = suppressedCounts.values().stream()
                .mapToLong(Integer::longValue).sum();
        synchronized (delivered) {
            return new Stats(delivered.size(), suppressed, providerFailures.get(),
                    hasOutOfBandDelivery());
        }
    }

    /** Names of the configured providers. */
    public List<String> providerNames() {
        return providers.stream().map(Provider::name).toList();
    }
}