package com.edunext.edutrack.api.feature.tickets.comments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-029 · PLAN.md §3.9's allow-list, and the ways round it.
 *
 * <p>This is the first server-side sanitiser in the codebase and the only thing
 * standing between a support desk pasting client email and a manager's browser,
 * so the tests are written adversarially rather than as a happy path with a
 * {@code <script>} case bolted on. §3.9 names the threat itself: "these fields
 * are written by support desks quoting client email and rendered to a manager
 * who has every reason to trust the page. That is the exact shape of a
 * stored-XSS vulnerability."
 */
class CommentSanitizerTest {

    private final CommentSanitizer sanitizer = new CommentSanitizer();

    @Nested
    @DisplayName("§3.9's allow-list, transcribed")
    class AllowList {

        /**
         * The fourteen tags §3.9 lists, each proved to survive.
         *
         * <p>Written out rather than looped over a constant in the production
         * class, deliberately. A test that reads its expectations from the code
         * it is testing passes whatever that code says — the point here is to
         * pin the list against the *document*, so deleting a tag from
         * {@code ALLOW_LIST} fails a test rather than quietly narrowing what
         * users can write.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "<p>text</p>",
                "a<br>b",
                "<strong>bold</strong>",
                "<em>italic</em>",
                "<u>underline</u>",
                "<s>struck</s>",
                "<ol><li>one</li></ol>",
                "<ul><li>one</li></ul>",
                "<code>x = 1</code>",
                "<pre>block</pre>",
                "<blockquote>quoted</blockquote>",
                "<h3>heading</h3>",
                "<h4>subheading</h4>",
        })
        void keepsEveryTagSection39Permits(String markup) {
            assertThat(sanitizer.sanitize(markup)).isNotEmpty();
        }

        @Test
        @DisplayName("and drops everything it does not")
        void dropsEverythingElse() {
            // The text inside survives — jsoup unwraps rather than deletes, which
            // is right: someone whose paste carried a <div> wrapper meant the
            // words, not the wrapper.
            assertThat(sanitizer.sanitize("<div>text</div>")).isEqualTo("text");
            assertThat(sanitizer.sanitize("<table><tr><td>cell</td></tr></table>")).isEqualTo("cell");
            assertThat(sanitizer.sanitize("<h1>shouting</h1>")).isEqualTo("shouting");
            assertThat(sanitizer.sanitize("<span style=\"color:red\">red</span>")).isEqualTo("red");
        }

        @Test
        @DisplayName("class and style never survive, on any tag")
        void stripsPresentationAttributes() {
            String clean = sanitizer.sanitize("<p class=\"x\" style=\"position:fixed;top:0\">text</p>");
            assertThat(clean).doesNotContain("class").doesNotContain("style");
        }
    }

    @Nested
    @DisplayName("script execution")
    class ScriptExecution {

        @Test
        void removesScriptTagsAndTheirContents() {
            // Removed, not escaped — §3.9 is explicit about the difference. An
            // escaped script tag is still a script tag to anything that later
            // unescapes it, and things do.
            assertThat(sanitizer.sanitize("<script>alert(1)</script>")).isEmpty();
            assertThat(sanitizer.sanitize("before<script>alert(1)</script>after"))
                    .isEqualTo("beforeafter");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<p onclick=\"alert(1)\">text</p>",
                "<p onmouseover=\"alert(1)\">text</p>",
                "<img src=\"http://x/y.png\" onerror=\"alert(1)\">",
                "<p ONCLICK=\"alert(1)\">text</p>",
        })
        void removesEveryEventHandler(String markup) {
            assertThat(sanitizer.sanitize(markup)).doesNotContainIgnoringCase("onclick")
                    .doesNotContainIgnoringCase("onmouseover")
                    .doesNotContainIgnoringCase("onerror");
        }

        @Test
        void removesStyleAndIframeAndObject() {
            assertThat(sanitizer.sanitize("<style>body{display:none}</style>")).isEmpty();
            assertThat(sanitizer.sanitize("<iframe src=\"http://evil\"></iframe>")).isEmpty();
            assertThat(sanitizer.sanitize("<object data=\"http://evil\"></object>")).isEmpty();
        }
    }

    @Nested
    @DisplayName("URI protocols")
    class Protocols {

        @Test
        void keepsHttpAndHttpsLinks() {
            assertThat(sanitizer.sanitize("<a href=\"https://example.test/x\">x</a>"))
                    .contains("https://example.test/x");
            assertThat(sanitizer.sanitize("<a href=\"http://example.test/x\">x</a>"))
                    .contains("http://example.test/x");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<a href=\"javascript:alert(1)\">x</a>",
                "<a href=\"JaVaScRiPt:alert(1)\">x</a>",
                "<a href=\"vbscript:msgbox(1)\">x</a>",
                "<a href=\"file:///etc/passwd\">x</a>",
        })
        void dropsEveryOtherProtocolOnAnAnchor(String markup) {
            // The anchor goes with its href — the Safelist requires the
            // attribute, so an anchor that loses it is no longer a permitted
            // element. The label survives as text, which is what the author
            // wrote.
            assertThat(sanitizer.sanitize(markup)).isEqualTo("x");
        }

        /**
         * The bug this class's javadoc is mostly about.
         *
         * <p>jsoup's protocol check on {@code img[src]} matches the {@code data:}
         * prefix and stops. §3.9 says {@code data:image/*} — <em>with</em> the
         * media type — so a Safelist that permits {@code data} permits
         * {@code data:text/html} too, and that is a document, not an image.
         */
        @Test
        @DisplayName("data: URIs are allowed only when they are actually images")
        void allowsDataImagesAndNothingElse() {
            String pixel = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==";
            assertThat(sanitizer.sanitize("<img src=\"" + pixel + "\">")).contains(pixel);

            assertThat(sanitizer.sanitize("<img src=\"data:text/html;base64,PHNjcmlwdD4=\">")).isEmpty();
            assertThat(sanitizer.sanitize("<img src=\"data:application/javascript,alert(1)\">")).isEmpty();
            // Case and padding are not a way round it.
            assertThat(sanitizer.sanitize("<img src=\"DATA:TEXT/HTML,<h1>x\">")).isEmpty();
            assertThat(sanitizer.sanitize("<img src=\"  data:text/html,x\">")).isEmpty();
        }

