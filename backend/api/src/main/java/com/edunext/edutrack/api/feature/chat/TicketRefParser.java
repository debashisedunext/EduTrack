package com.edunext.edutrack.api.feature.chat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-054 · the ticket codes named in a chat message.
 *
 * <p>Sibling of {@link MentionParser}, and deliberately the same shape: the
 * <strong>server</strong> reads the body and decides what it refers to. A
 * caller-supplied list of ticket ids would be a list of things to look up on
 * somebody else's behalf with no check in front of it, which is the same
 * argument D-052 makes about aiming the mention fan-out.
 *
 * <h2>The format is not the one the blueprint prints</h2>
 *
 * <p>§7.6 asks for "link preview of any {@code TKT-xxxx} mention", and §4A.1's
 * mockups show {@code TKT-000871}. No ticket has ever looked like that: C-011
 * implemented {@code {PROJECT_CODE}-{YY}-{NNNNN}} — {@code CRM-26-00347} — and
 * PLAN.md §3.2 carries the reason. Matching the blueprint's literal string would
 * produce a feature that recognises nothing, so this matches what the system
 * actually mints. The wording in §7.6 is illustrative; the format in C-011 is
 * the fact.
 *
 * <h2>Boundaries</h2>
 *
 * <p>The lookarounds keep a code from being found inside a longer token. Chat
 * carries URLs and file names, and {@code .../builds/CRM-26-00347-final.zip}
 * naming a ticket is not the same as somebody referring to one — a preview card
 * unfurling out of a filename is noise at best, and at worst it is a probe for
 * which ticket codes exist, run by pasting a list of guesses.
 */
final class TicketRefParser {

    /**
     * {@code PROJECT_CODE} is {@code [A-Z][A-Z0-9]{1,9}} in C-011, the year is
     * two digits and the sequence five. Written out rather than imported
     * because {@code TicketCode} is package-private to {@code feature/tickets}
     * — Stream C's — and reaching into it from here would be the kind of
     * cross-package coupling CLAUDE.md's feature packaging exists to prevent.
     * The duplication is one regex, and {@link TicketRefParserTest} pins it
     * against codes produced by C-011's own formatter.
     */
    private static final Pattern TICKET_CODE = Pattern.compile(
            "(?<![A-Za-z0-9-])([A-Z][A-Z0-9]{1,9}-\\d{2}-\\d{5})(?![A-Za-z0-9-])");

    /**
     * A message referring to more than this many tickets is not cross-
     * referencing, it is pasting a report. Each code costs a lookup and a card,
     * and an unbounded count turns one message into an unbounded fan-out of
     * queries — the same reasoning D-020 caps its scan at 500 per pass.
     * Extras stay plain text, which is what they already looked like.
     */
    static final int MAX_REFS = 10;

    private TicketRefParser() {
    }

    /**
     * @param body the message as typed
     * @return the distinct codes named, in the order they appear, capped at
     *         {@link #MAX_REFS}
     */
    static Set<String> codesIn(String body) {
        Set<String> found = new LinkedHashSet<>();
        if (body == null || body.isBlank()) {
            return found;
        }
        Matcher m = TICKET_CODE.matcher(body);
        while (m.find() && found.size() < MAX_REFS) {
            found.add(m.group(1));
        }
        return found;
    }
}
