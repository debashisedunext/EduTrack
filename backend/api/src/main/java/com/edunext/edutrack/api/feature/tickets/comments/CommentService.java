package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <h2>C-033 · the two writes that change a comment after it exists</h2>
 *
 * <p>{@link #edit} and {@link #delete}, blueprint §4B.5's immutability row:
 * <em>"editable for 5 minutes; after that the comment is locked and shows an
 * 'edited' marker with the original preserved. Deletion leaves a tombstone. No
 * role, including Admin, can silently rewrite a comment."</em>
 *
 * <p>That last sentence is the one the whole task turns on, and it produces an
 * asymmetry worth stating up front, because it looks like an inconsistency:
 *
 * <ul>
 *   <li><b>Editing is the author's alone</b>, and no role widens it. An Admin
 *       editing somebody else's comment would leave the thread attributing the
 *       new wording to the original author — which is the rewrite the sentence
 *       forbids, marker or no marker.</li>
 *   <li><b>Deleting widens to PM and Admin</b>, following C-028's identical
 *       widening for attachments and for the identical reason: a comment posted
 *       client-visible by mistake is a disclosure, and waiting for its author to
 *       come back from leave is not a remedy. Their act is never silent, because
 *       <em>every</em> deletion here leaves a tombstone naming who did it.</li>
 * </ul>
 *
 * <p>Note the second bullet's "every". C-028 derives whether an attachment's
 * tombstone is <em>visible</em> from the window and the actor, because §4B.4 says
 * "the uploader may delete within 15 minutes; after that it is a soft delete
 * leaving a tombstone" — two cases, spelled out. §4B.5 says only "deletion leaves
 * a tombstone", with the five minutes attached to editing and to nothing else. So
 * there is no silent branch here and no window on the delete at all, which is
 * both the simpler rule and the stricter one.
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
    /**
     * D-037 · §4B.6 row 9, "Comment added → Assignee, watchers". **Stream D's
     * producer, injected into Stream C's service** — the arrangement
     * `mentionNotifier` above already established. Flagged for Divyansh in the
     * pull request rather than added quietly.
     */
    private final TicketEventNotifier eventNotifier;
    private final CommentProperties properties;
    private final Clock clock;
    /**
     * C-032 · the sanctioned read for "where is this ticket right now",
     * needed to stamp {@code iterationNo} — see {@link #currentIterationNo}.
     * {@code TransitionService} injects the same bean for the same door.
     */
    private final TicketJournal journal;

    /*
     * @Autowired is required, not decoration. Two constructors means Spring has
     * no candidate to prefer and fails at startup with "no default constructor
     * found", which names neither the class's real problem nor this file —
     * AttachmentService carries the same marker for the same pair of
     * constructors, and the omission here cost a red PermissionMatrixTest run
     * before it was spotted.
     */
    @Autowired
    CommentService(ScopedTickets tickets,
                   TicketCommentRepository comments,
                   CommentRows rows,
                   CommentUserRefs people,
                   CommentSanitizer sanitizer,
                   CommentMentions mentions,
                   CommentMentionNotifier mentionNotifier,
                   TicketEventNotifier eventNotifier,
                   CommentProperties properties,
                   TicketJournal journal) {
        this(tickets, comments, rows, people, sanitizer, mentions, mentionNotifier, eventNotifier,
                properties, journal, Clock.systemUTC());
    }

    /**
     * The clock is injectable for the same reason {@code AttachmentService}'s is:
     * §4B.5's window is the rule under test, and a test that proved it by waiting
     * five minutes would not be run.
     */
    CommentService(ScopedTickets tickets,
                   TicketCommentRepository comments,
                   CommentRows rows,
                   CommentUserRefs people,
                   CommentSanitizer sanitizer,
                   CommentMentions mentions,
                   CommentMentionNotifier mentionNotifier,
                   TicketEventNotifier eventNotifier,
                   CommentProperties properties,
                   TicketJournal journal,
                   Clock clock) {
        this.tickets = tickets;
        this.comments = comments;
        this.rows = rows;
        this.people = people;
        this.sanitizer = sanitizer;
        this.mentions = mentions;
        this.mentionNotifier = mentionNotifier;
        this.eventNotifier = eventNotifier;
        this.properties = properties;
        this.journal = journal;
        this.clock = clock;
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
                                            String ticketCode,
                                            Integer cycle,
                                            String rawCursor,
                                            Integer rawLimit) {

        // The identifier is the ticket CODE. The contract's TicketId is a code
        // and it is the same $ref every ticket sub-resource uses, so a code is
        // what every real caller sends. These four methods took a long until
        // 21 Aug and answered 400 to all of them — see TicketDetailController.
        long ticketId = tickets.requireByCode(caller, ticketCode).getId();

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
            // C-033 · the tombstone's actor, folded into the same lookup for the
            // same reason the mentions were. It is usually the author and almost
            // always somebody already on the page; a second IN query for the
            // handful of removals in a thread would be the n+1 this method was
            // written to avoid, arriving by the back door.
            wanted.add(row.getDeletedBy());
        }
        CommentUserRefs.Resolved resolved = people.resolve(wanted);

        return new CursorPage<>(
                page.data().stream()
                        .map(row -> dto(row, resolved))
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
                                  String ticketCode,
                                  CommentDtos.CommentWriteRequest request) {

        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();

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

        CallerIdentity identity = requireIdentity(caller);
        long author = identity.userId();
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
        stamp(row, ticket, identity.roleCode());

        TicketComment saved = comments.save(row);
        ticket.setCommentCount(ticket.getCommentCount() + 1);

        CommentUserRefs.Resolved resolved = people.resolve(mentionedAnd(saved, mentioned));

        // After the row exists, because the notification deep-links to its id,
        // and last because nothing below it may fail the write. The notifier
        // swallows and logs its own failures for that reason.
        CommentDtos.UserRef authorRef = resolved.people().get(author);
        String authorName = authorRef == null ? null : authorRef.displayName();

        if (!mentioned.isEmpty()) {
            mentionNotifier.mentioned(ticket, saved.getId(), author, authorName, mentioned);
        }

        /*
         * D-037 · §4B.6's "Comment added → Assignee, watchers", which had no
         * producer at all until now — the thread's own participants were the
         * only people a comment never told.
         *
         * The mentioned users are passed so they can be *excluded*. §4B.6 lists
         * mention and comment-added as two rows, and somebody who is both would
         * otherwise get two mails about one sentence — of which D-035's
         * one-per-recipient-per-ticket-per-minute limit would drop one
         * silently, leaving which mail arrives to a race. Excluding here makes
         * it deterministically the mention, which is the more specific of the
         * two and says why they were singled out.
         */
        eventNotifier.commentAdded(ticket, saved.getId(), author, authorName,
                mentioned.stream().map(CommentMentions.MentionedUser::id).toList());

        return dto(saved, resolved);
    }

    /**
     * C-033 · rewrite a comment inside §4B.5's five minutes.
     *
     * <h2>Author only, and this is the one rule no role widens</h2>
     *
     * <p>§4B.5: <em>"no role, including Admin, can silently rewrite a comment"</em>.
     * The obvious reading is that an Admin edit would be allowed if it were
     * marked — and it is the wrong one, because the marker cannot carry the
     * information that matters. However the card is labelled, the thread still
     * attributes the words to their original author, and "Ravi said X" becoming
     * "Ravi said Y" over an Admin's signature is a rewrite of Ravi whatever badge
     * sits next to it. The remedies that do work are both open: reply, or delete
     * and leave a tombstone that names who removed it. So {@link #delete} widens
     * to PM and Admin and this deliberately does not.
     *
     * <h2>What is preserved, and when</h2>
     *
     * <p>{@code original_body} is written <b>on the first edit only</b> — the
     * column's own comment in the baseline migration says so ("preserved on first
     * edit"), and it is the difference between keeping what the author wrote and
     * keeping the last thing they tried. A second edit overwriting it would leave
     * the first revision as the "original" and lose the actual one, which is the
     * single most useless place to be: the record would look intact and be wrong.
     * Five minutes is easily enough for three edits.
     *
     * <h2>The body goes through everything a new one does</h2>
     *
     * <p>Sanitised (PLAN.md §3.9 is about the write path, and an edit is a write),
     * length-checked against the sanitised value, and re-parsed for mentions. An
     * edit that reduces to nothing is a 400 and not an empty comment — the same
     * refusal {@link #create} gives, because the alternative is using the edit
     * window as a way to blank a comment without leaving the tombstone that
     * deleting one would.
     *
     * @throws CommentNotEditableException 422 — locked, not the author's, or gone
     */
    @Transactional
    CommentDtos.CommentDto edit(Authentication caller,
                                String ticketCode,
                                long commentId,
                                CommentDtos.EditCommentRequest request) {

        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();
        TicketComment row = rows.find(ticketId, commentId).orElseThrow(CommentNotFoundException::new);

        long editor = authorId(caller);
        if (row.isDeleted()) {
            throw CommentNotEditableException.alreadyDeleted();
        }
        if (!Objects.equals(row.getAuthorId(), editor)) {
            throw CommentNotEditableException.notTheAuthor();
        }
        if (!withinEditWindow(row)) {
            throw CommentNotEditableException.windowClosed();
        }

        String html = sanitizer.sanitize(request.body());
        if (html.isEmpty()) {
            throw InvalidCommentException.emptyBody();
        }
        if (html.length() > CommentSanitizer.MAX_LENGTH) {
            throw InvalidCommentException.tooLong(html.length());
        }

        String plainText = sanitizer.toPlainText(html);
        List<CommentMentions.MentionedUser> mentioned = mentioned(ticket, plainText);

        // Only the people the previous wording did NOT already name. An edit is
        // the one path that can fire the same notification twice, and firing it
        // is a button anybody can hold down: post "@ravi", edit to "@ravi ", edit
        // back, and Ravi has three bell entries and three emails for one remark.
        // D-035's rate limit would eventually catch a determined abuser and would
        // not catch the ordinary case, which is somebody fixing a typo in a
        // sentence that happens to contain a name.
        List<Long> alreadyNotified = row.getMentionedUserIds() == null
                ? List.of()
                : row.getMentionedUserIds();
        List<CommentMentions.MentionedUser> newlyMentioned = mentioned.stream()
                .filter(person -> !alreadyNotified.contains(person.id()))
                .toList();

        // Written once and never again — see the javadoc. `getBodyHtml`, not the
        // request: what is preserved is what is being replaced.
        if (row.getOriginalBody() == null) {
            row.setOriginalBody(row.getBodyHtml());
        }
        row.setBodyHtml(html);
        row.setBodyText(plainText);
        // The stored list is the new one, not the union. It answers "who does this
        // comment name", which a reader checks against the text in front of them;
        // a union would leave a chip for somebody the wording no longer mentions.
        // Whom we have *already told* is a different question, and it is answered
        // above, before this line overwrites the evidence.
        row.setMentionedUserIds(mentioned.isEmpty()
                ? null
                : mentioned.stream().map(CommentMentions.MentionedUser::id).toList());
        row.setEditedAt(Instant.now(clock));

        TicketComment saved = comments.save(row);
        CommentUserRefs.Resolved resolved = people.resolve(mentionedAnd(saved, mentioned));

        if (!newlyMentioned.isEmpty()) {
            CommentDtos.UserRef editorRef = resolved.people().get(editor);
            mentionNotifier.mentioned(ticket, saved.getId(), editor,
                    editorRef == null ? null : editorRef.displayName(), newlyMentioned);
        }

        return dto(saved, resolved);
    }

    /**
     * C-033 · tombstone a comment — §4B.5's "deletion leaves a tombstone".
     *
     * <h2>There is no window, and no silent deletion</h2>
     *
     * <p>C-028's attachment delete has two outcomes: inside fifteen minutes the
     * uploader's own removal leaves no visible mark, outside it every removal
     * does. That is §4B.4's wording, which spells out both cases. §4B.5 does not —
     * it attaches the five minutes to editing and says of deletion only that it
     * leaves a tombstone. So every removal here is recorded, including an author
     * deleting their own comment ten seconds after posting it.
     *
     * <p>The stricter rule is also the better fit for what a comment is. A
     * mis-pasted screenshot is a mistake and a sentence is a statement:
     * colleagues have read it, it may already have gone to a client, and "Ravi
     * removed a comment" is exactly the kind of thing §4B.5's immutability row
     * exists to keep in the record.
     *
     * <h2>Who may, and what the row keeps</h2>
     *
     * <p>The author, a PM or an Admin — C-028's widening, for its reason: a
     * comment posted client-visible by mistake is a disclosure, and the person
     * who can fix it fastest is not always its author. Everyone else gets 403 and
     * not 404, because the caller is looking at the comment in a thread they just
     * fetched ({@link CommentDeletionNotPermittedException}).
     *
     * <p>The row survives with {@code is_deleted}, {@code deleted_by} and
     * {@code deleted_at}, and the <b>text goes</b> — both copies of it. The
     * contract says so ("the body is cleared") and it is what makes the feature
     * mean anything: a tombstone that still served the words would be a
     * strikethrough, not a deletion, and the commonest reason to reach for this
     * button is that the words themselves are the problem. {@code original_body}
     * is cleared alongside {@code body_html}, which is the trap here — a comment
     * edited before it was deleted would otherwise keep its first wording in a
     * field designed to preserve wording, and that first wording is precisely
     * what the author was trying to take back.
     *
     * <h2>Idempotent</h2>
     *
     * <p>A second delete of a tombstone is a 204 no-op, following C-028: the
     * client removes optimistically, a retry after a dropped response is
     * ordinary, and the caller asked for the comment to be gone and it is gone.
     * Re-stamping {@code deleted_by} would also let a second caller quietly take
     * the first one's name off the record.
     */
    @Transactional
    void delete(Authentication caller, String ticketCode, long commentId) {

        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();
        TicketComment row = rows.find(ticketId, commentId).orElseThrow(CommentNotFoundException::new);

        if (row.isDeleted()) {
            return;
        }

        CallerIdentity identity = CallerIdentity.of(caller).orElseThrow(CommentNotFoundException::new);
        requireMayDelete(identity, row);

        row.setBodyHtml("");
        row.setBodyText("");
        row.setOriginalBody(null);
        row.setDeleted(true);
        row.setDeletedBy(identity.userId());
        row.setDeletedAt(Instant.now(clock));
        comments.save(row);

        // The other half of the counter C-029 started maintaining. The baseline
        // migration names both events — "maintained by the service layer on
        // insert and tombstone" — and a badge that only ever counts up would
        // drift permanently on the first deletion. Floored at zero rather than
        // trusted: the counter was a hard zero for every ticket created before
        // C-029, so a row commented on then and tidied up now would otherwise go
        // negative and render "-1 comments" on the list.
        ticket.setCommentCount(Math.max(0, ticket.getCommentCount() - 1));
    }

    /**
     * §4B.5's "the uploader may delete" equivalent, widened to the two roles that
     * supervise the ticket — the same two, and the same argument, as C-028.
     *
     * <p>One refusal message rather than C-028's two, because there is no window
     * to be inside or outside of: a Developer looking at a colleague's comment is
     * in the same position now as in an hour, and a message implying otherwise
     * would send them back to try again.
     */
    private void requireMayDelete(CallerIdentity identity, TicketComment row) {
        if (Objects.equals(row.getAuthorId(), identity.userId())) {
            return;
        }
        if (RolePermissions.ADMIN.equals(identity.roleCode())
                || RolePermissions.PM.equals(identity.roleCode())) {
            return;
        }
        throw new CommentDeletionNotPermittedException();
    }

    /**
     * Whether the comment may still be rewritten by its author.
     *
     * <p><b>True whenever no window is configured, which is the default</b> —
     * see {@link CommentProperties}. The edit stays available for as long as the
     * comment exists, which is the behaviour asked for after the five minutes
     * proved too short for the case it is actually used in: remembering the
     * missing half of a root-cause note half an hour later.
     *
     * <p>When a window <em>is</em> configured, it is measured from
     * {@code created_at} and the boundary is inclusive — at exactly
     * {@code createdAt + window} the edit is still allowed. A user clicking Save
     * as the countdown reaches zero should not lose it to a microsecond, and
     * nothing depends on which way this rounds.
     *
     * <p><b>A row with no {@code createdAt} is outside a configured window.</b>
     * The column is {@code NOT NULL} with a database default, so this is
     * unreachable for anything the server inserted — but if an operator has
     * asked for a limit, the safe direction for a row that cannot be placed in
     * time is the locked one.
     */
    private boolean withinEditWindow(TicketComment row) {
        if (!properties.hasEditWindow()) {
            return true;
        }
        Instant postedAt = row.getCreatedAt();
        return postedAt != null
                && !Instant.now(clock).isAfter(postedAt.plus(properties.editWindow()));
    }

    /** One place where the configured window meets the wire, so the two cannot differ. */
    private CommentDtos.CommentDto dto(TicketComment row, CommentUserRefs.Resolved resolved) {
        return CommentDtos.CommentDto.of(row, resolved.people(), resolved.roles(),
                properties.editWindow());
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
     * §4B.5's journey stamp — author, role, stage and iteration, all at time
     * of writing (C-032).
     *
     * <p>The stamp is <b>copied, never joined</b> — the migration's own comment
     * makes the point: "the ribbon moves on, and a comment written during QA
     * iteration 2 must still read as QA iteration 2 forever". A stamp is the one
     * field that cannot be backfilled: once the ticket has moved on, what stage
     * it was in when a comment was written is gone. {@code cycleNo}, {@code
     * stageCode} and {@code authorRole} were written from C-029 onward for
     * exactly that reason, even before anything read them back — leaving them
     * null until this task landed would have meant every comment posted in the
     * meantime was permanently unplaceable in the journey, and C-034's timeline
     * would have a hole in it no later task could repair.
     *
     * @param authorRole the caller's role at the moment of posting — see
     *                   {@link CallerIdentity#roleCode}
     */
    private void stamp(TicketComment row, Ticket ticket, String authorRole) {
        row.setCycleNo(ticket.getCurrentCycleNo());
        row.setStageCode(ticket.getCurrentStage());
        row.setAuthorRole(authorRole);
        row.setIterationNo(currentIterationNo(ticket));
    }

    /**
     * C-032 · the iteration half of the stamp, readable now that C-042's
     * {@link TicketJournal#openHopFor} exists.
     *
     * <p><b>Filtered to the ticket's own {@code cycleNo}, not trusted blind.</b>
     * The open hop {@code openHopFor} returns is not necessarily on the cycle
     * just stamped onto {@code cycleNo} above — {@code ReopenService} leaves a
     * still-open hop at the previous cycle's last stage until something
     * advances it (its own javadoc names the gap, and {@code TransitionService
     * #advance} guards against the identical staleness before building the next
     * hop). Stamping a stale hop's iteration onto a fresh cycle's comment would
     * read as real: a real first iteration is also a small number, so a wrong
     * one is indistinguishable from a right one once it is on the row.
     *
     * @return null when there is no open hop for this cycle yet — most notably
     *         a comment written before the ticket's first stage transition,
     *         the same case {@code cycleNo}/{@code stageCode} are nullable for
     */
    private Short currentIterationNo(Ticket ticket) {
        return journal.openHopFor(ticket.getId())
                .filter(hop -> hop.getCycleNo() == ticket.getCurrentCycleNo())
                .map(TicketStageTransition::getIterationNo)
                .orElse(null);
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
        return requireIdentity(caller).userId();
    }

    /** {@link #authorId}'s full identity, needed by {@link #create} for the role half of the stamp. */
    private static CallerIdentity requireIdentity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "a comment reached the service with no identifiable author; "
                                + "the route's @PreAuthorize should have refused this caller"));
    }
}
