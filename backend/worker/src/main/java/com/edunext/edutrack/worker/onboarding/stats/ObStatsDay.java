package com.edunext.edutrack.worker.onboarding.stats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * B-120 · one day of the dashboard, resolved into the instants the SQL compares
 * against.
 *
 * <h2>Why the boundaries are computed here and not in the statements</h2>
 *
 * <p>Storage is UTC everywhere (CLAUDE.md), and "due today" is a question about
 * the organisation's day. In India that is five and a half hours away from
 * UTC's, so a step due at 03:00 IST is due <em>yesterday</em> as far as
 * {@code DATE(due_at)} is concerned — the Today's Delivery card would be wrong
 * every morning, and wrong by a plausible-looking amount.
 *
 * <p>The obvious fix in SQL is {@code CONVERT_TZ}, which needs the named
 * timezone tables loaded into MySQL. They are not loaded in Testcontainers and
 * are not guaranteed in a deployment, and {@code CONVERT_TZ} returns NULL
 * rather than failing when they are missing — so every date comparison would
 * quietly become false and every card would read zero. Resolving the boundaries
 * in Java against {@link ZoneId} removes the dependency altogether and makes
 * the comparison a plain {@code >= … AND < …} over an indexed column.
 *
 * <p>{@code zoneOffsetSeconds} is the one thing SQL still needs a zone for:
 * classifying a completion as early, on time or late is a comparison of two
 * <em>calendar days</em> (see {@link ObDashboardStatsRepository}), and adding a
 * fixed offset before {@code DATE()} is the same trick without the tables. It
 * is the offset in force at the start of this day, so a day containing a DST
 * transition is classified against the offset it began in — accepted rather
 * than solved, and stated here because the organisation calendar's default zone
 * has no DST at all.
 *
 * @param date        the day, in the calendar's zone. This is {@code stat_date}.
 * @param start       first instant of the day
 * @param end         first instant of the following day, exclusive
 * @param weekStart   first instant of the Monday of {@code date}'s week
 * @param weekEnd     first instant of the following Monday, exclusive
 * @param zoneOffsetSeconds the zone's offset from UTC at {@code start}
 */
public record ObStatsDay(
        LocalDate date,
        Instant start,
        Instant end,
        Instant weekStart,
        Instant weekEnd,
        int zoneOffsetSeconds) {

    /**
     * The week runs Monday to Sunday, matching {@code WorkingCalendar}'s own
     * weekly-off pattern — "This Week's Deadlines" and "which days are working
     * days" have to agree about where the week begins, or a Sunday deadline
     * lands in a week the calendar says has already ended.
     */
    public static ObStatsDay of(LocalDate date, ZoneId zone) {
        Instant start = date.atStartOfDay(zone).toInstant();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new ObStatsDay(
                date,
                start,
                date.plusDays(1).atStartOfDay(zone).toInstant(),
                monday.atStartOfDay(zone).toInstant(),
                monday.plusWeeks(1).atStartOfDay(zone).toInstant(),
                zone.getRules().getOffset(start).getTotalSeconds());
    }
}
