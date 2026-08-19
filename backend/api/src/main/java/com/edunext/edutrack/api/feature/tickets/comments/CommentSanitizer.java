package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * C-029 · PLAN.md §3.9 on the write path — <b>the first server-side sanitiser in
 * the codebase</b>.
 *
 * <p>§3.9 is normative for all three rich-text fields and says sanitisation
 * happens "on the server, on write, always — an allow-list sanitiser over tags
 * and attributes. The client sanitises too, for what it renders, but that copy
 * is <em>advice</em>: the only sanitiser an attacker cannot skip is the one on
 * the write path." Until this class the advice was all there was. C-066 shipped
 * {@code components/ui/rich-text.ts} and it is a good sanitiser, but it runs in
 * the browser of the person <em>writing</em> the comment, which is precisely the
 * party a stored-XSS attack is conducted by. {@code curl} skips it entirely.
 *
 * <p>The comment box is where that stopped being theoretical. The ticket
 * description is written and read largely by the same desk; a comment is written
 * by one user and rendered to another, which is §3.9's own stated threat model —
 * "written by support desks quoting client email and rendered to a manager who
 * has every reason to trust the page".
 *
 * <h2>The allow-list is §3.9's, transcribed</h2>
 *
 * <p>Fourteen tags and two protocol rules, in the order the spec lists them, so
 * the two can be compared by eye. That is the reason for jsoup over OWASP's
 * html-sanitizer: {@link Safelist} is close enough to a transcription that a
 * reviewer can check it against the document rather than against a builder DSL.
 *
 * <p>It is deliberately the same vocabulary as {@code RICH_TEXT_ALLOWED_TAGS} in
 * {@code rich-text.ts}, and the two are meant to be read side by side. They are
 * not generated from one source, and the honest reason is that no such source
 * exists in this stack — the contract describes shapes, not markup vocabularies.
 * {@code CommentSanitizerTest} pins the list against §3.9 so a drift on either
 * side is a failing test rather than a field that silently stops rendering.
 *
 * <h2>Two things the Safelist does not do, which are the interesting half</h2>
 *
 * <p><b>{@code data:} is a protocol, not a media type.</b> jsoup's protocol check
 * on {@code img[src]} matches the {@code data:} prefix and stops there — so
 * {@code data:text/html;base64,…} passes a Safelist that permits {@code data},
 * and that is a script-execution vector in any context that later treats the
 * value as a document rather than as an image. §3.9 says {@code data:image/*},
 * with the media type, so the media type is checked here in
 * {@link #stripNonImageData}.
 *
 * <p><b>Sanitising can make a string longer.</b> §3.9's 20 000 characters are
 * enforced by Bean Validation on the way in, over what the caller <em>sent</em>.
 * Escaping runs afterwards and a bare {@code &} becomes {@code &amp;}, so 20 000
 * legal characters can leave here as 100 000 — and {@code ticket_comments
 * .body_html} is {@code TEXT}, which is 65 535 bytes. That combination truncates
 * mid-tag and stores markup that will never parse again. §3.9 anticipated the
 * column ({@code MEDIUMTEXT}) and the baseline created {@code TEXT}; rather than
 * edit Stream A's applied migration, the bound is enforced over the sanitised
 * value as well as the submitted one — which is what §3.9's sentence means in
 * any case, since it is describing what gets stored.
 */
@Component
class CommentSanitizer {

    /**
     * §3.9's rule now lives in {@link RichTextSanitizer}, because that section
     * is normative for the ticket's two rich-text fields as well as this one
     * (C-067), and an allow-list that exists twice cannot satisfy its own
     * requirement that tightening it protects rows already stored.
     *
     * <p>This class stays, with its name, its package-private API and its
     * sixteen tests, so nothing about the comment box changed in the move —
     * those tests passing unaltered is the evidence for that.
     *
     * <p>Constructed directly rather than injected, so {@code new
     * CommentSanitizer()} keeps working in {@code CommentServiceTest}. The
     * delegate is stateless — its allow-list and bound are {@code static final}
     * — so there is nothing a container would give it that this does not.
     */
    private final RichTextSanitizer delegate = new RichTextSanitizer();

    static final int MAX_LENGTH = RichTextSanitizer.MAX_LENGTH;

    String sanitize(String html) {
        return delegate.sanitize(html);
    }

    String toPlainText(String sanitizedHtml) {
        return delegate.toPlainText(sanitizedHtml);
    }
}
