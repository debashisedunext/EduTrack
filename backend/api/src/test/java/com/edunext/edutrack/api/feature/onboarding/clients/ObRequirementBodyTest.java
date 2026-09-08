package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-106 · that a requirement body really goes through PLAN.md §3.9.
 *
 * <p>{@link ObRequirementBody} is a thin class over a shared sanitiser, and that
 * is exactly why it is worth its own test: the failure it exists to prevent is
 * <b>not calling the sanitiser</b>, or calling it and storing the input anyway.
 * Neither shows up as a broken build, a log line or a wrong-looking screen —
 * the page renders the stored markup and it looks correct right up until
 * somebody stores a {@code <script>}.
 *
 * <p>The allow-list itself is pinned against §3.9 by {@code CommentSanitizerTest}
 * and {@code RichTextSanitizerTest}, so this does not re-transcribe fourteen
 * tags. It asserts the three decisions this class makes on top of it.
 */
class ObRequirementBodyTest {

    private final ObRequirementBody bodies = new ObRequirementBody(new RichTextSanitizer());

    @Test
    @DisplayName("markup outside the allow-list is removed, and what is stored is the result")
    void sanitisesRatherThanStoringWhatArrived() {
        ObRequirementBody.Stored stored = bodies.of(
                "<p>SSO against their <strong>Azure AD</strong></p>"
                        + "<script>steal()</script><div onclick=\"x\">tenant</div>",
                "bodyHtml");

        assertThat(stored.html()).contains("<strong>Azure AD</strong>");
        assertThat(stored.html()).doesNotContain("script").doesNotContain("onclick");
        // div is not on §3.9's list, so its text survives and its tag does not.
        assertThat(stored.html()).doesNotContain("<div");
        assertThat(stored.text()).contains("SSO against their Azure AD").contains("tenant");
    }

    /**
     * The case a length check and a {@code @NotBlank} both wave through.
     *
     * <p>27 non-blank characters that mean nothing once the allow-list has run.
     * Storing it would put a line on somebody's checklist that says nothing and
     * that they still have to work through.
     */
    @Test
    @DisplayName("a body that reduces to nothing is a field-keyed 400, not an empty row")
    void refusesABodyThatSanitisesAway() {
        assertThatThrownBy(() -> bodies.of("<script>alert(1)</script>", "bodyHtml"))
                .isInstanceOf(ObClientValidationException.class)
                .satisfies(thrown -> assertThat(
                        ((ObClientValidationException) thrown).errors()).containsKey("bodyHtml"));
    }

    /**
     * The field key is a parameter because the wizard sends a list.
     *
     * <p>{@code requirements[2].bodyHtml} on a form of six rows is a message
     * about one of them; {@code requirements} alone is a message about none, and
     * the four-step wizard would have nothing to reopen.
     */
    @Test
    @DisplayName("the refusal is keyed to the field the caller named")
    void keysTheErrorToTheCallersField() {
        assertThatThrownBy(() -> bodies.of("<script>x</script>", "requirements[2].bodyHtml"))
                .isInstanceOf(ObClientValidationException.class)
                .satisfies(thrown -> assertThat(
                        ((ObClientValidationException) thrown).errors())
                        .containsKey("requirements[2].bodyHtml"));
    }

    /**
     * §3.9's bound is on what is <em>submitted</em>; this checks the stored
     * value too.
     *
     * <p>Escaping makes strings longer — a bare {@code &} leaves as
     * {@code &amp;} — so a body inside the limit on the way in can be over it by
     * the time it is stored. 19 000 ampersands is 19 000 characters submitted
     * and 95 000 stored. {@code CommentSanitizer}'s class note records this
     * arithmetic against a column that truncated mid-tag.
     */
    @Test
    @DisplayName("the length limit is applied to the sanitised value, which can be longer")
    void boundsTheStoredValueRatherThanTheSubmittedOne() {
        String submitted = "<p>" + "&".repeat(19_000) + "</p>";
        assertThat(submitted.length()).isLessThan(RichTextSanitizer.MAX_LENGTH);

        assertThatThrownBy(() -> bodies.of(submitted, "bodyHtml"))
                .isInstanceOf(ObClientValidationException.class)
                .hasMessageContaining("sanitised");
    }

    @Test
    @DisplayName("the plain-text projection is block-aware, so a list does not run together")
    void projectsBlocksAsLines() {
        ObRequirementBody.Stored stored = bodies.of(
                "<ul><li>Azure AD</li><li>Tally import</li></ul>", "bodyHtml");

        assertThat(stored.text()).isEqualTo("Azure AD\nTally import");
    }
}
