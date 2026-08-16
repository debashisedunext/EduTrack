package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * C-029 · posting and reading a ticket's thread, blueprint §4B.5.
 *
 * <p>Four rules, in the order a write meets them:
 *
 * <ol>
 *   <li><b>Scope</b> — {@link ScopedTickets#require} first, so a caller who may
 *       not see the ticket gets A-035's 404 and never learns whether it exists.
 *       Everything below runs on a ticket already proven visible.</li>
 *   <li><b>Sanitise</b> — PLAN.md §3.9 on the write path, in
 *       {@link CommentSanitizer}. Before any length judgement, because
 *       sanitising changes the length.</li>
 *   <li><b>Internal unless asked otherwise</b> — §4B.5 and the §16 decision.
 *       See {@link #isInternal}.</li>
 *   <li><b>Stamp</b> — §4B.5's cycle and stage, copied rather than joined. See
 *       {@link #stamp}.</li>
 * </ol>
 *
 * <h2>What this task does not do</h2>
 *
 * <p>C-029 is the box and the thread. {@code PATCH} and {@code DELETE} are
 * C-033's and are deliberately absent — not stubbed, not routed. The contract
 * has declared them since D-001 and a client calling one today gets a 404 from
 * Spring, which is the honest answer for a verb this server does not serve; C-028
 * is the cautionary tale for the alternative, where a route the contract promised
 * and no server implemented left a delete button that worked against the mock and
 * did nothing in production for three tasks running.
 *
 * <p>The one thing that would be invisible later, and so is done now, is the
 * stamp — see {@link #stamp}.
 */
@Service
class CommentService {

    private final ScopedTickets tickets;
    private final TicketCommentRepository comments;
    private final CommentRows rows;
    private final CommentUserRefs people;
    private final CommentSanitizer sanitizer;
    private final CommentMentions mentions;
    private final CommentMentionNotifier mentionNotifier;

    CommentService(ScopedTickets tickets,
                   TicketCommentRepository comments,
                   CommentRows rows,
                   CommentUserRefs people,
                   CommentSanitizer sanitizer,
                   CommentMentions mentions,
                   CommentMentionNotifier mentionNotifier) {
        this.tickets = tickets;
        this.comments = comments;
        this.rows = rows;
        this.people = people;
        this.sanitizer = sanitizer;
        this.mentions = mentions;
        this.mentionNotifier = mentionNotifier;
    }

    /**
     * One cursor page of the thread, oldest first.
     *
     * @param cycle null for the whole thread. A sealed cycle's comments stay
     *              readable — preserving a cycle is the point of sealing it —
     *              so this narrows rather than restricts
     */
    @Transactional(readOnly = true)
    CursorPage<CommentDtos.CommentDto> list(Authentication caller,
                                            long ticketId,
                                            Integer cycle,
                                            String rawCursor,
                                            Integer rawLimit) {

        tickets.require(caller, ticketId);

        int limit = PageLimit.clamp(rawLimit);
        Cursor cursor = Cursor.decode(rawCursor);

        List<TicketComment> fetched = rows.page(
                ticketId,
                cycle == null ? null : cycle.shortValue(),
                cursor == null ? null : Instant.parse(cursor.sortKey()),
                cursor == null ? null : cursor.id(),
                PageRequest.of(0, PageLimit.fetchSize(limit)));

        CursorPage<TicketComment> page = CursorPage.of(fetched, limit,
                row -> new Cursor(row.getCreatedAt().toString(), row.getId()));

        // Resolved over the page rather than the fetch: one IN query for the
        // whole thread, and it must not include the extra boundary row's author,
        // who may not appear on this page at all.
        //
        // C-030 folds the mentioned ids into the same call rather than adding a
        // second. They are overwhelmingly the same people — a thread's mentions
        // are its participants — so a separate lookup would be a second IN query
        // over a set that mostly overlaps the first, and `resolve` already
        // de-duplicates.
        List<Long> wanted = new ArrayList<>(page.data().size());
        for (TicketComment row : page.data()) {
            wanted.add(row.getAuthorId());
            if (row.getMentionedUserIds() != null) {
                wanted.addAll(row.getMentionedUserIds());
            }
        }
        CommentUserRefs.Resolved resolved = people.resolve(wanted);

        return new CursorPage<>(
                page.data().stream()
                        .map(row -> CommentDtos.CommentDto.of(row, resolved.people(), resolved.roles()))
                        .toList(),
                page.meta());
    }

    /**
     * Posts a comment and moves {@code tickets.comment_count}.
     *
     * <p>The counter is maintained here because the baseline migration says so
     * in as many words — "materialised counters maintained by the service layer
     * on insert and tombstone… so the ticket list can render badges without a
     * correlated subquery per row". Nothing has maintained it until now, so
     * {@code TicketListDtos.commentCount} has been serving a hard zero on every
     * row since the list shipped. It is incremented inside the same transaction
     * as the insert, which is what keeps the two from disagreeing.
     */
    @Transactional
    CommentDtos.CommentDto create(Authentication caller,
                                  long ticketId,
                                  CommentDtos.CommentWriteRequest request) {

        Ticket ticket = tickets.require(caller, ticketId);

        if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            throw InvalidCommentException.attachmentsNotSupported();
        }

        // §3.9 before the length check, and before anything is judged: the
        // sanitiser is what decides both what "empty" means and what the stored
        // length is, and neither question can be answered from the raw string.
        String html = sanitizer.sanitize(request.body());
        if (html.isEmpty()) {
            throw InvalidCommentException.emptyBody();
        }
        if (html.length() > CommentSanitizer.MAX_LENGTH) {
            throw InvalidCommentException.tooLong(html.length());
        }

        long author = authorId(caller);
        String plainText = sanitizer.toPlainText(html);

        // C-030. Parsed from the body, resolved against the ticket's project —
        // see #mentioned for why the request's own list is not what is stored.
        List<CommentMentions.MentionedUser> mentioned = mentioned(ticket, plainText);

        TicketComment row = new TicketComment();
        row.setTicketId(ticketId);
        row.setAuthorId(author);
        row.setBodyHtml(html);
        row.setBodyText(plainText);
        row.setInternal(isInternal(request));
        // Stored only when there is something to store, so a null column means
        // "nobody was mentioned" rather than "mentions were parsed and the list
        // was empty" — a distinction worth keeping now that something reads it.
        row.setMentionedUserIds(mentioned.isEmpty()
                ? null
                : mentioned.stream().map(CommentMentions.MentionedUser::id).toList());
        row.setSource("WEB");
        stamp(row, ticket);

        TicketComment saved = comments.save(row);
        ticket.setCommentCount(ticket.getCommentCount() + 1);

        CommentUserRefs.Resolved resolved = people.resolve(mentionedAnd(saved, mentioned));

        // After the row exists, because the notification deep-links to its id,
        // and last because nothing below it may fail the write. The notifier
        // swallows and logs its own failures for that reason.
        if (!mentioned.isEmpty()) {
            CommentDtos.UserRef authorRef = resolved.people().get(author);
            mentionNotifier.mentioned(ticket, saved.getId(), author,
                    authorRef == null ? null : authorRef.displayName(), mentioned);
        }

        return CommentDtos.CommentDto.of(saved, resolved.people(), resolved.roles());
    }

    /**
     * C-030 · who this comment mentions, decided by the server.
     *
     * <p><b>{@code CommentWriteRequest.mentionUserIds} is not consulted.</b>
     * C-029 stored it verbatim, which was harmless while nothing read the column
     * and stops being harmless the moment a bell entry and an email hang off it:
     * a caller-supplied recipient list is a fan-out anybody can aim at anybody,
     * with no membership check in front of it. D-052 settled this for chat in as
     * many words — <em>"the server parses; the request never says who it
     * mentioned"</em> — and the same rule is applied here rather than a second,
     * weaker one for comments.
     *
     * <p>The field stays on the request because the contract declares it and an
     * older client may still send it. It is accepted and has no effect, which is
     * the one shape "accepted and ignored" is defensible in: C-028 and C-029
     * both refused that pattern where a client would be entitled to believe
     * something was stored, and here what the client asked for either names a
     * real project member — in which case the body already named them and they
     * are notified — or it does not, in which case honouring it is the bug.
     * Refusing with a 400 instead would break every client that sends the field
     * today for a request that is not wrong, only redundant.
     *
     * @return active project members named in the body, in id order, never null
     */
    private List<CommentMentions.MentionedUser> mentioned(Ticket ticket, String plainText) {
        if (ticket.getProjectId() == null) {
            // project_id is NOT NULL in the schema. Guarded rather than trusted
            // because the alternative is a NullPointerException on the write
            // path of a comment, and a ticket with no project is a ticket with
            // no members — the empty answer is also the correct one.
            return List.of();
        }
        return mentions.resolveProjectMembers(
                ticket.getProjectId(), CommentMentionParser.handles(plainText));
    }

    /** The author plus everyone mentioned, for one {@code IN} lookup rather than two. */
    private static List<Long> mentionedAnd(TicketComment saved,
                                           List<CommentMentions.MentionedUser> mentioned) {
        List<Long> ids = new ArrayList<>(mentioned.size() + 1);
        ids.add(saved.getAuthorId());
        for (CommentMentions.MentionedUser person : mentioned) {
            ids.add(person.id());
        }
        return ids;
    }

    /**
     * §4B.5's journey stamp, as far as it can honestly be written today.
     *
     * <p>The stamp is <b>copied, never joined</b> — the migration's own comment
     * makes the point: "the ribbon moves on, and a comment written during QA
     * iteration 2 must still read as QA iteration 2 forever". That is also why
     * this is written in C-029 rather than left to C-032, which owns *displaying*
     * it. A stamp is the one field that cannot be backfilled: once the ticket has
     * moved on, what stage it was in when a comment was written is gone. Leaving
     * the columns null until C-032 lands would mean every comment posted in the
     * meantime is permanently unplaceable in the journey, and C-034's timeline
     * would have a hole in it that no later task could repair.
     *
     * <p><b>{@code iterationNo} stays null, deliberately.</b> Cycle and stage are
     * on the ticket row and can simply be read. An iteration number lives on the
     * open {@code ticket_stage_transitions} row, and nothing in this codebase can
     * read one yet — C-042 is the task that makes it readable. Writing {@code 1}
     * to fill the column would be worse than leaving it empty: a real first
     * iteration is also {@code 1}, so the guess would be indistinguishable from
     * the fact, and C-032 would have no way to find the rows needing repair.
     */
    private static void stamp(TicketComment row, Ticket ticket) {
        row.setCycleNo(ticket.getCurrentCycleNo());
        row.setStageCode(ticket.getCurrentStage());
        row.setIterationNo(null);
    }

    /**
     * §4B.5's visibility, defaulting to internal — <b>always</b>.
     *
     * <p>The blueprint's §4B.5 table says the default "follows whether the ticket
     * is client-raised". The contract, the migration's column default, the mock
     * and §16's own recommendation all say internal-always, and §16 is the later
     * word: "an accidental leak is far costlier than an extra click". PLAN.md §5
     * lists this among the accepted deviations, so this is not a quiet
     * disagreement with the blueprint.
     *
     * <p>Null becomes internal, which is the whole point of the boxed
     * {@code Boolean}: a client that forgets the field, an older client, an email
     * importer and a fixture all get the safe answer, and none of them has to
     * remember to ask for it.
     */
    private static boolean isInternal(CommentDtos.CommentWriteRequest request) {
        return !Boolean.TRUE.equals(request.isClientVisible());
    }

    /**
     * {@code author_id} is {@code NOT NULL}, so unlike an attachment's
     * {@code uploaded_by} there is no null to fall back on.
     *
     * <p>Unreachable in practice — the route is behind
     * {@code hasAuthority('ticket.update_progress')}, so an unauthenticated
     * caller is refused before the service runs, and both the JWT and the
     * {@code dev-noauth} principal yield an identity. It throws rather than
     * inventing a system user because a comment attributed to nobody is a record
     * that cannot be read back, and a 500 naming the cause is a better outcome
     * than a row that permanently claims an author it does not have.
     */
    private static long authorId(Authentication caller) {
        return CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "a comment reached the service with no identifiable author; "
                                + "the route's @PreAuthorize should have refused this caller"));
    }
}
