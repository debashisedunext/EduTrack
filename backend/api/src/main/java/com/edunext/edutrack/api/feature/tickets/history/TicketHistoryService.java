package com.edunext.edutrack.api.feature.tickets.history;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * C-059 · {@code GET /tickets/{ticketId}/history} — read only, cycle-grouped,
 * blueprint §7/§4B.5.
 *
 * <h2>One append-only source, one comment repository, no new write path</h2>
 *
 * <p>The append-only journal already exists — A-040/A-042 built
 * {@link TicketJournal}, and quick-update, priority-change and reopen already
 * write through it. This task reads it. {@link TicketJournal#historyFor} is
 * the sanctioned door; nothing here touches {@code ticket_history}'s
 * repository directly, for the reason {@code TicketJournal}'s own comment on
 * that method states — a class holding the repository is one edit away from
 * calling {@code save} on it, and the door stays exactly one class wide.
 *
 * <h2>{@code include=comments} is read straight from the domain repository</h2>
 *
 * <p>{@code TicketCommentRepository.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc}
 * is public and is already {@code TicketDetailService}'s own path to the same
 * rows for {@code /full}'s {@code comments} array — reused here rather than
 * reached through {@code comments.CommentService}, which is package-private
 * and returns a package-private DTO neither visible from this package. A
 * second read path over one repository costs nothing at runtime; the two
 * proxies share one persistence context.
 *
 * <h2>Synthetic ids, not a second id space</h2>
 *
 * <p>A comment folded into the stream is given the id {@code 100_000 +
 * comment.id} — {@code frontend/src/mocks/handlers/tickets.ts}'s own offset,
 * kept identical so the mock and this endpoint agree on what a client sees
 * either way. It is what makes a merged, cursor-paged stream of two different
 * id spaces resumable: a bare {@code history.id} and {@code comment.id} can
 * collide, and a cursor naming only a number could not then say which table it
 * meant.
 */
@Service
class TicketHistoryService {

    private static final long COMMENT_ID_OFFSET = 100_000L;
    private static final String INCLUDE_COMMENTS = "comments";

    private final ScopedTickets tickets;
    private final TicketJournal journal;
    private final TicketCommentRepository comments;
    private final TicketHistoryUserRefs people;

    TicketHistoryService(ScopedTickets tickets, TicketJournal journal, TicketCommentRepository comments,
                         TicketHistoryUserRefs people) {
        this.tickets = tickets;
        this.journal = journal;
        this.comments = comments;
        this.people = people;
    }

    /**
     * One cursor page of the history stream, oldest first — the order the chain
     * was built in and the order a history tab reads in.
     *
     * @param cycle   null means every cycle, unfiltered — the History tab's own
     *                default is the cycle-grouped view of the whole ticket, not
     *                one cycle at a time. A caller narrowing to one cycle (the
     *                same {@code ?cycle=} a cycle selector would send) gets that
     *                cycle's slice of both journals
     * @param include {@code null} or empty means field changes and handoffs
     *                only. {@code "comments"} interleaves the ticket's live
     *                comments into the same chronological stream — the contract's
     *                own words are "two separate lists that the reader has to
     *                reconcile by timestamp is the thing this avoids".
     *                {@code "attachments"} is declared on the contract and not
     *                honoured yet; C-060 is the attachments tab and nothing here
     *                pretends otherwise by silently ignoring the value
     */
    @Transactional(readOnly = true)
    TicketHistoryDtos.HistoryListResponse list(Authentication caller, String ticketCode,
                                               Integer cycle, List<String> include,
                                               String rawCursor, Integer rawLimit) {

        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        Short cycleNo = cycle == null ? null : cycle.shortValue();

        List<Row> merged = new ArrayList<>();
        for (TicketHistory row : journal.historyFor(ticket.getId(), cycleNo)) {
            merged.add(new Row(row.getId(), row.getCreatedAt(), row, null));
        }

        if (include != null && include.contains(INCLUDE_COMMENTS)) {
            for (TicketComment row : comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticket.getId())) {
                if (cycleNo != null && !Objects.equals(cycleNo, row.getCycleNo())) {
                    continue;
                }
                merged.add(new Row(COMMENT_ID_OFFSET + row.getId(), row.getCreatedAt(), null, row));
            }
        }

        // Oldest first, ties broken by id — the same keyset order every other
        // paged listing in this package's neighbours uses, and what keeps a
        // history row and a comment posted in the same instant in a stable order
        // across pages rather than reshuffling between requests.
        merged.sort(Comparator.comparing(Row::createdAt).thenComparingLong(Row::id));

        Cursor cursor = Cursor.decode(rawCursor);
        List<Row> afterCursor = cursor == null
                ? merged
                : merged.stream().filter(row -> row.id() > cursor.id()).toList();

        int limit = PageLimit.clamp(rawLimit);
        List<Row> fetched = afterCursor.stream().limit(PageLimit.fetchSize(limit)).toList();
        CursorPage<Row> page = CursorPage.of(fetched, limit, row -> new Cursor(String.valueOf(row.id()), row.id()));

        List<Long> actorIds = new ArrayList<>(page.data().size());
        for (Row row : page.data()) {
            actorIds.add(row.history() != null ? row.history().getActorId() : row.comment().getAuthorId());
        }
        Map<Long, TicketHistoryDtos.UserRef> resolved = people.resolve(actorIds);

        List<TicketHistoryDtos.HistoryEntryDto> data = page.data().stream()
                .map(row -> row.history() != null
                        ? TicketHistoryDtos.HistoryEntryDto.of(row.history(), resolved)
                        : TicketHistoryDtos.HistoryEntryDto.ofComment(
                                row.comment(), row.id(), resolved.get(row.comment().getAuthorId())))
                .toList();

        return new TicketHistoryDtos.HistoryListResponse(data, page.meta());
    }

    /**
     * One merged row, from either source — exactly one of {@code history} or
     * {@code comment} is non-null. {@code id} already carries the comment offset
     * where it applies, so sorting and cursoring never need to ask which source a
     * row came from.
     */
    private record Row(long id, Instant createdAt, TicketHistory history, TicketComment comment) {
    }
}
