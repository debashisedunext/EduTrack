package com.edunext.edutrack.api.feature.masters.tasktypes;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-020 · what the controller decides before delegating — the {@code If-Match}
 * precondition, the {@code ETag} it is checked against, and the 404 that comes
 * before both.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code RoleControllerTest} does: everything asserted here is method-level, and
 * {@code MasterRoutesTest} covers the one thing plain construction cannot see,
 * which is where the class is mounted.
 */
class TaskTypeControllerTest {

    private TaskTypeService service;
    private TaskTypeController controller;

    @BeforeEach
    void setUp() {
        service = mock(TaskTypeService.class);
        controller = new TaskTypeController(service);
    }

    @Nested
    @DisplayName("If-Match")
    class Preconditions {

        @BeforeEach
        void storedType() {
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
            String tag = controller.taskType(2).getHeaders().getETag();

            assertThat(controller.update(2, tag, rename()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(controller.update(2, tag.replace("\"", ""), rename()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("`*` matches anything, per RFC 9110")
        void wildcardIsAccepted() {
            when(service.update(anyInt(), any())).thenReturn(Optional.of(view(2, 0L)));

            assertThat(controller.update(2, "*", rename()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("the 404 comes before the 428")
        void notFoundBeatsPreconditionRequired() {
            // Answering 428 for a type that does not exist would send the caller
            // to fetch a tag from a URL that will 404 as well.
            when(service.find(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(99, null, rename()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("the ETag")
    class Tag {

        @Test
        @DisplayName("covers ticketCount, so a ticket raised meanwhile costs a reload")
        void ticketCountIsInTheTag() {
            // That count is what the deactivate decision was made against, so a
            // change to it is a change to what the admin was looking at.
            when(service.find(2)).thenReturn(Optional.of(view(2, 0L)));
            String before = controller.taskType(2).getHeaders().getETag();

            when(service.find(2)).thenReturn(Optional.of(view(2, 1L)));
            String after = controller.taskType(2).getHeaders().getETag();

            assertThat(before).isNotEqualTo(after);
        }

        @Test
        @DisplayName("is stable when nothing changed — a rewrite of identical values is not a conflict")
        void identicalContentGivesTheSameTag() {
            when(service.find(2)).thenReturn(Optional.of(view(2, 3L)));
            String first = controller.taskType(2).getHeaders().getETag();

            when(service.find(2)).thenReturn(Optional.of(view(2, 3L)));

            assertThat(controller.taskType(2).getHeaders().getETag()).isEqualTo(first);
        }

        @Test
        @DisplayName("the create emits one, so the form can edit what it just made")
        void createEmitsATag() {
            when(service.create(any())).thenReturn(view(2, 0L));
            ResponseEntity<TaskTypeDtos.TaskTypeResponse> created = controller.create(
                    new TaskTypeDtos.TaskTypeWrite(
                            "X", "X", null, "#4F46E5", "MEDIUM", null, null, null));

            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getHeaders().getETag()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("an unknown id is 404, never 403")
        void unknownIdIs404() {
            when(service.find(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.taskType(99))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("the list is wrapped in { data } and carries no meta")
        void listIsWrapped() {
            // CONVENTIONS.md §6 — a bounded list returns `data` with no `meta`,
            // and that absence is the signal that the set is complete.
            when(service.list()).thenReturn(List.of(view(2, 0L)));

            assertThat(controller.taskTypes().data()).hasSize(1);
        }
    }

    /**
     * There is no delete, and the absence is asserted rather than assumed.
     *
     * <p>Three foreign keys point at this table without cascades, and B-019's
     * migration named this screen as the reason they can stay that way. A
     * {@code DELETE} added later would compile, pass every other test here, and
     * quietly reintroduce the problem.
     */
    @Test
    @DisplayName("no method on this controller is mapped to DELETE")
    void thereIsNoDelete() {
        assertThat(TaskTypeController.class.getDeclaredMethods())
                .noneSatisfy(method -> assertThat(method.getAnnotations())
                        .anySatisfy(annotation -> assertThat(annotation.annotationType().getName())
                                .contains("DeleteMapping")));
    }

    private static TaskTypeDtos.TaskTypePatch rename() {
        TaskTypeDtos.TaskTypePatch patch = new TaskTypeDtos.TaskTypePatch();
        patch.setName("Production Defect");
        return patch;
    }

    private static TaskTypeDtos.TaskTypeView view(int id, long ticketCount) {
        return new TaskTypeDtos.TaskTypeView(id, "PRODUCTION_BUG", "Production Bug", "flame",
                "#06B6D4", "HIGH", new BigDecimal("8.00"), (short) 20, true, ticketCount);
    }
}
