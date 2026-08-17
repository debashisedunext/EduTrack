package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.notifications.NotificationTemplate;
import com.edunext.edutrack.domain.notifications.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-029 · rendering a queued row into a mail.
 *
 * <p>The assertion that matters most is the escaping one. Ticket titles, client
 * names and handoff comments are user text, and they land in an HTML body that
 * is sent to clients — so a title containing markup must arrive as characters,
 * not as markup. Everything else here is about not losing the mail.
 */
class MailRendererTest {

    private final NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
    private final MailContextRepository context = mock(MailContextRepository.class);
    private SpringTemplateEngine thymeleaf;
    private MailRenderer renderer;

    @BeforeEach
    void setUp() {
        // The real layout off the classpath, not a stub. Half of what this
        // class does is hand markup to Thymeleaf, and a fake engine would prove
        // only that we called something.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        thymeleaf = new SpringTemplateEngine();
        thymeleaf.setTemplateResolver(resolver);
        renderer = new MailRenderer(templates, context, thymeleaf);
    }

    // ─────────────────────────────────────────────── the one that must not fail

    @Test
    @DisplayName("a ticket title containing markup arrives as text, not as markup")
    void bodyValuesAreEscaped() {
        givenTemplate("Ticket {{ticket_title}} needs you");
        givenContext(MailContext.builder()
                .put(MergeTag.TICKET_TITLE, "<img src=x onerror=alert(1)>")
                .build());

        String html = renderer.render(ticketMail()).html();

        // The tag must not survive as a tag. If this ever fails, every ticket
        // title in the system is a stored-XSS vector aimed at whoever the mail
        // is addressed to — including client contacts outside the org.
        assertThat(html).doesNotContain("<img src=x");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    @DisplayName("the ampersand is escaped once, not twice")
    void escapingIsNotDoubled() {
        givenTemplate("{{ticket_title}}");
        givenContext(MailContext.builder().put(MergeTag.TICKET_TITLE, "A&B <import>").build());

        String html = renderer.render(ticketMail()).html();

        // Replacing < before & would produce &amp;lt; and print the entity to
        // the reader. Ordering is the whole of that bug.
        assertThat(html).contains("A&amp;B &lt;import&gt;");
        assertThat(html).doesNotContain("&amp;lt;");
    }

    @Test
    @DisplayName("the subject is not HTML-escaped")
    void subjectIsNotEscaped() {
        NotificationTemplate template = givenTemplate("body");
        template.setSubjectTemplate("[{{ticket_id}}] {{ticket_title}}");
        givenContext(MailContext.builder()
                .put(MergeTag.TICKET_ID, "CRM-26-00347")
                .put(MergeTag.TICKET_TITLE, "Fix A&B import")
                .build());

        // Escaping here would put a literal &amp; into the subject line of
        // every mail, in every client.
        assertThat(renderer.render(ticketMail()).subject())
                .isEqualTo("[CRM-26-00347] Fix A&B import");
    }

    // ───────────────────────────────────────────────────────── substitution

    @Test
    @DisplayName("the five §4B.6 tags all resolve")
    void theBlueprintTagsResolve() {
        givenTemplate("{{ticket_id}} / {{assignee}} / {{stage}} / {{client}} / {{planned_close}}");
        givenContext(MailContext.builder()
                .put(MergeTag.TICKET_ID, "CRM-26-00347")
                .put(MergeTag.ASSIGNEE, "Ravi Kumar")
                .put(MergeTag.STAGE, "QA")
                .put(MergeTag.CLIENT, "Acme Ltd")
                .put(MergeTag.PLANNED_CLOSE, "22 Aug 2026")
                .build());

        assertThat(renderer.render(ticketMail()).html())
                .contains("CRM-26-00347 / Ravi Kumar / QA / Acme Ltd / 22 Aug 2026");
    }

    @Test
    @DisplayName("whitespace inside the braces is tolerated, as the master's validator tolerates it")
    void innerWhitespaceIsTolerated() {
        givenTemplate("{{ ticket_id }}");
        givenContext(MailContext.builder().put(MergeTag.TICKET_ID, "CRM-26-00347").build());

        // Refusing this at render while accepting it at save is the drift
        // MergeTag's javadoc exists to prevent.
        assertThat(renderer.render(ticketMail()).html()).contains("CRM-26-00347");
    }

    @Test
    @DisplayName("a tag with no value renders as nothing, never as its own braces")
    void anAbsentValueRendersEmpty() {
        givenTemplate("Client: {{client}}.");
        givenContext(MailContext.empty());

        String html = renderer.render(ticketMail()).html();

        // An internal ticket has no client. Printing {{client}} would put the
        // placeholder in front of whoever gets the mail — the exact failure the
        // master's save-time validation exists to prevent, arriving instead at
        // send time where no Admin can see it.
        assertThat(html).contains("Client: .");
        assertThat(html).doesNotContain("{{client}}");
    }

    @Test
    @DisplayName("a value containing $ or a backslash is inserted literally")
    void regexSpecialCharactersSurvive() {
        givenTemplate("{{ticket_title}}");
        givenContext(MailContext.builder().put(MergeTag.TICKET_TITLE, "Raise $5 charge \\ retry").build());

        // Matcher.appendReplacement reads $ as a group reference and throws or
        // corrupts without quoteReplacement. Currency in a ticket title is
        // routine, not exotic.
        assertThat(renderer.render(ticketMail()).html()).contains("Raise $5 charge \\ retry");
    }

    @Test
    @DisplayName("an unknown tag is left visible rather than silently blanked")
    void anUnknownTagIsLeftAlone() {
        givenTemplate("Hello {{ticketId}}");
        givenContext(MailContext.empty());

        // B-022 refuses to save this, so a row containing it was written around
        // the screen. Blanking it hides the mistake; leaving it means the first
        // person to read the mail can report it.
        assertThat(renderer.render(ticketMail()).html()).contains("{{ticketId}}");
    }

    // ─────────────────────────────────────────────────── never lose the mail

    @Test
    @DisplayName("no template still sends, with the subject as the body")
    void noTemplateStillSends() {
        when(templates.findByEventCodeAndChannel(anyString(), anyString())).thenReturn(Optional.empty());
        givenContext(MailContext.empty());

        MailContent content = renderer.render(ticketMail());

        // §7.7 calls mail the guaranteed channel. "Guaranteed unless somebody
        // deleted a template" is not a guarantee.
        assertThat(content.subject()).isEqualTo("[CRM-26-00347] breached its SLA");
        assertThat(content.html()).contains("breached its SLA");
    }

    @Test
    @DisplayName("an inactive template is treated as absent, because deactivating is the only off switch")
    void anInactiveTemplateIsNotUsed() {
        NotificationTemplate template = template("SHOULD NOT APPEAR");
        template.setActive(false);
        when(templates.findByEventCodeAndChannel(anyString(), anyString())).thenReturn(Optional.of(template));
        givenContext(MailContext.empty());

        MailContent content = renderer.render(ticketMail());

        // There is no DELETE on the S-15 controller, so honouring is_active is
        // the entire feature.
        assertThat(content.html()).doesNotContain("SHOULD NOT APPEAR");
        assertThat(content.html()).contains("breached its SLA");
    }

    @Test
    @DisplayName("mail with no ticket costs no context read")
    void nonTicketMailReadsNoTicket() {
        givenTemplate("{{org}} digest");

        renderer.render(new OutboxMessage(1L, null, "DAILY_DIGEST", null, 7L,
                "ravi@edunext.test", "Your daily digest", 0));

        verify(context, never()).forTicket(anyLong());
    }

    @Test
    @DisplayName("an explicit templateId wins over the event lookup")
    void anExplicitTemplateIdIsHonoured() {
        when(templates.findById(99L)).thenReturn(Optional.of(template("chosen by the caller")));
        givenContext(MailContext.empty());

        MailContent content = renderer.render(new OutboxMessage(1L, 5L, "SLA_BREACHED", 99L, 7L,
                "ravi@edunext.test", "[CRM-26-00347] breached its SLA", 0));

        assertThat(content.html()).contains("chosen by the caller");
        // A caller naming a template is saying "this specific wording"; the
        // event lookup must not second-guess it.
        verify(templates, never()).findByEventCodeAndChannel(anyString(), anyString());
    }

    // ────────────────────────────────────────────────────────────── the layout

    @Test
    @DisplayName("the layout renders the Open ticket button only when there is a ticket to open")
    void theButtonNeedsATicket() {
        givenTemplate("body");
        givenContext(MailContext.builder()
                .put(MergeTag.TICKET_URL, "https://edutrack.test/tickets/CRM-26-00347")
                .build());
        assertThat(renderer.render(ticketMail()).html())
                .contains("Open ticket")
                .contains("https://edutrack.test/tickets/CRM-26-00347");

        givenContext(MailContext.empty());
        // A digest has no single ticket, and a button pointing at /tickets/ is
        // worse than no button.
        assertThat(renderer.render(ticketMail()).html()).doesNotContain("Open ticket");
    }

    // ───────────────────────────────────────────────────────────────── helpers

    private NotificationTemplate givenTemplate(String body) {
        NotificationTemplate template = template(body);
        when(templates.findByEventCodeAndChannel(anyString(), anyString())).thenReturn(Optional.of(template));
        return template;
    }

    private static NotificationTemplate template(String body) {
        NotificationTemplate template = new NotificationTemplate();
        template.setEventCode("SLA_BREACHED");
        template.setChannel("EMAIL");
        template.setBodyTemplate(body);
        template.setActive(true);
        return template;
    }

    private void givenContext(MailContext values) {
        when(context.forTicket(anyLong())).thenReturn(values);
    }

    private static OutboxMessage ticketMail() {
        return new OutboxMessage(1L, 5L, "SLA_BREACHED", null, 7L,
                "ravi@edunext.test", "[CRM-26-00347] breached its SLA", 0);
    }
}
