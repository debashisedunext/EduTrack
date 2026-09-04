package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-111 · rendering a claimed row into an onboarding mail.
 *
 * <p>The assertion that matters most is the escaping one. Client names,
 * escalation comments and objection reasons are user text — some of it typed by
 * a client into the portal — and they land in an HTML body that is mailed to
 * clients and to managers. Everything else here is about the mail still saying
 * something true when a value is missing.
 *
 * <p>The real layout is used off the classpath rather than a stub. Half of what
 * the renderer does is hand markup to Thymeleaf, and a fake engine would prove
 * only that something was called.
 */
class ObMailRendererTest {

    private static final String BASE = "https://edutrack.example";

    private ObMailRenderer renderer;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine thymeleaf = new SpringTemplateEngine();
        thymeleaf.setTemplateResolver(resolver);
        renderer = new ObMailRenderer(new ObMailLinks(BASE), thymeleaf);
    }

    // ─────────────────────────────────────────────── the one that must not fail

    @Test
    @DisplayName("a client name containing markup arrives as text, not as markup")
    void bodyValuesAreEscaped() {
        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "<img src=x onerror=alert(1)>",
                "step_title", "Data migration",
                "overdue_by", "2 working days")));

        assertThat(content.html()).doesNotContain("<img src=x");
        // Asserted with the prose around it, not on its own. The layout prints
        // the same value in its facts table through th:text, which escapes on
        // output — so an assertion that only looks for the escaped form passes
        // even when the substitution into the body escapes nothing at all.
        assertThat(content.html())
                .contains("on &lt;img src=x onerror=alert(1)&gt;'s onboarding");
    }

    @Test
    @DisplayName("a client's own escalation comment cannot become markup in a manager's inbox")
    void clientSuppliedTextIsEscaped() {
        // The comment is typed by a client on CP-03 and read by a manager. It is
        // the one value in the module that crosses from an untrusted keyboard
        // into a staff mailbox.
        ObMailContent content = renderer.render(staff(ObNotificationEvent.CLIENT_ESCALATION_RAISED, Map.of(
                "client_name", "Acme Ltd",
                "escalation_comment", "<script>alert('x')</script> nothing is moving")));

        assertThat(content.html()).doesNotContain("<script>");
        assertThat(content.html()).contains("<p>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
    }

    @Test
    @DisplayName("escaping is not doubled")
    void escapingIsNotDoubled() {
        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Marks & Spencer",
                "step_title", "UAT & issue closure",
                "overdue_by", "1 working day")));

        assertThat(content.html())
                .contains("on Marks &amp; Spencer's onboarding")
                .doesNotContain("&amp;amp;");
    }

    @Test
    @DisplayName("the subject is not escaped")
    void subjectIsNotEscaped() {
        // "Overdue by 1 working day: UAT &amp; issue closure" in every mail
        // client on earth is what escaping a subject buys.
        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Marks & Spencer",
                "step_title", "UAT & issue closure",
                "overdue_by", "1 working day")));

        assertThat(content.subject()).contains("UAT & issue closure").doesNotContain("&amp;");
    }

    // ────────────────────────────────────────────────── never braces, never gaps

    @Test
    @DisplayName("no placeholder ever reaches the reader")
    void noBracesSurvive() {
        // Every template, rendered against a payload holding nothing at all. The
        // output may be short; it may not contain a placeholder.
        for (ObMailTemplate template : ObMailTemplate.values()) {
            ObOutboxMessage message = template.audience() == ObMailAudience.CLIENT
                    ? client(template.event(), Map.of())
                    : staff(template.event(), Map.of());
            ObMailContent content = renderer.render(message);

            assertThat(content.subject()).as("%s subject", template).doesNotContain("{{");
            assertThat(content.html()).as("%s body", template).doesNotContain("{{");
        }
    }

    @Test
    @DisplayName("a subject that cannot be filled in falls back to the static line")
    void theSubjectFallsBackRatherThanHanging() {
        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Acme Ltd",
                "step_title", "Data migration")));   // no overdue_by

        assertThat(content.subject()).isEqualTo(ObMailTemplate.TAT_BREACHED.fallbackSubject());
        assertThat(content.subject()).doesNotContain("Overdue by :");
    }

    @Test
    @DisplayName("a subject with every value renders the interpolated line")
    void theSubjectInterpolatesWhenItCan() {
        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Acme Ltd",
                "step_title", "Data migration",
                "overdue_by", "2 working days")));

        assertThat(content.subject()).isEqualTo("Overdue by 2 working days: Data migration — Acme Ltd");
    }

    @Test
    @DisplayName("a paragraph that lost a value is dropped whole, not printed with a hole")
    void aParagraphThatLostAValueIsDropped() {
        ObMailContent withReason = renderer.render(client(ObNotificationEvent.PREREQ_RETURNED, Map.of(
                "client_name", "Acme Ltd",
                "prereq_title", "Signed agreement",
                "action_url", BASE + "/portal/onboarding",
                "return_reason", "The second page is unsigned.")));
        ObMailContent without = renderer.render(client(ObNotificationEvent.PREREQ_RETURNED, Map.of(
                "client_name", "Acme Ltd",
                "prereq_title", "Signed agreement",
                "action_url", BASE + "/portal/onboarding")));

        assertThat(withReason.html()).contains("The second page is unsigned.");
        // The sentence goes; the rest of the mail stays, including the one that
        // says what to do next.
        assertThat(without.html()).doesNotContain("<p></p>");
        assertThat(without.html()).contains("Signed agreement");
        assertThat(without.html()).contains("submit it again");
    }

    @Test
    @DisplayName("a payload value of the wrong type still prints")
    void nonStringPayloadValuesAreStringified() {
        // payload is JSON an enqueuer wrote: a level arrives as a number and a
        // count as an integer, and neither is a String on the way out of Jackson.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", "Acme Ltd");
        payload.put("step_title", "Data migration");
        payload.put("escalation_level", 2);

        ObMailContent content = renderer.render(staff(ObNotificationEvent.ESCALATION_RAISED, payload));

        assertThat(content.subject()).isEqualTo("Escalated to 2: Data migration — Acme Ltd");
    }

    @Test
    @DisplayName("a nested payload value is dropped rather than printed as a map")
    void structuredPayloadValuesAreNotPrinted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", "Acme Ltd");
        payload.put("step_title", Map.of("id", 3, "title", "Data migration"));
        payload.put("overdue_by", "2 working days");

        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, payload));

        assertThat(content.html()).doesNotContain("id=3").doesNotContain("{id=");
        assertThat(content.subject()).isEqualTo(ObMailTemplate.TAT_BREACHED.fallbackSubject());
    }

    // ────────────────────────────────────────────────────────── the two audiences

    @Test
    @DisplayName("one event, two readers, two mails")
    void audienceDecidesTheWording() {
        Map<String, Object> payload = Map.of("client_name", "Acme Ltd", "action_url", "");

        ObMailContent toClient = renderer.render(client(ObNotificationEvent.GATE_OPENED, payload));
        ObMailContent toStaff = renderer.render(staff(ObNotificationEvent.GATE_OPENED, payload));

        assertThat(toClient.subject()).isEqualTo("Your onboarding has started — Acme Ltd");
        assertThat(toStaff.subject()).isEqualTo("Prerequisites cleared — Acme Ltd is underway");
    }

    @Test
    @DisplayName("a client is never told who owns the step")
    void clientsDoNotSeeOwners() {
        // CP-03 hides owner names from a client. A staff-facing enqueuer reusing
        // one payload for both recipients must not be able to leak it by mail.
        Map<String, Object> payload = Map.of(
                "client_name", "Acme Ltd",
                "prereq_title", "Signed agreement",
                "owner_name", "Ravi Kumar");

        ObMailContent toClient = renderer.render(client(ObNotificationEvent.PREREQ_VERIFIED, payload));
        ObMailContent toStaff = renderer.render(staff(ObNotificationEvent.PREREQ_SUBMITTED, payload));

        assertThat(toClient.html()).doesNotContain("Ravi Kumar");
        assertThat(toStaff.html()).contains("Ravi Kumar");
    }

    @Test
    @DisplayName("the footer says why this mail arrived, and it differs by reader")
    void theFooterFollowsTheAudience() {
        ObMailContent toClient = renderer.render(client(ObNotificationEvent.PREREQ_VERIFIED,
                Map.of("client_name", "Acme Ltd", "prereq_title", "Signed agreement")));
        ObMailContent toStaff = renderer.render(staff(ObNotificationEvent.PREREQ_SUBMITTED,
                Map.of("client_name", "Acme Ltd", "prereq_title", "Signed agreement")));

        assertThat(toClient.html()).contains("named contact for this");
        assertThat(toClient.html()).doesNotContain("own or manage part of this onboarding");
        assertThat(toStaff.html()).contains("own or manage part of this onboarding");
    }

    @Test
    @DisplayName("staff open the client, clients open the portal")
    void theButtonGoesWhereTheReaderCanGo() {
        ObMailContent toStaff = renderer.render(staff(ObNotificationEvent.PREREQ_SUBMITTED,
                Map.of("client_name", "Acme Ltd", "prereq_title", "Signed agreement")));
        ObMailContent toClient = renderer.render(client(ObNotificationEvent.PREREQ_VERIFIED,
                Map.of("client_name", "Acme Ltd", "prereq_title", "Signed agreement")));

        assertThat(toStaff.html()).contains(BASE + "/onboarding/clients/77");
        assertThat(toClient.html()).contains(BASE + "/portal/onboarding");
        // The staff URL must not be mailed to a client contact — a link they
        // cannot use is an invitation to try it.
        assertThat(toClient.html()).doesNotContain("/onboarding/clients/");
    }

    @Test
    @DisplayName("a token-bearing link in the payload wins over every convention")
    void anExplicitLinkWins() {
        String signoffUrl = "https://edutrack.example/signoff/a1b2c3";
        ObMailContent content = renderer.render(client(ObNotificationEvent.SIGNOFF_REQUESTED, Map.of(
                "client_name", "Acme Ltd",
                "step_title", "Go-live sign-off",
                "action_url", signoffUrl)));

        assertThat(content.html()).contains(signoffUrl);
        assertThat(content.html()).doesNotContain("/portal/onboarding");
    }

    @Test
    @DisplayName("the sign-off code mail carries no button at all")
    void theOtpMailHasNoButton() {
        ObMailContent content = renderer.render(client(ObNotificationEvent.SIGNOFF_OTP, Map.of(
                "client_name", "Acme Ltd",
                "otp_code", "418293")));

        assertThat(content.html()).contains("418293");
        assertThat(content.html()).doesNotContain("<a ");
    }

    // ────────────────────────────────────────────────── the payload is only data

    @Test
    @DisplayName("a payload cannot relabel the button or change the chip")
    void reservedNamesAreNotThePayloadsToSet() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", "Acme Ltd");
        payload.put("step_title", "Data migration");
        payload.put("overdue_by", "2 working days");
        payload.put("action_label", "Claim your prize");
        payload.put("chip_label", "ROUTINE");
        payload.put("body", "<script>alert(1)</script>");

        ObMailContent content = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, payload));

        assertThat(content.html()).contains("Open the step").doesNotContain("Claim your prize");
        assertThat(content.html()).contains("Overdue").doesNotContain("ROUTINE");
        assertThat(content.html()).doesNotContain("<script>");
    }

    @Test
    @DisplayName("the chip is on the mail that is late and off the mail that is not")
    void theChipFollowsTheTemplate() {
        ObMailContent breach = renderer.render(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Acme Ltd", "step_title", "Data migration",
                "overdue_by", "2 working days")));
        ObMailContent routine = renderer.render(client(ObNotificationEvent.PREREQ_VERIFIED, Map.of(
                "client_name", "Acme Ltd", "prereq_title", "Signed agreement")));

        // §12.1's Critical pair, soft background and solid text.
        assertThat(breach.html()).contains("#FEF2F2").contains("#B91C1C");
        assertThat(routine.html()).doesNotContain("#FEF2F2");
    }

    @Test
    @DisplayName("absent facts leave no empty rows")
    void absentFactsLeaveNoRows() {
        ObMailContent content = renderer.render(client(ObNotificationEvent.PREREQ_VERIFIED, Map.of(
                "client_name", "Acme Ltd", "prereq_title", "Signed agreement")));

        assertThat(content.html()).contains("Task").contains("Signed agreement");
        // "Overdue by" with nothing after it reads as a bug in the product.
        assertThat(content.html()).doesNotContain("Overdue by").doesNotContain("Escalated to");
        assertThat(content.html()).doesNotContain("Owner");
    }

    // ───────────────────────────────────────────────────── the catalogue's gaps

    @Test
    @DisplayName("an event with no wording still sends, and staff are told which one")
    void anUnknownEventStillSends() {
        ObOutboxMessage message = new ObOutboxMessage(
                901, "SOMETHING_ADDED_LATER", ObChannel.EMAIL, new ObRecipient.Staff(5),
                new ObOutboxMessage.RecipientDetails("Ravi Kumar", "ravi@edunext.test", null, false, true),
                77L, 12L, 34L, Map.of("client_name", "Acme Ltd"), 0);

        ObMailContent content = renderer.render(message);

        assertThat(content.subject()).isEqualTo("EduTrack onboarding update — Acme Ltd");
        assertThat(content.html()).contains("SOMETHING_ADDED_LATER");
        assertThat(content.html()).contains(BASE + "/onboarding/clients/77");
    }

    @Test
    @DisplayName("a client is never shown an internal event code")
    void aClientNeverSeesTheEventKey() {
        ObOutboxMessage message = new ObOutboxMessage(
                902, "SOMETHING_ADDED_LATER", ObChannel.EMAIL, new ObRecipient.Client(9),
                new ObOutboxMessage.RecipientDetails("Priya Nair", "priya@acme.test", null, true, true),
                77L, 12L, null, Map.of("client_name", "Acme Ltd"), 0);

        ObMailContent content = renderer.render(message);

        assertThat(content.subject()).isEqualTo("An update on your onboarding");
        assertThat(content.html()).doesNotContain("SOMETHING_ADDED_LATER");
        assertThat(content.html()).contains(BASE + "/portal/onboarding");
    }

    @Test
    @DisplayName("an event with wording for the other audience only falls back rather than misfiring")
    void theWrongAudienceFallsBack() {
        // TAT_BREACHED is written for the step's owner. If something ever queues
        // it to a client contact, the generic client notice is what goes — not a
        // mail telling a client their own step is overdue in our words.
        ObMailContent content = renderer.render(client(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Acme Ltd", "step_title", "Data migration",
                "overdue_by", "2 working days")));

        assertThat(content.subject()).isEqualTo("An update on your onboarding");
        assertThat(content.html()).doesNotContain("Data migration is");
    }

    @Test
    @DisplayName("the reader is greeted by name when the row knows it")
    void theSalutationUsesTheRecipientName() {
        ObMailContent content = renderer.render(client(ObNotificationEvent.PREREQ_VERIFIED, Map.of(
                "client_name", "Acme Ltd", "prereq_title", "Signed agreement")));

        assertThat(content.html()).contains("Hello").contains("Priya Nair");
    }

    // ───────────────────────────────────────────────────────────────── fixtures

    private static ObOutboxMessage staff(ObNotificationEvent event, Map<String, Object> payload) {
        return new ObOutboxMessage(
                101, event.key(), ObChannel.EMAIL, new ObRecipient.Staff(5),
                new ObOutboxMessage.RecipientDetails("Meera Iyer", "meera@edunext.test", null, false, true),
                77L, 12L, 34L, payload, 0);
    }

    private static ObOutboxMessage client(ObNotificationEvent event, Map<String, Object> payload) {
        return new ObOutboxMessage(
                102, event.key(), ObChannel.EMAIL, new ObRecipient.Client(9),
                new ObOutboxMessage.RecipientDetails("Priya Nair", "priya@acme.test", "+91…", true, true),
                77L, 12L, 34L, payload, 0);
    }
}
