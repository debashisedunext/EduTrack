package com.edunext.edutrack.api.feature.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-052 · pulls {@code @handle} candidates out of a message body.
 *
 * <p><strong>The server parses; the client is never asked who it mentioned.</strong>
 * A request carrying its own recipient list is a request anybody can point at
 * anybody — the whole notification fan-out would become a spam primitive with no
 * membership check in front of it. The body is the only input, and
 * {@link ChatRepository#mentionableParticipants} decides which candidates are
 * real.
 *
 * <p><strong>The handle is the username, and it is stored verbatim.</strong> The
 * alternative — a structured token like {@code @[42]} that a renderer expands —
 * makes the stored body unreadable without the renderer, and §7.6 keeps chat as
 * evidence. Evidence you need our own UI to decipher is worse evidence. A
 * username also survives a change of display name, which a name match would not.
 *
 * <p>Resolution is left to the database. {@code users.username} is unique under
 * {@code utf8mb4_0900_ai_ci}, so case folding is already the collation's job and
 * the lower-casing here is only to fold {@code @Ravi @ravi} into one recipient.
 */
final class MentionParser {

    /**
     * The lookbehind is what stops an email address becoming a mention: in
     * {@code ravi@edunext.com} the character before the {@code @} is a letter,
     * so {@code @edunext} never matches. It costs one false negative —
     * {@code @ravi@meera} back to back finds only the first — which is a shape
     * nobody types, and the trade is worth it. Every message quoting an address
     * would otherwise notify whoever happened to own that domain as a username.
     *
     * <p>The 50-character bound is {@code users.username}'s own width. A longer
     * run is not a username, so there is nothing to gain by carrying it to the
     * query.
     */
    private static final Pattern HANDLE =
            Pattern.compile("(?<![A-Za-z0-9._@-])@([A-Za-z0-9][A-Za-z0-9._-]{0,49})");

    /**
     * A bound on candidates carried to the {@code IN (…)} clause, not on people
     * notified — participation is the real limit, and a thread has as many
     * participants as it has. This only stops a 20,000-character body turning
     * into a query with two thousand parameters.
     */
    private static final int MAX_CANDIDATES = 50;

    private MentionParser() {
    }

    /**
     * @return distinct lower-cased handles in the order they appear, never null
     */
    static List<String> handles(String body) {
        // The overwhelming majority of messages contain no '@' at all, and
        // scanning them with the regex is pure cost.
        if (body == null || body.indexOf('@') < 0) {
            return List.of();
        }

        Set<String> handles = new LinkedHashSet<>();
        Matcher matcher = HANDLE.matcher(body);
        while (matcher.find() && handles.size() < MAX_CANDIDATES) {
            String handle = trimTrailingPunctuation(matcher.group(1));
            if (!handle.isEmpty()) {
                handles.add(handle.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(handles);
    }

    /**
     * "Ask @ravi." ends a sentence; the full stop is punctuation, not part of
     * the name. Usernames do not end in {@code . _ -} in practice, so trimming
     * them is safe and not trimming them means every mention at the end of a
     * sentence silently fails to resolve.
     */
    private static String trimTrailingPunctuation(String handle) {
        int end = handle.length();
        while (end > 0 && ".-_".indexOf(handle.charAt(end - 1)) >= 0) {
            end--;
        }
        return handle.substring(0, end);
    }
}
