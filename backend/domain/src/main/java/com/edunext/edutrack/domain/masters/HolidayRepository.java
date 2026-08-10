package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Feeds B-024's working-hours service, which is where every duration and SLA
 * figure in the system is computed.
 *
 * <p>Both queries match on the stored {@code holidayDate}, so they return a
 * recurring holiday only in the year it was entered. Expanding
 * {@code isRecurring} rows across years is the calendar service's job — the
 * master deliberately holds one row, not one per year.
 */
public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    /** Both bounds inclusive. Served by {@code ix_holidays_date}. */
    List<Holiday> findByHolidayDateBetweenAndIsActiveTrue(LocalDate from, LocalDate to);

    /**
     * What a project actually observes: the org-wide calendar plus its own
     * additions. A project row does not replace the org row for the same date —
     * either one makes the day non-working, so the caller unions rather than
     * overriding.
     */
    @Query("""
            select h from Holiday h
             where h.isActive = true
               and h.holidayDate between :from and :to
               and (h.projectId is null or h.projectId = :projectId)
            """)
    List<Holiday> findOrgWideOrForProject(@Param("projectId") Long projectId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /**
     * Every org-wide or project holiday, unfiltered by date — what B-024 needs
     * and {@link #findOrgWideOrForProject} cannot give it.
     *
     * <p>A recurring holiday is stored once, in whatever year it was entered.
     * Filtering on {@code holidayDate between :from and :to} — as the read-model
     * query above does, deliberately, for a UI window — would make a 2020-dated
     * recurring holiday invisible to a 2026 SLA calculation. Expanding it into
     * every year the walk touches is the working-hours service's job, and it
     * cannot do that over rows it was never given.
     */
    @Query("""
            select h from Holiday h
             where h.isActive = true
               and (h.projectId is null or h.projectId = :projectId)
            """)
    List<Holiday> findAllOrgWideOrForProject(@Param("projectId") Long projectId);
}
