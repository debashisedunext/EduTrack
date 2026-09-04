package com.edunext.edutrack.api.feature.onboarding.notifications;

import com.edunext.edutrack.domain.onboarding.outbox.ObCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-112 · OB-13's tab parsing, which is the only place a query string decides
 * what somebody sees.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObNotificationTabTest {

    @Test
    void no_tab_is_all() {
        assertThat(ObNotificationTab.fromQuery(null)).contains(ObNotificationTab.ALL);
        assertThat(ObNotificationTab.fromQuery("")).contains(ObNotificationTab.ALL);
        assertThat(ObNotificationTab.fromQuery("   ")).contains(ObNotificationTab.ALL);
    }

    @Test
    void the_query_value_is_case_insensitive_and_trimmed() {
        assertThat(ObNotificationTab.fromQuery(" Escalations "))
                .contains(ObNotificationTab.ESCALATIONS);
    }

    /**
     * <b>The one that matters.</b> Falling through to All would show a caller
     * who mistyped {@code reminder} everything, and look like it worked.
     */
    @Test
    @DisplayName("an unrecognised tab is empty, not a silent fall-through to all")
    void anUnknownTabIsRejected() {
        assertThat(ObNotificationTab.fromQuery("reminder")).isEmpty();
        assertThat(ObNotificationTab.fromQuery("updates")).isEmpty();
        assertThat(ObNotificationTab.fromQuery("mentions")).isEmpty();
    }

    /**
     * S-26's vocabulary must not work here. Somebody copying a URL from the
     * ticketing centre should be told, not quietly shown a different list.
     */
    @Test
    void the_ticketing_centres_tabs_are_not_these_tabs() {
        assertThat(ObNotificationTab.fromQuery("status-requests")).isEmpty();
    }

    @Test
    void all_filters_on_nothing_rather_than_on_every_category_we_know() {
        // The difference is what lets an entry stamped by a newer deploy, with a
        // category this build has never heard of, still appear under All rather
        // than vanish from every tab at once.
        assertThat(ObNotificationTab.ALL.categoryCodes()).isEmpty();
    }

    @Test
    void each_tab_names_its_own_category() {
        assertThat(ObNotificationTab.ASSIGNMENTS.categoryCodes())
                .containsExactly(ObCategory.ASSIGNMENT.name());
        assertThat(ObNotificationTab.ESCALATIONS.categoryCodes())
                .containsExactly(ObCategory.ESCALATION.name());
        assertThat(ObNotificationTab.REMINDERS.categoryCodes())
                .containsExactly(ObCategory.REMINDER.name());
    }

    /**
     * {@link ObCategory#UPDATE} deliberately has no tab. Asserted so that adding
     * one becomes a decision about the screen rather than something that happens
     * because a category was added to the enum.
     */
    @Test
    void update_has_no_tab_of_its_own() {
        assertThat(java.util.Arrays.stream(ObNotificationTab.values())
                .flatMap(tab -> tab.categoryCodes().stream())
                .toList())
                .doesNotContain(ObCategory.UPDATE.name());
    }
}
