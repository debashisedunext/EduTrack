package com.edunext.edutrack.domain.mail;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-039 · which ticket an inbound reply belongs to.
 *
 * <p>The inverse of D-032's threading. Every mail we send about a ticket
 * references {@code <ticket.{id}@domain>} as its thread root and carries a
 * {@code Message-ID} of {@code <ticket.{id}.mail.{logId}@domain>}, so a client
 * replying quotes at least one of them straight back in {@code In-Reply-To} and
 * {@code References}. The ticket id therefore arrives in the headers and does
 * not have to be guessed.
 *
 * <p><strong>Deliberately not parsed from the subject line.</strong> The subject
 * does lead with {@code [CRM-26-00347]} (D-031), but subjects are user-editable,
 * get {@code Re:} and {@code AW:} prefixed by every locale, are truncated by
 * some gateways, and are trivially forged. Headers are how mail threading is
 * specified to work, and treating a typed string as the routing key for
 * "which ticket does this client's message join" is the kind of shortcut that
 * files a client's message against somebody else's ticket.
 *
 * <h2>Ambiguity resolves to nothing</h2>
 *
 * <p>If {@code References} names two different tickets — a forward across
 * threads, a client's mail app stitching conversations together — this returns
 * empty rather than picking one. Attaching a reply to the wrong ticket puts
 * somebody's words in a thread they never joined, and under row scoping it may
 * put them in front of people who could not otherwise read them. Dropping the
 * reply is recoverable; misfiling it quietly is not.
 */
public final class InboundMailReference {

    /**
     * Matches both forms D-032 emits, and only at the start of a message id.
     * The trailing {@code [.@]} is what distinguishes {@code ticket.347@…} and
     * {@code ticket.347.mail.9@…} from a domain that merely contains the word.
     */
    private static final Pattern TICKET_ID = Pattern.compile("<ticket\\.(\\d{1,18})[.@]");

    private InboundMailReference() {
    }

    /**
     * @param inReplyTo  the {@code In-Reply-To} header, or null
     * @param references the {@code References} header, or null
     * @return the ticket this reply joins, or empty if it names none or more
     *         than one
     */
    public static Optional<Long> ticketIdFrom(String inReplyTo, String references) {
        // In-Reply-To first: it names the single mail being answered, where
        // References accumulates the whole chain and is the more likely of the
        // two to have collected an unrelated thread along the way.
        Set<Long> direct = idsIn(inReplyTo);
        if (direct.size() == 1) {
            return Optional.of(direct.iterator().next());
        }

        Set<Long> chain = idsIn(references);
        if (direct.isEmpty() && chain.size() == 1) {
            return Optional.of(chain.iterator().next());
        }

        // Either header naming two tickets, or the two disagreeing, is
        // ambiguous. Say nothing rather than choose.
        return Optional.empty();
    }

    private static Set<Long> idsIn(String header) {
        Set<Long> found = new LinkedHashSet<>();
        if (header == null || header.isBlank()) {
            return found;
        }
        Matcher m = TICKET_ID.matcher(header);
        while (m.find()) {
            try {
                found.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException e) {
                // 18 digits is inside long's range, so this is unreachable in
                // practice; swallowing it keeps one malformed id from
                // discarding a header that also carries a good one.
            }
        }
        return found;
    }
}
