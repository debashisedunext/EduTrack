package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-017 · what the controller decides before delegating, plus the two claims
 * about the shape of the responses that no other test makes.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as every other
 * controller test in this feature does. {@code MasterRoutesTest} covers the one
 * thing plain construction cannot see — where the class is mounted — which is
 * the gap that let B-023 ship nine unreachable operations and B-014 find a route
 * that had been declared, mocked and never mounted.
 */
class ProjectMemberControllerTest {

    private static final long PROJECT = 7L;
    private static final long USER = 42L;

    private ProjectMemberService service;
    private ProjectMemberController controller;

    @BeforeEach
    void setUp() {
        service = mock(ProjectMemberService.class);
        controller = new ProjectMemberController(service);
    }

    // ------------------------------------------------------------------
    // the roster
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the roster")
    class Roster {

        @Test
        @DisplayName("is wrapped in { data } with no meta — the signal that the list is complete")
        void hasNoMeta() throws Exception {
            // CONVENTIONS.md §6: an unpaginated collection returns data with no
            // meta, and that absence is how a client knows there is no more.
            // The exemption is registered in check-conventions.py with a reason.
            when(service.roster(PROJECT)).thenReturn(List.of(member("QA", 40, 0)));

            // Built the way Spring Boot builds the application's, so `addedAt`
            // serialises through the same JSR-310 module the server registers.
            String json = Jackson2ObjectMapperBuilder.json().build()
                    .writeValueAsString(controller.list(PROJECT));

            assertThat(json).contains("\"data\"").doesNotContain("\"meta\"");
        }

        @Test
        @DisplayName("passes the 404 straight through rather than answering an empty team")
        void anUnknownProjectIsNotAnEmptyTeam() {
            // An empty array for a project that does not exist is a plausible
            // and wrong answer: the tab would render "nobody on this team" for a
            // URL somebody mistyped.
            when(service.roster(anyLong()))
                    .thenThrow(new ProjectMemberService.NoSuchProjectException());

            assertThatThrownBy(() -> controller.list(404L))
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
        @DisplayName("answers 201 with the created member, not an empty body")
        void answersCreatedWithABody() {
            // The contract's first draft was `'201': { description: Added. }`
            // with no body at all, against CONVENTIONS.md §2 — which would have
            // left the tab guessing what it had just created and re-fetching to
            // find out.
            when(service.add(anyLong(), any())).thenReturn(member("QA", 40, 0));

            ResponseEntity<ProjectMemberDtos.TeamMemberResponse> response =
                    controller.add(PROJECT, write());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().data().userId()).isEqualTo(USER);
        }

        @Test
        @DisplayName("answers 201 for a reactivation too, not 200")
        void reactivationIsAlso201() {
            // Two status codes would make a client branch on whether the
            // organisation happened to have removed this person from this
            // project at some point in the past — not a distinction the caller
            // asked about or can act on.
            when(service.add(anyLong(), any())).thenReturn(member(null, null, 0));

            assertThat(controller.add(PROJECT, write()).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    // ------------------------------------------------------------------
    // update and remove
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("an empty result from the service is a 404")
        void nonMemberIs404() {
            when(service.update(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(PROJECT, USER, patch()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("takes no If-Match — a registered exemption, not an omission")
        void takesNoPrecondition() throws Exception {
            // If it grew one, the tag would have to come from
            // listProjectMembers, which carries no ETag. The signature is the
            // assertion: three parameters, none of them a header.
            assertThat(ProjectMemberController.class
                    .getDeclaredMethod("update", long.class, long.class,
                            ProjectMemberDtos.TeamMemberPatch.class))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("answers 204, and delegates the refusal rather than deciding it")
        void answers204() {
            assertThat(controller.remove(PROJECT, USER).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            verify(service).remove(PROJECT, USER);
        }

        @Test
        @DisplayName("answers 204 for somebody who was not on the team")
        void removingANonMemberConverges() {
            // The service returns quietly; the controller must not turn that
            // into a 404. A client retrying after a dropped response has to
            // converge.
            assertThat(controller.remove(PROJECT, 999L).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
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

    private static ProjectMemberDtos.TeamMemberWrite write() {
        return new ProjectMemberDtos.TeamMemberWrite(USER, "QA", 40);
    }

    private static ProjectMemberDtos.TeamMemberPatch patch() {
        return new ProjectMemberDtos.TeamMemberPatch(Optional.of("QA"), null);
    }
}
