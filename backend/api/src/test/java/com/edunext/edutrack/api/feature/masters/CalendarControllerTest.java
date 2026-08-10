package com.edunext.edutrack.api.feature.masters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-023 · the two things the controller decides on its own — optimistic
 * concurrency on the working week, and cursor pagination over leave.
 *
 * <p>Everything else it does is delegation, which a test would only restate.
 */
class CalendarControllerTest {

    private final CalendarService service = mock(CalendarService.class);
    private final CalendarController controller = new CalendarController(service);

    private static final CalendarDtos.WorkingWeek WEEK = new CalendarDtos.WorkingWeek(
            List.of(6, 7), LocalTime.of(9, 30), LocalTime.of(18, 30), "Asia/Kolkata");

    private static CalendarDtos.WorkingWeekUpdate anyUpdate() {
        return new CalendarDtos.WorkingWeekUpdate(
                Set.of(6, 7), LocalTime.of(9, 0), LocalTime.of(18, 0), "Asia/Kolkata");
    }

    @Nested
    @DisplayName("the working week's If-Match guard")
    class OptimisticConcurrency {

        @BeforeEach
        void setUp() {
            when(service.workingWeek()).thenReturn(WEEK);
        }

        @Test
        @DisplayName("the read carries an ETag, so a client has something to send back")
        void readReturnsAnEtag() {
            ResponseEntity<CalendarDtos.WorkingWeekResponse> response = controller.workingWeek();

            assertThat(response.getHeaders().getETag()).isNotBlank();
        }

        /**
         * Treating an absent precondition as "no conflict" would leave the guard
         * protecting only clients that already opted in — the set that needed it
         * least.
         */
        @Test
        @DisplayName("a write with no If-Match is refused, not waved through")
        void refusesAWriteWithoutIfMatch() {
            assertThatThrownBy(() -> controller.updateWorkingWeek(null, anyUpdate()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("428");

            verify(service, never()).updateWorkingWeek(any());
        }

        @Test
        @DisplayName("a stale ETag is refused with 412 and nothing is written")
        void refusesAStaleEtag() {
            assertThatThrownBy(() -> controller.updateWorkingWeek("\"deadbeef\"", anyUpdate()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("412");

            verify(service, never()).updateWorkingWeek(any());
        }

        @Test
        @DisplayName("the ETag the read handed out is accepted")
        void acceptsTheCurrentEtag() {
            when(service.updateWorkingWeek(any())).thenReturn(WEEK);
            String etag = controller.workingWeek().getHeaders().getETag();

            ResponseEntity<CalendarDtos.WorkingWeekResponse> response =
                    controller.updateWorkingWeek(etag, anyUpdate());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(service).updateWorkingWeek(any());
        }

        @Test
        @DisplayName("a weak validator is accepted — W/ is a transport detail")
        void acceptsAWeakEtag() {
            when(service.updateWorkingWeek(any())).thenReturn(WEEK);
            String weak = "W/" + controller.workingWeek().getHeaders().getETag();

            assertThat(controller.updateWorkingWeek(weak, anyUpdate()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("* matches anything, per RFC 9110")
        void acceptsWildcard() {
            when(service.updateWorkingWeek(any())).thenReturn(WEEK);

            assertThat(controller.updateWorkingWeek("*", anyUpdate()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        /**
         * The tag is hashed from the content rather than a timestamp, so a save
         * that rewrites identical values does not invalidate a concurrent
         * caller's edit for a change that never happened.
         */
        @Test
        @DisplayName("the ETag tracks content, not the time of the last write")
        void etagIsContentDerived() {
            String first = controller.workingWeek().getHeaders().getETag();
            String second = controller.workingWeek().getHeaders().getETag();

            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("leave pagination")
    class Pagination {

        private List<CalendarDtos.ResourceLeave> page(int n) {
            return java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> new CalendarDtos.ResourceLeave(i, 7L,
                            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                            "PLANNED", false, "APPROVED", null))
                    .toList();
        }

        @Test
        @DisplayName("a short result set reports no more pages and no cursor")
        void completeResultHasNoCursor() {
            when(service.leaves(any(), any(), any())).thenReturn(page(3));

            CalendarDtos.ResourceLeaveListResponse response =
                    controller.leaves(null, null, null, null, null);

            assertThat(response.data()).hasSize(3);
            assertThat(response.meta().hasMore()).isFalse();
            assertThat(response.meta().nextCursor()).isNull();
            assertThat(response.meta().totalCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a full page hands back a cursor that resumes after it")
        void cursorResumesAfterThePage() {
            when(service.leaves(any(), any(), any())).thenReturn(page(5));

            CalendarDtos.ResourceLeaveListResponse first =
                    controller.leaves(null, null, null, null, 2);
            assertThat(first.data()).hasSize(2);
            assertThat(first.meta().hasMore()).isTrue();

            CalendarDtos.ResourceLeaveListResponse second =
                    controller.leaves(null, null, null, first.meta().nextCursor(), 2);

            assertThat(second.data())
                    .as("the second page continues rather than repeating the first")
                    .extracting(CalendarDtos.ResourceLeave::id)
                    .containsExactly(2L, 3L);
        }

        @Test
        @DisplayName("the limit is capped, so a caller cannot ask for everything at once")
        void capsTheLimit() {
            when(service.leaves(any(), any(), any())).thenReturn(page(500));

            assertThat(controller.leaves(null, null, null, null, 10_000).data())
                    .hasSize(CalendarService.MAX_LIMIT);
        }

        @Test
        @DisplayName("a garbage cursor restarts rather than throwing")
        void toleratesAGarbageCursor() {
            when(service.leaves(any(), any(), any())).thenReturn(page(3));

            assertThat(controller.leaves(null, null, null, "not-base64!", null).data()).hasSize(3);
        }

        @Test
        @DisplayName("a cursor past the end yields an empty page, not an exception")
        void toleratesACursorPastTheEnd() {
            when(service.leaves(any(), any(), any())).thenReturn(page(2));
            String far = java.util.Base64.getEncoder().encodeToString("99".getBytes());

            assertThat(controller.leaves(null, null, null, far, null).data()).isEmpty();
        }
    }

    @Nested
    @DisplayName("absent rows")
    class NotFound {

        @Test
        @DisplayName("editing a holiday that does not exist is 404, never 403")
        void missingHolidayIs404() {
            when(service.updateHoliday(anyLong(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.updateHoliday(99L, new CalendarDtos.HolidayPatch(
                    LocalDate.of(2026, 1, 1), "x", null, null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("deleting a leave that does not exist is 404")
        void missingLeaveIs404() {
            when(service.deleteLeave(99L)).thenReturn(false);

            assertThatThrownBy(() -> controller.deleteLeave(99L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }
}
