package com.edunext.edutrack.domain.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-036 · "critical mails cannot be disabled" (blueprint §4B.6).
 *
 * <p>Stated over the whole enum rather than over a handful of examples,
 * because the risk this rule guards against is a <em>new</em> event being
 * added to the wrong side of it. A test naming four codes would keep passing
 * while the fifth escalation quietly became optional.
 */
class MandatoryMailTest {

    @Test
    @DisplayName("every assignment and escalation mail is mandatory")
    void theFourKindsBlueprintNamesAreLocked() {
        assertThat(Arrays.stream(NotificationEvent.values())
                .filter(NotificationEvent::isMandatoryMail))
                .allMatch(e -> e.category() == NotificationEvent.Category.ASSIGNMENT
                        || e.category() == NotificationEvent.Category.ESCALATION);
    }

    @Test
    @DisplayName("nothing outside those two categories is locked")
    void everythingElseRespectsPreferences() {
        assertThat(Arrays.stream(NotificationEvent.values())
                .filter(e -> !e.isMandatoryMail()))
                .allMatch(e -> e.category() == NotificationEvent.Category.MENTION
                        || e.category() == NotificationEvent.Category.STATUS_REQUEST
                        || e.category() == NotificationEvent.Category.OTHER);
    }

    @Test
    @DisplayName("the events §4B.6 names by hand are covered")
    void theBlueprintsOwnExamples() {
        // Assignment, handoff, escalation and breach — the four the sentence
        // actually lists, pinned so a category reshuffle cannot quietly drop
        // one of them out of the rule.
        assertThat(NotificationEvent.TICKET_ASSIGNED.isMandatoryMail()).isTrue();
        assertThat(NotificationEvent.HANDOFF_RECEIVED.isMandatoryMail()).isTrue();
        assertThat(NotificationEvent.SLA_BREACHED.isMandatoryMail()).isTrue();
        assertThat(NotificationEvent.STAGE_SLA_BREACHED.isMandatoryMail()).isTrue();
        assertThat(NotificationEvent.LEVEL_RAISED_CRITICAL.isMandatoryMail()).isTrue();
    }

    @Test
    @DisplayName("a digest and a comment are opt-out, per §7.7")
    void theQuietOnesAreNot() {
        assertThat(NotificationEvent.DAILY_DIGEST.isMandatoryMail()).isFalse();
        assertThat(NotificationEvent.COMMENT_ADDED.isMandatoryMail()).isFalse();
        assertThat(NotificationEvent.MENTIONED.isMandatoryMail()).isFalse();
    }

    @Test
    @DisplayName("the lock is on mail, not on the in-app toast")
    void inAppStaysAPreference() {
        // §7.7 gives the guarantee to mail — "the guaranteed channel" — because
        // a toast only reaches somebody already logged in. Locking in-app too
        // would remove a real preference to protect a channel that was never
        // the guarantee.
        assertThat(NotificationChannel.values()).contains(NotificationChannel.IN_APP);
        assertThat(NotificationEvent.SLA_BREACHED.isMandatoryMail()).isTrue();
    }
}
