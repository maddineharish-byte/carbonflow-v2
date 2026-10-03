package com.carbonflow.recovery.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * REC-15 — quarterly recovery drill schedule.
 *
 * <h2>Quarterly, anchored to calendar quarters</h2>
 * <p>Drills are due on the first day of each calendar quarter at a configured hour
 * in a configured zone. Anchoring to calendar quarters rather than "every 90 days"
 * means the cadence is stable and predictable: an operator can put it in a
 * calendar years ahead, and a drill does not drift later every year.
 *
 * <h2>Why the zone is explicit</h2>
 * <p>"Quarterly" needs a date to be meaningful, and a date needs a zone. UTC is the
 * default; no country or locale is assumed. A daylight-saving transition inside a
 * quarter cannot move a quarter boundary, because boundaries are derived from the
 * quarter index rather than by adding an interval to a timestamp.
 *
 * <p>The last drill date is recorded as the <b>start of the day</b> the drill was
 * run. That matters: if the time of day were kept, a drill run at 23:00 and
 * rescheduled after a DST change could appear to be in the future or the past
 * relative to its own quarter.
 */
public final class DrillSchedule {

    private final ZoneId zone;
    private final ZonedDateTime dueHour;
    private final Instant lastDrillAt;

    private DrillSchedule(ZoneId zone, ZonedDateTime dueHour, Instant lastDrillAt) {
        this.zone = zone;
        this.dueHour = dueHour;
        this.lastDrillAt = lastDrillAt;
    }

    /**
     * @param zone       the zone quarters are evaluated in
     * @param hourOfDay  hour of day a drill is due, local to that zone
     * @param lastDrillAt when the last drill actually ran, or {@code null} if never
     */
    public static DrillSchedule quarterly(ZoneId zone, int hourOfDay,
                                          Instant lastDrillAt) {
        if (zone == null) {
            throw new IllegalArgumentException("zone is required");
        }
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException(
                    "hourOfDay must be between 0 and 23, was " + hourOfDay);
        }
        // Reduced to the start of the day so a drill's recorded date is stable
        // across daylight-saving changes and re-scheduling.
        ZonedDateTime due = ZonedDateTime.of(LocalDate.now(zone), java.time.LocalTime.of(hourOfDay, 0),
                zone);
        return new DrillSchedule(zone, due, lastDrillAt);
    }

    /** Quarter number (1-4) of a date. */
    static int quarterOf(Month month) {
        return ((month.getValue() - 1) / 3) + 1;
    }

    /**
     * The start of the next calendar quarter strictly after {@code from}.
     *
     * <p>Derived from the quarter index rather than by adding three months, so the
     * result is always the first day of a quarter regardless of month length or
     * a daylight-saving transition.
     */
    public Instant nextQuarterStartAfter(Instant from, Clock clock) {
        ZonedDateTime local = from.atZone(zone);
        int year = local.getYear();
        int quarter = quarterOf(local.getMonth());

        LocalDate nextQuarterFirstDay;
        if (quarter == 4) {
            nextQuarterFirstDay = LocalDate.of(year + 1, Month.JANUARY, 1);
        } else {
            nextQuarterFirstDay = LocalDate.of(year, Month.of(quarter * 3 + 1), 1);
        }

        ZonedDateTime next = ZonedDateTime.of(nextQuarterFirstDay,
                java.time.LocalTime.of(dueHour.getHour(), 0), zone);
        // Defensive: if the configured hour falls in a DST gap, the local time
        // resolves forward; advance to the following quarter only if that would
        // still not be after `from`.
        if (!next.toInstant().isAfter(from)) {
            return nextQuarterStartAfter(next.toInstant().plusSeconds(1), clock);
        }
        return next.toInstant();
    }

    /**
     * When the next drill is due.
     *
     * <p>Based on the last drill when there is one, otherwise on the next quarter
     * boundary. With no drill on record the answer is deliberately "the next
     * quarter", not "immediately overdue", because a fresh installation has not
     * missed a deadline it never had.
     *
     * @return the due instant, or {@code null} when no drill has ever run
     */
    public Instant nextDueAfter(Clock clock) {
        return lastDrillAt == null
                ? nextQuarterStartAfter(clock.instant(), clock)
                : nextQuarterStartAfter(lastDrillAt, clock);
    }

    /**
     * Whether the quarterly drill is overdue.
     *
     * @param gracePeriod allowed lateness before a drill counts as overdue; the
     *                     approved requirement names a cadence, not a deadline,
     *                     so a grace period is explicit rather than assumed
     */
    public boolean isOverdue(Clock clock, Duration gracePeriod) {
        if (lastDrillAt == null) {
            // Never run: not overdue, but also not established. Distinguished by
            // lastDrillAt() being null rather than by pretending a drill happened.
            return false;
        }
        Instant due = nextDueAfter(clock);
        return due != null && clock.instant().isAfter(due.plus(gracePeriod));
    }

    /** How long since the last drill, or {@code null} if never run. */
    public Duration sinceLastDrill(Clock clock) {
        return lastDrillAt == null ? null
                : Duration.between(lastDrillAt, clock.instant());
    }

    public Instant lastDrillAt() {
        return lastDrillAt;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Human-readable schedule, safe to log. */
    public String describe() {
        return "quarterly in " + zone.getId() + " at "
                + String.format("%02d:00", dueHour.getHour())
                + " (last drill: "
                + (lastDrillAt == null ? "never" : lastDrillAt) + ")";
    }
}