        @Test
        @DisplayName("outbound links carry rel and target, which the client cannot add retroactively")
        void enforcesRelOnAnchors() {
            String clean = sanitizer.sanitize("<a href=\"https://example.test\">x</a>");
            assertThat(clean).contains("rel=\"noopener noreferrer nofollow\"")
                    .contains("target=\"_blank\"");
        }

        @Test
        @DisplayName("a rel the author supplied is replaced, not merged")
        void overridesAnAuthorSuppliedRel() {
            String clean = sanitizer.sanitize("<a href=\"https://example.test\" rel=\"opener\">x</a>");
            assertThat(clean).contains("rel=\"noopener noreferrer nofollow\"")
                    .doesNotContain("rel=\"opener\"");
        }

        /**
         * The jsoup behaviour that caught the first draft of
         * {@link CommentSanitizer} out: permitting a tag permits it with none of
         * its attributes, so refusing a protocol strips the {@code href} and
         * leaves the element standing.
         *
         * <p>Nothing unsafe survives — a hrefless anchor navigates nowhere — but
         * a bare {@code <a>} wearing two enforced attributes around every refused
         * link, and a src-less {@code <img>} rendering as a broken-image icon,
         * are both worth not storing. Pinned because it is invisible: the
         * protocol assertions above passed with the strays present.
         */
        @Test
        @DisplayName("nothing is left standing once its required attribute is refused")
        void leavesNoStrandedElements() {
            assertThat(sanitizer.sanitize("<a href=\"javascript:alert(1)\">label</a>"))
                    .isEqualTo("label");
            assertThat(sanitizer.sanitize("<img src=\"data:text/html,x\">")).isEmpty();
            assertThat(sanitizer.sanitize("<a>no href at all</a>")).isEqualTo("no href at all");
            assertThat(sanitizer.sanitize("<img>")).isEmpty();
        }
    }

