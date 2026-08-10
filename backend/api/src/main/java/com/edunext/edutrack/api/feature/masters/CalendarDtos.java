package com.edunext.edutrack.api.feature.masters;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * B-023 · the wire shapes for S-14, matching {@code contracts/openapi.yaml}
 * §masters.
 *
 * <p>Grouped in one file for the reason {@code ChatDtos} gives: they are one
 * contract read together, and a change to the calendar's shape reviews better
 * as one diff than as eight.
 */
public final class CalendarDtos {

    private CalendarDtos() {
    }

    // ------------------------------------------------------------------
    // The working week
    // ------------------------------------------------------------------

    /**
     * {@code WorkingWeek} in the contract.
     *
     * <p>{@code weeklyOff} is a {@code List<Integer>} of ISO-8601 day numbers
     * rather than a {@code Set<DayOfWeek>}, because this is the wire shape and
     * Jackson would serialise the enum as {@code ["SATURDAY"]}. The typed form
     * lives on the entity; the mapper is the one place that converts.
     */
    public record WorkingWeek(
            List<Integer> weeklyOff,
            LocalTime workDayStart,
            LocalTime workDayEnd,
            String timezone) {
    }

    /**
     * {@code WorkingWeekUpdateRequest} — a whole-resource replace.
     *
     * <p>A {@code PUT} rather than a {@code PATCH} because a partial weekend is
     * meaningless and the two day bounds are only valid relative to each other.
     *
     * <p>The bounds here mirror the database's
     * {@code ck_working_calendar_weekly_off}: 1–7, no duplicates (a {@link Set}
     * gives that for free), and at most six days. Validating in both places is
     * deliberate — Bean Validation produces a 400 naming the field, where the
     * constraint alone would surface as a 500 from a driver exception.
     */
    public record WorkingWeekUpdate(
            @NotNull
            @Size(max = 6, message = "a seven-day weekend leaves no working day")
            Set<@NotNull @Min(1) @Max(7) Integer> weeklyOff,

            @NotNull
            LocalTime workDayStart,

            @NotNull
            LocalTime workDayEnd,

            @NotBlank
            String timezone) {
    }

    public record WorkingWeekResponse(WorkingWeek data) {
    }

    // ------------------------------------------------------------------
    // Holidays
    // ------------------------------------------------------------------

    /** {@code Holiday} in the contract. */
    public record Holiday(
            long id,
            LocalDate date,
            String name,
            Long projectId,
            boolean isRecurring,
            boolean isActive) {
    }

    /**
     * {@code HolidayWriteRequest} for <b>create</b>. Date and name are required
     * because a holiday without either is not a holiday.
     */
    public record HolidayWrite(
            @NotNull
            LocalDate date,

            @NotBlank
            @Size(max = 120)
            String name,

            Long projectId,

            Boolean isRecurring,

            Boolean isActive) {
    }

    /**
     * The same shape for <b>edit</b>, with every field optional.
     *
     * <p>A separate record rather than reusing {@link HolidayWrite}. Sharing one
     * looked economical and was wrong: {@code @NotNull date} and
     * {@code @NotBlank name} made {@code PATCH {"name": "..."}} a 400, so the
     * partial update the endpoint advertises could not be performed at all —
     * caught by calling it, not by any test. Bean Validation has no notion of
     * "required on create, optional on edit", so the two shapes have to be two
     * types.
     *
     * <p>Null here means "not sent", which
     * {@code nullValuePropertyMappingStrategy = IGNORE} turns into "leave it
     * alone". That is why omitting {@code date} cannot blank a {@code NOT NULL}
     * column.
     */
    public record HolidayPatch(
            LocalDate date,

            @Size(max = 120)
            String name,

            Long projectId,

            Boolean isRecurring,

            Boolean isActive) {
    }

    public record HolidayResponse(Holiday data) {
    }

    /**
     * {@code HolidayListResponse} — the calendar read model B-024 consumes.
     *
     * <p>One call returns both halves because a caller computing working hours
     * needs both, and two calls would let them disagree by the width of a
     * concurrent edit.
     */
    public record CalendarResponse(CalendarData data) {
    }

    public record CalendarData(
            List<Holiday> holidays,
            List<Integer> weeklyOff,
            LocalTime workDayStart,
            LocalTime workDayEnd) {
    }

    // ------------------------------------------------------------------
    // Resource leave
    // ------------------------------------------------------------------

    /** {@code ResourceLeave} in the contract. */
    public record ResourceLeave(
            long id,
            long userId,
            LocalDate startDate,
            LocalDate endDate,
            String leaveType,
            boolean isHalfDay,
            String status,
            String reason) {
    }

    /**
     * {@code ResourceLeaveWriteRequest}.
     *
     * <p>{@code endDate >= startDate} is not expressible as a field annotation,
     * so the service checks it. The database has the same rule as
     * {@code ck_resource_leaves_range} — this exists so the caller gets a 400
     * naming the field rather than a 500 from a constraint violation.
     */
    public record ResourceLeaveWrite(
            @NotNull
            Long userId,

            @NotNull
            LocalDate startDate,

            @NotNull
            LocalDate endDate,

            String leaveType,

            Boolean isHalfDay,

            String status,

            @Size(max = 255)
            String reason) {
    }

    /**
     * Edit, with every field optional — the same reason {@link HolidayPatch}
     * exists. Approving a leave is {@code {"status": "APPROVED"}} and nothing
     * else; requiring the dates back would make the common case the awkward one.
     */
    public record ResourceLeavePatch(
            Long userId,

            LocalDate startDate,

            LocalDate endDate,

            String leaveType,

            Boolean isHalfDay,

            String status,

            @Size(max = 255)
            String reason) {
    }

    public record ResourceLeaveResponse(ResourceLeave data) {
    }

    public record ResourceLeaveListResponse(List<ResourceLeave> data, Meta meta) {
    }

    /** {@code Meta} — cursor pagination, per CONVENTIONS.md §6. */
    public record Meta(String nextCursor, boolean hasMore, long totalCount) {
    }
}
