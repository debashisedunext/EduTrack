package com.edunext.edutrack.api.feature.notifications.webhook;

import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.mail.EmailLogRepository;
import com.edunext.edutrack.domain.mail.InboundMailReference;
import com.edunext.edutrack.domain.mail.QuotedReplyStripper;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * D-039 · an emailed reply becomes a comment on the ticket.
 *
 * <p>Four things have to be true before anything is written, and each one that
 * is not simply drops the mail. <strong>Dropping is always the answer</strong> —
 * there is nobody to report an error to. The sender is a mail client that has
 * already flushed, and answering the provider with a 4xx only buys a retry of a
 * message that will fail identically. What a dropped reply costs is one comment
 * that has to be typed into the ticket; what a wrongly-accepted one costs is
 * somebody's words filed against a ticket they never joined.
 *
 * <h2>Why {@code From} is not enough on its own</h2>
 *
 * <p>A {@code From} header is a string the sender chooses. Matching it to a
 * user and stopping there would let one forged address post a comment as any
 * colleague, on any ticket, and the comment would carry their name. So the
 * sender must also be somebody we <em>actually mailed about this ticket</em>
 * ({@code email_log}) — the one question the system can answer from its own
 * records rather than from the envelope.
 *
 * <p>That is a real check and not a complete one. Full protection is the
 * provider's DKIM and SPF verdict, which arrives in the webhook payload once a
 * provider is chosen (D-033 notes the same gap for the provider message id);
 * this handler should gate on it then. Recorded here rather than left implicit,
 * because "the From address matched a user" reads like authentication and is
 * not.
 *
 * <h2>What is deliberately not done</h2>
 *
 * <p>No {@code ticket_history} row. Appending to it is hash-chained and the
 * chain writer is A-042, unwritten — and inventing a second one in this handler
 * is exactly what CLAUDE.md warns costs the guarantee. A comment is its own
 * record and {@code source = 'EMAIL'} says where it came from.
 *
 * <p>No client-visible flag. {@code is_internal} keeps its default of true: a
 * mail arriving from a colleague is a colleague's remark, and the failure of
 * guessing wrong is one-directional — an internal note wrongly published to a
 * client cannot be recalled, while an internal note that should have been
 * shared is a click away.
 */
@Service
public class InboundReplyService {

    private static final Logger log = LoggerFactory.getLogger(InboundReplyService.class);

    /** Matches the blueprint's {@code source VARCHAR(15) — WEB | EMAIL | API}. */
    private static final String SOURCE_EMAIL = "EMAIL";

    /**
     * {@code body_text} is TEXT (65 535 bytes). A mail whose quoted part was
     * not recognised can be far longer than anything typed by hand, and letting
     * the insert throw would turn one unparsed reply into a 500 the provider
     * retries forever.
     */
    private static final int MAX_BODY = 60_000;

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final UserRepository users;
    private final EmailLogRepository emailLog;

    public InboundReplyService(TicketRepository tickets,
                               TicketCommentRepository comments,
                               UserRepository users,
                               EmailLogRepository emailLog) {
        this.tickets = tickets;
        this.comments = comments;
        this.users = users;
        this.emailLog = emailLog;
    }

    /**
     * @return the new comment's id, or empty if the reply was dropped
     */
    @Transactional
    public Optional<Long> accept(InboundReply reply) {
        Optional<Long> ticketId =
                InboundMailReference.ticketIdFrom(reply.inReplyTo(), reply.references());
        if (ticketId.isEmpty()) {
            // Either a mail that was never about a ticket, or a chain stitched
            // across two. Both are "we cannot tell", and guessing is the one
            // outcome worse than dropping it.
            log.info("inbound mail: no single ticket in the thread headers, dropped");
            return Optional.empty();
        }

        String from = normalisedAddress(reply.from());
        if (from == null) {
            log.info("inbound mail: no sender address, dropped");
            return Optional.empty();
        }

        Optional<Ticket> ticket = tickets.findById(ticketId.get());
        if (ticket.isEmpty()) {
            log.info("inbound mail: reply names a ticket that does not exist, dropped");
            return Optional.empty();
        }

        Optional<User> author = users.findByEmail(from).filter(User::isActive);
        if (author.isEmpty()) {
            // A client contact replying, or somebody who has left. author_id is
            // NOT NULL with an FK to users, so there is no row to write —
            // and inventing a system author would attribute somebody's words
            // to nobody. Email-to-ticket for non-users is phase 6.
            log.info("inbound mail: sender is not an active user, dropped");
            return Optional.empty();
        }

        if (!emailLog.existsByTicketIdAndToEmailIgnoreCase(ticketId.get(), from)) {
            // We never mailed this person about this ticket, so they cannot be
            // replying to it. This is the check that a forged From does not pass.
            log.warn("inbound mail: sender was never mailed about this ticket, dropped");
            return Optional.empty();
        }

        String body = QuotedReplyStripper.strip(reply.text());
        if (body.isBlank()) {
            log.info("inbound mail: reply had no text, dropped");
            return Optional.empty();
        }
        if (body.length() > MAX_BODY) {
            body = body.substring(0, MAX_BODY);
        }

        Ticket t = ticket.get();
        TicketComment comment = new TicketComment();
        comment.setTicketId(t.getId());
        // Stamped with where the ticket is now, like a comment typed on the
        // page — §4A.2's counters are what the History tab groups by, and a
        // comment with no cycle floats outside every group.
        comment.setCycleNo(t.getCurrentCycleNo());
        comment.setStageCode(t.getCurrentStage());
        comment.setIterationNo(t.getCurrentIteration());
        comment.setAuthorId(author.get().getId());
        comment.setBodyText(body);
        // No HTML part: what arrived was plain text, and generating markup from
        // it here would invent formatting nobody wrote. The renderer escapes.
        comment.setBodyHtml(body);
        comment.setSource(SOURCE_EMAIL);

        TicketComment saved = comments.save(comment);
        log.info("inbound mail: reply from user {} became comment {} on ticket {}",
                author.get().getId(), saved.getId(), t.getId());
        return Optional.of(saved.getId());
    }

    private static String normalisedAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // Clients send `Priya Nair <priya.nair@edunext.test>` as often as the
        // bare address, and the display name is attacker-chosen text.
        String address = raw.trim();
        int open = address.lastIndexOf('<');
        int close = address.lastIndexOf('>');
        if (open >= 0 && close > open) {
            address = address.substring(open + 1, close);
        }
        address = address.trim().toLowerCase(Locale.ROOT);
        return address.isEmpty() ? null : address;
    }
}
