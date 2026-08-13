package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-010 · the three things the service decides on its own — where a page ends,
 * how far an export walks, and what happens to each resource in a bulk status
 * change.
 *
 * <p>Against a mocked repository, so these run without Docker.
 * {@code ResourceListIT} proves the same behaviours against real SQL, where a
 * different set of things can be wrong.
 */
class ResourceServiceTest {

    private ResourceRepository repository;
    private ResourceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResourceRepository.class);
        // A-037 gave ResourceService the roles table, so the ?role= filter's
        // vocabulary is read behind a service rather than by the controller.
        // Nothing in this class exercises it; the mock is here to construct.
        service = new ResourceService(repository,
                mock(com.edunext.edutrack.domain.identity.RoleRepository.class));

        when(repository.projectsFor(any())).thenReturn(Map.of());
        when(repository.openTicketCounts(any())).thenReturn(Map.of());
        when(repository.count(any())).thenReturn(0L);
    }

    // ------------------------------------------------------------------
    // paging
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("asks for one more row than the page, to learn whether more exist")
        void asksForOneExtraRow() {
            when(repository.page(any(), any(), eq(21))).thenReturn(rows(21));

            service.list(ResourceFilter.NONE, null, 20);

            verify(repository).page(any(), isNull(), eq(21));
        }

        @Test
        @DisplayName("returns exactly the page size and reports there is more")
        void trimsTheProbeRow() {
            when(repository.page(any(), any(), eq(21))).thenReturn(rows(21));

            ResourceDtos.ResourceListResponse response = service.list(ResourceFilter.NONE, null, 20);

            assertThat(response.data()).hasSize(20);
            assertThat(response.meta().hasMore()).isTrue();
            assertThat(response.meta().nextCursor()).isNotBlank();
        }

        @Test
        @DisplayName("a full page with nothing after it does not offer a next page")
        void exactMultipleOfPageSize() {
            // The off-by-one that "a full page probably means more" gets wrong,
            // and it gets it wrong precisely when the total is a multiple of the
            // page size — a Next button leading to an empty grid.
            when(repository.page(any(), any(), eq(21))).thenReturn(rows(20));

            ResourceDtos.ResourceListResponse response = service.list(ResourceFilter.NONE, null, 20);

            assertThat(response.data()).hasSize(20);
            assertThat(response.meta().hasMore()).isFalse();
            assertThat(response.meta().nextCursor()).isNull();
        }

        @Test
        @DisplayName("the next cursor points at the last row shown, not at the probe row")
        void cursorPointsAtTheLastVisibleRow() {
            when(repository.page(any(), any(), eq(3))).thenReturn(rows(3));

            ResourceDtos.ResourceListResponse response = service.list(ResourceFilter.NONE, null, 2);

            // Rows are "Person 01".."Person 03" with ids 1..3. The page shows 1
            // and 2, so resuming must start after 2 — a cursor at 3 would skip
            // whoever sorts between them after a concurrent rename.
            assertThat(ResourceCursor.decode(response.meta().nextCursor()))
                    .isEqualTo(new ResourceCursor("Person 02", 2L));
        }

        @Test
        @DisplayName("limit is clamped to the contract's bounds")
        void clampsLimit() {
            assertThat(ResourceService.clampLimit(null)).isEqualTo(ResourceService.DEFAULT_LIMIT);
            assertThat(ResourceService.clampLimit(0)).isEqualTo(1);
            assertThat(ResourceService.clampLimit(-5)).isEqualTo(1);
            assertThat(ResourceService.clampLimit(1000)).isEqualTo(ResourceService.MAX_LIMIT);
            assertThat(ResourceService.clampLimit(37)).isEqualTo(37);
        }

        @Test
        @DisplayName("hydration costs two queries for the page, not two per row")
        void hydratesInBatches() {
            when(repository.page(any(), any(), eq(51))).thenReturn(rows(50));

            service.list(ResourceFilter.NONE, null, 50);

            // The N+1 this design exists to prevent: 50 rows, one call each.
            verify(repository, times(1)).projectsFor(any());
            verify(repository, times(1)).openTicketCounts(any());
        }

        @Test
        @DisplayName("projects are attached as both refs and ids, from one lookup")
        void attachesProjects() {
            ResourceDtos.ProjectRef crm = new ResourceDtos.ProjectRef(7L, "CRM", "CRM Revamp", "#4F46E5");
            when(repository.page(any(), any(), eq(2))).thenReturn(rows(1));
            when(repository.projectsFor(any())).thenReturn(Map.of(1L, List.of(crm)));

            ResourceDtos.Resource resource = service.list(ResourceFilter.NONE, null, 1).data().getFirst();

            assertThat(resource.projects()).containsExactly(crm);
            assertThat(resource.projectIds()).containsExactly(7L);
        }
    }

    // ------------------------------------------------------------------
    // export streaming
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("streamAll")
    class StreamAll {

        @Test
        @DisplayName("walks past the first batch instead of exporting one page")
        void walksEveryBatch() {
            // Stopping after one batch produces a file that looks complete and
            // is not — the failure nobody checks for, because the file opens.
            when(repository.page(any(), isNull(), eq(ResourceService.EXPORT_BATCH)))
                    .thenReturn(rows(ResourceService.EXPORT_BATCH));
            when(repository.page(any(), notNull(), eq(ResourceService.EXPORT_BATCH)))
                    .thenReturn(rows(7));

            List<ResourceDtos.Resource> exported = new ArrayList<>();
            service.streamAll(ResourceFilter.NONE, exported::addAll);

            assertThat(exported).hasSize(ResourceService.EXPORT_BATCH + 7);
        }

        @Test
        @DisplayName("a short first batch ends the walk without a second query")
        void shortFirstBatch() {
            when(repository.page(any(), any(), eq(ResourceService.EXPORT_BATCH))).thenReturn(rows(3));

            List<ResourceDtos.Resource> exported = new ArrayList<>();
            service.streamAll(ResourceFilter.NONE, exported::addAll);

            assertThat(exported).hasSize(3);
            verify(repository, times(1)).page(any(), any(), eq(ResourceService.EXPORT_BATCH));
        }

        @Test
        @DisplayName("an empty result writes nothing and does not loop")
        void emptyResult() {
            when(repository.page(any(), any(), eq(ResourceService.EXPORT_BATCH))).thenReturn(List.of());

            List<ResourceDtos.Resource> exported = new ArrayList<>();
            service.streamAll(ResourceFilter.NONE, exported::addAll);

            assertThat(exported).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // bulk status
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("bulk activate / deactivate")
    class BulkStatus {

        @Test
        @DisplayName("deactivating someone with open tickets is refused, and says how many")
        void blocksDeactivationWithOpenTickets() {
            given(1L, "Ravi Kumar", true);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 4));

            ResourceDtos.BulkStatusData result = service.setStatus(request(false, 1L));

            assertThat(result.results()).singleElement().satisfies(outcome -> {
                assertThat(outcome.outcome())
                        .isEqualTo(ResourceDtos.BulkStatusOutcomeCode.BLOCKED_OPEN_TICKETS);
                assertThat(outcome.openTicketCount()).isEqualTo(4);
            });
            assertThat(result.blocked()).isEqualTo(1);
            assertThat(result.reassignUrl()).isEqualTo(ResourceService.REASSIGN_URL);
            verify(repository, never()).setActive(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("one blocked resource does not fail the rest of the selection")
        void partialSuccessIsTheNormalCase() {
            given(1L, "Ravi Kumar", true);
            given(2L, "Neha Singh", true);
            given(3L, "Amit Rao", true);
            when(repository.openTicketCounts(List.of(1L, 2L, 3L)))
                    .thenReturn(Map.of(1L, 0, 2L, 9, 3L, 0));

            ResourceDtos.BulkStatusData result = service.setStatus(request(false, 1L, 2L, 3L));

            assertThat(result.changed()).isEqualTo(2);
            assertThat(result.blocked()).isEqualTo(1);
            verify(repository).setActive(1L, false);
            verify(repository).setActive(3L, false);
            verify(repository, never()).setActive(eq(2L), anyBoolean());
        }

        @Test
        @DisplayName("activation is never blocked — bringing someone back orphans nothing")
        void activationIgnoresOpenTickets() {
            given(1L, "Ravi Kumar", false);

            ResourceDtos.BulkStatusData result = service.setStatus(request(true, 1L));

            assertThat(result.changed()).isEqualTo(1);
            // Not even asked. The count cannot change the answer, so the query
            // would be a round trip spent to ignore its own result.
            verify(repository, never()).openTicketCounts(any());
            verify(repository).setActive(1L, true);
        }

        @Test
        @DisplayName("already in the requested state is UNCHANGED, and no write happens")
        void alreadyInTheRequestedState() {
            given(1L, "Ravi Kumar", true);

            ResourceDtos.BulkStatusData result = service.setStatus(request(true, 1L));

            assertThat(result.unchanged()).isEqualTo(1);
            assertThat(result.changed()).isZero();
            verify(repository, never()).setActive(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("an already-inactive resource is UNCHANGED, not BLOCKED, whatever it holds")
        void alreadyInactiveIsNotBlocked() {
            // The guard must sit after the equality check. Before it, someone
            // already deactivated but still holding open tickets — which is what
            // a half-finished reassignment leaves behind — would report blocked
            // forever and never clear from the grid.
            given(1L, "Ravi Kumar", false);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 3));

            ResourceDtos.BulkStatusData result = service.setStatus(request(false, 1L));

            assertThat(result.results()).singleElement()
                    .extracting(ResourceDtos.BulkStatusOutcome::outcome)
                    .isEqualTo(ResourceDtos.BulkStatusOutcomeCode.UNCHANGED);
        }

        @Test
        @DisplayName("an id that no longer exists is reported, not thrown")
        void missingResource() {
            when(repository.findStatus(99L)).thenReturn(Optional.empty());
            when(repository.openTicketCounts(List.of(99L))).thenReturn(Map.of(99L, 0));

            ResourceDtos.BulkStatusData result = service.setStatus(request(false, 99L));

            assertThat(result.notFound()).isEqualTo(1);
            assertThat(result.results()).singleElement()
                    .extracting(ResourceDtos.BulkStatusOutcome::outcome)
                    .isEqualTo(ResourceDtos.BulkStatusOutcomeCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a duplicated id yields one outcome, not two contradicting ones")
        void duplicateIdsCollapse() {
            given(1L, "Ravi Kumar", true);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 0));

            ResourceDtos.BulkStatusData result = service.setStatus(request(false, 1L, 1L, 1L));

            assertThat(result.results()).hasSize(1);
            assertThat(result.changed()).isEqualTo(1);
            verify(repository, times(1)).setActive(1L, false);
        }

        @Test
        @DisplayName("no reassign link when nothing was blocked — it would point nowhere useful")
        void reassignUrlOnlyWhenBlocked() {
            given(1L, "Ravi Kumar", true);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 0));

            assertThat(service.setStatus(request(false, 1L)).reassignUrl()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // B-014 · the singular status route
    // ------------------------------------------------------------------

    /**
     * The route the deactivation flow returns through, and the one the contract
     * declared for months while nothing served it.
     *
     * <p>These assert the <b>translation</b> — outcome code to exception or
     * silence — rather than re-testing the decision. The decision is
     * {@code apply}, which the class above already covers; the entire claim of
     * this route is that it does not have a second one.
     */
    @Nested
    @DisplayName("activate / deactivate one resource")
    class SingularStatus {

        @Test
        @DisplayName("deactivating someone with open tickets raises the same 409 the form does")
        void blockedCarriesTheCount() {
            given(1L, "Ravi Kumar", true);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 4));

            assertThatExceptionOfType(ResourceWriteService.OpenTicketsException.class)
                    .isThrownBy(() -> service.setStatus(1L, false))
                    .satisfies(e -> {
                        assertThat(e.userId()).isEqualTo(1L);
                        assertThat(e.openTicketCount()).isEqualTo(4);
                    });
            verify(repository, never()).setActive(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("a resource that is already inactive succeeds, even holding tickets")
        void alreadyInThatStateIsNotAConflict() {
            // What a half-finished reassignment leaves behind. Checking the flag
            // before the guard is the only thing that lets this row ever settle
            // — reversed, it would answer 409 forever and the grid would carry a
            // permanently unresolvable person.
            given(1L, "Ravi Kumar", false);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 7));

            service.setStatus(1L, false);

            verify(repository, never()).setActive(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("activating is never blocked — bringing somebody back orphans nothing")
        void activationSkipsTheGuardEntirely() {
            given(1L, "Ravi Kumar", false);

            service.setStatus(1L, true);

            verify(repository).setActive(1L, true);
            // Not merely unused: not asked for. An activation that queried the
            // ticket table would be a round trip whose answer cannot change the
            // outcome.
            verify(repository, never()).openTicketCounts(any());
        }

        @Test
        @DisplayName("deactivating someone with nothing open moves the flag")
        void theOrdinaryCase() {
            given(1L, "Ravi Kumar", true);
            when(repository.openTicketCounts(List.of(1L))).thenReturn(Map.of(1L, 0));

            service.setStatus(1L, false);

            verify(repository).setActive(1L, false);
        }

        @Test
        @DisplayName("an unknown id is 404, not a silent no-op")
        void unknownResource() {
            when(repository.findStatus(99L)).thenReturn(Optional.empty());
            when(repository.openTicketCounts(List.of(99L))).thenReturn(Map.of(99L, 0));

            assertThatExceptionOfType(ResourceWriteService.ResourceNotFoundException.class)
                    .isThrownBy(() -> service.setStatus(99L, false));
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void given(long id, String name, boolean isActive) {
        when(repository.findStatus(id))
                .thenReturn(Optional.of(new ResourceRepository.ResourceStatus(id, name, isActive)));
    }

    private static ResourceDtos.BulkStatusRequest request(boolean isActive, Long... ids) {
        return new ResourceDtos.BulkStatusRequest(List.of(ids), isActive, null);
    }

    /** {@code n} rows named "Person 01".."Person nn" with ids 1..n, already in sort order. */
    private static List<ResourceRepository.ResourceRow> rows(int n) {
        return IntStream.rangeClosed(1, n)
                .mapToObj(i -> new ResourceRepository.ResourceRow(
                        i,
                        "EMP%03d".formatted(i),
                        "person%d".formatted(i),
                        "person%d@edunext.test".formatted(i),
                        "Person %02d".formatted(i),
                        "DEVELOPER",
                        "Engineering",
                        "Engineer",
                        null,
                        true,
                        Instant.parse("2026-08-01T09:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z")))
                .toList();
    }
}
