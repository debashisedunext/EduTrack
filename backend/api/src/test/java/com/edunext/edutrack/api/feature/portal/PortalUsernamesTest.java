package com.edunext.edutrack.api.feature.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A-125 · the generated login name. */
class PortalUsernamesTest {

    private static final java.util.function.Predicate<String> NOTHING_TAKEN = candidate -> false;

    @Test
    void theClientCodeLeadsAndTheGivenNameFollows() {
        assertThat(PortalUsernames.generate("ACME", "Ravi Kumar", NOTHING_TAKEN))
                .isEqualTo("ACME.ravi");
    }

    @Test
    @DisplayName("the second Ravi at one client is ravi2, not ravi1 beside a bare ravi")
    void aCollisionCounts() {
        Set<String> existing = Set.of("ACME.ravi");

        assertThat(PortalUsernames.generate("ACME", "Ravi Kumar", existing::contains))
                .isEqualTo("ACME.ravi2");
    }

    @Test
    void itKeepsCountingPastTheSecond() {
        Set<String> existing = Set.of("ACME.ravi", "ACME.ravi2", "ACME.ravi3");

        assertThat(PortalUsernames.generate("ACME", "Ravi Kumar", existing::contains))
                .isEqualTo("ACME.ravi4");
    }

    /**
     * The same given name at two clients is not a collision at all — the code
     * carries the uniqueness, which is why it leads.
     */
    @Test
    void twoClientsMayBothHaveARavi() {
        Set<String> existing = Set.of("ACME.ravi");

        assertThat(PortalUsernames.generate("CONTOSO", "Ravi Kumar", existing::contains))
                .isEqualTo("CONTOSO.ravi");
    }

    @Test
    @DisplayName("accents, titles and punctuation reduce to something typable")
    void aNameIsReducedToWhatSomebodyCanTypeOnAPhoneCall() {
        assertThat(PortalUsernames.generate("ACME", "José-María Ferrández", NOTHING_TAKEN))
                .isEqualTo("ACME.josmara");
        assertThat(PortalUsernames.generate("ACME", "  priya   nair  ", NOTHING_TAKEN))
                .isEqualTo("ACME.priya");
    }

    /**
     * A name that reduces to nothing falls back rather than transliterating.
     *
     * <p>{@code ACME.user} is honest: it still disambiguates by counter, and it
     * does not render somebody's name into something they would not recognise
     * as theirs.
     */
    @Test
    void aNameWithNoLatinLettersFallsBackRatherThanTransliterating() {
        assertThat(PortalUsernames.generate("ACME", "李伟", NOTHING_TAKEN)).isEqualTo("ACME.user");
        assertThat(PortalUsernames.generate("ACME", "", NOTHING_TAKEN)).isEqualTo("ACME.user");
        assertThat(PortalUsernames.generate("ACME", null, NOTHING_TAKEN)).isEqualTo("ACME.user");
    }

    @Test
    void theLocalPartIsBoundedSoTheColumnCanNeverRefuseIt() {
        String username = PortalUsernames.generate("ACME", "A".repeat(200), NOTHING_TAKEN);

        assertThat(username).isEqualTo("ACME." + "a".repeat(24));
        assertThat(username.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void theCodeIsNormalisedRatherThanTrusted() {
        assertThat(PortalUsernames.generate("  acme  ", "Ravi", NOTHING_TAKEN))
                .isEqualTo("ACME.ravi");
    }

    @Test
    void aMissingClientCodeIsRefused() {
        assertThatThrownBy(() -> PortalUsernames.generate(null, "Ravi", NOTHING_TAKEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortalUsernames.generate("  ", "Ravi", NOTHING_TAKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The bound exists so a unique constraint cannot turn into a hang, and it
     * fails loudly rather than inventing {@code ACME.ravi7f3a} — at that point
     * something is wrong with the caller's assumptions and a random suffix
     * would hide it.
     */
    @Test
    @DisplayName("it gives up rather than looping, and rather than inventing a random suffix")
    void itGivesUpLoudly() {
        assertThatThrownBy(() -> PortalUsernames.generate("ACME", "Ravi", candidate -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACME.ravi");
    }
}
