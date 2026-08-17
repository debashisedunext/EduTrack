package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.domain.tickets.TicketComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * C-029 · the one read {@code TicketCommentRepository} cannot serve: a cursor
 * page of a ticket's thread, optionally narrowed to one cycle.
 *
 * <p>The domain repository has three derived queries and none of them fits.
 * {@code findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc} returns the whole
 * thread with no bound, which is what {@code TicketDetailService} wants and what
 * a paginated route must not do. The journey-filtered one requires stage
 * <em>and</em> iteration alongside the cycle, so it cannot answer
 * {@code ?cycle=2} — a cycle's comments span every stage in it.
 *
 * <h2>Why a second repository rather than a method on the first</h2>
 *
 * <p>{@code TicketCommentRepository} lives in {@code backend/domain}, which is
 * Stream A's. The working agreement asks for sign-off rather than a quiet edit,
 * and this feature's neighbour has answered the same question the same way
 * twice — {@code AttachmentRows} and {@code AttachmentSettingsRepository} both
 * sit in Stream C's package for exactly this reason. A second Spring Data
 * interface over the same entity costs nothing at runtime; the proxies share one
 * persistence context.
 *
 * <p>Extends {@link Repository} rather than {@code JpaRepository}, which matters
 * more here than it looks. Comments are the one ticket-child table that
 * genuinely mutates — the baseline migration's own header says so, and C-033's
 * edit window and tombstone are why. That makes it the table where an
 * accidental {@code deleteById} is <em>most</em> plausible and least visible, so
 * the narrow interface publishes one read method and nothing else. The insert
 * goes through the domain repository's {@code save}, deliberately in a different
 * place from the reads.
 *
 * <p><b>Named for the rows, not the table.</b> Spring derives a bean name from
 * the simple class name and {@code TicketCommentRepository} is taken by
 * {@code domain} — a collision takes out every {@code @SpringBootTest} in the
 * module with a message naming neither.
 */
interface CommentRows extends Repository<TicketComment, Long> {

    /**
     * One keyset page of a ticket's live comments, oldest first.
     *
     * <p><b>Ascending, unlike every other paged list in the product.</b> A
     * thread is read top to bottom — the first comment is the one that gives the
     * rest their context, and a reader arriving at a ticket wants the beginning,
     * not the most recent remark. The ticket list sorts newest-first because it
     * is a work queue; this is a conversation. Reversing it later would silently
     * change what {@code ?cursor=} means for anyone holding one.
     *
     * <p>Keyset, not offset: {@code (createdAt, id)} rather than
     * {@code createdAt} alone, because {@code DATETIME(6)} ties are ordinary
     * here. Two comments posted in the same microsecond is unlikely; a fixture
     * or an import that inserts a thread with one timestamp is not, and an
     * offset page over a tie silently drops or repeats a row. Served by
     * {@code ix_comments_ticket (ticket_id, created_at)}, with the id comparison
     * falling out of the primary key.
     *
     * <p><b>Tombstones are returned, and C-033 changed that.</b> C-029 filtered
     * {@code isDeleted = false} here on the reasonable assumption that a deleted
     * comment should not appear — and a tombstone that is never returned is not a
     * tombstone, it is a delete. §4B.5 asks for the opposite in as many words
     * ("deletion leaves a tombstone"), the contract has typed
     * {@code Comment.isDeleted} as "tombstone; the row survives" since D-001,
     * and C-034's chronological timeline cannot place a comment the thread read
     * refuses to hand it. Filtering the row out would also make the whole feature
     * indistinguishable from a hard delete to anyone reading the API. The body is
     * cleared at deletion instead — see {@code CommentService#delete} — so
     * nothing sensitive rides along.
     *
     * <p>Note the domain repository's
     * {@code findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc} still filters,
     * and is deliberately left alone: it feeds {@code /full}'s projection, which
     * is Stream A's and already drifted from the contract (C-029's note).
     *
     * @param cycleNo when null, the whole thread across every cycle. A sealed
     *                cycle's comments stay readable, which is the point of
     *                sealing rather than removing one
     * @param afterAt null for the first page. Non-null values come from a
     *                {@code Cursor} this server minted; a forged or stale one
     *                decodes to null upstream and lands here as a first page
     *                rather than as a 400
     */
    @Query("""
            select c from TicketComment c
            where c.ticketId = :ticketId
              and (:cycleNo is null or c.cycleNo = :cycleNo)
              and (:afterAt is null
                   or c.createdAt > :afterAt
                   or (c.createdAt = :afterAt and c.id > :afterId))
            order by c.createdAt asc, c.id asc
            """)
    List<TicketComment> page(@Param("ticketId") Long ticketId,
                             @Param("cycleNo") Short cycleNo,
                             @Param("afterAt") Instant afterAt,
                             @Param("afterId") Long afterId,
                             Pageable pageable);

    /**
     * C-033 · one comment, for the edit and the delete.
     *
     * <p><b>Keyed by both ids rather than by the comment's own.</b> The ticket in
     * the path is not decoration: {@code ScopedTickets} has proved the caller may
     * see <em>that</em> ticket, and a lookup by {@code commentId} alone would let
     * {@code /tickets/{one-i-can-see}/comments/{one-i-cannot}} reach a row on a
     * ticket the caller has no scope for. Both conditions in one query means "no
     * such comment" and "not on this ticket" are answered from a single empty
     * {@link Optional}, which is what keeps them indistinguishable — see
     * {@link CommentNotFoundException}.
     *
     * <p>Tombstones are included. The delete is idempotent and needs to see one
     * to answer 204, and the edit needs to see one to say the comment is gone
     * rather than that it never existed.
     */
    @Query("""
            select c from TicketComment c
            where c.id = :commentId and c.ticketId = :ticketId
            """)
    Optional<TicketComment> find(@Param("ticketId") Long ticketId, @Param("commentId") Long commentId);
}
