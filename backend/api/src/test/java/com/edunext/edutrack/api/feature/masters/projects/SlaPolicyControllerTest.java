package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-018 · what the controller decides before delegating — which here is almost
 * entirely the precondition, because the {@code PUT} is a wholesale replace and
 * the {@code ETag} is the only thing standing between two administrators and a
 * silently erased matrix.
 *
 * <p>Plain construction, as every other controller test in this feature does.
 * {@code MasterRoutesTest} covers the one thing plain construction cannot see —
 * where the class is mounted — which is the gap that let B-023 ship nine
 * unreachable operations, and which this task's own contract had already fallen
 * into: {@code getSlaPolicies} has been in the spec and in the generated client
 * since D-001 with no server behind it.
 */
class SlaPolicyControllerTest {

    private static final long PROJECT = 7L;

    private SlaMatrixService service;
    private SlaPolicyController controller;

    @BeforeEach
    void setUp() {
        service = mock(SlaMatrixService.class);
        controller = new SlaPolicyController(service);
        when(service.matrix(PROJECT)).thenReturn(List.of(cell()));
    }

    // ------------------------------------------------------------------
    // the read
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the matrix read")
    class Read {

        @Test
        @DisplayName("is wrapped in { data } with no meta — the signal that the grid is complete")
        void hasNoMeta() throws Exception {
            String json = Jackson2ObjectMapperBuilder.json().build()
                    .writeValueAsString(controller.matrix(PROJECT).getBody());

            assertThat(json).contains("\"data\"").doesNotContain("\"meta\"");
        }

        @Test
        @DisplayName("carries an ETag, because the PUT requires one and nothing else emits it")
        void carriesAnEtag() {
            // The contract required If-Match on the PUT and declared an ETag
            // nowhere, so the operation was uncallable — the same gap B-016
            // closed by adding GET /projects/{projectId}. check-conventions.py
            // would not have caught it: its detail-read rule fires on paths
            // ending in a path variable and this one ends in a collection.
            assertThat(controller.matrix(PROJECT).getHeaders().getETag()).isNotBlank();
        }

        @Test
        @DisplayName("the tag is content-derived, so re-saving identical figures does not invalidate it")
        void theTagIsContentDerived() {
            String first = controller.matrix(PROJECT).getHeaders().getETag();

            when(service.matrix(PROJECT)).thenReturn(List.of(cell()));

            assertThat(controller.matrix(PROJECT).getHeaders().getETag()).isEqualTo(first);
        }

