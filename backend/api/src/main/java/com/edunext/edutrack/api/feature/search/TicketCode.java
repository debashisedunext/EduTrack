package com.edunext.edutrack.api.feature.search;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A-072 · recognising a ticket code in what somebody typed.
 *
 * <p>Blueprint gap item 9 is one sentence and it is the whole reason this class
 * exists: <i>"Global search + Ticket ID deep link — people share ticket IDs in
 * email all day."</i> The dominant search is somebody pasting
 * {@code CRM-26-00347} out of a message, and PLAN.md §3.8 is explicit that it
 * <b>must be exact and instant</b> — a unique-index lookup, never the full-text
 * path.
 *
 * <h2>Pasted, not typed</h2>
 *
 * <p>A code arrives out of an email, a chat message or a spreadsheet cell, so
 * it arrives with whatever came with it: surrounding whitespace, a trailing
 * full stop, wrapping brackets from D-031's {@code [CRM-26-00347]} subject
 * prefix, or a whole ticket URL that somebody copied from their address bar.
 * Requiring a bare code would refuse the exact input this feature was built to
 * accept, so the noise is stripped before matching.
 *
 * <p><b>Case is normalised up.</b> Codes are upper-case by construction and
 * mail clients lower-case them in links often enough to matter.
 *
 * <h2>Recognising is not finding</h2>
 *
 * <p>This says only "that is code-shaped". Whether such a ticket exists, and
 * whether this caller may see it, are {@code SearchRepository}'s questions and
 * are answered under scope — a code-shaped string for a ticket in somebody
 * else's project simply finds nothing, exactly as §2 requires. There is no path
 * here that confirms existence.
 */
final class TicketCode {

    private TicketCode() {
    }

    /**
     * The contract's {@code TicketId} pattern, character for character.
     *
     * <p>Deliberately the same expression rather than a looser one: a laxer
     * pattern here would send strings to the exact-lookup branch that cannot be
     * ticket codes, and the two would then disagree about what a ticket id is.
     * Five digits is a <em>minimum</em> width — {@code projects.ticket_seq}
     * does not reset at year rollover (PLAN.md §3.2, deviation D-8), so a
     * long-lived project reaches {@code CRM-30-100000}.
     */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,9}-\\d{2}-\\d{5,}$");

    /**
     * Everything a pasted code drags along with it.
     *
     * <p>Leading and trailing runs of anything that cannot appear inside a code.
     * Anchored to the ends rather than applied globally, because a code has no
     * interior punctuation to lose and a global strip would silently repair a
     * malformed string into a valid-looking one.
     */
    private static final Pattern SURROUNDING_NOISE = Pattern.compile("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$");

    /**
     * The code in {@code raw}, or empty when it is not code-shaped.
     *
     * <p>A whole URL counts: {@code …/tickets/CRM-26-00347} is what a browser
     * address bar gives somebody who wants to share a ticket, and the last path
     * segment is the code. Tried after the plain reading so an ordinary code is
     * never routed through URL handling.
     */
    static Optional<String> from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = strip(raw);
        if (CODE.matcher(cleaned).matches()) {
            return Optional.of(cleaned);
        }
        // A pasted link. Only the last segment is considered — a query string
        // or a fragment is stripped first, since `?from=…` on a shared
        // dashboard link would otherwise be read as part of the code.
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < cleaned.length() - 1) {
            String segment = strip(cleaned.substring(lastSlash + 1).split("[?#]", 2)[0]);
            if (CODE.matcher(segment).matches()) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }

    private static String strip(String value) {
        return SURROUNDING_NOISE.matcher(value.trim().toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
