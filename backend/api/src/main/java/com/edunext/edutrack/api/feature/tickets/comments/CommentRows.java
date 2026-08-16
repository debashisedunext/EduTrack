package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.domain.tickets.TicketComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

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
     * <p>{@code isDeleted = false} filters C-033's tombstones. None exist yet —
     * nothing deletes a comment until that task — but the thread read is the
     * wrong place to discover that later, and the index covers it either way.
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
              and c.isDeleted = false
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
}
