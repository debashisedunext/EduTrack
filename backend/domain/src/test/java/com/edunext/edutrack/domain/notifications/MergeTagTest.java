package com.edunext.edutrack.domain.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-022 · the placeholder catalogue, and the scanner S-15 refuses a save with.
 *
 * <p>Worth its own test rather than only being exercised through the service,
 * because {@code worker} will substitute with the same pattern and the two have
 * to agree on the edge cases exactly. A renderer that treated
 * {@code {{ ticket_id }}} as unknown while the validator accepted it would print
 * braces into a mail that passed every check.
 */
class MergeTagTest {

    @Test
    @DisplayName("blueprint §4B.6's five are spelled the way it spells them")
    void blueprintTagsAreSpelledAsWritten() {
        assertThat(List.of("ticket_id", "assignee", "stage", "client", "planned_close"))
                .allSatisfy(tag -> assertThat(MergeTag.of(tag)).isPresent());
    }

    @Test
    @DisplayName("a tag is recognised regardless of case")
    void lookupIsCaseInsensitive() {
        assertThat(MergeTag.of("TICKET_ID")).contains(MergeTag.TICKET_ID);
        assertThat(MergeTag.of("  ticket_id  ")).contains(MergeTag.TICKET_ID);
    }

    @Test
    @DisplayName("a name that is not a tag is empty rather than an exception")
    void unknownNameIsEmpty() {
        assertThat(MergeTag.of("ticketId")).isEmpty();
        assertThat(MergeTag.of(null)).isEmpty();
        assertThat(MergeTag.of("")).isEmpty();
    }

    @Test
    @DisplayName("placeholder() round-trips through the scanner")
    void placeholderRoundTrips() {
        for (MergeTag tag : MergeTag.values()) {
            assertThat(MergeTag.unknownIn("body " + tag.placeholder() + " end")).isEmpty();
        }
    }

    /** The camelCase near-miss that would print literal braces in a mail. */
    @Test
    @DisplayName("the classic misspelling is caught")
    void camelCaseMisspellingIsCaught() {
        assertThat(MergeTag.unknownIn("<p>{{ticketId}} is late</p>")).containsExactly("ticketId");
    }

    @Test
    @DisplayName("whitespace inside the braces is tolerated")
    void whitespaceInsideBracesIsTolerated() {
        assertThat(MergeTag.unknownIn("{{ ticket_id }} and {{  assignee  }}")).isEmpty();
    }

    /**
     * One mistake reported once, in first-appearance order — a body that
     * misspells the same tag four times would otherwise bury the second mistake
     * under three repeats of the first.
     */
    @Test
    @DisplayName("repeats collapse and order is first appearance")
    void repeatsCollapseInFirstAppearanceOrder() {
        assertThat(MergeTag.unknownIn("{{b}} {{a}} {{b}} {{a}} {{c}}"))
                .containsExactly("b", "a", "c");
    }

    /** {@code subject_template} is null on every in-app row. */
    @Test
    @DisplayName("null and blank text yield nothing rather than throwing")
    void nullTextIsSafe() {
        assertThat(MergeTag.unknownIn(null)).isEmpty();
        assertThat(MergeTag.unknownIn("   ")).isEmpty();
    }

    /**
     * A single brace is not a placeholder and must not be reported as a broken
     * one — CSS and inline styles in an HTML body are full of them.
     */
    @Test
    @DisplayName("single braces are not placeholders")
    void singleBracesAreIgnored() {
        assertThat(MergeTag.unknownIn("<style>p { margin: 0 }</style>")).isEmpty();
    }
}
