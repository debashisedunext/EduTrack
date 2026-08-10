package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The org working week — one row, so the interesting method is the one that does
 * not take an id.
 *
 * <p>B-024's working-hours service reads this on every SLA, duration and
 * utilisation calculation in the system, which makes it the hottest read in the
 * application and an obvious cache target. Deliberately not cached here: the
 * caching decision belongs with the service that knows its own invalidation
 * story, and a cache in the repository would be invisible to the Admin who has
 * just changed the working day and cannot see why nothing moved.
 */
public interface WorkingCalendarRepository extends JpaRepository<WorkingCalendar, Byte> {

    /**
     * The calendar.
     *
     * <p>{@code V20260808_1630} seeds the row and
     * {@code ck_working_calendar_singleton} stops a second one existing, so this
     * throws only if someone has deleted it — a broken installation rather than
     * an empty result, which is why it is not an {@code Optional}. B-024 has no
     * meaningful behaviour without a calendar and a caller cannot invent one:
     * defaulting silently to Sat/Sun 09:30–18:30 would produce SLA figures that
     * look right and are not.
     */
    default WorkingCalendar getCalendar() {
        return findById(WorkingCalendar.SINGLETON_ID).orElseThrow(() -> new IllegalStateException(
                "working_calendar has no row with id=" + WorkingCalendar.SINGLETON_ID
                        + ". V20260808_1630 seeds it and a CHECK constraint pins it there, so it "
                        + "has been deleted. Every SLA and duration figure reads this; restore it "
                        + "rather than letting callers assume a default."));
    }
}
