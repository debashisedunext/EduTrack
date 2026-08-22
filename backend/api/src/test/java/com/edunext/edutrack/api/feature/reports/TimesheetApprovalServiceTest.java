package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-065 · the row question underneath the {@code hasAnyRole('ADMIN','PM')}
 * gate — who may approve a GIVEN resource's week — and the 409 for a week
 * that already carries somebody else's review.
 *
 * <p>{@code TimesheetApprovalIT} covers the half this cannot: the real
 * {@code reporting_manager_id} chain and {@code uq_timesheet_approvals_week}'s
 * own guarantee against a genuine race.
 */
class TimesheetApprovalServiceTest {

    private final TimesheetApprovalRepository repository = mock(TimesheetApprovalRepository.class);
    private final Profile360Repository people = mock(Profile360Repository.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);

    private final TimesheetApprovalService service =
            new TimesheetApprovalService(repository, people, calendars);

    private static final long SUBJECT = 7L;
    private static final LocalDate WEEK_OF = LocalDate.of(2026, 8, 13);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);

    @BeforeEach
    void calendar() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("Asia/Kolkata");
        when(calendars.getCalendar()).thenReturn(calendar);

        when(people.subject(SUBJECT)).thenReturn(Optional.of(new Profile360Repository.Subject(
                SUBJECT, "Ravi Kumar", "ravi.kumar", "ravi@example.test", "DEVELOPER",
                "Delivery", "Engineer", true, LocalDate.of(2024, 1, 1), "Meera Nair")));
    }

    @Nested
    @DisplayName("who may approve — a row question, narrower than isVisibleTo")
    class Authorisation {

        @Test
        @DisplayName("an Admin approves anybody's week")
        void adminAlwaysMay() {
            CallerIdentity admin = new CallerIdentity(1L, "ADMIN", List.of());
            when(repository.insert(eq(SUBJECT), eq(MONDAY), eq(1L), any(), isNull()))
                    .thenReturn(Optional.of(99L));
            when(repository.find(SUBJECT, MONDAY)).thenReturn(Optional.of(
                    new TimesheetApprovalRepository.Approval(99L, SUBJECT, MONDAY,
                            new TimesheetDtos.UserRef(1L, "Admin", "ADMIN", "admin"), Instant.now(), null)));

            assertThat(service.approve(admin, SUBJECT, WEEK_OF, null)).isPresent();
        }

        @Test
        @DisplayName("the resource's own direct reporting manager may approve")
        void directManagerMay() {
            CallerIdentity manager = new CallerIdentity(2L, "PM", List.of());
            when(people.isDirectManagerOf(SUBJECT, 2L)).thenReturn(true);
            when(repository.insert(eq(SUBJECT), eq(MONDAY), eq(2L), any(), any()))
                    .thenReturn(Optional.of(99L));
            when(repository.find(SUBJECT, MONDAY)).thenReturn(Optional.of(
                    new TimesheetApprovalRepository.Approval(99L, SUBJECT, MONDAY,
                            new TimesheetDtos.UserRef(2L, "Meera Nair", "PM", "meera.nair"), Instant.now(), null)));

            assertThat(service.approve(manager, SUBJECT, WEEK_OF, null)).isPresent();
        }

        @Test
        @DisplayName("a PM sharing only a project, and not the reporting line, may not")
        void projectMateMayNot() {
            // Deliberately narrower than GET .../timesheet's isVisibleTo, which
            // this PM would pass — approving is a manager's act, seeing is not.
            CallerIdentity teammatePm = new CallerIdentity(3L, "PM", List.of());
            when(people.isDirectManagerOf(SUBJECT, 3L)).thenReturn(false);

            assertThat(service.approve(teammatePm, SUBJECT, WEEK_OF, null)).isEmpty();
            verify(repository, never()).insert(anyLong(), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("a user id naming nobody answers empty before the manager chain is even asked")
        void noSuchSubject() {
            when(people.subject(SUBJECT)).thenReturn(Optional.empty());
            CallerIdentity admin = new CallerIdentity(1L, "ADMIN", List.of());

            assertThat(service.approve(admin, SUBJECT, WEEK_OF, null)).isEmpty();
            verify(people, never()).isDirectManagerOf(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("one review per resource per week")
    class Idempotency {

        @Test
        @DisplayName("a week already reviewed answers 409, naming who got there first")
        void alreadyApprovedNamesTheFirstReviewer() {
            CallerIdentity admin = new CallerIdentity(1L, "ADMIN", List.of());
            TimesheetDtos.UserRef firstReviewer = new TimesheetDtos.UserRef(2L, "Meera Nair", "PM", "meera.nair");
            TimesheetApprovalRepository.Approval existing = new TimesheetApprovalRepository.Approval(
                    99L, SUBJECT, MONDAY, firstReviewer, Instant.parse("2026-08-11T09:00:00Z"), "Reviewed");

            // uq_timesheet_approvals_week's race: the insert reports it lost,
            // and the service re-reads to name who won.
            when(repository.insert(eq(SUBJECT), eq(MONDAY), eq(1L), any(), any()))
                    .thenReturn(Optional.empty());
            when(repository.find(SUBJECT, MONDAY)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.approve(admin, SUBJECT, WEEK_OF, null))
                    .isInstanceOf(TimesheetApprovalService.AlreadyApprovedException.class)
                    .extracting(e -> ((TimesheetApprovalService.AlreadyApprovedException) e).existing())
                    .isEqualTo(existing);
        }
    }

    @Nested
    @DisplayName("the week resolved from weekOf")
    class WeekResolution {

        @Test
        @DisplayName("any date inside the week resolves to the same Monday the GET would")
        void resolvesToMonday() {
            CallerIdentity admin = new CallerIdentity(1L, "ADMIN", List.of());
            when(repository.insert(eq(SUBJECT), eq(MONDAY), anyLong(), any(), any()))
                    .thenReturn(Optional.of(1L));
            when(repository.find(SUBJECT, MONDAY)).thenReturn(Optional.of(
                    new TimesheetApprovalRepository.Approval(1L, SUBJECT, MONDAY,
                            new TimesheetDtos.UserRef(1L, "Admin", "ADMIN", "admin"), Instant.now(), null)));

            assertThat(service.approve(admin, SUBJECT, WEEK_OF, null)).isPresent();
            verify(repository).insert(eq(SUBJECT), eq(MONDAY), anyLong(), any(), any());
        }
    }
}
