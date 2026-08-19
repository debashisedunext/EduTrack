package com.edunext.edutrack.api.feature.masters.statuses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-039 · what the controller decides before delegating — the {@code If-Match}
 * preconditions on both writes, the two {@code ETag}s they are checked against,
 * the 404 that comes before one of them, and the {@code includeInactive} default.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code PriorityControllerTest} and {@code TaskTypeControllerTest} do:
 * everything asserted here is method-level, and {@code MasterRoutesTest} covers
 * the one thing plain construction cannot see, which is where the class is
 * mounted.
 */
class StatusControllerTest {

    private StatusService service;
    private StatusTransitionService matrix;
    private StatusController controller;

    @BeforeEach
    void setUp() {
        service = mock(StatusService.class);
        matrix = mock(StatusTransitionService.class);
        controller = new StatusController(service, matrix);
        when(matrix.list(isNull())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // the list default
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("includeInactive")
    class ListDefault {

        @Test
        @DisplayName("the parameter defaults to false — a retired status is not offered")
        void defaultsToActiveOnly() {
            when(service.list(anyBoolean())).thenReturn(List.of());

            controller.statuses(false);

            verify(service).list(false);
        }

        @Test
        @DisplayName("true is passed through for the S-13 grid")
        void widensWhenAsked() {
            when(service.list(anyBoolean())).thenReturn(List.of());

            controller.statuses(true);

            verify(service).list(true);
        }
    }

    // ------------------------------------------------------------------
    // the status precondition
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("If-Match on the status PATCH")
    class StatusPrecondition {

        @Test
        @DisplayName("a missing If-Match is 428, not a silent success")
        void missingIsRefused() {
            when(service.find(1)).thenReturn(Optional.of(view(1, "NEW")));

            assertThatThrownBy(() -> controller.update(1, null, patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
            verify(service, never()).update(anyInt(), any());
        }

        @Test
        @DisplayName("a blank If-Match is treated as missing")
        void blankIsRefused() {
            when(service.find(1)).thenReturn(Optional.of(view(1, "NEW")));

            assertThatThrownBy(() -> controller.update(1, "   ", patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        }

        @Test
        @DisplayName("a stale If-Match is 412")
        void staleIsRefused() {
            when(service.find(1)).thenReturn(Optional.of(view(1, "NEW")));

            assertThatThrownBy(() -> controller.update(1, "\"deadbeef\"", patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_FAILED));
            verify(service, never()).update(anyInt(), any());
        }

        /**
         * The ordering that matters. A 428 for a status that does not exist would
         * send the caller to fetch a tag from a URL that will 404 as well.
         */
        @Test
        @DisplayName("an unknown id is 404 before the missing If-Match is 428")
        void notFoundComesFirst() {
            when(service.find(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(404, null, patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("the ETag from the GET is accepted by the PATCH")
        void roundTrip() {
            StatusDtos.StatusView current = view(1, "NEW");
            when(service.find(1)).thenReturn(Optional.of(current));
            when(service.update(anyInt(), any())).thenReturn(Optional.of(current));

            String etag = controller.status(1).getHeaders().getETag();

            assertThat(controller.update(1, etag, patch()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a star matches anything, per RFC 9110")
        void starMatches() {
            StatusDtos.StatusView current = view(1, "NEW");
            when(service.find(1)).thenReturn(Optional.of(current));
            when(service.update(anyInt(), any())).thenReturn(Optional.of(current));

            assertThat(controller.update(1, "*", patch()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        /**
         * The tag is over the content, so a save rewriting identical values does
         * not invalidate a concurrent editor's read.
         */
        @Test
        @DisplayName("two identical rows produce the same ETag")
        void tagIsOverContent() {
            when(service.find(1)).thenReturn(Optional.of(view(1, "NEW")));
            when(service.find(2)).thenReturn(Optional.of(view(1, "NEW")));

            assertThat(controller.status(1).getHeaders().getETag())
                    .isEqualTo(controller.status(2).getHeaders().getETag());
        }

        /**
         * {@code deactivatedTransitions} is deliberately outside the tag. A row
         * that reads identically must tag identically whether it was last written
         * by a retire or by a rename, or the next edit would fail for no reason.
         */
        @Test
        @DisplayName("deactivatedTransitions does not move the ETag")
        void deactivatedIsOutsideTheTag() {
            StatusDtos.StatusView plain = view(1, "NEW");
            StatusDtos.StatusView afterRetire = new StatusDtos.StatusView(
                    1, "NEW", "New", "TODO", "#4F46E5", (short) 10,
                    true, false, true, 0L, 0, 6);
            when(service.find(1)).thenReturn(Optional.of(plain));
            when(service.find(2)).thenReturn(Optional.of(afterRetire));

            assertThat(controller.status(1).getHeaders().getETag())
                    .isEqualTo(controller.status(2).getHeaders().getETag());
        }
    }

    // ------------------------------------------------------------------
    // the matrix precondition
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("If-Match on the matrix PUT")
    class MatrixPrecondition {

        @Test
        @DisplayName("a missing If-Match is 428")
        void missingIsRefused() {
            assertThatThrownBy(() -> controller.replaceTransitions(null, matrixWrite()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
            verify(matrix, never()).replace(any());
        }

        @Test
        @DisplayName("the refusal says what a replace without one would do")
        void refusalExplainsTheStake() {
            assertThatThrownBy(() -> controller.replaceTransitions("", matrixWrite()))
                    .hasMessageContaining("silently discard");
        }

        @Test
        @DisplayName("a stale If-Match is 412 and nothing is written")
        void staleIsRefused() {
            when(matrix.list(isNull())).thenReturn(List.of(transition(1, "NEW", "ADMIN")));

            assertThatThrownBy(() -> controller.replaceTransitions("\"deadbeef\"", matrixWrite()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_FAILED));
            verify(matrix, never()).replace(any());
        }

        @Test
        @DisplayName("the ETag from the GET is accepted by the PUT")
        void roundTrip() {
            when(matrix.list(isNull())).thenReturn(List.of(transition(1, "NEW", "ADMIN")));
            when(matrix.replace(any())).thenReturn(List.of(transition(1, "NEW", "ADMIN")));

            String etag = controller.transitions(null).getHeaders().getETag();

            assertThat(controller.replaceTransitions(etag, matrixWrite()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        /**
         * The tag has to cover the whole matrix even when the read was filtered,
         * or two Admins editing different columns would each save over the other
         * with both preconditions passing.
         */
        @Test
        @DisplayName("a role-filtered read still carries the whole matrix's tag")
        void filteredReadCarriesTheWholeTag() {
            when(matrix.list(isNull())).thenReturn(List.of(
                    transition(1, "NEW", "ADMIN"), transition(2, "NEW", "QA")));
            when(matrix.list("QA")).thenReturn(List.of(transition(2, "NEW", "QA")));

            assertThat(controller.transitions("QA").getHeaders().getETag())
                    .isEqualTo(controller.transitions(null).getHeaders().getETag());
        }

        @Test
        @DisplayName("the tag moves when any cell changes")
        void tagMovesWithTheMatrix() {
            when(matrix.list(isNull())).thenReturn(List.of(transition(1, "NEW", "ADMIN")));
            String before = controller.transitions(null).getHeaders().getETag();

            when(matrix.list(isNull())).thenReturn(List.of(
                    transition(1, "NEW", "ADMIN"), transition(2, "NEW", "QA")));

            assertThat(controller.transitions(null).getHeaders().getETag()).isNotEqualTo(before);
        }
    }

    // ------------------------------------------------------------------
    // the missing verb
    // ------------------------------------------------------------------

    /**
     * The absence is the design, and it is asserted rather than trusted. Nothing
     * has a foreign key to {@code statuses}, so a delete would <em>succeed</em>
     * and strand every ticket in that status with no move offered anywhere.
     */
    @Test
    @DisplayName("no mapping on this controller is a DELETE")
    void thereIsNoDelete() {
        assertThat(StatusController.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(DeleteMapping.class));
    }

    @Test
    @DisplayName("every write asserts master.write and every read only authentication")
    void authorityAnnotationsAreWhereTheyShouldBe() {
        for (Method m : StatusController.class.getDeclaredMethods()) {
            var preAuthorize = m.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            if (preAuthorize == null) {
                continue;
            }
            var operation = m.getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
            assertThat(operation).as("%s has no @Operation", m.getName()).isNotNull();

            boolean isWrite = m.getName().equals("create")
                    || m.getName().equals("update")
                    || m.getName().equals("replaceTransitions");
            assertThat(preAuthorize.value())
                    .as("%s", m.getName())
                    .isEqualTo(isWrite ? "hasAuthority('master.write')" : "isAuthenticated()");
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static StatusDtos.StatusView view(int id, String code) {
        return new StatusDtos.StatusView(id, code, "New", "TODO", "#4F46E5", (short) 10,
                true, false, true, 0L, 0, null);
    }

    private static StatusDtos.TransitionView transition(int id, String to, String role) {
        return new StatusDtos.TransitionView(id, null, to, role, false, false, true);
    }

    private static StatusDtos.StatusPatch patch() {
        return new StatusDtos.StatusPatch(null, "Raised", null, null, null, null, null, null);
    }

    private static StatusDtos.TransitionMatrixWrite matrixWrite() {
        return new StatusDtos.TransitionMatrixWrite(List.of(
                new StatusDtos.TransitionWrite(null, "NEW", "ADMIN", null, null)));
    }
}
