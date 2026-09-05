package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.masters.ResourceLeaveRepository;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * C-108 · "make the inheritance leave-aware against the working calendar" —
 * {@code PHASE-2-BUILD-PLAN.md}'s own ruling on the backup-owner discrepancy,
 * word for word.
 *
 * <p>Reads {@link ResourceLeaveRepository} directly rather than going through
 * {@code WorkingHoursService}: that class answers "how much working time" and
 * "where does a duration land", both keyed off a window of instants: this
 * answers a different, simpler question — "is this one person away today" —
 * and {@code WorkingHoursService#leavesFor} is private to it, on its own
 * class javadoc's single-choke-point reasoning for the questions it exists to
 * answer. Duplicating the *query* (one repository call) costs nothing;
 * routing through the duration engine for a yes/no would cost a window this
 * question does not have.
 */
@Service
public class ObBackupOwnerResolver {

    private final ResourceLeaveRepository leaves;
    private final WorkingCalendarRepository calendars;

    ObBackupOwnerResolver(ResourceLeaveRepository leaves, WorkingCalendarRepository calendars) {
        this.leaves = leaves;
        this.calendars = calendars;
    }

    /**
     * Who effectively owns {@code step} today: the backup owner when the
     * primary owner is on approved leave today, or when the step has no
     * resolved primary owner at all (C-103's "unresolved" case — a role-only
     * or nameless template step); the primary owner otherwise.
     *
     * <p>{@code null} only when neither is set — nobody to inherit to, which
     * is the same "unresolved" state {@code findByOwnerUserIdIsNull} already
     * surfaces on the Manager's unassigned list.
     */
    @Transactional(readOnly = true)
    public Long effectiveOwnerUserId(ObJourneyStep step) {
        Long owner = step.getOwnerUserId();
        if (owner == null) {
            return step.getBackupOwnerUserId();
        }
        if (step.getBackupOwnerUserId() != null && isOnLeaveToday(owner)) {
            return step.getBackupOwnerUserId();
        }
        return owner;
    }

    private boolean isOnLeaveToday(long userId) {
        LocalDate today = LocalDate.now(calendars.getCalendar().zone());
        return !leaves.findApprovedOverlapping(userId, today, today).isEmpty();
    }
}
