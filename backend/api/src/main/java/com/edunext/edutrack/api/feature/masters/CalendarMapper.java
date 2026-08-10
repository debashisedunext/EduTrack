package com.edunext.edutrack.api.feature.masters;

import com.edunext.edutrack.api.config.BaseMapperConfig;
import com.edunext.edutrack.domain.masters.Holiday;
import com.edunext.edutrack.domain.masters.ResourceLeave;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * B-023 · entity ⇄ DTO for S-14, and the first mapper to use B-006's shared
 * config.
 *
 * <p>Everything inherited from {@link BaseMapperConfig} matters here rather than
 * being decoration:
 *
 * <ul>
 *   <li><b>{@code unmappedTargetPolicy = ERROR}</b> means a field added to a
 *       response record without a mapping fails the build instead of rendering
 *       blank on the calendar screen.</li>
 *   <li><b>{@code nullValuePropertyMappingStrategy = IGNORE}</b> is what lets
 *       {@link #applyUpdate} be a real partial update — a {@code PATCH} that
 *       sends only {@code name} leaves the date alone rather than nulling a
 *       {@code NOT NULL} column.</li>
 *   <li><b>{@code typeConversionPolicy = ERROR}</b> guards the {@code Long}
 *       {@code projectId} against the {@code Integer} keys that sit beside it
 *       elsewhere in this schema.</li>
 * </ul>
 *
 * <h2>Why {@code TARGET_IMMUTABLE}</h2>
 *
 * <p>MapStruct's default for a collection on a {@code @MappingTarget} is to
 * mutate what the getter returns — {@code getWeeklyOff().clear()} then
 * {@code addAll(...)} — rather than call the setter.
 * {@link WorkingCalendar#getWeeklyOff()} builds a fresh {@link java.util.EnumSet}
 * on every call, because the stored form is a list of ISO numbers and the typed
 * form is derived from it. So the default cleared and repopulated a throwaway
 * object: {@code PUT /masters/working-calendar} answered 200, reported the new
 * week back, and changed nothing. Both a service test and the returned body
 * looked correct; only the entity did not move.
 *
 * <p>{@code TARGET_IMMUTABLE} makes MapStruct call the setter, which is the only
 * thing that can work when a getter returns a derived view.
 *
 * <p><b>Not promoted to {@link BaseMapperConfig}</b>, tempting as it is. Setting
 * it globally would make every future mapper replace collection <em>instances</em>
 * on JPA entities, and for a real association with
 * {@code orphanRemoval = true} that is how you get "A collection with
 * cascade=all-delete-orphan was no longer referenced". The right default depends
 * on whether the getter exposes state or computes it, which is a per-entity fact.
 */
@Mapper(config = BaseMapperConfig.class,
        collectionMappingStrategy = CollectionMappingStrategy.TARGET_IMMUTABLE)
public interface CalendarMapper {

    // ------------------------------------------------------------------
    // Working week
    // ------------------------------------------------------------------

    /**
     * {@code weeklyOff} is mapped by {@link #toIsoDays} rather than field-by-field:
     * the entity holds {@code Set<DayOfWeek>} and the wire holds ISO numbers, and
     * this is the single place that conversion happens.
     */
    @Mapping(target = "weeklyOff", source = "weeklyOff")
    CalendarDtos.WorkingWeek toWorkingWeek(WorkingCalendar calendar);

    /**
     * The write side of the working week.
     *
     * <p>Every target is listed. {@code id} is the singleton key and
     * {@code updatedAt} is database-generated, so both are ignored by name —
     * under {@code unmappedTargetPolicy = ERROR} that exemption has to be
     * stated, which is the point: a field nobody thought about does not quietly
     * become writable.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "weeklyOff", source = "weeklyOff")
    // The three *Min / *Days properties are the stored encoding of the three
    // above them. Writing both would be two sources of truth for one value, and
    // whichever MapStruct emitted second would win. Ignored by name because
    // unmappedTargetPolicy = ERROR requires the exemption to be stated — which
    // is how the duplication got noticed rather than compiling quietly.
    @Mapping(target = "weeklyOffDays", ignore = true)
    @Mapping(target = "workDayStartMin", ignore = true)
    @Mapping(target = "workDayEndMin", ignore = true)
    void applyWorkingWeek(CalendarDtos.WorkingWeekUpdate update, @MappingTarget WorkingCalendar calendar);

    /** Entity → wire. Sorted so the response does not depend on set iteration order. */
    default List<Integer> toIsoDays(Set<DayOfWeek> days) {
        return days == null ? List.of() : days.stream().map(DayOfWeek::getValue).sorted().toList();
    }

    /**
     * Wire → entity.
     *
     * <p>{@link DayOfWeek#of(int)} throws on anything outside 1–7. The DTO's
     * {@code @Min(1) @Max(7)} rejects those first with a 400 naming the field,
     * so reaching this with a bad value would mean validation was bypassed —
     * and throwing beats silently resolving a 0 to some day.
     */
    default Set<DayOfWeek> toDayOfWeek(Set<Integer> isoDays) {
        Set<DayOfWeek> days = new TreeSet<>();
        if (isoDays != null) {
            isoDays.forEach(iso -> days.add(DayOfWeek.of(iso)));
        }
        return days;
    }

    // ------------------------------------------------------------------
    // Holidays
    // ------------------------------------------------------------------

    @Mapping(target = "date", source = "holidayDate")
    @Mapping(target = "isRecurring", source = "recurring")
    @Mapping(target = "isActive", source = "active")
    CalendarDtos.Holiday toHoliday(Holiday holiday);

    List<CalendarDtos.Holiday> toHolidays(List<Holiday> holidays);

    /**
     * Create. Defaults for the two flags are applied by the service, not here —
     * a mapper that invents values hides where they came from.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "holidayDate", source = "date")
    @Mapping(target = "recurring", source = "isRecurring", defaultValue = "false")
    @Mapping(target = "active", source = "isActive", defaultValue = "true")
    Holiday toHolidayEntity(CalendarDtos.HolidayWrite write);

    /**
     * Edit. Null source properties are left alone — that is
     * {@code nullValuePropertyMappingStrategy = IGNORE} doing the work, and it
     * is why a request sending only {@code name} cannot blank {@code date}.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "holidayDate", source = "date")
    @Mapping(target = "recurring", source = "isRecurring")
    @Mapping(target = "active", source = "isActive")
    void applyUpdate(CalendarDtos.HolidayPatch patch, @MappingTarget Holiday holiday);

    // ------------------------------------------------------------------
    // Resource leave
    // ------------------------------------------------------------------

    @Mapping(target = "isHalfDay", source = "halfDay")
    CalendarDtos.ResourceLeave toLeave(ResourceLeave leave);

    List<CalendarDtos.ResourceLeave> toLeaves(List<ResourceLeave> leaves);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "halfDay", source = "isHalfDay", defaultValue = "false")
    @Mapping(target = "status", source = "status", defaultValue = "APPROVED")
    ResourceLeave toLeaveEntity(CalendarDtos.ResourceLeaveWrite write);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "halfDay", source = "isHalfDay")
    void applyUpdate(CalendarDtos.ResourceLeavePatch patch, @MappingTarget ResourceLeave leave);
}
