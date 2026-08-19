package com.edunext.edutrack.api.feature.tickets.history;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-059 · what the History tab needs from {@code GET /tickets/{ticketId}/history}.
 *
 * <p>A unit test with mocked collaborators, following {@code EffortLogServiceTest}'s
 * own note: none of this is a database behaviour, and the journal's own
 * guarantees are proved elsewhere. What is asserted here is what this service
 * does with what the journal and the comment repository hand it — the cycle
 * filter reaching both sources, the merge-and-sort across two id spaces, and
 * that a hash never reaches the DTO.
 */
class TicketHistoryServiceTest {

    private static final long TICKET = 4711L;
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long ACTOR = 88L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final TicketCommentRepository comments = mock(TicketCommentRepository.class);
    private final TicketHistoryUserRefs people = mock(TicketHistoryUserRefs.class);

    private final TicketHistoryService service = new TicketHistoryService(tickets, journal, comments, people);

    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of()),
            "n/a", "isAuthenticated");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", TICKET);
        ReflectionTestUtils.setField(ticket, "ticketCode", TICKET_CODE);
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(people.resolve(any())).thenReturn(Map.of());
    }

    // ── the read path ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reading history")
    class Reading {

        @Test
        @DisplayName("cycle is passed through to the journal as a Short, null meaning every cycle")
        void cyclePassesThroughAsShort() {
            when(journal.historyFor(eq(TICKET), any())).thenReturn(List.of());

            service.list(caller, TICKET_CODE, 2, null, null, null);
            verify(journal).historyFor(TICKET, (short) 2);

            service.list(caller, TICKET_CODE, null, null, null, null);
            verify(journal).historyFor(eq(TICKET), isNull());
        }

        @Test
        @DisplayName("maps a row to the contract shape, and never exposes prevHash or rowHash")
        void mapsToContractShape() {
            TicketHistory row = historyRow(501L, "LEVEL_CHANGED", "level", "HIGH", "CRITICAL",
                    ACTOR, "USER", "SLA breach", Instant.parse("2026-08-13T10:15:00Z"));
            when(journal.historyFor(TICKET, null)).thenReturn(List.of(row));
            when(people.resolve(any())).thenReturn(Map.of(ACTOR, new TicketHistoryDtos.UserRef(ACTOR, "Ravi Kumar")));

            TicketHistoryDtos.HistoryListResponse response = service.list(caller, TICKET_CODE, null, null, null, null);

            assertThat(response.data()).hasSize(1);
            TicketHistoryDtos.HistoryEntryDto dto = response.data().get(0);
            assertThat(dto.id()).isEqualTo(501L);
            assertThat(dto.action()).isEqualTo("LEVEL_CHANGED");
            assertThat(dto.actor()).isEqualTo(new TicketHistoryDtos.UserRef(ACTOR, "Ravi Kumar"));
            assertThat(dto.fieldName()).isEqualTo("level");
            assertThat(dto.oldValue()).isEqualTo("HIGH");
            assertThat(dto.newValue()).isEqualTo("CRITICAL");
            assertThat(dto.note()).isEqualTo("SLA breach");
            // HistoryEntryDto has no hash field at all — TicketJournal.historyFor's
            // own javadoc says a client-facing render should drop it, and the type
            // itself is the enforcement: there is nothing to assert null on.
        }

        @Test
        @DisplayName("a SYSTEM row's actor renders null rather than resolving actorId 0/absent")
        void systemRowHasNoActor() {
            TicketHistory row = historyRow(502L, "STATUS_CHANGED", "status", "OPEN", "RESOLVED",
                    null, "SYSTEM", null, Instant.parse("2026-08-13T09:00:00Z"));
            when(journal.historyFor(TICKET, null)).thenReturn(List.of(row));

            TicketHistoryDtos.HistoryListResponse response = service.list(caller, TICKET_CODE, null, null, null, null);

            assertThat(response.data().get(0).actor()).isNull();
            assertThat(response.data().get(0).actorType()).isEqualTo("SYSTEM");
        }
    }

    // ── include=comments ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("include=comments")
    class IncludeComments {

        @Test
        @DisplayName("without it, the comment repository is never queried")
        void notQueriedByDefault() {
            when(journal.historyFor(TICKET, null)).thenReturn(List.of());

            service.list(caller, TICKET_CODE, null, null, null, null);

            verify(comments, never()).findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(any());
        }

        @Test
        @DisplayName("interleaves comments with history in one chronological stream")
        void interleavesChronologically() {
            TicketHistory earlyHistory = historyRow(1L, "STATUS_CHANGED", "status", "OPEN", "IN_PROGRESS",
                    ACTOR, "USER", null, Instant.parse("2026-08-06T14:00:00Z"));
            TicketHistory lateHistory = historyRow(2L, "LEVEL_CHANGED", "level", "HIGH", "CRITICAL",
                    ACTOR, "USER", null, Instant.parse("2026-08-06T16:40:00Z"));
            TicketComment comment = commentRow(9L, "Root cause is the retry timeout.",
                    Instant.parse("2026-08-06T14:22:00Z"));

            when(journal.historyFor(TICKET, null)).thenReturn(List.of(earlyHistory, lateHistory));
            when(comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET)).thenReturn(List.of(comment));

            TicketHistoryDtos.HistoryListResponse response =
                    service.list(caller, TICKET_CODE, null, List.of("comments"), null, null);

            assertThat(response.data()).extracting(TicketHistoryDtos.HistoryEntryDto::action)
                    .containsExactly("STATUS_CHANGED", "COMMENTED", "LEVEL_CHANGED");
        }

        @Test
        @DisplayName("a comment's id is 100_000 + comment.id, so it never collides with a real history id")
        void syntheticIdIsOffset() {
            when(journal.historyFor(TICKET, null)).thenReturn(List.of());
            when(comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET))
                    .thenReturn(List.of(commentRow(9L, "hello", Instant.parse("2026-08-06T14:22:00Z"))));

            TicketHistoryDtos.HistoryListResponse response =
                    service.list(caller, TICKET_CODE, null, List.of("comments"), null, null);

            assertThat(response.data().get(0).id()).isEqualTo(100_009L);
            assertThat(response.data().get(0).note()).isEqualTo("hello");
        }

        @Test
        @DisplayName("a cycle filter narrows comments the same way it narrows history")
        void cycleFilterAppliesToComments() {
            when(journal.historyFor(TICKET, (short) 2)).thenReturn(List.of());
            TicketComment cycle1 = commentRow(1L, "cycle 1 note", Instant.parse("2026-08-06T14:00:00Z"));
            ReflectionTestUtils.setField(cycle1, "cycleNo", (short) 1);
            TicketComment cycle2 = commentRow(2L, "cycle 2 note", Instant.parse("2026-08-07T14:00:00Z"));
            ReflectionTestUtils.setField(cycle2, "cycleNo", (short) 2);
            when(comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET))
                    .thenReturn(List.of(cycle1, cycle2));

            TicketHistoryDtos.HistoryListResponse response =
                    service.list(caller, TICKET_CODE, 2, List.of("comments"), null, null);

            assertThat(response.data()).hasSize(1);
            assertThat(response.data().get(0).note()).isEqualTo("cycle 2 note");
        }
    }

    // ── pagination ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("hasMore follows CursorPage's fetch-one-extra rule, not rows.size() == limit")
        void hasMoreFollowsFetchOneExtra() {
            List<TicketHistory> rows = List.of(
                    historyRow(1L, "CREATED", null, null, null, ACTOR, "USER", null, Instant.parse("2026-08-06T10:00:00Z")),
                    historyRow(2L, "STATUS_CHANGED", null, null, null, ACTOR, "USER", null, Instant.parse("2026-08-06T11:00:00Z")),
                    historyRow(3L, "STATUS_CHANGED", null, null, null, ACTOR, "USER", null, Instant.parse("2026-08-06T12:00:00Z")));
            when(journal.historyFor(TICKET, null)).thenReturn(rows);

            TicketHistoryDtos.HistoryListResponse response = service.list(caller, TICKET_CODE, null, null, null, 2);

            assertThat(response.data()).hasSize(2);
            assertThat(response.meta().hasMore()).isTrue();
            assertThat(response.meta().nextCursor()).isNotNull();
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static TicketHistory historyRow(long id, String eventType, String fieldName,
                                            String oldValue, String newValue,
                                            Long actorId, String actorType, String remarks,
                                            Instant createdAt) {
        TicketHistory h = new TicketHistory();
        ReflectionTestUtils.setField(h, "id", id);
        h.setTicketId(TICKET);
        h.setEventType(eventType);
        h.setFieldName(fieldName);
        h.setOldValue(oldValue);
        h.setNewValue(newValue);
        h.setActorId(actorId);
        h.setActorType(actorType);
        h.setRemarks(remarks);
        ReflectionTestUtils.setField(h, "createdAt", createdAt);
        return h;
    }

    private static TicketComment commentRow(long id, String bodyText, Instant createdAt) {
        TicketComment c = new TicketComment();
        ReflectionTestUtils.setField(c, "id", id);
        c.setTicketId(TICKET);
        c.setAuthorId(ACTOR);
        c.setBodyHtml(bodyText);
        c.setBodyText(bodyText);
        ReflectionTestUtils.setField(c, "createdAt", createdAt);
        return c;
    }
}