        @Test
        @DisplayName("the tag moves when an inherited figure moves, not only when this project changes")
        void inheritedChangesMoveTheTag() {
            // Correct rather than a defect. The administrator was shown
            // inherited figures and is deciding which of them to override; if
            // those moved underneath them, the decision was made against numbers
            // that are no longer true and a reload is the right cost.
            String before = controller.matrix(PROJECT).getHeaders().getETag();

            when(service.matrix(PROJECT)).thenReturn(List.of(
                    inherited(BigDecimal.valueOf(99))));

            assertThat(controller.matrix(PROJECT).getHeaders().getETag()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("passes the 404 straight through rather than answering an empty grid")
        void anUnknownProjectIsNotAnEmptyGrid() {
            when(service.matrix(anyLong())).thenThrow(new SlaMatrixService.NoSuchProjectException());

            assertThatThrownBy(() -> controller.matrix(999L))
                    .isInstanceOf(SlaMatrixService.NoSuchProjectException.class);
        }
    }

    // ------------------------------------------------------------------
    // the precondition
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the precondition")
    class Precondition {

        @Test
        @DisplayName("a write without If-Match is 428, not allowed through")
        void missingIfMatchIs428() {
            // Treating a missing precondition as "no conflict" means the guard
            // protects only the clients that already opted in — the set that
            // needed it least. It matters more here than anywhere else in the
            // feature: this is a wholesale replace, so a stale tab's save does
            // not merge with another administrator's, it erases it.
            assertThatThrownBy(() -> controller.replace(PROJECT, null, List.of(write())))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(status(e)).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

            verify(service, never()).replace(anyLong(), any());
        }

        @Test
        @DisplayName("a blank If-Match is treated as absent, not as a match")
        void blankIfMatchIs428() {
            assertThatThrownBy(() -> controller.replace(PROJECT, "  ", List.of(write())))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(status(e)).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        }

        @Test
        @DisplayName("a stale If-Match is 412 and writes nothing")
        void staleIfMatchIs412() {
            assertThatThrownBy(() -> controller.replace(PROJECT, "\"deadbeef\"", List.of(write())))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(status(e)).isEqualTo(HttpStatus.PRECONDITION_FAILED));

            verify(service, never()).replace(anyLong(), any());
        }

        @Test
        @DisplayName("the tag the read emitted is accepted, quotes and weak prefix included")
        void theReadsTagIsAccepted() {
            String etag = controller.matrix(PROJECT).getHeaders().getETag();
            when(service.replace(anyLong(), any())).thenReturn(List.of(cell()));

            assertThat(controller.replace(PROJECT, etag, List.of(write())).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(controller.replace(PROJECT, "W/" + etag, List.of(write())).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("* matches anything, per RFC 9110")
        void starMatches() {
            when(service.replace(anyLong(), any())).thenReturn(List.of(cell()));

            assertThat(controller.replace(PROJECT, "*", List.of(write())).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("the 404 comes before the 428 — a tag cannot be fetched from a URL that 404s")
        void unknownProjectBeatsMissingPrecondition() {
            when(service.matrix(999L)).thenThrow(new SlaMatrixService.NoSuchProjectException());

            assertThatThrownBy(() -> controller.replace(999L, null, List.of(write())))
                    .isInstanceOf(SlaMatrixService.NoSuchProjectException.class);
        }
    }

    // ------------------------------------------------------------------
    // the write
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the replace")
    class Replace {

        @Test
        @DisplayName("answers with the newly resolved grid and its new tag")
        void answersWithTheNewGrid() {
            String before = controller.matrix(PROJECT).getHeaders().getETag();
            when(service.replace(anyLong(), any())).thenReturn(List.of(inherited(BigDecimal.TEN)));

            ResponseEntity<SlaPolicyDtos.SlaMatrixResponse> response =
                    controller.replace(PROJECT, "*", List.of(write()));

            // The body is what was saved, not what was sent: the two differ
            // wherever a cell was cleared and now inherits.
            assertThat(response.getBody().data()).allMatch(c -> !c.isOverride());
            assertThat(response.getHeaders().getETag()).isNotBlank().isNotEqualTo(before);
        }

        @Test
        @DisplayName("an empty body reaches the service — clearing every override is a real request")
        void anEmptyBodyIsForwarded() {
            when(service.replace(anyLong(), any())).thenReturn(List.of());

            controller.replace(PROJECT, "*", List.of());

            verify(service).replace(PROJECT, List.of());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static HttpStatus status(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    private static SlaPolicyDtos.SlaCell cell() {
        return new SlaPolicyDtos.SlaCell(2, "PROD_BUG", "Production Bug", "HIGH",
                BigDecimal.ONE, BigDecimal.valueOf(6), true, false,
                SlaPolicyDtos.Source.PROJECT_TASK_TYPE, true);
    }

    private static SlaPolicyDtos.SlaCell inherited(BigDecimal resolutionHrs) {
        return new SlaPolicyDtos.SlaCell(2, "PROD_BUG", "Production Bug", "HIGH",
                null, resolutionHrs, true, false,
                SlaPolicyDtos.Source.ORG_DEFAULT, false);
    }

    private static SlaPolicyDtos.SlaPolicyWrite write() {
        return new SlaPolicyDtos.SlaPolicyWrite(2, "HIGH", BigDecimal.ONE, BigDecimal.valueOf(6), true, false);
    }
}
