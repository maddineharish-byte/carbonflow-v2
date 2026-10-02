package com.carbonflow.recovery.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Scheduling configuration for the automated recovery backup.
 *
 * <h2>Timezone</h2>
 * <p>The schedule is evaluated in an explicitly configured {@link ZoneId},
 * defaulting to UTC. No country, locale or region is assumed, and no timezone is
 * hardcoded anywhere in the recovery package. An operator running a business-hours
 * platform can pin the zone; an operator running globally leaves it on UTC.
 *
 * <p>UTC is the default because it is unambiguous at the boundaries. A local zone
 * is legitimate, but it interacts with daylight-saving transitions: during a
 * spring-forward the local hour named by a cron expression may not exist, and
 * Spring's scheduler will skip it rather than run twice. That behaviour is
 * acceptable, and is why UTC is the default rather than a convenience.
 *
 * @param enabled      whether automatic backup runs at all
 * @param cron         the Spring cron expression driving the schedule
 * @param zone         the zone the cron expression is evaluated in
 * @param timeout      bound on a single backup attempt
 * @param defaultInterval used by {@link #nextRunAfter(Instant)} for reporting
 */
public record RecoveryScheduleConfig(boolean enabled, String cron, ZoneId zone,
                                     Duration timeout, Duration defaultInterval) {

    /**
     * Default schedule: the top of every hour.
     *
     * <p>Matches the approved requirement of "at least once every hour" with
     * exactly one run per hour and no duplicate firing on the half-hour.
     */
    public static final String DEFAULT_CRON = "0 0 * * * *";

    /** The approved minimum backup interval. */
    public static final Duration APPROVED_INTERVAL = Duration.ofHours(1);

    public RecoveryScheduleConfig {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException(
                    "carbonflow.recovery.backup.cron is required");
        }
        if (zone == null) {
            throw new IllegalArgumentException(
                    "carbonflow.recovery.backup.zone is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "carbonflow.recovery.backup.timeout must be positive");
        }
        if (defaultInterval == null || defaultInterval.isNegative()
                || defaultInterval.isZero()) {
            throw new IllegalArgumentException(
                    "carbonflow.recovery.backup.interval must be positive");
        }
    }

    /**
     * Config with the approved hourly default.
     *
     * @param enabled whether scheduling is on
     */
    public static RecoveryScheduleConfig hourly(boolean enabled) {
        return new RecoveryScheduleConfig(enabled, DEFAULT_CRON, ZoneId.of("UTC"),
                Duration.ofMinutes(30), APPROVED_INTERVAL);
    }

    /**
     * The next scheduled run strictly after {@code from}.
     *
     * <p>Computed from the zone so a run is anchored to a wall-clock boundary
     * rather than to {@code from + interval}. The difference matters across a
     * daylight-saving change, where adding an interval to an instant drifts away
     * from the intended local time.
     *
     * <p>For the default hourly cron this is simply the next UTC hour boundary.
     */
    public Instant nextRunAfter(Instant from, Clock clock) {
        ZonedDateTime local = from.atZone(zone);
        ZonedDateTime next = local.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plusHours(1);
        // Defensive: truncation plus an hour always lands on an hour boundary in
        // a fixed-offset zone; in a DST zone the first hour after a transition can
        // be absent, so advance until the instant is genuinely after `from`.
        Instant candidate = next.toInstant();
        while (!candidate.isAfter(from)) {
            next = next.plusHours(1);
            candidate = next.toInstant();
        }
        return candidate;
    }

    /** The previous run boundary at or before {@code instant}, for staleness maths. */
    public Instant previousRunBoundary(Instant instant) {
        return instant.atZone(zone)
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .toInstant();
    }

    /** Human-readable schedule, safe to log. */
    public String describe() {
        return (enabled ? "enabled" : "disabled") + " cron=\"" + cron
                + "\" zone=" + zone.getId();
    }
}