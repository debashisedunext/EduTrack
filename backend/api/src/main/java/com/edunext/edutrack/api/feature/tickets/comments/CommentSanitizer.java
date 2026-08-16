package com.edunext.edutrack.api.feature.tickets.comments;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

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
     * §3.9: "20 000 characters". Applied to the sanitised result as well as to
     * the request — see the class note on why the two can differ.
     */
    static final int MAX_LENGTH = 20_000;

    /** Tags whose boundaries are line breaks when the HTML is flattened. */
    private static final Set<String> BLOCK_TAGS =
            Set.of("p", "ol", "ul", "li", "pre", "blockquote", "h3", "h4");

    /**
     * §3.9's allowed markup, transcribed:
     * {@code p, br, strong, em, u, s, ol, ul, li, code, pre, blockquote,
     * a[href], img[src], h3, h4}, with {@code href} and {@code src} restricted
     * to {@code http}, {@code https} and {@code data:image/*}.
     *
     * <p>Built from {@link Safelist#none()} rather than from {@code basic()} or
     * {@code relaxed()}. Those are supersets — {@code relaxed()} carries tables,
     * {@code div}, {@code span}, {@code class} and {@code style} — and starting
     * from one would mean the list here described what we <em>removed</em> from
     * jsoup's opinion instead of what §3.9 permits. A future jsoup that adds a
     * tag to {@code relaxed()} would silently widen us; nothing widens
     * {@code none()}.
     */
    private static final Safelist ALLOW_LIST = Safelist.none()
            .addTags("p", "br", "strong", "em", "u", "s",
                    "ol", "ul", "li",
                    "code", "pre", "blockquote",
                    "a", "img",
                    "h3", "h4")
            .addAttributes("a", "href")
            .addAttributes("img", "src", "alt")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https", "data")
            // Anchors leave the application. Without this a comment can be used
            // to hand an attacker a `window.opener` handle on the tab it opened
            // from — cheap to add, and the client cannot add it retroactively to
            // markup already stored.
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addEnforcedAttribute("a", "target", "_blank");

    /**
     * Sanitised HTML for {@code body_html}, or an empty string when nothing
     * survived.
     *
     * <p>An empty return is a real and expected outcome, not a failure: a body
     * of {@code <script>alert(1)</script>} is a non-blank 27-character string
     * that Bean Validation accepts and that means nothing once §3.9 is applied.
     * {@link CommentService} turns it into the same 400 a blank body gets. It is
     * returned rather than thrown because "what does this reduce to" and "may
     * this be posted" are different questions, and only the caller knows whether
     * an empty result is fatal.
     */
    String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Document dirty = Jsoup.parseBodyFragment(html);
        stripNonImageData(dirty);

        Document clean = new Cleaner(ALLOW_LIST).clean(dirty);
        // Off, or jsoup reformats the markup with indentation of its own —
        // which inflates the stored value, changes the rendering of `pre`, and
        // makes a round-trip comparison in a test fail for reasons that have
        // nothing to do with what was stripped.
        clean.outputSettings().prettyPrint(false);
        dropStrandedElements(clean);

        return clean.body().html();
    }

    /**
     * The plain-text projection stored in {@code body_text}.
     *
     * <p>The column exists so neither search (PLAN.md §3.8, which indexes a
     * plain-text projection and never the markup column, because an index over
     * HTML matches {@code li} and {@code href} as readily as prose) nor the
     * text/plain part of a reply-by-email (D-039) has to derive it at read time.
     *
     * <p>Block-aware, mirroring {@code richTextToPlainText} in
     * {@code rich-text.ts}: a list that flattened to one run-together line would
     * make the search snippet unreadable, which is the one place this value is
     * shown to a person.
     */
    String toPlainText(String sanitizedHtml) {
        if (sanitizedHtml == null || sanitizedHtml.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        flatten(Jsoup.parseBodyFragment(sanitizedHtml).body(), out);
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void flatten(Node node, StringBuilder out) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode text) {
                out.append(text.text());
            } else if (child instanceof Element element) {
                if ("br".equals(element.tagName())) {
                    out.append('\n');
                    continue;
                }
                flatten(element, out);
                if (BLOCK_TAGS.contains(element.tagName())) {
                    out.append('\n');
                }
            }
        }
    }

    /**
     * Removes {@code src} from any {@code img} whose {@code data:} URI is not an
     * image, before the Safelist runs.
     *
     * <p>§3.9 permits {@code data:image/*} and jsoup's protocol check cannot
     * express the media type — it matches the {@code data:} prefix and accepts
     * whatever follows. {@code data:text/html;base64,PHNjcmlwdD4…} therefore
     * satisfies a Safelist that allows {@code data}.
     *
     * <p>The attribute is dropped rather than the element, and
     * {@link #dropStrandedElements} then removes the {@code img} that is left
     * behind — see its note on why that second pass is needed.
     */
    private static void stripNonImageData(Document document) {
        for (Element img : document.select("img[src]")) {
            String src = img.attr("src").trim().toLowerCase(Locale.ROOT);
            // Whitespace and control characters inside a URI are a classic way
            // to smuggle a prefix past a naive check; jsoup has already
            // normalised the attribute, and the comparison is over the trimmed,
            // lower-cased value for the same reason.
            if (src.startsWith("data:") && !src.startsWith("data:image/")) {
                img.removeAttr("src");
            }
        }
    }

    /**
     * Removes {@code img} with no {@code src} and unwraps {@code a} with no
     * {@code href}, after the Cleaner has run.
     *
     * <p><b>A Safelist that permits a tag permits it with none of its
     * attributes.</b> This is the one piece of jsoup behaviour here that is not
     * obvious from the API and that the first draft of this class got wrong:
     * {@code addAttributes("a", "href")} says <em>href is allowed on a</em>, not
     * <em>a requires href</em>. So a {@code javascript:} link does not vanish
     * when its protocol is refused — the {@code href} vanishes and a bare
     * {@code <a>} remains, wearing the two enforced attributes that
     * {@link #ALLOW_LIST} adds to every anchor.
     *
     * <p>Nothing unsafe survives that — an anchor with no {@code href} navigates
     * nowhere — so this is tidiness rather than defence. It is worth doing
     * anyway on both counts that matter here: the stored value is what every
     * future reader parses, and {@code <a rel="noopener noreferrer nofollow"
     * target="_blank">} around every refused link is 48 bytes of confusing noise
     * per occurrence in a column already tight enough to need
     * {@link #MAX_LENGTH} re-checked. An {@code img} with no {@code src} is
     * worse than noise: it renders as a broken-image icon, which tells the
     * reader something failed rather than that something was refused.
     *
     * <p>Anchors are unwrapped and images removed, which is the difference
     * between an element that has contents worth keeping and one that does not.
     * The link text is what the author wrote and is frequently the whole
     * meaning; an image has nothing inside it.
     */
    private static void dropStrandedElements(Document document) {
        document.select("a:not([href])").unwrap();
        document.select("img:not([src])").remove();
    }
}
