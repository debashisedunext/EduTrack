package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** B-030 · the step-3 auto-match. Allowed to be incomplete; not allowed to be confidently wrong. */
class HeaderMatcherTest {

    @Test
    @DisplayName("case, spacing and punctuation in a heading carry no meaning")
    void matchesAcrossSpellings() {
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS,
                List.of("CLIENT CODE", "code", "  Name  ", "e-mail", "Status"));

        assertThat(mapping.targetToSource())
                .containsEntry("code", "code")
                .containsEntry("name", "  Name  ")
                .containsEntry("status", "Status");
    }

    @Test
    @DisplayName("a file exported from this system matches on our own field names")
    void matchesOnFieldNameToo() {
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS, List.of("code", "name", "email"));

        assertThat(mapping.targetToSource()).containsKeys("code", "name", "email");
    }

    @Test
    @DisplayName("no fuzzy matching — an unrecognised column is left for a human")
    void leavesUnrecognisedColumnsUnmapped() {
        // "Contact Email Address" is not "Email". Guessing here is how the
        // helpdesk address ends up in the account contact field, inside a
        // mapping the user was shown and skimmed.
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS,
                List.of("Code", "Name", "Contact Email Address"));

        assertThat(mapping.targetToSource()).doesNotContainKey("email");
        assertThat(mapping.missingRequired(TestImportSchema.FIELDS)).isEmpty();
    }

    @Test
    @DisplayName("an unmapped required column is what blocks Next")
    void reportsMissingRequiredFields() {
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS, List.of("Email", "Status"));

        assertThat(mapping.missingRequired(TestImportSchema.FIELDS))
                // Template order, not hash order — the same complaint should
                // read the same way on every run.
                .containsExactly("code", "name");
    }

    @Test
    @DisplayName("a heading appearing twice takes the first; the user resolves it in the override")
    void firstColumnWinsOnDuplicateHeadings() {
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS, List.of("Code", "Name", "code"));

        assertThat(mapping.targetToSource()).containsEntry("code", "Code");
    }

    @Test
    void ignoresBlankHeadings() {
        // Trailing empty columns are ubiquitous in real workbooks.
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS, List.of("Code", "Name", "", "   "));

        assertThat(mapping.targetToSource()).hasSize(2);
    }

    @Test
    void mapsNothingWhenNothingMatches() {
        ImportMapping mapping = HeaderMatcher.suggest(
                TestImportSchema.FIELDS, List.of("Foo", "Bar"));

        assertThat(mapping.targetToSource()).isEmpty();
        assertThat(mapping.missingRequired(TestImportSchema.FIELDS)).containsExactly("code", "name");
    }
}
