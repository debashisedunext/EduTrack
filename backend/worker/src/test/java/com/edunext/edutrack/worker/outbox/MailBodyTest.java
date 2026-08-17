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
import static org.mockito.Mockito.when;

/**
 * D-030 · what a §4B.6 mail body actually contains.
 *
 * <p>The blueprint lists the contents — "the level chip, project, client,
 * current stage, planned close date, who acted and what they said, a primary
 * Open ticket button, and a reply hint". This asserts each one appears when
 * there is something to show, and — the half that is easy to skip —
 * <em>disappears</em> when there is not. A row reading "Project:" with nothing
 * after it reads as a bug in the product rather than an absent field, and §4B.6
 * mail spans fifteen events with very different amounts of context.
 */
class MailBodyTest {

    private final NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
    private final MailContextRepository context = mock(MailContextRepository.class);
    private MailRenderer renderer;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        NotificationTemplate template = new NotificationTemplate();
        template.setEventCode("TICKET_HANDED_OFF");
        template.setChannel("EMAIL");
        template.setBodyTemplate("Ravi handed this to you.");
        template.setActive(true);
        when(templates.findByEventCodeAndChannel(anyString(), anyString()))
                .thenReturn(Optional.of(template));

        renderer = new MailRenderer(templates, context, engine);
    }

    // ───────────────────────────────────────────────────── §4B.6's contents

    @Test
    @DisplayName("a fully-populated ticket mail carries every §4B.6 field")
    void everyBlueprintFieldAppears() {
        given(full());

        String html = render();

        assertThat(html)
                .contains("CRITICAL")                       // the level chip
                .contains("CRM Revamp")                     // project
                .contains("Acme Ltd")                       // client
                .contains("QA")                             // current stage
                .contains("22 Aug 2026")                    // planned close date
                .contains("Ravi Kumar")                     // who acted
                .contains("Blocked on the sandbox")         // what they said
                .contains("Open ticket")                    // the primary action
                .contains("Replies to this address are not monitored"); // the reply hint
    }

    @Test
    @DisplayName("the level chip is soft-tinted with solid text, never a solid block — §12.1")
    void theChipFollowsTheDesignTokens() {
        given(MailContext.builder().put(MergeTag.LEVEL, "CRITICAL").build());

        String html = render();

        // §12.1 pins these two together for Critical. A solid red band across
        // the top of an alert is what trains people to filter the alert.
        assertThat(html).contains("background:#FEF2F2");
        assertThat(html).contains("color:#B91C1C");
    }

    @Test
    @DisplayName("a level this build has never heard of still renders a legible chip")
    void anAdminAddedLevelDoesNotVanish() {
        // S-12 lets an Admin add levels without a release, so this is a normal
        // future state rather than corruption.
        given(MailContext.builder().put(MergeTag.LEVEL, "BLOCKER").build());

        String html = render();

        assertThat(html).contains("BLOCKER");
        // Neutral, deliberately: grey says "no opinion", red would say
        // something false about a level nobody has defined here.
        assertThat(html).contains("background:#F7F8FB");
    }

    // ──────────────────────────────────────── the half that is easy to skip

    @Test
    @DisplayName("an internal ticket shows no client row rather than an empty one")
    void absentFieldsLeaveNoEmptyRows() {
        given(MailContext.builder()
                .put(MergeTag.TICKET_ID, "INT-26-00012")
                .put(MergeTag.PROJECT, "Internal Tools")
                .build());

        String html = render();

        assertThat(html).contains("Internal Tools");
        // Not "Client:" followed by nothing.
        assertThat(html).doesNotContain("Client");
        assertThat(html).doesNotContain("Current stage");
        assertThat(html).doesNotContain("Planned close");
    }

    @Test
    @DisplayName("mail about no ticket shows no ticket furniture at all")
    void aChainFailureMailIsJustProse() {
        // CHAIN_VERIFICATION_FAILED has no ticket, no project and no actor —
        // its template is static prose, and every block here must fold away.
        given(MailContext.empty());

        String html = render();

        assertThat(html)
                .doesNotContain("Open ticket")
                .doesNotContain("Project")
                .doesNotContain("Assigned to");
        // The wording still arrives, which is the entire mail.
        assertThat(html).contains("Ravi handed this to you.");
    }

    @Test
    @DisplayName("an actor with no comment is still attributed")
    void anActorWithoutACommentIsNamed() {
        given(MailContext.builder().put(MergeTag.ACTOR, "Meera Nair").build());

        // "Meera moved this to QA" is the whole content of several §4B.6
        // events; dropping the name because there was no note would lose it.
        assertThat(render()).contains("Actioned by").contains("Meera Nair");
    }

    @Test
    @DisplayName("overdue_by appears only on the mail where it is the point")
    void overdueOnlyShowsOnABreach() {
        given(MailContext.builder().put(MergeTag.TICKET_ID, "CRM-26-00347").build());
        assertThat(render()).doesNotContain("Overdue by");

        given(MailContext.builder()
                .put(MergeTag.TICKET_ID, "CRM-26-00347")
                .put(MergeTag.OVERDUE_BY, "6 working hours")
                .build());
        assertThat(render()).contains("Overdue by").contains("6 working hours");
    }

    // ───────────────────────────────────────────── the escaping, once more

    @Test
    @DisplayName("a metadata value containing markup is escaped by the layout too")
    void layoutValuesAreEscapedAsWell() {
        given(MailContext.builder()
                .put(MergeTag.CLIENT, "<script>alert(1)</script>")
                .build());

        String html = render();

        // These go through th:text rather than the body's th:utext. Adding a
        // second utext to that file is how a client name becomes markup in
        // somebody's inbox, so this pins the distinction.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    // ───────────────────────────────────────────────────────────── helpers

    private void given(MailContext values) {
        when(context.forTicket(anyLong())).thenReturn(values);
    }

    private String render() {
        return renderer.render(new OutboxMessage(1L, 5L, "TICKET_HANDED_OFF", null, 7L,
                "ravi@edunext.test", "[CRM-26-00347] Handed to you at QA", 0)).html();
    }

    private static MailContext full() {
        return MailContext.builder()
                .put(MergeTag.TICKET_ID, "CRM-26-00347")
                .put(MergeTag.TICKET_TITLE, "Import fails on header row")
                .put(MergeTag.LEVEL, "CRITICAL")
                .put(MergeTag.PROJECT, "CRM Revamp")
                .put(MergeTag.CLIENT, "Acme Ltd")
                .put(MergeTag.STAGE, "QA")
                .put(MergeTag.ASSIGNEE, "Priya Sharma")
                .put(MergeTag.PLANNED_CLOSE, "22 Aug 2026")
                .put(MergeTag.ACTOR, "Ravi Kumar")
                .put(MergeTag.COMMENT, "Blocked on the sandbox credentials.")
                .put(MergeTag.TICKET_URL, "https://edutrack.test/tickets/CRM-26-00347")
                .build();
    }
}
