package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObCategory;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-112 · turning a claimed {@code IN_APP} row into a bell entry.
 *
 * <p>Most of these are about the entry still saying something true when a value
 * is missing. B-111's paragraph rule does not apply here — a bell entry is two
 * lines and dropping one leaves an entry that under-reports the event — so the
 * fallback path is the one worth proving, in both directions.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObInAppRendererTest {

    private final ObInAppRenderer renderer = new ObInAppRenderer(new ObMailLinks("https://edutrack.example"));

    @Test
    void a_complete_payload_interpolates_both_lines() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Northwind Technologies",
                "step_title", "Data migration",
                "overdue_by", "2 working days")));

        assertThat(content.title()).isEqualTo("Overdue by 2 working days: Data migration");
        assertThat(content.body())
                .isEqualTo("The onboarding for Northwind Technologies is held up until this closes.");
        assertThat(content.category()).isEqualTo(ObCategory.ESCALATION);
    }

    @Test
    @DisplayName("a line that lost a value falls back rather than printing braces")
    void aMissingValueFallsBack() {
        // No overdue_by. The title needs it; the body does not.
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Northwind Technologies",
                "step_title", "Data migration")));

        assertThat(content.title()).isEqualTo("One of your services has passed its TAT");
        assertThat(content.body())
                .isEqualTo("The onboarding for Northwind Technologies is held up until this closes.");
    }

    /**
     * The whole catalogue against an empty payload. D-029's guarantee, stated
     * over every constant rather than the one this test happened to pick.
     */
    @Test
    void no_braces_survive_an_empty_payload() {
        for (ObNotificationEvent event : ObNotificationEvent.all()) {
            ObInAppContent content = renderer.render(staff(event, Map.of()));

            assertThat(content.title()).as("%s title", event).doesNotContain("{{").doesNotContain("}}");
            assertThat(content.body()).as("%s body", event).doesNotContain("{{").doesNotContain("}}");
            assertThat(content.title()).as("%s title", event).isNotBlank();
            assertThat(content.body()).as("%s body", event).isNotBlank();
        }
    }

    /**
     * The value is inserted verbatim. A bell entry is rendered as text by the
     * client, so escaping here would show a client whose name contains an
     * ampersand the entity rather than the character.
     */
    @Test
    void values_are_not_html_escaped() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.GO_LIVE,
                Map.of("client_name", "Smith & Sons")));

        assertThat(content.title()).isEqualTo("Smith & Sons is live");
    }

    /**
     * {@code Matcher.appendReplacement} reads {@code $} and {@code \} as group
     * references. A client name with a currency sign is not rare and would
     * either corrupt the entry or throw.
     */
    @Test
    void a_value_containing_a_dollar_sign_is_inserted_literally() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.GO_LIVE,
                Map.of("client_name", "$50 Labs")));

        assertThat(content.title()).isEqualTo("$50 Labs is live");
    }

    @Test
    void a_number_in_the_payload_is_printed() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.ESCALATION_RAISED, Map.of(
                "client_name", "Contoso",
                "step_title", "User training",
                "escalation_level", 2)));

        assertThat(content.title()).isEqualTo("Escalated to 2: User training");
    }

    @Test
    @DisplayName("an object in the payload is dropped rather than printed as a map")
    void anUnprintableValueIsDropped() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("client_name", Map.of("name", "Contoso"));
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.GO_LIVE, payload));

        assertThat(content.title()).isEqualTo("A client has gone live");
    }

    /**
     * Strict mode would fail the insert and lose the whole notification over a
     * long client name, so the renderer truncates rather than the column.
     */
    @Test
    void an_overlong_title_is_truncated_to_the_column() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.GO_LIVE,
                Map.of("client_name", "N".repeat(400))));

        assertThat(content.title()).hasSize(ObInAppRenderer.TITLE_MAX);
        assertThat(content.title()).endsWith("…");
    }

    @Test
    @DisplayName("an event this build has never heard of is rendered generically, not dropped")
    void anUnknownEventStillProducesAnEntry() {
        ObOutboxMessage message = new ObOutboxMessage(
                101, "SOMETHING_A_NEWER_DEPLOY_QUEUED", ObChannel.IN_APP, new ObRecipient.Staff(5),
                details(), 77L, 12L, 34L, Map.of("client_name", "Contoso"), 0);

        ObInAppContent content = renderer.render(message);

        assertThat(content.title()).isEqualTo("Onboarding update");
        assertThat(content.body()).contains("Contoso");
        // Under All, which is what All is for.
        assertThat(content.category()).isEqualTo(ObCategory.UPDATE);
    }

    // ── the link ────────────────────────────────────────────────────────────

    @Test
    void the_link_is_relative_and_points_at_the_client() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.GO_LIVE,
                Map.of("client_name", "Contoso")));

        assertThat(content.linkUrl()).isEqualTo("/onboarding/clients/77");
    }

    /**
     * <b>The security assertion.</b> {@code action_url} is minted for a mail
     * recipient, and on a sign-off or a password reset it carries a client's
     * one-time token. Following it from a staff bell would hand a member of
     * staff a credential link, and do it silently.
     */
    @Test
    void the_payloads_action_url_is_never_followed() {
        ObInAppContent content = renderer.render(staff(ObNotificationEvent.SIGNOFF_REQUESTED, Map.of(
                "client_name", "Contoso",
                "step_title", "Go-live readiness",
                "action_url", "https://edutrack.example/signoff/one-time-token-abc123")));

        assertThat(content.linkUrl()).isEqualTo("/onboarding/clients/77");
        assertThat(content.linkUrl()).doesNotContain("one-time-token");
    }

    @Test
    void a_row_naming_no_client_has_no_link_rather_than_a_broken_one() {
        ObOutboxMessage message = new ObOutboxMessage(
                101, ObNotificationEvent.GO_LIVE.key(), ObChannel.IN_APP, new ObRecipient.Staff(5),
                details(), null, null, null, Map.of("client_name", "Contoso"), 0);

        assertThat(renderer.render(message).linkUrl()).isNull();
    }

    // ── the categories, as the tabs will read them ──────────────────────────

    @Test
    void the_reminder_and_the_breach_of_one_step_are_different_categories() {
        // The same step and the same owner. One is still actionable and the
        // other is already a failure, and a bell that filed them together could
        // not answer "what is on fire".
        assertThat(ObNotificationEvent.TAT_REMINDER.category()).isEqualTo(ObCategory.REMINDER);
        assertThat(ObNotificationEvent.TAT_BREACHED.category()).isEqualTo(ObCategory.ESCALATION);
    }

    @Test
    void a_prerequisite_submission_is_an_assignment_although_nothing_was_assigned() {
        // Something is now expected of the verifier, which is what the category
        // means. See ObCategory's note on why the split is by why the event
        // exists rather than by who receives it.
        assertThat(ObNotificationEvent.PREREQ_SUBMITTED.category()).isEqualTo(ObCategory.ASSIGNMENT);
    }

    @Test
    void every_event_declares_a_category() {
        assertThat(ObNotificationEvent.all())
                .allSatisfy(event -> assertThat(event.category()).as("%s", event).isNotNull());
        assertThat(ObNotificationEvent.all().stream().map(ObNotificationEvent::category).distinct().toList())
                .containsAll(List.of(ObCategory.ASSIGNMENT, ObCategory.ESCALATION,
                        ObCategory.REMINDER, ObCategory.UPDATE));
    }

    private static ObOutboxMessage staff(ObNotificationEvent event, Map<String, Object> payload) {
        return new ObOutboxMessage(
                101, event.key(), ObChannel.IN_APP, new ObRecipient.Staff(5),
                details(), 77L, 12L, 34L, payload, 0);
    }

    private static ObOutboxMessage.RecipientDetails details() {
        return new ObOutboxMessage.RecipientDetails(
                "Meera Iyer", "meera@edunext.test", null, false, true);
    }
}
