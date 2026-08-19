package com.edunext.edutrack.api.feature.masters.stages;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-040 · what the controller decides before delegating — the two {@code If-Match}
 * preconditions, the two {@code ETag}s they are checked against, and the 404 that
 * comes before both.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code StatusControllerTest} and {@code PriorityControllerTest} do: everything
 * asserted here is method-level, and {@code MasterRoutesTest} covers the one thing
 * plain construction cannot see, which is where the class is mounted.
 */
class StageControllerTest {

    private StageService service;
    private StageController controller;

    @BeforeEach
    void setUp() {
        service = mock(StageService.class);
        controller = new StageController(service);
    }

    private static StageDtos.StageView view(long id, String code, short seq, int position) {
        return new StageDtos.StageView(id, 1L, code, code, "DEVELOPER",
                new BigDecimal("4.00"), false, List.of(), "code-2", seq, position,
                0L, 0L, true, false, null, true);
    }

    private String listTag(List<StageDtos.StageView> data) {
        when(service.list(1L)).thenReturn(Optional.of(data));
        return controller.stages(1L).getHeaders().getETag().replace("\"", "");
    }

    // ------------------------------------------------------------------
    // 404 before anything else
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an unknown template")
    class UnknownTemplate {

        @Test
        @DisplayName("is 404 on the list, not an empty ribbon")
        void listIs404() {
            when(service.list(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.stages(7L))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("is 404 on the PATCH before the precondition is even considered")
        void patchIs404BeforePrecondition() {
            when(service.find(7L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(7L, 1L, null,
                    new StageDtos.StagePatch(null, "x", null, null, null, null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verify(service, never()).update(anyLong(), anyLong(), any());
        }
    }

    // ------------------------------------------------------------------
    // the PATCH precondition
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("If-Match on the stage PATCH")
    class StagePrecondition {

        @BeforeEach
        void stageExists() {
            when(service.find(1L, 30L)).thenReturn(Optional.of(view(30L, "DEV", (short) 30, 3)));
        }

        @Test
        @DisplayName("a missing If-Match is 428, not a silent success")
        void absentIs428() {
            assertThatThrownBy(() -> controller.update(1L, 30L, null,
                    new StageDtos.StagePatch(null, "Development", null, null, null, null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

            verify(service, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("a stale If-Match is 412 and nothing is written")
        void staleIs412() {
            assertThatThrownBy(() -> controller.update(1L, 30L, "\"deadbeef\"",
                    new StageDtos.StagePatch(null, "Development", null, null, null, null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_FAILED);

            verify(service, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("the tag from GET is accepted, quotes and all")
        void currentTagPasses() {
            String tag = controller.stage(1L, 30L).getHeaders().getETag();
            when(service.update(anyLong(), anyLong(), any()))
                    .thenReturn(view(30L, "DEV", (short) 30, 3));

            ResponseEntity<StageDtos.StageResponse> response = controller.update(
                    1L, 30L, tag,
                    new StageDtos.StagePatch(null, "Development", null, null, null, null, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(service).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("the tag moves when a usage count moves, because it decides the rename rule")
        void tagCoversTheUsageCounts() {
            String before = controller.stage(1L, 30L).getHeaders().getETag();

            when(service.find(1L, 30L)).thenReturn(Optional.of(new StageDtos.StageView(
                    30L, 1L, "DEV", "DEV", "DEVELOPER", new BigDecimal("4.00"), false,
                    List.of(), "code-2", (short) 30, 3, 1L, 0L, false, false, null, false)));
            String after = controller.stage(1L, 30L).getHeaders().getETag();

            assertThat(after).isNotEqualTo(before);
        }
    }

    // ------------------------------------------------------------------
    // the reorder precondition
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("If-Match on the reorder")
    class ReorderPrecondition {

        private final List<StageDtos.StageView> ribbon = List.of(
                view(10L, "INTAKE", (short) 10, 1),
                view(20L, "TRIAGE", (short) 20, 2));

        @Test
        @DisplayName("a missing If-Match is 428")
        void absentIs428() {
            when(service.list(1L)).thenReturn(Optional.of(ribbon));

            assertThatThrownBy(() -> controller.reorder(1L, null,
                    new StageDtos.StageOrder(List.of(20L, 10L))))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

            verify(service, never()).reorder(anyLong(), anyList());
        }

        @Test
        @DisplayName("a stale If-Match is 412 — the losing order would otherwise just reappear")
        void staleIs412() {
            when(service.list(1L)).thenReturn(Optional.of(ribbon));

            assertThatThrownBy(() -> controller.reorder(1L, "\"stale\"",
                    new StageDtos.StageOrder(List.of(20L, 10L))))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_FAILED);

            verify(service, never()).reorder(anyLong(), anyList());
        }

        @Test
        @DisplayName("the tag from listStages is accepted")
        void currentTagPasses() {
            String tag = listTag(ribbon);
            when(service.reorder(anyLong(), anyList())).thenReturn(ribbon);

            ResponseEntity<StageDtos.StageListResponse> response = controller.reorder(
                    1L, tag, new StageDtos.StageOrder(List.of(20L, 10L)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(service).reorder(1L, List.of(20L, 10L));
        }

        @Test
        @DisplayName("the collection tag moves when one stage is edited, not only when order changes")
        void tagCoversEveryRowsContent() {
            String before = listTag(ribbon);

            String after = listTag(List.of(
                    view(10L, "INTAKE", (short) 10, 1),
                    new StageDtos.StageView(20L, 1L, "TRIAGE", "Triage & Planning", "PM",
                            null, false, List.of(), null, (short) 20, 2, 0L, 0L, true,
                            false, null, true)));

            assertThat(after).isNotEqualTo(before);
        }

        @Test
        @DisplayName("an empty stageIds is 400 from the controller, before the service is called")
        void emptyOrderIsRefused() {
            String tag = listTag(ribbon);

            assertThatThrownBy(() -> controller.reorder(1L, tag,
                    new StageDtos.StageOrder(List.of())))
                    .isInstanceOf(StageService.StageValidationException.class);

            verify(service, never()).reorder(anyLong(), anyList());
        }
    }

    // ------------------------------------------------------------------
    // removal — B-042
    // ------------------------------------------------------------------

    /**
     * <b>This test replaced B-040's {@code noDeleteExists}</b>, which asserted no
     * {@code DELETE} existed anywhere on this controller and named this task as
     * the one that would take it away. It was doing real work while it lived: the
     * ribbon's definition table had no rule protecting it, and a delete route
     * arriving before that rule would have been unreviewable.
     *
     * <p>What replaces it is not "a delete exists" — that would assert less than
     * nothing. It is the shape of the delete, and the guard it cannot be called
     * without.
     */
    @Nested
    @DisplayName("deprecated, never deleted")
    class Removal {

        @Test
        @DisplayName("the DELETE is preconditioned — the guard is both usage counts, and the tag carries them")
        void deleteRequiresIfMatch() {
            when(service.find(1L, 30L)).thenReturn(Optional.of(view(30L, "DEV", (short) 30, 3)));

            assertThatThrownBy(() -> controller.delete(1L, 30L, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("428");

            verify(service, never()).delete(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a stale tag refuses the DELETE with 412 rather than removing the row")
        void staleTagRefusesTheDelete() {
            when(service.find(1L, 30L)).thenReturn(Optional.of(view(30L, "DEV", (short) 30, 3)));

            assertThatThrownBy(() -> controller.delete(1L, 30L, "\"deadbeef\""))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("412");

            verify(service, never()).delete(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a current tag deletes and answers 204 — the screen refetches, every later position moved")
        void deleteReturns204() {
            when(service.find(1L, 30L)).thenReturn(Optional.of(view(30L, "DEV", (short) 30, 3)));
            String tag = controller.stage(1L, 30L).getHeaders().getETag().replace("\"", "");

            ResponseEntity<Void> response = controller.delete(1L, 30L, tag);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(service).delete(1L, 30L);
        }

        @Test
        @DisplayName("the deprecation setter takes no If-Match — it names a state rather than a delta")
        void deprecationIsIdempotentAndUnconditioned() {
            StageDtos.StageView retired = view(30L, "DEV", (short) 30, 3);
            when(service.find(1L, 30L)).thenReturn(Optional.of(retired));
            when(service.setDeprecated(1L, 30L, true)).thenReturn(retired);

            ResponseEntity<StageDtos.StageResponse> response =
                    controller.deprecation(1L, 30L, new StageDtos.StageDeprecation(true));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getETag()).isNotBlank();
            verify(service).setDeprecated(1L, 30L, true);
        }

        @Test
        @DisplayName("both routes 404 on an unknown stage before the service is asked to write")
        void unknownStageIs404OnBothRoutes() {
            when(service.find(1L, 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.delete(1L, 99L, "*"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() ->
                    controller.deprecation(1L, 99L, new StageDtos.StageDeprecation(true)))
                    .isInstanceOf(ResponseStatusException.class);

            verify(service, never()).delete(anyLong(), anyLong());
            verify(service, never()).setDeprecated(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("the ETag moves when the stage is retired, so a delete cannot ride a tag read before it")
        void tagCoversTheDeprecationFlag() {
            when(service.find(1L, 30L)).thenReturn(Optional.of(view(30L, "DEV", (short) 30, 3)));
            String live = controller.stage(1L, 30L).getHeaders().getETag();

            when(service.find(1L, 30L)).thenReturn(Optional.of(new StageDtos.StageView(
                    30L, 1L, "DEV", "DEV", "DEVELOPER", new BigDecimal("4.00"), false,
                    List.of(), "code-2", (short) 30, 3, 0L, 0L, true,
                    true, java.time.Instant.parse("2026-08-19T09:00:00Z"), false)));

            assertThat(controller.stage(1L, 30L).getHeaders().getETag()).isNotEqualTo(live);
        }
    }

    @Test
    @DisplayName("the create returns 201 with an ETag, so the form can edit without a reload")
    void createReturns201WithATag() {
        when(service.create(anyLong(), any())).thenReturn(view(50L, "DEPLOY", (short) 50, 5));

        ResponseEntity<StageDtos.StageResponse> response = controller.create(1L,
                new StageDtos.StageWrite("DEPLOY", "Deployment", "DEPLOYMENT",
                        null, false, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isNotBlank();
    }
}
