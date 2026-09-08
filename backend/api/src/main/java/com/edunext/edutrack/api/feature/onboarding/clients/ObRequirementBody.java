package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * B-106 · PLAN.md §3.9 applied to a requirement body, in one place.
 *
 * <h2>Why this is its own component and not a method on the service</h2>
 *
 * <p>A requirement can be written down two ways: through the OB-04 wizard, which
 * commits its whole four-step form in {@code ObClientWriteService.create}, and
 * through OB-05's list, which is {@code ObRequirementService.add}. Those are
 * different services with different transactions, and both have to reduce a
 * submitted body to exactly the same stored pair.
 *
 * <p>{@code ObApplicationWriteRepository.insert} states the general form of this
 * argument about a SQL statement — "one row shape written two ways is how a
 * column added to one path goes missing on the other". Here the stakes are
 * higher than a missing column, because what would drift is a <b>security
 * boundary</b>: the day one path gains a tag the other has not, the weaker one is
 * the vulnerability and nothing fails a build to say so. That is §3.9's own
 * closing requirement, that tightening the list "retroactively protects rows
 * already stored", and it is the reason {@link RichTextSanitizer} is one shared
 * class rather than a copy per feature.
 *
 * <p>So the allow-list has one caller per stored field, and this is it.
 *
 * <h2>What it decides, in order</h2>
 *
 * <p>Sanitise first, then judge — {@code CommentService}'s order and not
 * incidental: the sanitiser is what decides both what "empty" means and what the
 * stored length is, and neither question can be answered from the raw string.
 */
@Component
class ObRequirementBody {

    private final RichTextSanitizer sanitizer;

    ObRequirementBody(RichTextSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /** The two stored halves of one rich-text field: the markup, and its projection. */
    record Stored(String html, String text) {
    }

    /**
     * The sanitised markup and its plain-text projection, or a field-keyed 400.
     *
     * <p><b>An empty result is a refusal, not an empty row.</b>
     * {@code <script>alert(1)</script>} is a non-blank 27-character string that
     * satisfies {@code @NotBlank} and every length check and that means nothing
     * once the allow-list has run. Storing it as an empty requirement puts a line
     * on somebody's checklist that says nothing and that they still have to work
     * through.
     *
     * <p><b>The length bound is re-checked against the sanitised value.</b>
     * Escaping makes strings longer — a bare {@code &} leaves as {@code &amp;} —
     * so 20 000 legal characters can be stored as five times that, and §3.9's
     * sentence is about what gets stored. {@code CommentSanitizer}'s class note
     * records finding this against a {@code TEXT} column that truncated mid-tag;
     * {@code body_html} is {@code MEDIUMTEXT} because that lesson was available
     * when V20260908_1210 was written, so this check enforces a rule rather than
     * defending a column.
     *
     * @param field the request field the message lands on, so the wizard knows
     *              which of its four steps to reopen and the panel knows which
     *              row's editor to keep open. The wizard's requirements arrive
     *              indexed, and an unkeyed message on a list of six is a message
     *              about none of them
     */
    Stored of(String submitted, String field) {
        String html = sanitizer.sanitize(submitted);

        if (html.isEmpty()) {
            throw new ObClientValidationException(Map.of(field,
                    "A requirement needs something to say. Nothing in that body survived the "
                            + "allow-list — markup outside it is removed rather than escaped."));
        }
        if (html.length() > RichTextSanitizer.MAX_LENGTH) {
            throw new ObClientValidationException(Map.of(field,
                    "That is " + html.length() + " characters once sanitised, over the "
                            + RichTextSanitizer.MAX_LENGTH + " limit. Escaping makes text longer, "
                            + "so a body already near the limit can cross it."));
        }
        return new Stored(html, sanitizer.toPlainText(html));
    }
}