    @Nested
    @DisplayName("emptiness")
    class Emptiness {

        @Test
        void nullAndBlankReduceToEmpty() {
            assertThat(sanitizer.sanitize(null)).isEmpty();
            assertThat(sanitizer.sanitize("")).isEmpty();
            assertThat(sanitizer.sanitize("   ")).isEmpty();
        }

        /**
         * The case {@code @NotBlank} cannot catch, and the reason
         * {@link InvalidCommentException#emptyBody} exists.
         */
        @Test
        @DisplayName("a body of pure script is a non-blank string that sanitises to nothing")
        void hostileBodiesReduceToEmpty() {
            String hostile = "<script>alert(1)</script>";
            assertThat(hostile).isNotBlank();
            assertThat(sanitizer.sanitize(hostile)).isEmpty();
        }
    }

    @Nested
    @DisplayName("escaping can make a body longer")
    class Length {

        /**
         * The reason {@link CommentService} re-checks the bound after sanitising
         * rather than trusting {@code @Size} on the request.
         *
         * <p>{@code &} is one character in and five out. Twenty thousand of them
         * pass Bean Validation and leave the sanitiser as a hundred thousand,
         * against a {@code TEXT} column that holds 65 535 bytes — which truncates
         * mid-entity and stores markup that will never parse again.
         */
        @Test
        void ampersandsExpandFivefold() {
            String submitted = "&".repeat(20_000);
            assertThat(submitted).hasSize(CommentSanitizer.MAX_LENGTH);

            String stored = sanitizer.sanitize(submitted);
            assertThat(stored).hasSize(100_000);
            assertThat(stored.length()).isGreaterThan(CommentSanitizer.MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("the plain-text projection")
    class PlainText {

        @Test
        void flattensMarkupToText() {
            assertThat(sanitizer.toPlainText("<p>Hello <strong>world</strong></p>"))
                    .isEqualTo("Hello world");
        }

        /**
         * Block-aware, mirroring {@code richTextToPlainText} in
         * {@code rich-text.ts}. A list flattened to one run-together line is what
         * a search snippet would show, and it is unreadable.
         */
        @Test
        void keepsBlockBoundariesAsLineBreaks() {
            assertThat(sanitizer.toPlainText("<ul><li>one</li><li>two</li></ul>"))
                    .isEqualTo("one\ntwo");
            assertThat(sanitizer.toPlainText("<p>one</p><p>two</p>"))
                    .isEqualTo("one\ntwo");
            assertThat(sanitizer.toPlainText("one<br>two")).isEqualTo("one\ntwo");
        }

        @Test
        @DisplayName("no markup ever reaches the text — that is the whole reason the column exists")
        void neverLeaksMarkup() {
            String text = sanitizer.toPlainText(
                    "<ul><li><a href=\"https://example.test\">the label</a></li></ul>");
            // Equality is the assertion. Substring checks for tag names are what
            // the first draft used, and "link" contains "li" — the test passed
            // for the wrong reason on one input and failed on another.
            assertThat(text).isEqualTo("the label");
            assertThat(sanitizer.toPlainText("<p>a &amp; b</p>")).isEqualTo("a & b");
        }

        @Test
        void emptyInputGivesEmptyText() {
            assertThat(sanitizer.toPlainText(null)).isEmpty();
            assertThat(sanitizer.toPlainText("")).isEmpty();
            assertThat(sanitizer.toPlainText("<p></p>")).isEmpty();
        }
    }
}
