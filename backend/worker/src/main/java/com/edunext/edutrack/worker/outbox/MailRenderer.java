package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationTemplate;
import com.edunext.edutrack.domain.notifications.NotificationTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * D-029 · turning a queued row into the mail that is actually sent.
 *
 * <p>Until this landed, {@code SmtpMailTransport} sent the subject line as the
 * body. Every §4B.6 mail was one sentence with no ticket, no link and no way to
 * act on it.
 *
 * <h2>Two substitutions, and only one of them escapes</h2>
 *
 * <p>The body is HTML and its values are user text — ticket titles, client
 * names, handoff comments. Substituted raw, a ticket titled
 * {@code <img onerror=…>} becomes live markup in a mail sent to a client. So
 * body values are HTML-escaped, always, with no opt-out: there is no legitimate
 * reason for a ticket title to carry markup, and an "allow HTML in this tag"
 * switch would be a stored-XSS feature request with a friendly name.
 *
 * <p>The subject is not HTML and is escaped as nothing. Escaping it would put a
 * literal {@code &amp;} into {@code [CRM-26-00347] Fix A&amp;B import} in every
 * mail client on earth.
 *
 * <h2>The template is the Admin's, the layout is ours</h2>
 *
 * <p>B-022's master holds what an Admin can edit — the wording. The chrome
 * around it (D-030's level chip, the button, the footer) is a Thymeleaf layout
 * in this module, because it is markup and an Admin editing markup in a
 * {@code <textarea>} is how a mail stops rendering in Outlook.
 *
 * <h2>Falling back is not an error path</h2>
 *
 * <p>No template, an inactive one, or a template for an event nobody seeded:
 * the mail still goes, with the subject as its body, exactly as before this
 * class existed. A missing row in a master an Admin controls must never be able
 * to stop a breach alert — §7.7 calls mail the guaranteed channel, and
 * "guaranteed unless a template was deactivated" is not a guarantee.
 */
@Component
class MailRenderer {

    private static final Logger log = LoggerFactory.getLogger(MailRenderer.class);

    /** The Thymeleaf layout. D-030 owns what is inside it. */
    private static final String LAYOUT = "mail/notification";

    private final NotificationTemplateRepository templates;
    private final MailContextRepository context;
    private final TemplateEngine thymeleaf;

    MailRenderer(NotificationTemplateRepository templates,
                 MailContextRepository context,
                 TemplateEngine thymeleaf) {
        this.templates = templates;
        this.context = context;
        this.thymeleaf = thymeleaf;
    }

    /**
     * @return the subject to send and the HTML body to send with it
     */
    /**
     * ⚠️ <b>A-065 · Stream D's file, one line changed by Stream A — flagged per
     * CLAUDE.md rather than edited quietly, and it needs Debashis's sign-off.</b>
     *
     * <p>The change is {@code MailContext.empty()} becoming
     * {@link MailContextRepository#base()}. Before it, a mail with no ticket
     * rendered against an empty context, so every {@code {{tag}}} in its
     * template resolved to nothing and <em>no non-ticket mail could contain a
     * working link</em> — {@link com.edunext.edutrack.domain.notifications.MergeTag#TICKET_URL}
     * was the only link tag and it is built from a ticket. A-065's scheduled
     * report is a mail whose whole job is to get somebody to a page, so it is
     * the first that could not live with that.
     *
     * <p>The daily digest and the weekly manager summary have the same gap and
     * are unchanged here: their templates can now use {@code {{portal_url}}},
     * but changing seeded template copy for D-038's mails is Stream D's call
     * and not a side effect of a reports task.
     */
    MailContent render(OutboxMessage message) {
        MailContext values = message.ticketId() == null
                ? context.base()
                : context.forTicket(message.ticketId());

        Optional<NotificationTemplate> template = templateFor(message);
        if (template.isEmpty()) {
            // Not logged at WARN. Most events have no EMAIL template by design
            // — §11 ticks the email column for a subset — and a line per mail
            // for an expected condition is how a log stops being read.
            return new MailContent(message.subject(), layout(escape(message.subject()), values));
        }

        String body = substitute(template.get().getBodyTemplate(), values, true);
        String subject = Optional.ofNullable(template.get().getSubjectTemplate())
                .filter(s -> !s.isBlank())
                .map(s -> substitute(s, values, false))
                // D-031 prefixes the ticket code centrally at enqueue, so the
                // stored subject is used only when the template supplies one.
                // Otherwise the already-prefixed one is right.
                .orElse(message.subject());

        return new MailContent(subject, layout(body, values));
    }

    /**
     * The template row for this mail.
     *
     * <p>{@code templateId} wins when the enqueuer named one — that is a caller
     * saying "this specific wording" and it must not be second-guessed. The
     * event lookup is the normal path, since {@code NewMail.templateId} is
     * documented as null-to-resolve-at-render.
     *
     * <p>An inactive template is treated as absent rather than used anyway.
     * Deactivating is the only "off" switch the screen has — there is no DELETE
     * on that controller — so honouring it is the whole feature.
     */
    private Optional<NotificationTemplate> templateFor(OutboxMessage message) {
        Optional<NotificationTemplate> byId = message.templateId() == null
                ? Optional.empty()
                : templates.findById(message.templateId());
        return byId
                .or(() -> message.eventCode() == null
                        ? Optional.empty()
                        : templates.findByEventCodeAndChannel(
                                message.eventCode(), NotificationChannel.EMAIL.name()))
                .filter(NotificationTemplate::isActive);
    }

    /**
     * Replace every {@code {{tag}}} this build knows.
     *
     * <p>An unknown tag is left exactly as written. B-022 refuses to save one,
     * so reaching here means the row predates that validation or was written
     * straight to the database — and blanking it would hide the mistake, while
     * leaving it makes the next person reading the mail able to report it.
     *
     * @param escapeValues true for the HTML body, false for the plain subject
     */
    private String substitute(String template, MailContext values, boolean escapeValues) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = MergeTag.PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Optional<MergeTag> tag = MergeTag.of(matcher.group(1));
            String replacement = tag
                    .map(values::get)
                    .map(value -> escapeValues ? escape(value) : value)
                    .orElseGet(matcher::group);
            // Quoted: a value containing $ or \ is otherwise read as a group
            // reference and either corrupts the output or throws. Ticket titles
            // contain currency signs more often than anyone expects.
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String layout(String body, MailContext values) {
        Context model = new Context();
        model.setVariable("body", body);
        for (MergeTag tag : MergeTag.values()) {
            // Raw, not escaped. The layout reads these through th:text, which
            // escapes on output — escaping here as well would double it and
            // print &amp;amp; to the recipient. The one exception is `body`
            // above, which is already substituted and escaped and is therefore
            // the only th:utext in the file.
            model.setVariable(tag.tag(), values.get(tag));
        }
        // D-030's chip. Resolved in Java so an Admin-added level (S-12) falls
        // back to something legible rather than to no chip at all.
        LevelChip chip = LevelChip.of(values.get(MergeTag.LEVEL));
        model.setVariable("levelBackground", chip.background());
        model.setVariable("levelText", chip.text());
        try {
            return thymeleaf.process(LAYOUT, model);
        } catch (RuntimeException e) {
            // A broken layout must not stop the mail. The rendered body is
            // already correct and readable on its own; losing the chrome is a
            // cosmetic failure and losing the mail is not.
            log.error("Mail layout {} failed to render; sending the body unwrapped", LAYOUT, e);
            return body;
        }
    }

    /**
     * The five characters that matter, by hand.
     *
     * <p>Spring's {@code HtmlUtils} would do, but it lives in {@code spring-web}
     * and this module has no web layer — adding a servlet stack to a mail worker
     * for one function is a worse trade than eleven lines. Thymeleaf's
     * {@code unbescape} is on the classpath transitively, which is exactly why
     * it is not used: a transitive dependency can vanish in a minor upgrade and
     * take the escaping with it.
     *
     * <p>Ampersand is replaced first, or it would re-escape the ampersands the
     * later replacements introduce and turn {@code <} into {@code &amp;lt;}.
     * Both quote forms are covered because a value can land inside an
     * attribute — D-030's button href among them.
     */
    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
