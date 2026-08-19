package com.edunext.edutrack.api.feature.reports;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * A-065 · §7.8's three cadences — when a schedule fires, and what period the
 * report it sends is about.
 *
 * <h2>Two questions, and conflating them is the bug</h2>
 *
 * <p>"When does it run" and "what does it cover" are different, and the second
 * is the one that goes wrong quietly. A weekly report that fires on Monday and
 * covers <em>this</em> week covers one day — Monday — and reads as a
 * catastrophic drop in activity. Every cadence here therefore reports on the
 * <b>completed</b> period before the run: yesterday, last week, last month.
 * There is no partial period, ever, which is also what makes two consecutive
 * runs comparable.
 *
 * <h2>The period is derived, never stored</h2>
 *
 * <p>{@code report_schedules} keeps no date range. Storing one would freeze it,
 * and the second run would email a copy of the first — a failure that looks
 * exactly like a working schedule until somebody compares two files. The
 * viewer's other filters (project, resource, level) <em>are</em> stored,
 * because those are choices about which rows, not about when.
 *
 * <h2>The clock is the organisation's</h2>
 *
 * <p>Storage is UTC everywhere (CLAUDE.md) and "Monday" is not a UTC fact — a
 * schedule computed in UTC for a team in Asia/Kolkata sends its Monday report
 * on Sunday evening, covering a week that ends five and a half hours early.
 * Every method here takes the zone explicitly for that reason; none of them
 * reads the JVM default, which is whatever the container was started with.
 */
enum ReportCadence {

    DAILY,
    WEEKLY,
    MONTHLY;

    /** Case-insensitive, and empty rather than an exception for an unknown value. */
    static Optional<ReportCadence> of(String requested) {
        if (requested == null || requested.isBlank()) {
            return Optional.empty();
        }
        for (ReportCadence cadence : values()) {
            if (cadence.name().equalsIgnoreCase(requested.trim())) {
                return Optional.of(cadence);
            }
        }
        return Optional.empty();
    }

    /**
     * The completed period a run at {@code runDate} reports on.
     *
     * <p>Both ends inclusive, matching {@code ?from=}/{@code ?to=} on
     * {@code GET /reports/{reportKey}} — the schedule runs the identical report
     * the viewer does, and a window that meant something subtly different here
     * would make the emailed file disagree with the screen it was scheduled
     * from.
     */
    Period periodEnding(LocalDate runDate) {
        return switch (this) {
            // Yesterday. Not "today so far": a daily report sent at 06:00
            // covering today would be six hours of a day nobody has worked yet.
            case DAILY -> {
                LocalDate day = runDate.minusDays(1);
                yield new Period(day, day);
            }
            // The ISO week before this one, Monday to Sunday. previousOrSame
            // rather than previous, so a run on any day of the week reports the
            // last complete week rather than skipping one when the schedule is
            // created mid-week and fires immediately.
            case WEEKLY -> {
                LocalDate thisMonday = runDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                LocalDate lastMonday = thisMonday.minusWeeks(1);
                yield new Period(lastMonday, lastMonday.plusDays(6));
            }
            // The previous calendar month, whatever its length. lengthOfMonth
            // on the *first* of that month, so February and the 31sts both come
            // out right — a fixed 30 would silently drop a day of every long
            // month and invent one every February.
            case MONTHLY -> {
                LocalDate firstOfLastMonth = runDate.withDayOfMonth(1).minusMonths(1);
                yield new Period(firstOfLastMonth,
                        firstOfLastMonth.withDayOfMonth(firstOfLastMonth.lengthOfMonth()));
            }
        };
    }

    /**
     * The next firing strictly after {@code after}, at {@code sendHour} local.
     *
     * <p><b>Strictly after, which is what makes the sweep safe to re-run.</b>
     * The runner advances this column once a run finishes; if it computed a
     * time that could equal the one just used, a schedule would be immediately
     * due again and would send the same report in a loop until the hour passed.
     *
     * <p>Returned as an {@link java.time.Instant} through
     * {@link ZonedDateTime#toInstant()} by the caller, because the column is
     * {@code DATETIME(6)} in UTC and the local hour is only how a human
     * expresses it.
     */
    ZonedDateTime nextRunAfter(ZonedDateTime after, ZoneId zone, int sendHour) {
        ZonedDateTime local = after.withZoneSameInstant(zone);
        LocalTime at = LocalTime.of(sendHour, 0);

        ZonedDateTime candidate = switch (this) {
            case DAILY -> local.toLocalDate().atTime(at).atZone(zone);
            // Monday, so the week that just ended is the subject.
            case WEEKLY -> local.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .atTime(at).atZone(zone);
            // The 1st, for the same reason.
            case MONTHLY -> local.toLocalDate().withDayOfMonth(1).atTime(at).atZone(zone);
        };

        if (!candidate.isAfter(local)) {
            candidate = switch (this) {
                case DAILY -> candidate.plusDays(1);
                case WEEKLY -> candidate.plusWeeks(1);
                // plusMonths on the 1st is always the 1st — no clamping case,
                // unlike a schedule anchored to the 31st would have.
                case MONTHLY -> candidate.plusMonths(1);
            };
        }
        return candidate;
    }

    /** "daily", for a subject line a person reads. */
    String label() {
        return name().toLowerCase(Locale.ENGLISH);
    }

    /**
     * A completed reporting window, both ends inclusive.
     *
     * @param from first day covered
     * @param to   last day covered — the same day as {@code from} for DAILY
     */
    record Period(LocalDate from, LocalDate to) {
    }
}
