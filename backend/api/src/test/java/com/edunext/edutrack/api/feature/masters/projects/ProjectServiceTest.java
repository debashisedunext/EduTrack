package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-016 · the four S-10 rules, none of which the schema encodes.
 *
 * <p>{@code ProjectMasterIT} proves the same decisions against real MySQL, where
 * {@code uq_projects_code} and {@code ck_projects_status} have opinions of their
 * own. This proves the decisions themselves, without Docker.
 */
class ProjectServiceTest {

    private ProjectMasterRepository repository;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectMasterRepository.class);
        service = new ProjectService(repository);

        when(repository.codeTaken(anyString(), anyLong())).thenReturn(false);
        when(repository.findManager(anyLong())).thenReturn(
                Optional.of(new ProjectMasterRepository.ManagerCandidate(2L, "Priya Sharma", "PM", true)));
        // Stands in for AUTO_INCREMENT plus the read-back. Without it, create()
        // throws its own "vanished between insert and read" and the failure
        // looks like a bug in the service rather than a missing stub.
        when(repository.insert(any())).thenReturn(9L);
        when(repository.findDetail(9L)).thenReturn(Optional.of(detail(9L, "CRM", 0)));
    }

    // ------------------------------------------------------------------
    // isActive
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("isActive is derived, and On Hold is not Closed")
    class Derivation {

        @Test
        @DisplayName("an On Hold project is still active")
        void onHoldIsStillActive() {
            // The whole point of the derivation. Five screens send
            // ?isActive=true to fill a picker; if ON_HOLD answered false,
            // putting a project on hold would silently remove it from the
            // create-ticket form with nothing on screen saying why.
            assertThat(ProjectService.isActive("ON_HOLD")).isTrue();
        }

        @Test
        @DisplayName("only CLOSED is inactive")
        void closedIsInactive() {
            assertThat(ProjectService.isActive("ACTIVE")).isTrue();
            assertThat(ProjectService.isActive("CLOSED")).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("the code is upper-cased before uniqueness is checked, not after")
        void upperCasesBeforeTheDuplicateCheck() {
            // Checking first and upper-casing second would ask the database
            // about `crm`, get "free", and then store CRM straight into
            // uq_projects_code — a 500 for a request the check had approved.
            service.create(write("crm", "CRM Platform"));

            verify(repository).codeTaken(eq("CRM"), anyLong());

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).insert(row.capture());
            assertThat(row.getValue().projectCode()).isEqualTo("CRM");
        }

        @Test
        @DisplayName("a duplicate code is refused before anything is written")
        void duplicateCodeIsRefused() {
            when(repository.codeTaken("CRM", -1L)).thenReturn(true);

            assertThatThrownBy(() -> service.create(write("CRM", "Another CRM")))
                    .isInstanceOf(ProjectService.DuplicateCodeException.class);

            verify(repository, never()).insert(any());
        }

        @Test
        @DisplayName("status defaults to ACTIVE and the auto-assign rule to MANUAL")
        void defaultsAreTheConservativeOnes() {
            // MANUAL specifically: round-robin and least-loaded both hand live
            // work to somebody without a human deciding, and that is not a
            // behaviour a project should acquire by being created.
            service.create(write("NEW", "Greenfield"));

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).insert(row.capture());
            assertThat(row.getValue().status()).isEqualTo("ACTIVE");
            assertThat(row.getValue().autoAssignRule()).isEqualTo("MANUAL");
        }

        @Test
        @DisplayName("an unknown status is a field-keyed 400, not a 500 at the CHECK")
        void unknownStatusIsRefusedInJava() {
            ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                    "NEW", "Greenfield", null, null, 2L, null, null, null, "ARCHIVED", null);

            assertThatThrownBy(() -> service.create(write))
                    .isInstanceOf(ProjectService.ProjectValidationException.class)
                    .hasMessageContaining("ACTIVE, ON_HOLD, CLOSED");

            verify(repository, never()).insert(any());
        }

        @Test
        @DisplayName("a blank description is stored as NULL, not as an empty string")
        void blankTextIsNulled() {
            // A cleared input posts "". Storing it makes "no description" two
            // values that every reader then has to handle.
            ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                    "NEW", "Greenfield", "  ", "   ", 2L, null, null, null, null, null);

            service.create(write);

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).insert(row.capture());
            assertThat(row.getValue().description()).isNull();
            assertThat(row.getValue().clientName()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // the manager
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the project manager")
    class Manager {

        @Test
        @DisplayName("an unknown manager is a 400 keyed on the picker")
        void unknownManagerIsRefused() {
            when(repository.findManager(404L)).thenReturn(Optional.empty());

            ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                    "NEW", "Greenfield", null, null, 404L, null, null, null, null, null);

            assertThatThrownBy(() -> service.create(write))
                    .isInstanceOf(ProjectService.ProjectValidationException.class)
                    .extracting(e -> ((ProjectService.ProjectValidationException) e).field())
                    .isEqualTo("projectManagerId");
        }

        @Test
        @DisplayName("a deactivated resource cannot be named project manager")
        void inactiveManagerIsRefused() {
            // This is who Stream D's SLA scanners escalate to. Naming somebody
            // who has left sends every escalation on the project to a mailbox
            // nobody reads.
            when(repository.findManager(7L)).thenReturn(Optional.of(
                    new ProjectMasterRepository.ManagerCandidate(7L, "Departed Person", "PM", false)));

            ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                    "NEW", "Greenfield", null, null, 7L, null, null, null, null, null);

            assertThatThrownBy(() -> service.create(write))
                    .isInstanceOf(ProjectService.ProjectValidationException.class)
                    .hasMessageContaining("deactivated");
        }

        @Test
        @DisplayName("a Developer may be named project manager — the role is not checked")
        void theRoleIsDeliberatelyNotChecked() {
            // Restricting this to PM/ADMIN is the obvious rule and the wrong
            // one: a support-led project has a legitimate reason to name its
            // Support lead, and a hardcoded role set is what B-015 removed from
            // ResourceController — the first custom role an Admin creates would
            // be refused here by a screen that had just granted it everything.
            when(repository.findManager(5L)).thenReturn(Optional.of(
                    new ProjectMasterRepository.ManagerCandidate(5L, "Ravi Kumar", "DEVELOPER", true)));

            ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                    "NEW", "Greenfield", null, null, 5L, null, null, null, null, null);

            service.create(write);

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).insert(row.capture());
            assertThat(row.getValue().managerId()).isEqualTo(5L);
        }
    }

    // ------------------------------------------------------------------
    // dates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a target end date before the start date is a 400 on endDate")
    void endDateCannotPrecedeStart() {
        // Two fields, so Bean Validation cannot say it and the DB does not.
        ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                "NEW", "Greenfield", null, null, 2L,
                null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1), null, null);

        assertThatThrownBy(() -> service.create(write))
                .isInstanceOf(ProjectService.ProjectValidationException.class)
                .extracting(e -> ((ProjectService.ProjectValidationException) e).field())
                .isEqualTo("endDate");
    }

    @Test
    @DisplayName("start and end on the same day is fine")
    void aOneDayProjectIsLegal() {
        LocalDate day = LocalDate.of(2026, 8, 13);
        ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                "NEW", "Greenfield", null, null, 2L, null, day, day, null, null);

        service.create(write);

        verify(repository).insert(any());
    }

    // ------------------------------------------------------------------
    // the immutability rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("projectCode immutability")
    class ImmutableCode {

        @Test
        @DisplayName("changing the code is refused once a ticket ID has been issued")
        void refusedAfterTheFirstTicketId() {
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 347)));

            assertThatThrownBy(() -> service.update(1L, patchCode("NEWCRM")))
                    .isInstanceOf(ProjectService.ImmutableCodeException.class)
                    .hasMessageContaining("347");

            verify(repository, never()).update(anyLong(), any());
        }

        @Test
        @DisplayName("resending the same code on a live project is a no-op, not a 409")
        void resendingTheSameCodeIsFine() {
            // S-10 submits the whole form on every save, so any other reading
            // would make every edit to a live project a 409 — the mirror of the
            // `u.id <> ?` that B-013 documents on the resource form.
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 347)));

            service.update(1L, patchCode("CRM"));

            verify(repository).update(eq(1L), any());
        }

        @Test
        @DisplayName("the same code in different case is still the same code")
        void caseDoesNotMakeItARename() {
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 347)));

            service.update(1L, patchCode("crm"));

            verify(repository).update(eq(1L), any());
        }

        @Test
        @DisplayName("the code is still editable while the project has issued none")
        void editableBeforeTheFirstTicketId() {
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 0)));

            service.update(1L, patchCode("BILLING"));

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).update(eq(1L), row.capture());
            assertThat(row.getValue().projectCode()).isEqualTo("BILLING");
        }

        @Test
        @DisplayName("a rename to a code another project holds is a duplicate, not an immutability refusal")
        void renameCollisionIsADuplicate() {
            // Two different 409s with two different types: one is fixed by
            // choosing another code, the other cannot be fixed at all.
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 0)));
            when(repository.codeTaken("PAY", 1L)).thenReturn(true);

            assertThatThrownBy(() -> service.update(1L, patchCode("PAY")))
                    .isInstanceOf(ProjectService.DuplicateCodeException.class);
        }

        @Test
        @DisplayName("the counter, not the ticket table, is what closes the code")
        void theCounterIsTheTest() {
            // ticket_seq counts codes ISSUED. A ticket created and later
            // hard-deleted still had CRM-26-00347 sent to a client — counting
            // live rows would quietly re-open the prefix for editing.
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 1)));

            assertThatThrownBy(() -> service.update(1L, patchCode("GONE")))
                    .isInstanceOf(ProjectService.ImmutableCodeException.class)
                    .hasMessageContaining("1 ticket ID");
        }
    }

    // ------------------------------------------------------------------
    // patch semantics
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an omitted field keeps its stored value")
    class PatchSemantics {

        @Test
        @DisplayName("an empty patch rewrites the row unchanged")
        void emptyPatchChangesNothing() {
            when(repository.findDetail(1L)).thenReturn(Optional.of(detail(1L, "CRM", 347)));

            service.update(1L, new ProjectDtos.ProjectPatch(
                    null, null, null, null, null, null, null, null, null, null));

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).update(eq(1L), row.capture());
            assertThat(row.getValue().projectCode()).isEqualTo("CRM");
            assertThat(row.getValue().name()).isEqualTo("Client CRM Platform");
            assertThat(row.getValue().status()).isEqualTo("ACTIVE");
            assertThat(row.getValue().managerId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a project with no manager stays editable")
        void aManagerlessProjectIsStillEditable() {
            // Demanding a manager on every save would make such a project
            // uneditable until somebody supplied one — including for the edit
            // that was going to supply it, since the form loads an empty picker.
            ProjectDtos.ProjectDetail managerless = new ProjectDtos.ProjectDetail(
                    1L, "OLD", "Legacy", null, null, null, null, null, null,
                    "ACTIVE", true, "MANUAL", 12);
            when(repository.findDetail(1L)).thenReturn(Optional.of(managerless));

            service.update(1L, new ProjectDtos.ProjectPatch(
                    null, "Legacy renamed", null, null, null, null, null, null, null, null));

            ArgumentCaptor<ProjectMasterRepository.ProjectRow> row =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectRow.class);
            verify(repository).update(eq(1L), row.capture());
            assertThat(row.getValue().managerId()).isNull();
        }

        @Test
        @DisplayName("a project that does not exist is empty, not an exception")
        void unknownProjectIsEmpty() {
            // The controller turns this into a 404, never a 403.
            when(repository.findDetail(404L)).thenReturn(Optional.empty());

            assertThat(service.update(404L, patchCode("X"))).isEmpty();
            verify(repository, never()).update(anyLong(), any());
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ProjectDtos.ProjectWrite write(String code, String name) {
        return new ProjectDtos.ProjectWrite(code, name, null, null, 2L, null, null, null, null, null);
    }

    private static ProjectDtos.ProjectPatch patchCode(String code) {
        return new ProjectDtos.ProjectPatch(code, null, null, null, null, null, null, null, null, null);
    }

    private static ProjectDtos.ProjectDetail detail(long id, String code, long ticketsIssued) {
        return new ProjectDtos.ProjectDetail(
                id, code, "Client CRM Platform", null, null,
                new ProjectDtos.UserRef(2L, "Priya Sharma", "PM"),
                "#4F46E5", null, null, "ACTIVE", true, "MANUAL", ticketsIssued);
    }
}
