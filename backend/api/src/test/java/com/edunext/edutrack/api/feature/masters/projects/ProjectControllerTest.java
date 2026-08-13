package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-016 · what the controller decides before delegating — cursor paging, the
 * {@code If-Match} precondition and the 404 that comes before it.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code RoleControllerTest} and {@code ResourceControllerTest} both do:
 * everything asserted here is method-level, and {@code MasterRoutesTest} covers
 * the one thing plain construction cannot see, which is where the class is
 * mounted.
 */
class ProjectControllerTest {

    private ProjectService service;
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        service = mock(ProjectService.class);
        controller = new ProjectController(service);
    }

    // ------------------------------------------------------------------
    // paging
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("cursor pagination")
    class Paging {

        @Test
        @DisplayName("one row over the page size is fetched, and dropped")
        void fetchesOneExtraToAnswerHasMore() {
            // That extra row is how hasMore is answered without a second
            // COUNT(*) over a table being written to — and the count and the
            // page would disagree anyway.
            when(service.page(any(), any(), anyInt())).thenReturn(rows(11));

            ProjectDtos.ProjectListResponse response =
                    controller.list(null, 10, null, null, null, null);

            verify(service).page(any(), any(), org.mockito.ArgumentMatchers.eq(11));
            assertThat(response.data()).hasSize(10);
            assertThat(response.meta().hasMore()).isTrue();
            assertThat(response.meta().nextCursor()).isNotNull();
        }

        @Test
        @DisplayName("a short page carries no cursor at all")
        void aFinalPageHasNoCursor() {
            // A non-null nextCursor on the last page makes a client fetch an
            // empty page to discover it was finished.
            when(service.page(any(), any(), anyInt())).thenReturn(rows(3));

            ProjectDtos.ProjectListResponse response =
                    controller.list(null, 10, null, null, null, null);

            assertThat(response.data()).hasSize(3);
            assertThat(response.meta().hasMore()).isFalse();
            assertThat(response.meta().nextCursor()).isNull();
        }

        @Test
        @DisplayName("the cursor resumes from the last row of the page, not the extra one")
        void theCursorPointsAtTheLastReturnedRow() {
            // Encoding the dropped row would skip it on the next page — the
            // exact defect keyset paging exists to prevent.
            when(service.page(any(), any(), anyInt())).thenReturn(rows(11));

            ProjectDtos.ProjectListResponse response =
                    controller.list(null, 10, null, null, null, null);

            ProjectCursor decoded = ProjectCursor.decode(response.meta().nextCursor());
            assertThat(decoded).isNotNull();
            assertThat(decoded.id()).isEqualTo(response.data().get(9).id());
        }

        @Test
        @DisplayName("limit is clamped to 200 and defaults to 50")
        void limitIsBounded() {
            // CONVENTIONS.md §6.
            when(service.page(any(), any(), anyInt())).thenReturn(List.of());

            controller.list(null, null, null, null, null, null);
            verify(service).page(any(), any(), org.mockito.ArgumentMatchers.eq(51));

            controller.list(null, 5000, null, null, null, null);
            verify(service).page(any(), any(), org.mockito.ArgumentMatchers.eq(201));
        }

        @Test
        @DisplayName("a malformed cursor means the first page, not a 400")
        void aStaleCursorStartsOver() {
            // A bookmarked URL carrying yesterday's cursor should show the top
            // of the list.
            when(service.page(any(), any(), anyInt())).thenReturn(List.of());

            controller.list("not-base64-at-all!!", 10, null, null, null, null);

            verify(service).page(any(), org.mockito.ArgumentMatchers.isNull(), anyInt());
        }

        @Test
        @DisplayName("?status= is upper-cased, and an unknown one is a filter, not a refusal")
        void statusFilterIsNormalisedNotValidated() {
            // A filter is a question, and "no projects are ON_HOLDD" is a true
            // answer to a mistyped one. 400 belongs on a write.
            when(service.page(any(), any(), anyInt())).thenReturn(List.of());

            controller.list(null, 10, null, "on_hold", null, null);
            controller.list(null, 10, null, "NONSENSE", null, null);

            ArgumentCaptor<ProjectMasterRepository.ProjectFilter> filter =
                    ArgumentCaptor.forClass(ProjectMasterRepository.ProjectFilter.class);
            verify(service, org.mockito.Mockito.times(2))
                    .page(filter.capture(), any(), anyInt());

            assertThat(filter.getAllValues().get(0).status()).isEqualTo("ON_HOLD");
            assertThat(filter.getAllValues().get(1).status()).isEqualTo("NONSENSE");
        }
    }

    // ------------------------------------------------------------------
    // If-Match
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("If-Match")
    class Preconditions {

        @BeforeEach
        void storedProject() {
            when(service.find(9L)).thenReturn(Optional.of(detail(9L, "CRM", 0)));
        }

        @Test
        @DisplayName("a write without If-Match is 428, not allowed through")
        void missingPreconditionIs428() {
            // Treating a missing precondition as "no conflict" means the guard
            // protects only the clients that already opted in.
            assertThatThrownBy(() -> controller.update(9L, null, patchName("Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

            verify(service, never()).update(anyLong(), any());
        }

        @Test
        @DisplayName("a blank If-Match is treated as absent")
        void blankPreconditionIs428() {
            assertThatThrownBy(() -> controller.update(9L, "   ", patchName("Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        }

        @Test
        @DisplayName("a stale If-Match is 412")
        void stalePreconditionIs412() {
            assertThatThrownBy(() -> controller.update(9L, "\"deadbeef\"", patchName("Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_FAILED));

            verify(service, never()).update(anyLong(), any());
        }

        @Test
        @DisplayName("the tag from the read is accepted back on the write")
        void theReadsTagRoundTrips() {
            // The claim the whole precondition rests on: a client that GETs and
            // immediately PATCHes must succeed.
            ResponseEntity<ProjectDtos.ProjectDetailResponse> read = controller.get(9L);
            String tag = read.getHeaders().getETag();
            assertThat(tag).isNotNull();

            when(service.update(anyLong(), any())).thenReturn(Optional.of(detail(9L, "CRM", 0)));
            controller.update(9L, tag, patchName("Renamed"));

            verify(service).update(anyLong(), any());
        }

        @Test
        @DisplayName("the 404 comes before the 428")
        void notFoundBeatsPreconditionRequired() {
            // Answering 428 for a project that does not exist sends the caller
            // to fetch a tag from a URL that will 404 as well.
            when(service.find(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(404L, null, patchName("Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a ticket allocated while the form was open invalidates the tag")
        void ticketsIssuedIsPartOfTheTag() {
            // Deliberate, not a defect: ticket_seq crossing zero is precisely
            // the event that fixes projectCode, and a save carrying a new code
            // that was legal when the form loaded must not land afterwards.
            String before = controller.get(9L).getHeaders().getETag();

            when(service.find(9L)).thenReturn(Optional.of(detail(9L, "CRM", 1)));
            String after = controller.get(9L).getHeaders().getETag();

            assertThat(after).isNotEqualTo(before);
        }
    }

    // ------------------------------------------------------------------
    // reads and creates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown project is 404, never 403")
    void unknownProjectIs404() {
        // CLAUDE.md's no-existence-leak rule.
        when(service.find(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("a create answers 201 with an ETag, so an immediate edit needs no re-read")
    void createCarriesItsTag() {
        when(service.create(any())).thenReturn(detail(9L, "NEW", 0));

        ResponseEntity<ProjectDtos.ProjectDetailResponse> created = controller.create(
                new ProjectDtos.ProjectWrite("NEW", "Greenfield", null, null, 2L,
                        null, null, null, null, null));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getETag()).isNotNull();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ProjectDtos.ProjectPatch patchName(String name) {
        return new ProjectDtos.ProjectPatch(null, name, null, null, null, null, null, null, null, null);
    }

    private static List<ProjectDtos.Project> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ProjectDtos.Project(
                        i, "P" + i, "Project " + i, null,
                        new ProjectDtos.UserRef(2L, "Priya Sharma", "PM"),
                        "#4F46E5", null, null, "ACTIVE", true, "MANUAL"))
                .toList();
    }

    private static ProjectDtos.ProjectDetail detail(long id, String code, long ticketsIssued) {
        return new ProjectDtos.ProjectDetail(
                id, code, "Client CRM Platform", null, null,
                new ProjectDtos.UserRef(2L, "Priya Sharma", "PM"),
                "#4F46E5", null, null, "ACTIVE", true, "MANUAL", ticketsIssued);
    }
}
