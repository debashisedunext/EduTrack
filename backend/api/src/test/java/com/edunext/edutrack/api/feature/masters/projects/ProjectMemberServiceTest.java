package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-017 · the Team tab's four rules, none of which the schema encodes.
 *
 * <p>{@code ProjectTeamIT} proves the same decisions against real MySQL, where
 * {@code uq_project_members} and {@code ck_project_members_allocation} have
 * opinions of their own — and where the interaction with B-011's writer of the
 * same table can actually happen. This proves the decisions themselves, without
 * Docker.
 */
class ProjectMemberServiceTest {

    private static final long PROJECT = 7L;
    private static final long USER = 42L;

    private ProjectTeamRepository repository;
    private ProjectMemberService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectTeamRepository.class);
        service = new ProjectMemberService(repository);

        when(repository.projectExists(anyLong())).thenReturn(true);
        when(repository.findCandidate(anyLong())).thenReturn(
                Optional.of(new ProjectTeamRepository.Candidate(USER, "Priya Sharma", "DEVELOPER", true)));
        when(repository.membershipState(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(repository.findMember(PROJECT, USER)).thenReturn(Optional.of(member("QA", 40, 0)));
    }

    // ------------------------------------------------------------------
    // the project comes first
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an unknown project is 404 on every operation")
    class ProjectFirst {

        @BeforeEach
        void noSuchProject() {
            when(repository.projectExists(anyLong())).thenReturn(false);
        }

        @Test
        @DisplayName("and the check runs before anything looks at the resource")
        void theProjectIsCheckedFirst() {
            // Ordering, not pedantry: answering "that resource is deactivated"
            // for a project that does not exist tells the caller about a
            // resource on a project they cannot see. This is the only operation
            // set in the feature whose path carries two ids.
            assertThatThrownBy(() -> service.add(PROJECT, write(USER, "QA", 40)))
                    .isInstanceOf(ProjectMemberService.NoSuchProjectException.class);

            verify(repository, never()).findCandidate(anyLong());
        }

        @Test
        @DisplayName("including the read and the remove")
        void everyOperationChecksIt() {
            assertThatThrownBy(() -> service.roster(PROJECT))
                    .isInstanceOf(ProjectMemberService.NoSuchProjectException.class);
            assertThatThrownBy(() -> service.remove(PROJECT, USER))
                    .isInstanceOf(ProjectMemberService.NoSuchProjectException.class);
            assertThatThrownBy(() -> service.update(PROJECT, USER, patch(null, null)))
                    .isInstanceOf(ProjectMemberService.NoSuchProjectException.class);
        }
    }

    // ------------------------------------------------------------------
    // add
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("an unknown resource is a 400 keyed on the field")
        void unknownResourceIsRefused() {
            when(repository.findCandidate(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(PROJECT, write(999L, null, null)))
                    .isInstanceOf(ProjectMemberService.MemberValidationException.class)
                    .hasMessage("no such resource");
        }

        @Test
        @DisplayName("a deactivated resource cannot be put on a team")
        void deactivatedResourceIsRefused() {
            // Same rule and same reason as the project manager (B-016): a
            // deactivated resource is somebody who has left, and putting them on
            // a team means the auto-assign rule, the assignment picker and every
            // capacity figure count somebody who will never pick the work up.
            when(repository.findCandidate(anyLong())).thenReturn(
                    Optional.of(new ProjectTeamRepository.Candidate(USER, "Amit Rao", "DEVELOPER", false)));

            assertThatThrownBy(() -> service.add(PROJECT, write(USER, null, null)))
                    .isInstanceOf(ProjectMemberService.MemberValidationException.class)
                    .hasMessageContaining("deactivated");
        }

        @Test
        @DisplayName("the resource's global role is deliberately not checked")
        void anyRoleMayHoldAnyProjectRole() {
            // A Developer mapped as QA here is the case role_in_project exists
            // for (B-011), so there is nothing to reconcile — and a hardcoded
            // role set is what B-015 removed from ResourceController.
            when(repository.findCandidate(anyLong())).thenReturn(
                    Optional.of(new ProjectTeamRepository.Candidate(USER, "Priya Sharma", "SUPPORT", true)));

            service.add(PROJECT, write(USER, "PM", 100));

            verify(repository).upsert(PROJECT, USER, "PM", 100);
        }

        @Test
        @DisplayName("somebody already on the team is a 409, not a silent overwrite")
        void alreadyOnTheTeamIsRefused() {
            when(repository.membershipState(PROJECT, USER)).thenReturn(Optional.of(true));

            assertThatThrownBy(() -> service.add(PROJECT, write(USER, "QA", 40)))
                    .isInstanceOf(ProjectMemberService.AlreadyOnTeamException.class);

            // The request has nothing to do, and quietly rewriting their role
            // and allocation from an "add" dialog is not what the click meant.
            verify(repository, never()).upsert(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("somebody who was removed comes back rather than conflicting")
        void aRemovedMemberIsReactivated() {
            // The rule that makes removal reversible. A removal deactivates the
            // row, so uq_project_members keeps it forever; a 409 here would make
            // every removal permanent, and that reads as a bug in the remove
            // button rather than as a rule.
            when(repository.membershipState(PROJECT, USER)).thenReturn(Optional.of(false));

            service.add(PROJECT, write(USER, "QA", 40));

            verify(repository).upsert(PROJECT, USER, "QA", 40);
        }

        @Test
        @DisplayName("the project role is normalised, and blank means 'same as their global role'")
        void roleIsNormalised() {
            service.add(PROJECT, write(USER, " developer ", null));
            verify(repository).upsert(PROJECT, USER, "DEVELOPER", null);

            service.add(PROJECT, write(USER, "  ", null));
            verify(repository).upsert(PROJECT, USER, null, null);
        }

        @Test
        @DisplayName("an omitted allocation stays null — it is never defaulted to 100")
        void allocationIsNotDefaulted() {
            // The contract carried `default: 100` and it was dropped. A member
            // added without a stated allocation reads as "not stated", which is
            // the honest answer; 100 would be a claim nobody made, and B-061's
            // capacity report could not tell it from a real one.
            service.add(PROJECT, write(USER, "QA", null));

            ArgumentCaptor<Integer> allocation = ArgumentCaptor.forClass(Integer.class);
            verify(repository).upsert(eq(PROJECT), eq(USER), any(), allocation.capture());
            assertThat(allocation.getValue()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update — omitted keeps, explicit null clears")
    class Update {

        @Test
        @DisplayName("an omitted field keeps its stored value")
        void omittedKeeps() {
            service.update(PROJECT, USER, patch(null, Optional.of(80)));

            // projectRole was not sent, so it stays QA rather than becoming null.
            verify(repository).update(PROJECT, USER, "QA", 80);
        }

        @Test
        @DisplayName("an explicit null clears — the only way back to 'same as their global role'")
        void explicitNullClears() {
            // With boxed types this state would be write-once: settable on the
            // add and unreachable afterwards.
            service.update(PROJECT, USER, patch(Optional.empty(), null));

            verify(repository).update(PROJECT, USER, null, 40);
        }

        @Test
        @DisplayName("an explicit null clears an allocation entered by mistake")
        void allocationCanReturnToUnstated() {
            service.update(PROJECT, USER, patch(null, Optional.empty()));

            verify(repository).update(PROJECT, USER, "QA", null);
        }

        @Test
        @DisplayName("zero is a real allocation, not an absent one")
        void zeroIsNotNull() {
            // "No capacity committed" and "not stated" are different facts, and
            // the tab renders them differently. This is why the repository reads
            // the column with getObject rather than getInt.
            service.update(PROJECT, USER, patch(null, Optional.of(0)));

            verify(repository).update(PROJECT, USER, "QA", 0);
        }

        @Test
        @DisplayName("a patch against a non-member is empty, which the controller makes a 404")
        void nonMemberIsNotFound() {
            when(repository.findMember(PROJECT, USER)).thenReturn(Optional.empty());

            assertThat(service.update(PROJECT, USER, patch(Optional.of("QA"), null))).isEmpty();
            verify(repository, never()).update(anyLong(), anyLong(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // remove
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("a member holding open tickets here is refused, with the count")
        void openTicketsBlockRemoval() {
            when(repository.findMember(PROJECT, USER)).thenReturn(Optional.of(member("QA", 40, 3)));

            assertThatThrownBy(() -> service.remove(PROJECT, USER))
                    .isInstanceOf(ProjectMemberService.OpenTicketsException.class)
                    .hasMessageContaining("3 open tickets");

            verify(repository, never()).deactivate(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a member holding none is deactivated, never deleted")
        void removalIsDeactivation() {
            service.remove(PROJECT, USER);

            verify(repository).deactivate(PROJECT, USER);
        }

        @Test
        @DisplayName("removing somebody who is not on the team succeeds")
        void removingANonMemberConverges() {
            // It is a setter. A client retrying after a dropped response, or the
            // second half of a double-click, must not be told an error about a
            // thing that did happen — B-014's call for UNCHANGED on the resource
            // status route.
            when(repository.findMember(PROJECT, USER)).thenReturn(Optional.empty());

            service.remove(PROJECT, USER);

            verify(repository, never()).deactivate(anyLong(), anyLong());
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ProjectMemberDtos.TeamMember member(String projectRole, Integer allocation, int openTickets) {
        return new ProjectMemberDtos.TeamMember(
                USER, "Priya Sharma", "priya@example.test", "DEVELOPER",
                projectRole, allocation, true, openTickets, Instant.parse("2026-08-01T09:00:00Z"));
    }

    private static ProjectMemberDtos.TeamMemberWrite write(long userId, String role, Integer allocation) {
        return new ProjectMemberDtos.TeamMemberWrite(userId, role, allocation);
    }

    private static ProjectMemberDtos.TeamMemberPatch patch(
            Optional<String> role, Optional<Integer> allocation) {
        return new ProjectMemberDtos.TeamMemberPatch(role, allocation);
    }
}
