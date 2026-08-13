package com.edunext.edutrack.api.feature.chat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * D-055 · "Ask Status" (blueprint §7.6).
 *
 * <p>A Reporting Manager or PM clicks the button and EduTrack posts a structured
 * message into the ticket's thread:
 *
 * <blockquote>
 * <strong>📌 Status requested by Meera P.</strong> — <em>"Please share the
 * current status and expected closure."</em><br>
 * {@code [ Reply with update ]} {@code [ Open Quick Update ]}
 * </blockquote>
 *
 * <h2>The card is a message, not a new shape</h2>
 *
 * <p>Everything the blockquote shows is already carried by an ordinary message:
 * the sender is the manager, so "Status requested by Meera P." is a rendering of
 * {@code author}; the italic line is the body; and {@link MessageKind#STATUS_REQUEST}
 * is what tells the client to draw the card and its two buttons instead of a
 * chat bubble. Nothing here invents a payload for the actions, because an action
 * list sent from the server would be a second place to decide what the client
 * can do — and both buttons navigate, neither calls anything this feature owns.
 *
 * <p>That the body is a plain message body also means the manager's question is
 * searchable (D-053), quotable, and subject to §7.6's five-minute edit window
 * and its tombstone, like anything else said on the ticket. A status request
 * ought not to be the one utterance in the system that cannot be corrected.
 *
 * <h2>What "answered" means is D-056, and it is decided in SQL</h2>
 *
 * <p>See {@code StatusRequestRepository.ANSWERABLE}. It is the one judgement in
 * this feature that the reportable metric depends on, so it is written once, in
 * the query, rather than reconstructed by each reader.
 *
 * <h2>Known gap — the Quick Update button does not close a request</h2>
 *
 * <p>§7.6 offers {@code [Open Quick Update]} beside {@code [Reply with update]},
 * and only the second one closes anything today: a resource who logs effort and
 * revises the ETA through S-30 without saying so in the thread leaves the
 * request open. Closing it from there needs a hook in Stream C's quick-update
 * path, which is not mine to add. Recorded rather than quietly ignored, because
 * the symptom — a manager's list that will not clear — reads as a bug in this
 * feature.
 */
@Service
public class StatusRequestService {

    /** Blueprint §7.6's own wording, used when the manager types nothing. */
    static final String DEFAULT_NOTE = "Please share the current status and expected closure.";

    /**
     * The awaiting-response list is every request one manager is still waiting
     * on. It is bounded in practice by how many people report to them, and a
     * manager with more than this outstanding has a problem no page control
     * fixes — but an unbounded query on a table that only grows is not something
     * to leave to practice.
     */
    static final int AWAITING_LIMIT = 200;

    private final StatusRequestRepository requests;
    private final ChatRepository chat;
    private final ChatService chatService;
    private final StatusRequestNotifier notifier;

    StatusRequestService(StatusRequestRepository requests,
                         ChatRepository chat,
                         ChatService chatService,
                         StatusRequestNotifier notifier) {
        this.requests = requests;
        this.chat = chat;
        this.chatService = chatService;
        this.notifier = notifier;
    }

    /**
     * What happened, in the caller's vocabulary rather than HTTP's.
     *
     * <p>Sealed for the reason {@link ChatService.Outcome} is: "there is nobody
     * to ask" and "you may not ask" are different answers and must not collapse
     * into one, or the manager is told the ticket does not exist when the real
     * problem is that nobody is assigned to it.
     */
    public sealed interface Outcome {

        /**
         * No such ticket — <em>or</em> the caller is not entitled to ask about
         * it.
         *
         * <p>404 for both, per CONVENTIONS.md §7: 403 is legitimate only where
         * the failure does not depend on a row, and "are you this assignee's
         * reporting manager" depends entirely on the row. Answering 403 would
         * confirm the ticket exists to anyone willing to try ids.
         */
        record NotFound() implements Outcome {
        }

        /** The ticket exists and the caller may ask, but the ask cannot stand. */
        record Rejected(String reason) implements Outcome {
        }

        /**
         * @param alreadyOpen the caller already had an unanswered request on
         *                    this ticket, so nothing new was posted and this is
         *                    that one
         */
        record Asked(StatusRequestDtos.StatusRequest request, boolean alreadyOpen) implements Outcome {
        }
    }

    /**
     * Post the card, notify the assignee, start the clock.
     *
     * @param note the manager's own wording, or null for {@link #DEFAULT_NOTE}
     */
    @Transactional
    public Outcome ask(long ticketId, long requesterId, String note) {
        Optional<StatusRequestRepository.TicketRow> found = requests.ticket(ticketId);
        if (found.isEmpty() || !requests.mayAsk(requesterId, ticketId)) {
            return new Outcome.NotFound();
        }
        StatusRequestRepository.TicketRow ticket = found.get();

        if (ticket.assignedTo() == null) {
            // §11 addresses this notification to the Assignee, and there is not
            // one. Posting the card anyway would put a question in the thread
            // with nobody's name against it and start a clock that can never be
            // stopped — D-026 is the alert that covers an unassigned ticket, and
            // it is already watching this one.
            return new Outcome.Rejected(
                    "This ticket has no assignee yet, so there is nobody to ask.");
        }
        if (ticket.assignedTo() == requesterId) {
            return new Outcome.Rejected("You are the assignee on this ticket.");
        }

        // Idempotent by design, not by accident. A manager who clicks twice is
        // asking the same question twice: a second card in the thread, a second
        // bell entry and a second row in their own awaiting list would all be
        // noise, and the second row would also make the metric count one wait
        // as two. uq_ticket_status_requests_open enforces it in the schema; this
        // is what turns the constraint into a civil answer instead of a 500.
        Optional<Long> existing = requests.openRequestFrom(ticketId, requesterId);
        if (existing.isPresent()) {
            return requests.byId(existing.get())
                    .map(row -> (Outcome) new Outcome.Asked(toDto(row), true))
                    .orElseGet(Outcome.NotFound::new);
        }

        long threadId = requests.ensureTicketThread(ticketId, requesterId);
        // Both sides of the exchange must be in the thread before the message is
        // written: the assignee is about to be sent a link to it, and a
        // notification that deep-links somebody to a 404 is worse than none.
        requests.addParticipant(threadId, requesterId);
        requests.addParticipant(threadId, ticket.assignedTo());

        String body = note == null || note.isBlank() ? DEFAULT_NOTE : note.trim();
        Optional<ChatDtos.ChatMessage> card =
                chatService.post(threadId, requesterId, body, MessageKind.STATUS_REQUEST);
        if (card.isEmpty()) {
            // Unreachable — the participant row was just written in this
            // transaction. If it fires, membership means something other than
            // what this feature assumes and the request must not be recorded.
            throw new IllegalStateException(
                    "chat: could not post a status request into thread " + threadId);
        }

        long requestId = requests.insert(ticketId, threadId, card.get().id(),
                requesterId, ticket.assignedTo(), Instant.now());

        notifier.requested(ticket.assignedTo(), ticketId, ticket.ticketCode(), threadId,
                card.get().author() == null ? null : card.get().author().displayName());

        return requests.byId(requestId)
                .map(row -> (Outcome) new Outcome.Asked(toDto(row), false))
                .orElseThrow(() -> new IllegalStateException(
                        "chat: status request " + requestId + " vanished immediately after insert"));
    }

    /**
     * D-056 · the manager's "Awaiting response" list, longest wait first.
     *
     * <p>Ordered by the ask rather than by recency, unlike every other list in
     * chat. The list exists to be cleared, and the thing most in need of
     * clearing is the question that has been ignored longest.
     */
    @Transactional(readOnly = true)
    public List<StatusRequestDtos.StatusRequest> awaiting(long userId) {
        return requests.awaiting(userId, AWAITING_LIMIT).stream().map(StatusRequestService::toDto).toList();
    }

    /**
     * D-056 · the badge — open requests on one ticket, whoever asked.
     *
     * <p>Scoped by chat membership, the same rule the rest of this feature uses
     * and the same one {@link ChatRepository} enforces everywhere else: an
     * explicit row in {@code chat_participants}, needing no role reasoning and
     * no {@code ScopeResolver}. Empty rather than 404 for a non-participant —
     * the caller is asking "is anything outstanding here", and the truthful
     * answer to somebody who cannot see the conversation is "nothing you can
     * see".
     *
     * <p><strong>This is narrower than it should eventually be.</strong> A PM
     * who can read the ticket but has never been added to its thread sees no
     * badge. Widening it is a question about who can read a ticket, which is
     * A-034's to answer — and the failure of being narrow is a badge that does
     * not appear, while the failure of guessing wide is showing one person's
     * ticket to another.
     */
    @Transactional(readOnly = true)
    public List<StatusRequestDtos.StatusRequest> openOnTicket(long ticketId, long viewerId) {
        if (!chat.participatesInTicketThread(viewerId, ticketId)) {
            return List.of();
        }
        return requests.openOnTicket(ticketId).stream().map(StatusRequestService::toDto).toList();
    }

    static StatusRequestDtos.StatusRequest toDto(StatusRequestRepository.RequestRow row) {
        return new StatusRequestDtos.StatusRequest(
                row.id(),
                // The human code, not the row id — the same choice ChatThread
                // makes, and for the same reason: it is what the UI links on.
                row.ticketCode(),
                row.ticketTitle(),
                row.threadId(),
                row.requestMessageId(),
                ChatDtos.UserRef.of(row.requestedById(), row.requestedByName()),
                ChatDtos.UserRef.of(row.askedOfId(), row.askedOfName()),
                row.requestedAt().toInstant(),
                row.note(),
                row.answeredAt() != null,
                row.answerMessageId(),
                row.answeredAt() == null ? null : row.answeredAt().toInstant(),
                row.responseWorkingMins());
    }
}
