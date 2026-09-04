package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.onboarding.outbox.ObCategory;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-112 · the OB-13 wording catalogue, checked against the event catalogue.
 *
 * <p>{@code ObMailCatalogueTest}'s counterpart, and it earns its place the same
 * way: a template that references a variable no enqueuer sends is invisible
 * everywhere else. It renders, the line quietly falls back to its static form,
 * and the entry says less than it was written to say — in a list somebody
 * skims, which is the least likely place for anyone to notice.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObInAppCatalogueTest {

    @Test
    void every_event_has_wording() {
        List<ObNotificationEvent> missing = ObNotificationEvent.all().stream()
                .filter(event -> ObInAppTemplate.forEvent(event).isEmpty())
                .toList();

        // The renderer falls back to a generic entry rather than dropping the
        // row, so a hole here is not a crash — which is exactly why it needs
        // asserting. B-113 replaces the constants with rows; this is the check
        // that survives that change.
        assertThat(missing)
                .as("every ObNotificationEvent needs a bell wording")
                .isEmpty();
    }

    @Test
    void one_template_per_event() {
        Set<ObNotificationEvent> seen = new LinkedHashSet<>();
        for (ObInAppTemplate template : ObInAppTemplate.values()) {
            assertThat(seen.add(template.event()))
                    .as("two templates for %s — forEvent would pick whichever was declared first",
                            template.event())
                    .isTrue();
        }
    }

    @Test
    void every_placeholder_is_a_variable_its_event_declares() {
        for (ObInAppTemplate template : ObInAppTemplate.values()) {
            Set<String> declared = template.event().variables();
            assertThat(placeholdersIn(template.title()))
                    .as("%s title", template)
                    .isSubsetOf(declared);
            assertThat(placeholdersIn(template.body()))
                    .as("%s body", template)
                    .isSubsetOf(declared);
        }
    }

    /**
     * The fallbacks are what a lost value falls back <em>to</em>. One carrying a
     * placeholder of its own would print braces to the reader on exactly the
     * path taken because a value was missing.
     */
    @Test
    void no_fallback_carries_a_placeholder() {
        for (ObInAppTemplate template : ObInAppTemplate.values()) {
            assertThat(placeholdersIn(template.fallbackTitle()))
                    .as("%s fallback title", template)
                    .isEmpty();
            assertThat(placeholdersIn(template.fallbackBody()))
                    .as("%s fallback body", template)
                    .isEmpty();
        }
    }

    /**
     * <b>The one that is a security rule rather than a wording rule.</b> A
     * one-time password belongs in the mail it was minted for. A bell entry sits
     * open on a shared screen and is still readable tomorrow, so a code that
     * reached this list would outlive every control on it.
     */
    @Test
    void no_template_repeats_the_signoff_otp() {
        for (ObInAppTemplate template : ObInAppTemplate.values()) {
            assertThat(placeholdersIn(template.title()))
                    .as("%s title", template)
                    .doesNotContain("otp_code");
            assertThat(placeholdersIn(template.body()))
                    .as("%s body", template)
                    .doesNotContain("otp_code");
        }
    }

    /**
     * The static halves have to fit the column on their own — those are the
     * strings truncation must never have to touch, because a truncated fallback
     * is a sentence that lost its end for no reason a reader can see.
     */
    @Test
    void fallback_titles_fit_the_column() {
        for (ObInAppTemplate template : ObInAppTemplate.values()) {
            assertThat(template.fallbackTitle().length())
                    .as("%s fallback title length", template)
                    .isLessThanOrEqualTo(ObInAppRenderer.TITLE_MAX);
        }
    }

    /** Nothing here is HTML; the bell renders text. */
    @Test
    void no_template_carries_markup() {
        for (ObInAppTemplate template : ObInAppTemplate.values()) {
            assertThat(template.title() + template.body()
                    + template.fallbackTitle() + template.fallbackBody())
                    .as("%s", template)
                    .doesNotContain("<");
        }
    }

    /**
     * The two categories that carry a tab must actually be reachable, or OB-13
     * ships a tab that is empty whatever happens.
     */
    @Test
    void every_tabbed_category_has_at_least_one_event() {
        for (ObCategory category : List.of(
                ObCategory.ASSIGNMENT, ObCategory.ESCALATION, ObCategory.REMINDER)) {
            assertThat(ObNotificationEvent.all().stream()
                    .anyMatch(event -> event.category() == category))
                    .as("no event is categorised %s, so its tab can never fill", category)
                    .isTrue();
        }
    }

    private static Set<String> placeholdersIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = MergeTag.PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
