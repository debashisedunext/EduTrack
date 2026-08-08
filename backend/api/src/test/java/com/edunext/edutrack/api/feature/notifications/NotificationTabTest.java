package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.domain.notifications.NotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-041 · the tab filter, and the vocabulary it filters on.
 *
 * <p>These are the assertions that stop S-26 quietly losing a category. The
 * expensive failure is not a wrong SQL clause — it is an event that belongs in
 * Escalations turning up only under All, which looks like nothing at all went
 * wrong.
 */
class NotificationTabTest {

    @Test
    void everyTabIsReachableByItsQueryValue() {
        assertThat(NotificationTab.fromQuery("all")).contains(NotificationTab.ALL);
        assertThat(NotificationTab.fromQuery("mentions")).contains(NotificationTab.MENTIONS);
        assertThat(NotificationTab.fromQuery("assignments")).contains(NotificationTab.ASSIGNMENTS);
        assertThat(NotificationTab.fromQuery("escalations")).contains(NotificationTab.ESCALATIONS);
        assertThat(NotificationTab.fromQuery("status-requests")).contains(NotificationTab.STATUS_REQUESTS);
    }

    @Test
    @DisplayName("the hyphenated tab is why this is not valueOf")
    void statusRequestsCannotBeParsedByValueOf() {
        // `status-requests` is not a Java identifier, so an enum whose name was
        // the query value could never have existed.
        assertThat(NotificationTab.STATUS_REQUESTS.name()).isEqualTo("STATUS_REQUESTS");
        assertThat(NotificationTab.fromQuery("STATUS_REQUESTS"))
                .as("the wire value is the hyphenated one, not the enum name")
                .isEmpty();
    }

    @Test
    void anAbsentTabMeansAll() {
        assertThat(NotificationTab.fromQuery(null)).contains(NotificationTab.ALL);
        assertThat(NotificationTab.fromQuery("")).contains(NotificationTab.ALL);
        assertThat(NotificationTab.fromQuery("  ")).contains(NotificationTab.ALL);
    }

    @Test
    @DisplayName("an unknown tab is rejected, not quietly treated as all")
    void anUnknownTabIsEmpty() {
        // Falling back to All would show somebody who typed `mention` every
        // notification they have, and look like it worked.
        assertThat(NotificationTab.fromQuery("mention")).isEmpty();
        assertThat(NotificationTab.fromQuery("everything")).isEmpty();
    }

    @Test
    @DisplayName("All filters on nothing, rather than on every code we happen to know")
    void allCarriesNoEventFilter() {
        // The difference is invisible today and decisive at the next deploy: a
        // row whose code this build has never heard of must still show in All.
        assertThat(NotificationTab.ALL.eventCodes()).isEmpty();
    }

    @Test
    void mentionsAdmitsExactlyTheMentionEvent() {
        assertThat(NotificationTab.MENTIONS.eventCodes())
                .containsExactly(NotificationEvent.MENTIONED.name());
    }

    @Test
    void escalationsCarryTheEventsThatMeanSomethingWentWrong() {
        assertThat(NotificationTab.ESCALATIONS.eventCodes())
                .contains("SLA_BREACHED", "STAGE_SLA_BREACHED", "LEVEL_RAISED_CRITICAL")
                .doesNotContain("TICKET_ASSIGNED", "MENTIONED");
    }

    @Test
    void assignmentsCarryTheEventsThatChangeWhoIsResponsible() {
        assertThat(NotificationTab.ASSIGNMENTS.eventCodes())
                .contains("TICKET_ASSIGNED", "HANDOFF_RECEIVED", "QA_FAILED_REWORK")
                .doesNotContain("SLA_BREACHED");
    }

    @Test
    @DisplayName("no event appears under two tabs")
    void tabsDoNotOverlap() {
        // An event in two tabs is counted twice by anybody who sums them, and
        // means the categories are not a partition after all.
        List<String> seen = new ArrayList<>();
        for (NotificationTab tab : NotificationTab.values()) {
            seen.addAll(tab.eventCodes());
        }
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every event has a category, so none can be forgotten")
    void everyEventIsCategorised() {
        assertThat(NotificationEvent.values())
                .allSatisfy(event -> assertThat(event.category()).isNotNull());
    }

    @Test
    @DisplayName("every event name fits notifications.event_code")
    void eventNamesFitTheColumn() {
        // VARCHAR(60) in the A-007 baseline. A longer name is a truncated
        // insert under a non-strict mode, and a row that matches no tab.
        assertThat(NotificationEvent.values())
                .allSatisfy(event -> assertThat(event.name()).hasSizeLessThanOrEqualTo(60));
    }

    @Test
    @DisplayName("an unknown code resolves to empty rather than throwing")
    void unknownCodesAreTolerated() {
        // Strict on write, tolerant on read: a row left by an older deploy must
        // render in the bell. Losing the notification is worse than losing its
        // tab.
        assertThat(NotificationEvent.of("SOMETHING_WE_RETIRED")).isEmpty();
        assertThat(NotificationEvent.of(null)).isEmpty();
        assertThat(NotificationEvent.of("")).isEmpty();
        assertThat(NotificationEvent.of("MENTIONED")).contains(NotificationEvent.MENTIONED);
    }
}
