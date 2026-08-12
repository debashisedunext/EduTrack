package com.edunext.edutrack.domain.mail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * D-039 · the reply, without the conversation it was replying to.
 *
 * <p>A mail client answering one of our notifications sends back the whole
 * thread: the four words somebody typed, then the mail they were sent, then the
 * mail before that. Stored unstripped, the first reply to a ticket is a screen
 * of quoted text, the second contains the first, and by the fifth the comment
 * thread is unreadable — which is the thread §4B.5 expects people to work from.
 *
 * <h2>Truncate at the first marker, not delete the quoted lines</h2>
 *
 * <p>Every marker here means "everything below this is the mail I am replying
 * to", so the first one found ends the message. Deleting only the lines that
 * look quoted would keep whatever the client left unmarked between them —
 * headers, separators, the trailing blank block — and those vary per client in
 * ways this cannot enumerate.
 *
 * <p>The cost is <strong>interleaved replies</strong>: somebody who answers
 * point-by-point underneath the quoted text loses everything after their first
 * quoted line. That is a real loss and it is the accepted trade. Top-posting is
 * what every client here defaults to, the alternative is an unreadable thread
 * for everyone, and §4B.6 sends a link to the ticket in every mail — the full
 * exchange is one click away. Worth revisiting only if it actually bites.
 *
 * <h2>Never strip a message down to nothing</h2>
 *
 * <p>If truncation empties the body, the original is returned unchanged. An
 * empty comment records that somebody replied and destroys what they said,
 * which is worse than a comment carrying quoted text — and it is exactly what a
 * client whose format is not handled here would produce. Failing back to too
 * much beats failing to nothing.
 *
 * <p>Signatures are deliberately not touched. "Sent from my iPhone" is noise,
 * but a signature has no reliable marker (RFC 3676's {@code "-- "} is honoured
 * by roughly nobody), and a heuristic that guesses wrong deletes the last line
 * of what somebody actually wrote.
 */
public final class QuotedReplyStripper {

    /**
     * Each pattern matches the <em>start of a line</em> that begins quoted
     * material. Anchored with {@code ^} under {@code MULTILINE} rather than
     * searched loosely, because "wrote:" and "From:" both appear in ordinary
     * prose — "I asked Priya and she wrote: no" should not truncate a comment.
     */
    private static final List<Pattern> QUOTE_MARKERS = List.of(
            // Gmail, Apple Mail, most mobile clients. The date between "On" and
            // "wrote:" varies wildly by locale and can wrap onto a second line,
            // so this spans up to ~200 characters and any newlines within them
            // rather than trying to parse a date.
            Pattern.compile("^On\\s.{0,200}?\\bwrote:\\s*$",
                    Pattern.MULTILINE | Pattern.DOTALL | Pattern.CASE_INSENSITIVE),

            // Outlook, English and the two other locales in use here. Outlook
            // also emits a long underscore rule above this block; whichever
            // comes first wins, which is what earliest-match already does.
            Pattern.compile("^-{2,}\\s*Original Message\\s*-{2,}\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("^_{10,}\\s*$", Pattern.MULTILINE),

            // Outlook's header block when it does not draw a rule. All four
            // fields are required in order, so a comment that merely mentions
            // "From: the client" is untouched.
            Pattern.compile("^From:.*\\R(^Sent:.*\\R)?^To:.*\\R(^Cc:.*\\R)?^Subject:.*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),

            // A run of ">" quoting. Requires the line to be quoted and the
            // previous line to be blank or the very start, so a single stray
            // ">" inside a paragraph does not truncate the reply.
            Pattern.compile("^>.*$", Pattern.MULTILINE),

            // Some clients localise "wrote:" but keep the address in angle
            // brackets on its own line above the quote.
            Pattern.compile("^\\s*<[^>@\\s]+@[^>@\\s]+>\\s*(wrote|schrieb|a écrit):\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE));

    private QuotedReplyStripper() {
    }

    /**
     * @param body the plain-text part of an inbound mail
     * @return what the sender actually typed, or the original body if that
     *         cannot be told apart from the quoted thread
     */
    public static String strip(String body) {
        if (body == null || body.isBlank()) {
            return body == null ? "" : body;
        }

        String normalised = body.replace("\r\n", "\n").replace('\r', '\n');

        int cut = normalised.length();
        for (Pattern marker : QUOTE_MARKERS) {
            var m = marker.matcher(normalised);
            if (m.find()) {
                cut = Math.min(cut, m.start());
            }
        }

        String stripped = normalised.substring(0, cut).strip();

        // The fail-safe. A body that is entirely quoted material, or one whose
        // client format put a marker on line one, would otherwise become an
        // empty comment — a record that somebody replied with the reply removed.
        return stripped.isEmpty() ? normalised.strip() : stripped;
    }
}
