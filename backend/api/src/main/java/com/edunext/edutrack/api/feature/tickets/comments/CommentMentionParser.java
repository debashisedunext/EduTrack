package com.edunext.edutrack.api.feature.tickets.comments;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C-030 · pulls {@code @handle} candidates out of a comment body.
 *
 * <p><strong>The server parses; the client is never asked who it mentioned.</strong>
 * This is D-052's rule, restated here because C-030 is the second feature to
 * need it and the argument is unchanged: a request carrying its own recipient
 * list is a request anybody can point at anybody, and the fan-out behind it —
 * a bell entry plus an email — becomes a spam primitive with no membership
 * check in front of it. The body is the only input, and
 * {@link CommentMentions#resolveProjectMembers} decides which candidates are
 * real.
 *
 * <p>That is a change to what C-029 does. C-029 stored
 * {@code CommentWriteRequest.mentionUserIds} verbatim, which was harmless while
 * nothing read the column and is not harmless now that something notifies from
 * it. The field stays on the request because the contract declares it and an
 * older client may still send it, but it is a <em>hint</em> — see
 * {@link CommentService}.
 *
 * <h2>Why this is a near-twin of {@code chat.MentionParser} rather than a shared class</h2>
 *
 * <p>{@code MentionParser} is package-private in {@code feature/chat}, which is
 * Stream D's. Importing across features is the thing feature packaging exists to
 * prevent, and promoting it to {@code common/} would make one stream's regex a
 * four-stream negotiation. The precedent is {@link CommentUserRefs}' own note on
 * being a deliberate twin of {@code AttachmentUserRefs}.
 *
 * <p>The rules are kept identical on purpose: two parsers that disagree about
 * what an {@code @} means would make {@code @ravi} notify in chat and not in a
 * comment, which reads as a bug in the product rather than as two
 * implementations. If they must diverge, that is a decision to write down.
 *
 * <h2>Plain text, never the HTML</h2>
 *
 * <p>A comment is stored as §3.9's fourteen-tag HTML, and matching over the
 * markup would find handles inside attributes — a link to
 * {@code /search?q=@ravi} is not somebody addressing Ravi. The caller passes
 * {@link CommentSanitizer#toPlainText}, which is the same text the reader sees.
 */
final class CommentMentionParser {

    /**
     * The lookbehind is what stops an email address becoming a mention: in
     * {@code ravi@edunext.com} the character before the {@code @} is a letter,
     * so {@code @edunext} never matches. It costs one false negative —
     * {@code @ravi@meera} back to back finds only the first — which is a shape
     * nobody types. Comments quote addresses constantly ("chase this with
     * ops@edunext.com"), so without it the common case is a wrong notification.
     *
     * <p>The 50-character bound is {@code users.username}'s own width. A longer
     * run is not a username, so there is nothing to gain by carrying it to the
     * query.
     */
    private static final Pattern HANDLE =
            Pattern.compile("(?<![A-Za-z0-9._@-])@([A-Za-z0-9][A-Za-z0-9._-]{0,49})");

    /**
     * A bound on candidates carried to the {@code IN (…)} clause, not on people
     * notified — project membership is the real limit. This only stops a
     * 20 000-character body turning into a query with two thousand parameters.
     */
    private static final int MAX_CANDIDATES = 50;

    private CommentMentionParser() {
    }

    /**
     * @param plainText the comment as a reader sees it, from
     *                  {@link CommentSanitizer#toPlainText}
     * @return distinct lower-cased handles in the order they appear, never null
     */
    static List<String> handles(String plainText) {
        // The overwhelming majority of comments contain no '@' at all, and
        // scanning them with the regex is pure cost on the write path.
        if (plainText == null || plainText.indexOf('@') < 0) {
            return List.of();
        }

        Set<String> handles = new LinkedHashSet<>();
        Matcher matcher = HANDLE.matcher(plainText);
        while (matcher.find() && handles.size() < MAX_CANDIDATES) {
            String handle = trimTrailingPunctuation(matcher.group(1));
            if (!handle.isEmpty()) {
                // Only to fold "@Ravi @ravi" into one recipient. Case folding
                // itself is the collation's job — users.username is unique under
                // utf8mb4_0900_ai_ci — so this is about the Set, not the query.
                handles.add(handle.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(handles);
    }

    /**
     * "Ask @ravi." ends a sentence; the full stop is punctuation, not part of
     * the username. The character class has to admit {@code .} and {@code -}
     * mid-handle — {@code ravi.kumar} is the house style for
     * {@code users.username} — so the only way to tell the two apart is
     * position, and a username cannot end in either.
     */
    private static String trimTrailingPunctuation(String handle) {
        int end = handle.length();
        while (end > 0) {
            char last = handle.charAt(end - 1);
            if (last != '.' && last != '-' && last != '_') {
                break;
            }
            end--;
        }
        return handle.substring(0, end);
    }
}
