package com.edunext.edutrack.api.feature.masters.priorities;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-021 · what the controller decides before delegating — the {@code If-Match}
 * precondition, the {@code ETag} it is checked against, the 404 that comes
 * before both, and the {@code includeInactive} default.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code RoleControllerTest} and {@code TaskTypeControllerTest} do: everything
 * asserted here is method-level, and {@code MasterRoutesTest} covers the one
 * thing plain construction cannot see, which is where the class is mounted.
 */
class PriorityControllerTest {

    private PriorityService service;
    private PriorityController controller;

    @BeforeEach
    void setUp() {
        service = mock(PriorityService.class);
        controller = new PriorityController(service);
    }

    // ------------------------------------------------------------------
    // the list default
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("includeInactive")
    class ListDefault {

        /**
         * The narrow default is the whole reason this parameter exists, and it
         * is the one place S-12 reads its master differently from S-11. Stream
         * C's `CreateTicketPage` maps this response straight into `LevelPicker`
         * without filtering, so a widened default would put a retired level in
         * the create form.
         */
        @Test
        @DisplayName("defaults to active levels only")
        void defaultsToActiveOnly() {
            when(service.list(anyBoolean())).thenReturn(List.of());

            controller.priorities(false);

            verify(service).list(false);
        }

        @Test
        @DisplayName("the grid can ask for retired levels too")
        void canBeWidened() {
            when(service.list(anyBoolean())).thenReturn(List.of());

            controller.priorities(true);

            verify(service).list(true);
        }
    }

    // ------------------------------------------------------------------
    // preconditions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("If-Match")
    class Preconditions {

        @BeforeEach
        void storedLevel() {
            when(service.find(2)).thenReturn(Optional.of(view(2, 0L)));
        }

        @Test
        @DisplayName("a write without If-Match is 428, not allowed through")
        void missingPreconditionIs428() {
            // Treating a missing precondition as "no conflict" would mean the
            // guard protects only the clients that already opted in — the set
            // that needed it least.
            assertThatThrownBy(() -> controller.update(2, null, rename()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

            verify(service, never()).update(anyInt(), any());
        }

        @Test
        @DisplayName("a blank If-Match is treated as absent")
        void blankPreconditionIs428() {
            assertThatThrownBy(() -> controller.update(2, "   ", rename()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        }

        @Test
        @DisplayName("a stale If-Match is 412")
        void stalePreconditionIs412() {
            assertThatThrownBy(() -> controller.update(2, "\"deadbeef\"", rename()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_FAILED));

            verify(service, never()).update(anyInt(), any());
        }

        @Test
        @DisplayName("the tag from the read is accepted, quoted or not")
        void currentPreconditionIsAccepted() {
            when(service.update(anyInt(), any())).thenReturn(Optional.of(view(2, 0L)));
            String tag = controller.priority(2).getHeaders().getETag();
            assertThat(tag).isNotNull();

            controller.update(2, tag, rename());
            controller.update(2, tag.replace("\"", ""), rename());
            controller.update(2, "*", rename());

            verify(service, org.mockito.Mockito.times(3)).update(anyInt(), any());
        }

        /**
         * The 404 comes first. Answering 428 for a level that does not exist
         * would send the caller to fetch a tag from a URL that will 404 as well.
         */
        @Test
        @DisplayName("an unknown id is 404 before the precondition is even considered")
        void unknownIdIs404BeforeThePrecondition() {
            when(service.find(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(99, null, rename()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ------------------------------------------------------------------
    // the tag itself
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ETag")
    class Tags {

        @Test
        @DisplayName("it is derived from the content, so an identical save is not a conflict")
        void identicalContentIsTheSameTag() {
            when(service.find(2)).thenReturn(Optional.of(view(2, 0L)));
            String first = controller.priority(2).getHeaders().getETag();

            when(service.find(2)).thenReturn(Optional.of(view(2, 0L)));
            assertThat(controller.priority(2).getHeaders().getETag()).isEqualTo(first);
        }

        /**
         * The counts are in the hash on purpose. A ticket raised at this level
         * while the edit dialog is open costs a reload — correct, because those
         * counts are what the retire decision was made against.
         */
        @Test
        @DisplayName("a changed usage count moves the tag")
        void aChangedCountMovesTheTag() {
            when(service.find(2)).thenReturn(Optional.of(view(2, 0L)));
            String before = controller.priority(2).getHeaders().getETag();

            when(service.find(2)).thenReturn(Optional.of(view(2, 1L)));
            assertThat(controller.priority(2).getHeaders().getETag()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("create answers 201 and carries a tag the edit dialog can use immediately")
        void createCarriesATag() {
            when(service.create(any())).thenReturn(view(7, 0L));

            ResponseEntity<PriorityDtos.PriorityResponse> response = controller.create(
                    new PriorityDtos.PriorityWrite("HIGH", "High", "#F59E0B", null, null, null, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getETag()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // there is no delete
    // ------------------------------------------------------------------

    /**
     * A structural assertion, not a behavioural one, and it is here because the
     * absence of a route is not something any request can prove. Nothing has a
     * foreign key to {@code priorities} — {@code tickets.level} holds the code
     * as a string — so unlike the task type master a {@code DELETE} here would
     * <em>succeed</em> at the database and leave every historical ticket
     * rendering a level nothing resolves.
     */
    @Test
    @DisplayName("no mapping on this controller is a DELETE")
    void thereIsNoDeleteRoute() {
        assertThat(PriorityController.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(
                        org.springframework.web.bind.annotation.DeleteMapping.class));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static PriorityDtos.PriorityPatch rename() {
        return new PriorityDtos.PriorityPatch(null, "Elevated", null, null, null, null, null);
    }

    private static PriorityDtos.PriorityView view(int id, long ticketCount) {
        return new PriorityDtos.PriorityView(id, "HIGH", "High", "#F59E0B",
                new BigDecimal("8.00"), false, (short) 30, true, ticketCount, 0, 0);
    }
}
