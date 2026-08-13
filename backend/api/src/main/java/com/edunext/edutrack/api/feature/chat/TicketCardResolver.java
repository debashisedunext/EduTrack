package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-054 · turns the ticket codes in a message into cards the reader is allowed
 * to see (blueprint §7.6).
 *
 * <h2>A card is per-reader, so it cannot be stored</h2>
 *
 * <p>This is the one structural difference from D-052's mentions, and it decides
 * the whole shape of this class. A mention is resolved once, at write time, and
 * kept in {@code mentioned_user_ids}: who was named is a fact about the message.
 * A card is not. Two people reading the same message are entitled to different
 * answers — a PM sees the referenced ticket, a Developer on another project sees
 * the code as plain text — and the card also shows <em>live</em> state: level,
 * status, stage, who holds it, whether it is late. A stored card would be both a
 * scope leak and stale, and the leak would be invisible because the message
 * would look identical to everyone.
 *
 * <p>So resolution happens on read, against the caller, every time.
 *
 * <h2>An unresolvable code stays plain text</h2>
 *
 * <p>A code the reader may not see is simply absent from the card list, exactly
 * like a code for a ticket that does not exist. That is D-052's rule for a
 * mention restated, and it is what stops the preview from becoming an oracle:
 * pasting {@code CRM-26-00001} through {@code CRM-26-00999} into a message must
 * not report back which of them are real. {@link ScopedTickets} makes that the
 * default rather than something this class remembers — an out-of-scope row comes
 * back as absence, and there is no method on it that says "exists but not
 * yours".
 *
 * <h2>Two queries per page, not two per message</h2>
 *
 * <p>Every code on the page is resolved in one scoped query and every assignee
 * name in a second. The obvious spelling — {@code byCode} per reference — is
 * fifty round trips on a fifty-message page, and the page is what a reader opens
 * a thread to get.
 */
@Component
class TicketCardResolver {

    /**
     * A page-wide ceiling on top of {@link TicketRefParser#MAX_REFS}, which is
     * per message. Fifty messages each naming ten tickets is five hundred codes
     * in one {@code IN (…)}, and the reader can see at most a handful of cards
     * without scrolling. Beyond this the remaining codes stay plain text, which
     * is what they already looked like.
     */
    static final int MAX_CODES_PER_PAGE = 50;

    private final ScopedTickets tickets;
    private final UserRepository users;

    TicketCardResolver(ScopedTickets tickets, UserRepository users) {
        this.tickets = tickets;
        this.users = users;
    }

    /**
     * The same messages, each carrying the cards it names and the caller may see.
     *
     * <p>Rebuilds rather than mutates because {@link ChatDtos.ChatMessage} is a
     * record. The order of a message's own cards follows the order the codes
     * appear in its body, so a card list reads in the order somebody wrote them.
     */
    List<ChatDtos.ChatMessage> attach(Authentication caller, List<ChatDtos.ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        // Deleted messages contribute nothing. Two things stop them, and it is
        // worth being precise about which does the work: by the time a
        // tombstone reaches here its body is already null, so the parser finds
        // nothing regardless — removing this check breaks no test that goes
        // through the service, which is exactly what a mutation showed. The
        // check earns its place against the day somebody hands a moderator the
        // deleted text: then the withheld body stops protecting anything and
        // this is all that is left between a reader and what the author
        // removed. There is a test that constructs that message directly.
        Map<Long, Set<String>> codesByMessage = new LinkedHashMap<>();
        Set<String> allCodes = new LinkedHashSet<>();
        for (ChatDtos.ChatMessage message : messages) {
            if (message.isDeleted()) {
                continue;
            }
            Set<String> codes = TicketRefParser.codesIn(message.body());
            if (codes.isEmpty()) {
                continue;
            }
            codesByMessage.put(message.id(), codes);
            allCodes.addAll(codes);
        }
        if (allCodes.isEmpty()) {
            // The overwhelmingly common page. It costs no query at all, the same
            // discipline ChatRepository's mention expansion uses.
            return messages;
        }

        Map<String, ChatDtos.TicketCard> resolved = byCode(caller, allCodes);
        if (resolved.isEmpty()) {
            return messages;
        }

        List<ChatDtos.ChatMessage> withCards = new ArrayList<>(messages.size());
        for (ChatDtos.ChatMessage message : messages) {
            List<ChatDtos.TicketCard> cards = codesByMessage.getOrDefault(message.id(), Set.of()).stream()
                    .map(resolved::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            withCards.add(cards.isEmpty() ? message : message.withTicketRefs(cards));
        }
        return withCards;
    }

    /**
     * Cards for these codes, scoped to the caller.
     *
     * <p>Public to the feature because the live path needs it: a message
     * arriving over the socket is one frame delivered to a whole room, so it
     * cannot carry cards resolved for any particular reader — each client asks
     * for its own. See {@code ChatController.ticketCards}.
     */
    List<ChatDtos.TicketCard> cardsFor(Authentication caller, Set<String> codes) {
        return List.copyOf(byCode(caller, codes).values());
    }

    private Map<String, ChatDtos.TicketCard> byCode(Authentication caller, Set<String> codes) {
        List<String> capped = codes.stream().limit(MAX_CODES_PER_PAGE).toList();
        if (capped.isEmpty()) {
            return Map.of();
        }

        // Through ScopedTickets, never TicketRepository: A-034 AND-s §10.2 in
        // ahead of this criteria and cannot be talked out of it by a null.
        List<Ticket> visible = tickets.list(
                caller,
                (root, query, builder) -> root.get("ticketCode").in(capped),
                Sort.unsorted());
        if (visible.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> assigneeNames = namesOf(visible);
        Map<String, ChatDtos.TicketCard> cards = new LinkedHashMap<>();
        for (Ticket ticket : visible) {
            cards.put(ticket.getTicketCode(), card(ticket, assigneeNames));
        }
        return cards;
    }

    /** One query for every assignee on the page, or none when nothing is assigned. */
    private Map<Long, String> namesOf(List<Ticket> visible) {
        Set<Long> ids = visible.stream()
                .map(Ticket::getAssignedTo)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (User user : users.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    /**
     * What §4A.1's own compact row shows — {@code TKT-000871  Payment gateway
     * timeout   Ravi K.   -3d overdue} — plus the stage, because §7.6's card
     * sits beside a ribbon and "where is it" is the question a reference in
     * chat is usually asking.
     *
     * <p><strong>Deliberately not the description, the steps to reproduce or
     * the client.</strong> A preview is a convenience, and a convenience that
     * copies a client's words into a thread they were never sent to is not one.
     * Everything here is already on the ticket list a reader who can see this
     * card can open.
     */
    private static ChatDtos.TicketCard card(Ticket ticket, Map<Long, String> assigneeNames) {
        Long assigneeId = ticket.getAssignedTo();
        return new ChatDtos.TicketCard(
                ticket.getTicketCode(),
                ticket.getTitle(),
                ticket.getLevel(),
                ticket.getStatus(),
                ticket.getCurrentStage(),
                assigneeId == null
                        ? null
                        : ChatDtos.UserRef.of(assigneeId, assigneeNames.get(assigneeId)),
                ticket.getPlannedCloseDate(),
                ticket.isDelayed());
    }
}
