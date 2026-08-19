package com.edunext.edutrack.api.text;

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
 * PLAN.md §3.9's allow-list sanitiser, shared.
 *
 * <p><b>This is the whole of §3.9's rule, in one class, on purpose.</b> That
 * section is normative for three fields — the ticket's Task Description and
 * Steps to Generate (§7.5) and the comment body (§4B.5) — and it ends with the
 * requirement that tightening the list "retroactively protects rows already
 * stored". Two copies of an allow-list cannot do that: the day one gains a tag
 * the other does not, the weaker one is the vulnerability, and nothing fails a
 * build to say so.
 *
 * <p><b>Extracted from {@code CommentSanitizer} (C-029/C-066) rather than
 * rewritten</b>, which is why the comments below still argue from the comment
 * box. That class now delegates here and keeps its own name and package-private
 * API, so nothing about comment behaviour changed and its sixteen tests pass
 * untouched — they are the proof this move was behaviour-preserving.
 *
 * <p><b>Ownership needs settling and has not been.</b> {@code api/text/} is a
 * new package TEAM-PLAN §6 does not list, holding code Stream C wrote and
 * Stream D now also depends on. Raised with both rather than assigned here.
 */
@Component
public class RichTextSanitizer {

    /**
     * §3.9: "20 000 characters". Applied to the sanitised result as well as to
     * the request — see the class note on why the two can differ.
     */
    public static final int MAX_LENGTH = 20_000;

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
     * {@code CommentService} turns it into the same 400 a blank body gets. It is
     * returned rather than thrown because "what does this reduce to" and "may
     * this be posted" are different questions, and only the caller knows whether
     * an empty result is fatal.
     */
    public String sanitize(String html) {
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
    public String toPlainText(String sanitizedHtml) {
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
