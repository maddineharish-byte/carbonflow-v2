package com.carbonflow.recovery.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The notification provider CarbonFlow actually ships: a structured log writer.
 *
 * <h2>Why this and not an email integration</h2>
 * <p>CarbonFlow has no mail server, no SMS gateway and no pager integration, and
 * Phase 10.8 does not invent one. A provider-neutral
 * {@link RecoveryNotificationService.Provider} contract is defined so an email or
 * pager provider can be added later without touching the notification logic, and
 * this implementation makes the notification genuinely <em>visible</em> today: it
 * is written at {@code ERROR} or {@code WARN} in a structured, greppable form in
 * the application log, where an operator or an existing log shipper will find it.
 *
 * <h2>What this is not</h2>
 * <p>{@link #deliversOutOfBand()} returns {@code false}. A log line is a real
 * channel but it does not <b>reach</b> anyone: nobody is emailed, messaged or
 * paged. That distinction is recorded rather than glossed, because a recovery
 * programme that believes it is being paged when it is not is worse than one that
 * knows it is only logging.
 *
 * <p>Severity maps to level, so an operator filtering on {@code ERROR} sees only
 * genuine failures.
 */
public final class LoggingNotificationProvider
        implements RecoveryNotificationService.Provider {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingNotificationProvider.class);

    private final java.util.List<RecoveryNotification> recorded =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Override
    public void deliver(RecoveryNotification notification) {
        recorded.add(notification);
        String rendered = notification.toMessage();
        switch (notification.severity()) {
            case CRITICAL -> log.error(rendered);
            case WARNING -> log.warn(rendered);
            case INFO -> log.info(rendered);
        }
    }

    @Override
    public String name() {
        return "structured-log";
    }

    /**
     * {@code false}: a log line does not notify a human.
     *
     * <p>This is the honest answer and it is what keeps any status report from
     * claiming delivery.
     */
    @Override
    public boolean deliversOutOfBand() {
        return false;
    }

    /** Notifications this provider has recorded, for inspection in tests. */
    public java.util.List<RecoveryNotification> recorded() {
        return java.util.List.copyOf(recorded);
    }
}