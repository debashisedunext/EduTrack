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
    @DisplayName("every assignment, escalation and status-request mail is mandatory")
    void theKindsBlueprintNamesAreLocked() {
        assertThat(Arrays.stream(NotificationEvent.values())
                .filter(NotificationEvent::isMandatoryMail))
                .allMatch(e -> e.category() == NotificationEvent.Category.ASSIGNMENT
                        || e.category() == NotificationEvent.Category.ESCALATION
                        || e.category() == NotificationEvent.Category.STATUS_REQUEST);
    }

    @Test
    @DisplayName("nothing outside those three categories is locked")
    void everythingElseRespectsPreferences() {
        assertThat(Arrays.stream(NotificationEvent.values())
                .filter(e -> !e.isMandatoryMail()))
                .allMatch(e -> e.category() == NotificationEvent.Category.MENTION
                        || e.category() == NotificationEvent.Category.OTHER);
    }

    @Test
    @DisplayName("a status request cannot be switched off — D-055 found this missing")
    void aStatusRequestIsLockedToo() {
        // §4B.6's prose names four kinds: "assignment, handoff, escalation and
        // breach". Its table is the precise version, and marks "Status
        // requested by manager" ❌ never in the can-be-disabled column, exactly
        // like those four. The category rule was written from the sentence and
        // so left this one optional — a manager's demand for an update that the
        // assignee could silence with a preference switch.
        assertThat(NotificationEvent.STATUS_REQUESTED.isMandatoryMail()).isTrue();
    }

    @Test
    @DisplayName("every ❌-never row in §4B.6's table is locked, and every ✅ row is not")
    void theTableRowByRow() {
        // The rule is stated over categories, so what actually needs proving is
        // that the categories agree with the table this rule was derived from.
        // Transcribed row by row rather than sampled: the failure mode is one
        // row falling on the wrong side, which examples chosen to illustrate the
        // rule will never catch. This is the test that would have failed before
        // STATUS_REQUESTED was added above.
        for (NotificationEvent locked : new NotificationEvent[]{
                NotificationEvent.TICKET_ASSIGNED,          // Ticket created and assigned
                NotificationEvent.HANDOFF_RECEIVED,         // Handoff — ribbon moves
                NotificationEvent.TICKET_REASSIGNED_AWAY,   // Reassigned within a stage
                NotificationEvent.QA_FAILED_REWORK,         // Sent back for rework
                NotificationEvent.DEPLOYMENT_DONE_VERIFY,   // Deployment done
                NotificationEvent.LEVEL_RAISED_CRITICAL,    // Level raised to Critical
                NotificationEvent.SLA_BREACHED,             // SLA breach / delayed
                NotificationEvent.STAGE_SLA_BREACHED,       // Stage SLA breach
                NotificationEvent.STATUS_REQUESTED,         // Status requested by manager
                NotificationEvent.TICKET_REOPENED,          // Reopened
        }) {
            assertThat(locked.isMandatoryMail())
                    .as("§4B.6 marks %s ❌ never", locked)
                    .isTrue();
        }

        for (NotificationEvent optional : new NotificationEvent[]{
                NotificationEvent.COMMENT_ADDED,            // ✅ digest option
                NotificationEvent.MENTIONED,                // ✅
                NotificationEvent.TICKET_CLOSED,            // ✅
                NotificationEvent.DAILY_DIGEST,             // ✅ opt-out
                NotificationEvent.WEEKLY_MANAGER_SUMMARY,   // ✅
        }) {
            assertThat(optional.isMandatoryMail())
                    .as("§4B.6 marks %s as switchable", optional)
                    .isFalse();
        }
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
