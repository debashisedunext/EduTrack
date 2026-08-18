package com.edunext.edutrack.api.feature.masters.statuses;

import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.masters.Status;
import com.edunext.edutrack.domain.masters.StatusRepository;
import com.edunext.edutrack.domain.masters.WorkflowTransition;
import com.edunext.edutrack.domain.masters.WorkflowTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-039 · the transition matrix's rules.
 *
 * <p>The suite is deliberately heavier on the <em>refusals</em> than the
 * happy path, because the table is a whitelist: a row wrongly present grants a
 * move nobody approved, and a row wrongly absent is invisible until somebody
 * tries to make it. Neither shows up as an error anywhere.
 */
class StatusTransitionServiceTest {

    private WorkflowTransitionRepository transitions;
    private StatusRepository statuses;
    private RoleRepository roles;
    private StatusTransitionService service;

    private List<WorkflowTransition> stored;

    @BeforeEach
    void setUp() {
        transitions = mock(WorkflowTransitionRepository.class);
        statuses = mock(StatusRepository.class);
        roles = mock(RoleRepository.class);
        service = new StatusTransitionService(transitions, statuses, roles);

        stored = new ArrayList<>();

        when(statuses.findAll()).thenReturn(List.of(
                status("NEW"), status("IN_PROGRESS"), status("ON_HOLD"),
                status("RESOLVED"), status("CLOSED"), status("REOPENED")));
        when(roles.findAll()).thenReturn(List.of(
                role("ADMIN"), role("PM"), role("DEVELOPER"),
                role("QA"), role("DEPLOYMENT"), role("SUPPORT")));

        when(transitions.findAllByOrderByIdAsc()).thenAnswer(i -> List.copyOf(stored));
        when(transitions.findByFromStatusAndToStatusAndRoleCode(any(), any(), any()))
                .thenAnswer(i -> stored.stream()
                        .filter(t -> Objects.equals(t.getFromStatus(), i.getArgument(0))
                                && i.getArgument(1).equals(t.getToStatus())
                                && i.getArgument(2).equals(t.getRoleCode()))
                        .findFirst());
        when(transitions.save(any(WorkflowTransition.class))).thenAnswer(i -> {
            WorkflowTransition row = i.getArgument(0);
            if (!stored.contains(row)) {
                row.setId(stored.size() + 1);
                stored.add(row);
            }
            return row;
        });
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the read shows cleared cells, unlike every other read of this table")
    class Reading {

        @Test
        @DisplayName("retired rows come back carrying isActive false, not omitted")
        void retiredRowsAreVisible() {
            stored.add(row(1, null, "NEW", "ADMIN", true));
            stored.add(row(2, "NEW", "IN_PROGRESS", "QA", false));

            assertThat(service.list(null))
                    .extracting(StatusDtos.TransitionView::toStatus,
                            StatusDtos.TransitionView::isActive)
                    .containsExactly(
                            tuple("NEW", true),
                            tuple("IN_PROGRESS", false));
        }

        @Test
        @DisplayName("a roleCode filters to one column and is upper-cased on the way in")
        void roleFilter() {
            when(transitions.findByRoleCodeOrderByIdAsc("QA"))
                    .thenReturn(List.of(row(2, "NEW", "IN_PROGRESS", "QA", true)));

            assertThat(service.list("qa")).hasSize(1);
            verify(transitions, never()).findAllByOrderByIdAsc();
        }

        @Test
        @DisplayName("a blank roleCode is the same as none — the grid sends an empty param")
        void blankRoleIsNoFilter() {
            stored.add(row(1, null, "NEW", "ADMIN", true));

            assertThat(service.list("  ")).hasSize(1);
        }

        @Test
        @DisplayName("the on-create row keeps its null fromStatus through the mapping")
        void onCreateRowSurvivesMapping() {
            stored.add(row(1, null, "NEW", "ADMIN", true));

            assertThat(service.list(null)).singleElement()
                    .extracting(StatusDtos.TransitionView::fromStatus)
                    .isNull();
        }
    }

    // ------------------------------------------------------------------
    // the invariant
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("at least one on-create row must survive — the only global refusal")
    class OnCreateInvariant {

        @Test
        @DisplayName("an empty matrix is refused, and the message says why it is different")
        void emptyMatrixRefused() {
            assertThatThrownBy(() -> service.replace(matrix()))
                    .isInstanceOf(StatusTransitionService.NoCreateTransitionException.class)
                    .hasMessageContaining("no role can raise a ticket");
        }

        @Test
        @DisplayName("a matrix with moves but no on-create row is refused")
        void noOnCreateRowRefused() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell("NEW", "IN_PROGRESS", "PM"),
                    cell("IN_PROGRESS", "RESOLVED", "PM"))))
                    .isInstanceOf(StatusTransitionService.NoCreateTransitionException.class);
        }

        @Test
        @DisplayName("nothing is written when the invariant would be broken")
        void refusalWritesNothing() {
            stored.add(row(1, null, "NEW", "ADMIN", true));

            assertThatThrownBy(() -> service.replace(matrix(cell("NEW", "IN_PROGRESS", "PM"))))
                    .isInstanceOf(StatusTransitionService.NoCreateTransitionException.class);

            verify(transitions, never()).save(any());
            verify(transitions, never()).saveAll(any());
            assertThat(stored).singleElement()
                    .extracting(WorkflowTransition::isActive).isEqualTo(true);
        }

        /**
         * The invariant is about the set that will exist, not the set that does.
         * A body containing only an on-create row is legal even though it clears
         * every other move in the table.
         */
        @Test
        @DisplayName("one on-create row is enough, even if it clears everything else")
        void oneOnCreateRowIsEnough() {
            assertThatCode(() -> service.replace(matrix(cell(null, "NEW", "ADMIN"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an empty-string fromStatus is read as on-create, not as a status code")
        void emptyStringIsOnCreate() {
            assertThatCode(() -> service.replace(matrix(cell("", "NEW", "ADMIN"))))
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // per-row refusals
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a row the database would happily store and nothing would ever match")
    class RowRefusals {

        /**
         * B-008's defect, in the shape a screen could now reproduce: thirteen
         * seeded rows carrying `SUPPORT_DESK` against a `roles` table holding
         * `SUPPORT`. No foreign key, so nothing failed — the Support Desk simply
         * could not make any status move at all.
         */
        @Test
        @DisplayName("an unknown role code is refused, because no FK would catch it")
        void unknownRole() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "SUPPORT_DESK"))))
                    .isInstanceOfSatisfying(StatusTransitionService.InvalidTransitionException.class,
                            e -> assertThat(e.field()).isEqualTo("roleCode"))
                    .hasMessageContaining("silently matches no caller");
        }

        @Test
        @DisplayName("an unknown toStatus is refused")
        void unknownToStatus() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "TRIAGED", "PM"))))
                    .isInstanceOfSatisfying(StatusTransitionService.InvalidTransitionException.class,
                            e -> assertThat(e.field()).isEqualTo("toStatus"));
        }

        @Test
        @DisplayName("an unknown fromStatus is refused")
        void unknownFromStatus() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("TRIAGED", "IN_PROGRESS", "PM"))))
                    .isInstanceOfSatisfying(StatusTransitionService.InvalidTransitionException.class,
                            e -> assertThat(e.field()).isEqualTo("fromStatus"));
        }

        @Test
        @DisplayName("a self-transition is refused — the unique key would store it as a permission")
        void selfTransition() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("ON_HOLD", "ON_HOLD", "PM"))))
                    .isInstanceOf(StatusTransitionService.InvalidTransitionException.class)
                    .hasMessageContaining("cannot transition to itself");
        }

        @Test
        @DisplayName("the same cell twice is refused — which one won would be iteration order")
        void duplicateCell() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "PM"),
                    cell("NEW", "IN_PROGRESS", "PM"))))
                    .isInstanceOfSatisfying(StatusTransitionService.InvalidTransitionException.class,
                            e -> assertThat(e.field()).isEqualTo("transitions"))
                    .hasMessageContaining("appears twice");
        }

        /**
         * Two on-create rows for the same role are the same cell — null is a real
         * key here, and a naive dedupe keyed on a concatenation without a
         * separator would let `NEW|IN_PROGRESS` and `NEWIN|PROGRESS` collide.
         */
        @Test
        @DisplayName("two on-create rows for one role are a duplicate, null key included")
        void duplicateOnCreateCell() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell(null, "NEW", "ADMIN"))))
                    .isInstanceOf(StatusTransitionService.InvalidTransitionException.class);
        }

        @Test
        @DisplayName("a row naming a retired status is accepted — this is how it comes back")
        void retiredStatusIsAllowed() {
            when(statuses.findAll()).thenReturn(List.of(
                    status("NEW"), retired("ON_HOLD"), status("IN_PROGRESS")));

            assertThatCode(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("IN_PROGRESS", "ON_HOLD", "PM"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nothing is written when any one row is refused")
        void oneBadRowWritesNothing() {
            assertThatThrownBy(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "PM"),
                    cell("NEW", "TRIAGED", "PM"))))
                    .isInstanceOf(StatusTransitionService.InvalidTransitionException.class);
            verify(transitions, never()).save(any());
        }
    }

    // ------------------------------------------------------------------
    // the upsert
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("upsert, not delete-and-reinsert")
    class Upsert {

        @Test
        @DisplayName("an existing row keeps its id and is updated in place")
        void existingRowKeepsItsId() {
            WorkflowTransition existing = row(7, "NEW", "IN_PROGRESS", "PM", true);
            stored.add(row(1, null, "NEW", "ADMIN", true));
            stored.add(existing);

            service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "PM", true, false)));

            assertThat(existing.getId()).isEqualTo(7);
            assertThat(existing.isRequiresReason()).isTrue();
        }

        @Test
        @DisplayName("a row absent from the body is deactivated, not deleted")
        void absentRowIsDeactivated() {
            WorkflowTransition dropped = row(2, "NEW", "IN_PROGRESS", "QA", true);
            stored.add(row(1, null, "NEW", "ADMIN", true));
            stored.add(dropped);

            service.replace(matrix(cell(null, "NEW", "ADMIN")));

            assertThat(dropped.isActive()).isFalse();
            verify(transitions, never()).delete(any());
            verify(transitions, never()).deleteAll(any());
        }

        @Test
        @DisplayName("a previously cleared cell is reactivated when it comes back")
        void clearedCellIsReactivated() {
            WorkflowTransition dormant = row(2, "NEW", "IN_PROGRESS", "QA", false);
            stored.add(row(1, null, "NEW", "ADMIN", true));
            stored.add(dormant);

            service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "QA")));

            assertThat(dormant.isActive()).isTrue();
            assertThat(dormant.getId()).isEqualTo(2);
        }

        @Test
        @DisplayName("a cell not in the table yet is inserted")
        void newCellIsInserted() {
            service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "DEVELOPER")));

            assertThat(stored).hasSize(2);
            assertThat(stored).extracting(WorkflowTransition::getRoleCode)
                    .containsExactlyInAnyOrder("ADMIN", "DEVELOPER");
        }

        @Test
        @DisplayName("omitted flags default to false rather than keeping the stored value")
        void flagsAreNotPartial() {
            WorkflowTransition existing = row(2, "NEW", "IN_PROGRESS", "PM", true);
            existing.setRequiresReason(true);
            existing.setRequiresEffort(true);
            stored.add(row(1, null, "NEW", "ADMIN", true));
            stored.add(existing);

            service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("NEW", "IN_PROGRESS", "PM")));

            assertThat(existing.isRequiresReason()).isFalse();
            assertThat(existing.isRequiresEffort()).isFalse();
        }

        @Test
        @DisplayName("codes are upper-cased before they are matched or stored")
        void codesAreNormalised() {
            service.replace(matrix(cell(null, "new", "admin")));

            assertThat(stored).singleElement()
                    .extracting(WorkflowTransition::getToStatus, WorkflowTransition::getRoleCode)
                    .containsExactly("NEW", "ADMIN");
        }

        /**
         * G-3 is data, and this is the test that says so. The service does not
         * refuse a Developer close — refusing it here would put back into code the
         * one decision `workflow_transitions` exists to keep out of it.
         */
        @Test
        @DisplayName("granting a Developer RESOLVED -> CLOSED is allowed — G-3 is data, not code")
        void governanceIsNotHardCoded() {
            assertThatCode(() -> service.replace(matrix(
                    cell(null, "NEW", "ADMIN"),
                    cell("RESOLVED", "CLOSED", "DEVELOPER"))))
                    .doesNotThrowAnyException();

            assertThat(stored).extracting(WorkflowTransition::getRoleCode)
                    .contains("DEVELOPER");
        }

        @Test
        @DisplayName("the response is the whole matrix as it now stands, not just what was sent")
        void responseIsTheWholeMatrix() {
            stored.add(row(1, null, "NEW", "ADMIN", true));
            stored.add(row(2, "NEW", "IN_PROGRESS", "QA", true));

            assertThat(service.replace(matrix(cell(null, "NEW", "ADMIN"))))
                    .hasSize(2)
                    .extracting(StatusDtos.TransitionView::isActive)
                    .containsExactly(true, false);
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Status status(String code) {
        Status s = new Status();
        s.setCode(code);
        s.setActive(true);
        return s;
    }

    private static Status retired(String code) {
        Status s = status(code);
        s.setActive(false);
        return s;
    }

    private static Role role(String code) {
        Role r = new Role();
        r.setCode(code);
        return r;
    }

    private static WorkflowTransition row(Integer id, String from, String to,
                                          String roleCode, boolean active) {
        WorkflowTransition t = new WorkflowTransition();
        t.setId(id);
        t.setFromStatus(from);
        t.setToStatus(to);
        t.setRoleCode(roleCode);
        t.setActive(active);
        return t;
    }

    private static StatusDtos.TransitionWrite cell(String from, String to, String role) {
        return new StatusDtos.TransitionWrite(from, to, role, null, null);
    }

    private static StatusDtos.TransitionWrite cell(String from, String to, String role,
                                                   Boolean reason, Boolean effort) {
        return new StatusDtos.TransitionWrite(from, to, role, reason, effort);
    }

    private static StatusDtos.TransitionMatrixWrite matrix(StatusDtos.TransitionWrite... cells) {
        return new StatusDtos.TransitionMatrixWrite(List.of(cells));
    }
}
