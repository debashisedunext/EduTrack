package com.edunext.edutrack.api.feature.onboarding.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-112 · the paging and the mark-read outcomes, without a database.
 *
 * <p>The repository is mocked because what is under test here is the arithmetic
 * around it: whether the extra row is stripped before the page goes out, what
 * the cursor is drawn from, and how "already read" is told apart from "not
 * yours". {@code ObNotificationCentreIT} covers the SQL.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObNotificationServiceTest {

    private static final long ME = 7;

    private final ObNotificationReadRepository repository = mock(ObNotificationReadRepository.class);
    private final ObNotificationService service = new ObNotificationService(repository);

    @Test
    @DisplayName("the extra row is asked for, and never sent")
    void theProbeRowIsStripped() {
        // Ten asked for; eleven come back; ten go out. The eleventh is how
        // hasMore is answered without a second COUNT over the same predicate,
        // and sending it would make every page one row longer than requested.
        when(repository.list(eq(ME), any(), anyBoolean(), any(), eq(11))).thenReturn(rows(11));
        when(repository.unreadCount(ME)).thenReturn(3);

        var page = service.list(ME, ObNotificationTab.ALL, false, null, 10);

        assertThat(page.data()).hasSize(10);
        assertThat(page.meta().page().hasMore()).isTrue();
        // The cursor is the last row *sent*, not the last row fetched — a cursor
        // taken from the probe would skip a notification on every page turn.
        assertThat(page.meta().page().nextCursor()).isEqualTo("10");
    }

    @Test
    void a_short_page_has_no_cursor() {
        when(repository.list(eq(ME), any(), anyBoolean(), any(), anyInt())).thenReturn(rows(4));
        when(repository.unreadCount(ME)).thenReturn(0);

        var page = service.list(ME, ObNotificationTab.ALL, false, null, 10);

        assertThat(page.data()).hasSize(4);
        assertThat(page.meta().page().hasMore()).isFalse();
        assertThat(page.meta().page().nextCursor()).isNull();
    }

    @Test
    void an_exactly_full_page_has_no_cursor_either() {
        // Ten asked for, ten came back, so the eleventh does not exist.
        when(repository.list(eq(ME), any(), anyBoolean(), any(), eq(11))).thenReturn(rows(10));

        var page = service.list(ME, ObNotificationTab.ALL, false, null, 10);

        assertThat(page.data()).hasSize(10);
        assertThat(page.meta().page().hasMore()).isFalse();
    }

    /**
     * The badge is the caller's total, whatever tab is open. A badge that
     * changed as you clicked between tabs would be answering a question nobody
     * asked, on a control read on every page load.
     */
    @Test
    void the_unread_count_is_the_total_and_not_the_tabs() {
        when(repository.list(eq(ME), any(), anyBoolean(), any(), anyInt())).thenReturn(rows(1));
        when(repository.unreadCount(ME)).thenReturn(42);

        var page = service.list(ME, ObNotificationTab.ESCALATIONS, false, null, 10);

        assertThat(page.data()).hasSize(1);
        assertThat(page.meta().unreadCount()).isEqualTo(42);
        // Unqualified by the tab — the count query takes no categories at all.
        verify(repository).unreadCount(ME);
    }

    @Test
    void a_tab_passes_its_categories_and_all_passes_none() {
        when(repository.list(anyLong(), any(), anyBoolean(), any(), anyInt())).thenReturn(List.of());

        service.list(ME, ObNotificationTab.ESCALATIONS, false, null, 10);
        verify(repository).list(ME, null, false, List.of("ESCALATION"), 11);

        service.list(ME, ObNotificationTab.ALL, false, null, 10);
        verify(repository).list(ME, null, false, List.of(), 11);
    }

    // ── mark read ───────────────────────────────────────────────────────────

    @Test
    void marking_an_unread_entry_marks_it() {
        when(repository.markRead(5, ME)).thenReturn(true);

        assertThat(service.markRead(5, ME)).isEqualTo(ObNotificationService.ReadOutcome.MARKED);
        // No existence probe when the update itself succeeded.
        verify(repository, never()).exists(anyLong(), anyLong());
    }

    @Test
    @DisplayName("re-reading something already read is ALREADY_READ, not NOT_FOUND")
    void anAlreadyReadEntryIsNotAMiss() {
        // The UPDATE carries `is_read = 0`, so it changes nothing and reports
        // nothing changed — which on its own is indistinguishable from "not
        // yours". The existence probe is what separates them, and the separation
        // is the difference between a 204 and a 404.
        when(repository.markRead(5, ME)).thenReturn(false);
        when(repository.exists(5, ME)).thenReturn(true);

        assertThat(service.markRead(5, ME)).isEqualTo(ObNotificationService.ReadOutcome.ALREADY_READ);
    }

    /**
     * <b>Somebody else's entry, and the reason it is a 404.</b> The probe is
     * itself scoped by user, so "not yours" and "no such row" answer the same
     * way — a 403 would confirm that entry 5 exists and belongs to somebody
     * else.
     */
    @Test
    void an_entry_that_is_not_yours_is_not_found() {
        when(repository.markRead(5, ME)).thenReturn(false);
        when(repository.exists(5, ME)).thenReturn(false);

        assertThat(service.markRead(5, ME)).isEqualTo(ObNotificationService.ReadOutcome.NOT_FOUND);
    }

    @Test
    void mark_all_read_takes_no_tab() {
        when(repository.markAllRead(ME)).thenReturn(9);

        assertThat(service.markAllRead(ME)).isEqualTo(9);
        verify(repository).markAllRead(ME);
    }

    /** Ids 1..n, newest first — the order the repository returns them in. */
    private static List<ObNotificationReadRepository.ObNotificationRow> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ObNotificationReadRepository.ObNotificationRow(
                        i, "TAT_BREACHED", "ESCALATION", "Overdue: step " + i,
                        "The onboarding is held up.", "/onboarding/clients/1",
                        1L, 2L, 3L, false, Timestamp.from(Instant.parse("2026-09-04T10:00:00Z"))))
                .toList();
    }
}
