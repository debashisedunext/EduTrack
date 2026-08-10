package com.edunext.edutrack.domain.masters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The org's working week — B-023, the third part of S-14 alongside
 * {@link Holiday} and {@link ResourceLeave}.
 *
 * <p><b>Exactly one row exists</b>, pinned to id 1 by
 * {@code ck_working_calendar_singleton}. An org has one working week; two rows
 * would mean two answers to "when does the working day end" and every SLA
 * figure would depend on which the query happened to read. The id is not
 * meaningful and nothing should ever set it.
 *
 * <p>This is what makes B-024 possible. {@code addWorkingHours(start, n)} cannot
 * answer "add four working hours to Friday 18:00" without knowing the length of
 * a working day, and nothing stored it before this table.
 *
 * <h2>Days are ISO-8601, end to end</h2>
 *
 * <p>{@code weekly_off} holds ISO day numbers — 1=Mon … 7=Sun — which is
 * precisely {@link DayOfWeek#getValue()}. That is deliberate:
 * {@link #getWeeklyOff()} converts through {@link DayOfWeek#of(int)} and nothing
 * in between does arithmetic, because arithmetic on a day number is where the
 * off-by-one gets in.
 *
 * <p>The API contract described this field as "ISO" while constraining it to
 * 0–6 and mocking {@code [0, 6]} — JavaScript's {@code Date.getDay()}
 * convention wearing an ISO label. Read literally by a backend using
 * {@code DayOfWeek}, {@code 0} is not a day at all and Sunday silently becomes a
 * working day, which mis-states every SLA that spans a weekend. The database now
 * refuses to store it: {@code ck_working_calendar_weekly_off} validates the
 * array against a JSON Schema that rejects 0, rejects 8, rejects duplicates and
 * caps the length at six so a seven-day weekend cannot send
 * {@code addWorkingHours} hunting for a working day that does not exist.
 */
@Entity
@Table(name = "working_calendar")
public class WorkingCalendar {

    /** The singleton key. Fixed at 1 by a CHECK constraint; never assign it. */
    public static final byte SINGLETON_ID = 1;

    /** A working day may close at midnight, which is 1440 rather than 0. */
    static final int MINUTES_IN_DAY = 1440;

    @Id
    @Column(name = "id", nullable = false)
    private Byte id = SINGLETON_ID;

    /**
     * Raw ISO day numbers as stored. {@link #getWeeklyOff()} is the accessor to
     * prefer — a caller comparing {@code Integer}s is one autoboxing mistake
     * away from the bug this column's CHECK exists to prevent.
     *
     * <p>{@code List<Integer>} rather than {@code Set<DayOfWeek>} because
     * Hibernate serialises through Jackson, which would write
     * {@code ["SATURDAY","SUNDAY"]} for the enum and fail the JSON Schema. The
     * stored form has to stay numeric; the typed form is built on read.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weekly_off", nullable = false)
    private List<Integer> weeklyOffDays;

    /**
     * Minutes from midnight, local to {@link #timezone}. 570 is 09:30.
     *
     * <p><b>Not a {@code TIME}/{@link LocalTime} column, and that is deliberate.</b>
     * Both {@code api} and {@code worker} set
     * {@code hibernate.jdbc.time_zone: UTC}, so a {@code TIME} round-trips
     * through a UTC calendar and returns shifted by the JVM zone's offset —
     * 09:30 written, 15:00 read, on any machine that is not on UTC. That was not
     * theoretical; it is what the first version of this entity did, and
     * {@code WorkingCalendarIT} caught it. A working-day boundary carries no
     * instant, so a type the driver is entitled to convert is the wrong type for
     * it. PLAN.md §3.1 made the same call choosing {@code DATETIME(6)} over
     * {@code TIMESTAMP}.
     *
     * <p>{@link #getWorkDayStart()} is the accessor to use — the minutes are an
     * encoding, not the concept.
     */
    @Column(name = "work_day_start_min", nullable = false)
    private Short workDayStartMin;

    /** Minutes from midnight. 1110 is 18:30. See {@link #workDayStartMin}. */
    @Column(name = "work_day_end_min", nullable = false)
    private Short workDayEndMin;

    /**
     * The zone the working-day minutes are counted in.
     *
     * <p>Storage is UTC everywhere (PLAN.md §3.1), but a working day is a local
     * fact: 09:30 in Kolkata is not 09:30 in UTC, and pinning the boundaries to
     * UTC would move everyone's working day when the org's had not. B-024 needs
     * this to resolve either boundary to an instant; it cannot from the minutes
     * alone.
     */
    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------
    // Typed accessors — the ones callers should use
    // ------------------------------------------------------------------

    /**
     * The non-working days of the week.
     *
     * <p>An {@link EnumSet}, so a caller's {@code contains} check cannot depend
     * on numbering at all. Empty rather than null when unset — a calendar with
     * no configured weekend means every day is workable, which is a coherent
     * answer; null would make every caller handle a case the CHECK already
     * forbids.
     */
    public Set<DayOfWeek> getWeeklyOff() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (weeklyOffDays != null) {
            for (Integer iso : weeklyOffDays) {
                // DayOfWeek.of throws on anything outside 1–7, which is the
                // same range the column's CHECK enforces. Belt and braces:
                // this is the last line of defence if a row were ever written
                // by something that bypassed the constraint.
                days.add(DayOfWeek.of(iso));
            }
        }
        return days;
    }

    /** Replaces the pattern wholesale; a partial update of a weekend is meaningless. */
    public void setWeeklyOff(Set<DayOfWeek> days) {
        this.weeklyOffDays = days == null
                ? List.of()
                : days.stream().map(DayOfWeek::getValue).sorted().toList();
    }

    /** {@code true} if the given day is a weekend or other standing day off. */
    public boolean isNonWorkingDay(DayOfWeek day) {
        return getWeeklyOff().contains(day);
    }

    /** Parsed once here so callers do not each repeat the lookup. */
    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    /** When the working day opens, local to {@link #zone()}. */
    public LocalTime getWorkDayStart() {
        return toLocalTime(workDayStartMin);
    }

    public void setWorkDayStart(LocalTime start) {
        this.workDayStartMin = toMinutes(start);
    }

    /**
     * When the working day closes, local to {@link #zone()}.
     *
     * <p>A day configured to end at midnight stores 1440, which is not a
     * {@link LocalTime} — it is the end of the day, not 00:00 at the start of
     * it. {@link LocalTime#MIDNIGHT} would read as 00:00 and make the working day
     * negative, so 1440 reads as 23:59:59.999999999 instead: the last instant the
     * day contains, which is what a closing boundary means.
     */
    public LocalTime getWorkDayEnd() {
        return workDayEndMin != null && workDayEndMin == MINUTES_IN_DAY
                ? LocalTime.MAX
                : toLocalTime(workDayEndMin);
    }

    public void setWorkDayEnd(LocalTime end) {
        this.workDayEndMin = LocalTime.MAX.equals(end)
                ? (short) MINUTES_IN_DAY
                : toMinutes(end);
    }

    /** The length of a working day, which is what B-024 actually consumes. */
    public Duration workDayLength() {
        return Duration.ofMinutes((long) workDayEndMin - workDayStartMin);
    }

    private static LocalTime toLocalTime(Short minutes) {
        return minutes == null ? null : LocalTime.MIN.plusMinutes(minutes);
    }

    private static Short toMinutes(LocalTime time) {
        return time == null ? null : (short) (time.getHour() * 60 + time.getMinute());
    }

    // ------------------------------------------------------------------
    // Plain accessors
    // ------------------------------------------------------------------

    public Byte getId() {
        return id;
    }

    public List<Integer> getWeeklyOffDays() {
        return weeklyOffDays;
    }

    public void setWeeklyOffDays(List<Integer> weeklyOffDays) {
        this.weeklyOffDays = weeklyOffDays;
    }

    /** The stored encoding. Prefer {@link #getWorkDayStart()}. */
    public Short getWorkDayStartMin() {
        return workDayStartMin;
    }

    public void setWorkDayStartMin(Short workDayStartMin) {
        this.workDayStartMin = workDayStartMin;
    }

    /** The stored encoding. Prefer {@link #getWorkDayEnd()}. */
    public Short getWorkDayEndMin() {
        return workDayEndMin;
    }

    public void setWorkDayEndMin(Short workDayEndMin) {
        this.workDayEndMin = workDayEndMin;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
