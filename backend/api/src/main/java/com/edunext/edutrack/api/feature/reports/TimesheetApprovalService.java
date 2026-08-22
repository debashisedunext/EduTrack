package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * B-065 · §21's "an approval step for the manager", decided 22 Aug 2026 and
 * built as a new row rather than a flag on the append-only effort log — see
 * {@code V20260822_1200__timesheet_approvals.sql}.
 *
 * <h2>Why this is not a method on {@link TimesheetService}</h2>
 *
 * <p>{@code ResourceWriteRepository}'s own precedent: a read projection and
 * the statements that change a different table belong in different classes,
 * so the class the export streams five thousand rows through never sits next
 * to an {@code INSERT}. {@link TimesheetService} stays a read over
 * {@code ticket_effort_logs}; this is a write over {@code timesheet_approvals},
 * and {@link TimesheetService#week} reads the result back through
 * {@link TimesheetApprovalRepository} rather than through this class.
 *
 * <h2>Who may approve, and why the refusal is 404</h2>
 *
 * <p>Two gates, deliberately at different layers. {@code hasAnyRole('ADMIN','PM')}
 * on {@link TimesheetController#approve} refuses Support and the three
 * delivery roles before any row is read — a rowless capability question,
 * exactly like {@code BulkReassignController}'s identical annotation, and the
 * one place this feature answers {@code 403}.
 *
 * <p>Underneath that, whether <em>this particular</em> PM may approve
 * <em>this particular</em> resource's week is a row question, and
 * CONVENTIONS.md §7 is explicit that a row-dependent refusal must not be a
 * {@code 403} — it would concede which user ids exist to a caller who is
 * not their manager. So it is 404, on {@link TimesheetService#week}'s own
 * precedent: Admin always passes, and a PM passes only when
 * {@link Profile360Repository#isDirectManagerOf} says so. Neither branch is
 * widened to {@link Profile360Repository#isVisibleTo}'s project-sharing
 * half — seeing a colleague's week and approving it are different acts, and
 * only the narrower one implies management authority.
 *
 * <h2>One review per week</h2>
 *
 * <p>{@code uq_timesheet_approvals_week} is the authority; this service only
 * gives the race a name. A second approval of an already-approved week is
 * {@link AlreadyApprovedException}, carrying who got there first — never a
 * silent second row two managers could each believe was the record.
 */
@Service
class TimesheetApprovalService {

    private final TimesheetApprovalRepository repository;
    private final Profile360Repository people;
    private final WorkingCalendarRepository calendars;

    TimesheetApprovalService(TimesheetApprovalRepository repository, Profile360Repository people,
                             WorkingCalendarRepository calendars) {
        this.repository = repository;
        this.people = people;
        this.calendars = calendars;
    }

    /**
     * @return empty when the subject does not exist, or the caller is
     *         neither an Admin nor the subject's own direct reporting
     *         manager — reported as a 404 by the controller, on
     *         {@link TimesheetService#week}'s own reasoning.
     * @throws AlreadyApprovedException when this resource's week has already
     *         been reviewed
     */
    @Transactional
    Optional<TimesheetDtos.TimesheetApproval> approve(CallerIdentity caller, long userId,
                                                       LocalDate weekOf, String note) {

        if (people.subject(userId).isEmpty()) {
            return Optional.empty();
        }
        if (!mayApprove(caller, userId)) {
            return Optional.empty();
        }

        ZoneId zone = calendars.getCalendar().zone();
        LocalDate weekStart = TimesheetService.mondayOf(weekOf != null ? weekOf : LocalDate.now(zone));

        Optional<TimesheetApprovalRepository.Approval> inserted =
                repository.insert(userId, weekStart, caller.userId(), Instant.now(), note)
                        .flatMap(id -> repository.find(userId, weekStart));

        if (inserted.isEmpty()) {
            // uq_timesheet_approvals_week's race: somebody else's approval won
            // the insert between our check and now. Re-read it so the 409
            // names who got there first, rather than reporting a write that
            // did not happen as if it had.
            TimesheetApprovalRepository.Approval existing = repository.find(userId, weekStart)
                    .orElseThrow(() -> new IllegalStateException(
                            "timesheet_approvals insert failed with no row to explain why"));
            throw new AlreadyApprovedException(existing);
        }

        return inserted.map(this::toView);
    }

    /**
     * Admin, or {@code userId}'s own direct reporting manager. Deliberately
     * narrower than {@link Profile360Repository#isVisibleTo} — see this
     * class's header.
     */
    private boolean mayApprove(CallerIdentity caller, long userId) {
        if ("ADMIN".equals(caller.roleCode())) {
            return true;
        }
        return "PM".equals(caller.roleCode()) && people.isDirectManagerOf(userId, caller.userId());
    }

    private TimesheetDtos.TimesheetApproval toView(TimesheetApprovalRepository.Approval approval) {
        return new TimesheetDtos.TimesheetApproval(
                approval.weekStart().toString(), approval.approvedBy(), approval.approvedAt(), approval.note());
    }

    /** A week that already carries somebody else's review. */
    static class AlreadyApprovedException extends RuntimeException {

        private final TimesheetApprovalRepository.Approval existing;

        AlreadyApprovedException(TimesheetApprovalRepository.Approval existing) {
            super("The week of " + existing.weekStart() + " for user " + existing.userId()
                    + " was already reviewed by " + existing.approvedBy().displayName()
                    + " at " + existing.approvedAt() + ".");
            this.existing = existing;
        }

        TimesheetApprovalRepository.Approval existing() {
            return existing;
        }
    }
}
