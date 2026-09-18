package com.edunext.edutrack.api.feature.onboarding.mytasks;

import com.edunext.edutrack.api.feature.onboarding.mytasks.ObMyTaskDtos.ObMyTask;
import com.edunext.edutrack.api.feature.onboarding.mytasks.ObMyTaskDtos.ObMyTaskListResponse;
import com.edunext.edutrack.api.feature.onboarding.mytasks.ObMyTaskReadRepository.Row;
import com.edunext.edutrack.common.pagination.Cursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * My Tasks' page boundary and its one derived field.
 *
 * <p>The scoping and the ordering are the query's, and are asserted where they
 * live. What is worth testing here is the part that is easy to get subtly
 * wrong: the cursor carries the <em>coalesced</em> sort key rather than the due
 * date, so a page boundary landing in a run of tasks with no due date resumes
 * where it left off instead of at the top.
 */
class ObMyTaskServiceTest {

    private static final long ME = 41L;
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private ObMyTaskReadRepository reads;
    private ObMyTaskService service;

    @BeforeEach
    void setUp() {
        reads = mock(ObMyTaskReadRepository.class);
        service = new ObMyTaskService(reads, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Row row(long taskId, Instant dueAt) {
        return row(taskId, dueAt, 0, 0, 0);
    }

    /**
     * The sort key is {@code created_at}, not the due date.
     *
     * <p>My Tasks orders newest-first, so the key the cursor carries is the
     * row's creation stamp and a task with no due date needs no sentinel —
     * which is why {@code NO_DUE_DATE} is gone. Derived from the task id here
     * so two rows of one test are ordered and distinct without the test
     * having to state a timestamp it does not care about.
     */
    private static Row row(long taskId, Instant dueAt, int out, int returned, int approved) {
        return row(taskId, dueAt, out, returned, approved, false);
    }

    private static Row row(long taskId, Instant dueAt, int out, int returned, int approved,
                            boolean pendingMyVerification) {
        return new Row(taskId, "Week off", "PENDING", dueAt,
                "2026-09-15 09:%02d:00".formatted(taskId % 60),
                500L, "Student Attendance", 7L, "DAV Proj",
                3L, "DAV School", "DAV-101", 1L, "Configuration", 1,
                out, returned, approved, pendingMyVerification);
    }

    @Test
    @DisplayName("a task past its due date is overdue")
    void marksAnOverdueTask() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, NOW.minusSeconds(3600))));

        ObMyTaskListResponse response = service.list(ME, false, null, null);

        assertThat(response.data()).singleElement()
                .extracting(ObMyTask::isOverdue).isEqualTo(true);
    }

    @Test
    @DisplayName("a task due later today is not overdue")
    void doesNotMarkAFutureDeadline() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, NOW.plusSeconds(3600))));

        assertThat(service.list(ME, false, null, null).data())
                .singleElement().extracting(ObMyTask::isOverdue).isEqualTo(false);
    }

    /**
     * A task that has never activated has no deadline to have missed. Reporting
     * it overdue would put a red date on every task nobody has started.
     */
    @Test
    @DisplayName("a task with no due date is never overdue")
    void neverMarksATaskWithNoDeadline() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, null)));

        ObMyTask task = service.list(ME, false, null, null).data().getFirst();
        assertThat(task.isOverdue()).isFalse();
        assertThat(task.dueAt()).isNull();
    }

    /**
     * The page is the caller's own — the id the service was handed, never one
     * a request could carry. Asserted because the endpoint's whole security
     * model is that there is no other value this can be.
     */
    @Test
    @DisplayName("the query is run for the calling user")
    void queriesForTheCaller() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt())).thenReturn(List.of());

        service.list(ME, false, null, null);

        verify(reads).openTasksOf(ME, false, null, 51);
    }

    @Test
    @DisplayName("a requested page size is clamped and fetches one extra row")
    void fetchesOneMoreThanThePage() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt())).thenReturn(List.of());

        service.list(ME, false, null, 10);

        verify(reads).openTasksOf(ME, false, null, 11);
    }

    /**
     * The boundary this exists for. The cursor carries the row's own sort key
     * — {@code created_at}, which the query orders by — so a page resumes
     * exactly where it stopped rather than at the top of the queue.
     *
     * <p>It used to carry a {@code NO_DUE_DATE} sentinel, because the queue
     * was ordered by due date and a run of tasks with none had no key to
     * resume among. Ordering by creation removed both the sentinel and the
     * problem: every task has a creation stamp.
     */
    @Test
    @DisplayName("the next cursor carries the sort key, not the due date")
    void cursorCarriesTheCoalescedKey() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, null), row(2L, null)));

        ObMyTaskListResponse response = service.list(ME, false, null, 1);

        assertThat(response.data()).hasSize(1);
        assertThat(response.meta().hasMore()).isTrue();

        Cursor decoded = Cursor.decode(response.meta().nextCursor());
        assertThat(decoded).isNotNull();
        assertThat(decoded.sortKey()).isEqualTo("2026-09-15 09:01:00");
        // The last *returned* row, never the extra one that proved there is more.
        assertThat(decoded.id()).isEqualTo(1L);
    }

    /**
     * C-141 · the three review counts reach the wire untouched.
     *
     * <p>They are computed in SQL, per row, and the service must not
     * second-guess them: the highlight on My Tasks is the one figure on the
     * page that has to be exact the instant a row moves, and a client that
     * re-derives it from the task's status would be wrong the moment a task
     * holds rows in two states at once — which is now the ordinary case.
     */
    @Test
    @DisplayName("the review counts pass straight through")
    void carriesTheReviewCounts() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, NOW, 2, 1, 3)));

        ObMyTask task = service.list(ME, false, null, 10).data().getFirst();

        assertThat(task.rowsOut()).isEqualTo(2);
        assertThat(task.rowsReturned()).isEqualTo(1);
        assertThat(task.rowsApproved()).isEqualTo(3);
    }

    /**
     * The fourth {@code MINE} clause, told apart from the other three. This is
     * what the "Pending for verification" tab is — nothing else on the row
     * says whether a {@code PENDING_REVIEW} task is the caller's own
     * submission or a review sitting on their desk.
     */
    @Test
    @DisplayName("a row reviewed by the caller carries pendingMyVerification through")
    void carriesPendingMyVerificationThrough() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, NOW, 0, 0, 0, true), row(2L, NOW, 0, 0, 0, false)));

        List<ObMyTask> tasks = service.list(ME, false, null, 10).data();

        assertThat(tasks).extracting(ObMyTask::taskId, ObMyTask::pendingMyVerification)
                .containsExactly(tuple(1L, true), tuple(2L, false));
    }

    /**
     * Page-independent, unlike the row-level flag above: a caller who reviews
     * at least one project must see the tab even on a page whose own ten rows
     * happen to hold none of their reviews.
     */
    @Test
    @DisplayName("isReviewerForAnyProject reflects the repository, not this page's rows")
    void carriesReviewerForAnyProjectThrough() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, NOW, 0, 0, 0, false)));
        when(reads.reviewsAnyProject(ME, false)).thenReturn(true);

        ObMyTaskListResponse response = service.list(ME, false, null, 10);

        assertThat(response.meta().isReviewerForAnyProject()).isTrue();
    }

    @Test
    @DisplayName("a caller who reviews nothing gets no such tab")
    void reviewerForAnyProjectDefaultsFalse() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt())).thenReturn(List.of());
        when(reads.reviewsAnyProject(ME, false)).thenReturn(false);

        assertThat(service.list(ME, false, null, 10).meta().isReviewerForAnyProject()).isFalse();
    }

    @Test
    @DisplayName("a decoded cursor is handed to the query")
    void passesTheCursorThrough() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt())).thenReturn(List.of());
        String encoded = new Cursor("2026-09-16 13:00:00", 900L).encode();

        service.list(ME, false, encoded, null);

        ArgumentCaptor<Cursor> captor = ArgumentCaptor.forClass(Cursor.class);
        verify(reads).openTasksOf(anyLong(), anyBoolean(), captor.capture(), anyInt());
        assertThat(captor.getValue().id()).isEqualTo(900L);
        assertThat(captor.getValue().sortKey()).isEqualTo("2026-09-16 13:00:00");
    }

    /** A forged or truncated cursor means the first page, never an error. */
    @Test
    @DisplayName("an unreadable cursor is the first page")
    void treatsAForgedCursorAsTheFirstPage() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt())).thenReturn(List.of());

        service.list(ME, false, "not-a-cursor", null);

        verify(reads).openTasksOf(ME, false, null, 51);
    }

    @Test
    @DisplayName("the last page has nowhere to resume to")
    void reportsTheLastPage() {
        when(reads.openTasksOf(anyLong(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row(1L, NOW)));

        ObMyTaskListResponse response = service.list(ME, false, null, 10);

        assertThat(response.meta().hasMore()).isFalse();
        assertThat(response.meta().nextCursor()).isNull();
    }

    @Test
    @DisplayName("one task of the caller's carries the same derived fields")
    void findsOneTask() {
        when(reads.findOwnTask(ME, false, 900L)).thenReturn(Optional.of(row(900L, NOW.minusSeconds(60))));

        Optional<ObMyTask> found = service.findOwnTask(ME, false, 900L);

        assertThat(found).isPresent();
        assertThat(found.get().taskId()).isEqualTo(900L);
        assertThat(found.get().isOverdue()).isTrue();
        assertThat(found.get().stepName()).isEqualTo("Configuration");
    }

    /** Empty, which the controller answers as a 404 — never a 403. */
    @Test
    @DisplayName("a task that is not the caller's is absent, not refused")
    void answersEmptyForSomebodyElsesTask() {
        when(reads.findOwnTask(ME, false, 900L)).thenReturn(Optional.empty());

        assertThat(service.findOwnTask(ME, false, 900L)).isEmpty();
    }
}
