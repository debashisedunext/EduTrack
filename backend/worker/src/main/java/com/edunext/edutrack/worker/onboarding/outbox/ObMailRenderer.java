package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B-111 · turning a claimed {@code ob_notification_outbox} row into the mail
 * that is sent.
 *
 * <p>The seam B-110 left: "B-111 renders the message from an email template
 * through the existing mail engine — no new transport". This is the rendering
 * half. {@link SmtpObMailTransport} is the other, and the engine it renders
 * <em>through</em> is the phase-1 one — Thymeleaf, the same {@code {{tag}}}
 * dialect B-022's templates use, the same {@code JavaMailSender}, the same
 * {@code edutrack.mail.*} configuration. Nothing new is introduced that could
 * be configured differently from the mail EduTrack already sends.
 *
 * <h2>Three rules taken unchanged from D-029, because they were argued once</h2>
 *
 * <ol>
 *   <li><strong>Body values are HTML-escaped, always, with no opt-out.</strong>
 *       A client name, an escalation comment and an objection reason are user
 *       text, and this body is mailed to clients. A client named
 *       {@code <img onerror=…>} must arrive as characters.</li>
 *   <li><strong>The subject is escaped as nothing.</strong> It is not HTML, and
 *       escaping it would print a literal {@code &amp;} in front of every reader
 *       whose client is Outlook, Gmail or anything else.</li>
 *   <li><strong>A missing value renders as nothing.</strong> Never as
 *       {@code {{braces}}}, which is the failure that reaches a client, and
 *       never as a refusal to send — §7.7 makes mail the guaranteed channel and
 *       "guaranteed unless a variable was absent" is not a guarantee.</li>
 * </ol>
 *
 * <h2>And one rule of its own: a paragraph that lost a value is not printed</h2>
 *
 * <p>Rule 3 is right for the ticketing layout, where every value sits in its own
 * table row and an absent one leaves no row. An onboarding body is prose, and
 * prose with a hole in it reads worse than prose that is shorter: "We have
 * returned  for a small change" is a bug in the product as far as the reader is
 * concerned. So substitution happens per paragraph, and a paragraph whose
 * placeholders did not all resolve is dropped whole. If that empties the body,
 * the generic notice below is sent instead — which is the same choice D-029 made
 * when a template was missing, one level down.
 */
@Component
class ObMailRenderer {

    private static final Logger log = LoggerFactory.getLogger(ObMailRenderer.class);

    /** The Thymeleaf layout. B-owned, unlike {@code mail/notification} (D-030). */
    private static final String LAYOUT = "mail/onboarding";

    /**
     * {@code {{variable}}} — B-022's dialect, borrowed rather than reinvented.
     *
     * <p>Read from {@link MergeTag} on purpose. The names an onboarding template
     * uses are {@link ObNotificationEvent}'s and not {@code MergeTag}'s, but the
     * <em>syntax</em> is the one an Admin already types on S-15 and will type on
     * OB-12, and two dialects for one job is how a template that looks right
     * renders literal braces.
     */
    private static final Pattern PLACEHOLDER = MergeTag.PLACEHOLDER;

    /** One prose block of a template body. */
    private static final Pattern PARAGRAPH = Pattern.compile("(?s)<p\\b[^>]*>.*?</p>");

    /**
     * The structured fields the layout prints as a facts table, in the order it
     * prints them. Absent ones leave no row — D-030's rule, and the reason it
     * exists: "Step:" with nothing after it reads as a bug rather than an absent
     * field.
     */
    private static final List<String> FACTS = List.of(
            "client_name", "product_name", "prereq_title", "step_title",
            "due_on", "overdue_by", "escalation_level", "owner_name", "live_on");

    /**
     * Names the layout sets for itself, which a payload may therefore not
     * supply. A queue row is data, and data does not get to decide what the
     * button says or how urgent the chip claims the mail is.
     *
     * <p>{@code action_url} is deliberately <em>not</em> on this list: a
     * payload-supplied link is the documented override
     * ({@link ObMailLinks}), and the two events that cannot work without one —
     * a sign-off and a password reset — are exactly the ones carrying a token
     * only the enqueuer knows.
     */
    private static final List<String> RESERVED = List.of(
            "body", "action_label", "chip_label", "chip_background",
            "chip_text", "recipient_name", "is_client");

    private final ObMailLinks links;
    private final ObDigestBody digestBody;
    private final TemplateEngine thymeleaf;

    ObMailRenderer(ObMailLinks links, ObDigestBody digestBody, TemplateEngine thymeleaf) {
        this.links = links;
        this.digestBody = digestBody;
        this.thymeleaf = thymeleaf;
    }

    /**
     * @return the subject to send and the HTML body to send with it — never null,
     *         whatever the row contains
     */
    ObMailContent render(ObOutboxMessage message) {
        ObMailAudience audience = ObMailAudience.of(message.recipient());
        Optional<ObNotificationEvent> event = ObNotificationEvent.of(message.eventKey());
        Optional<ObMailTemplate> template = event
                .flatMap(e -> ObMailTemplate.forEvent(e, audience));

        Map<String, String> values = valuesFor(message, event, audience);

        if (template.isEmpty()) {
            // WARN, unlike D-029's silence on the same condition. There, most
            // events legitimately have no EMAIL template — §11 ticks a subset.
            // Here every queued event is one somebody chose to mail, so a row
            // with no wording is a gap in the catalogue and should be loud.
            log.warn("ob-mail: no {} template for event={} (id={}), sending the generic notice",
                    audience, message.eventKey(), message.id());
            return generic(message, audience, values);
        }

        ObMailTemplate chosen = template.get();
        // The digest's rows, if this is the digest. Appended rather than
        // substituted — see ObDigestBody for why a list cannot be a {{variable}}
        // and why one event gets a body the template engine did not write.
        String table = digestBody.tableFor(message).orElse("");
        String body = bodyOf(chosen, values) + table;
        if (body.isBlank()) {
            log.warn("ob-mail: every paragraph of {} lost a value for id={}, sending the generic notice",
                    chosen, message.id());
            return generic(message, audience, values);
        }
        return new ObMailContent(subjectOf(chosen, values), layout(body, chosen, message, audience, values));
    }

    // ───────────────────────────────────────────────────────────────── values

    /**
     * The payload, as strings, plus what the row itself knows.
     *
     * <p>Payload values are stringified rather than trusted to be strings:
     * {@code payload} is JSON an enqueuer wrote, so a TAT of {@code 3} arrives
     * as a number and a flag as a boolean. A nested object or array is dropped —
     * there is no way to print one into a sentence, and {@code {a=1, b=2}} in a
     * client's mail is worse than the sentence being short.
     *
     * <p>Reserved names are applied last, so a payload cannot redirect the
     * button or blank the chip.
     */
    private Map<String, String> valuesFor(ObOutboxMessage message,
                                          Optional<ObNotificationEvent> event,
                                          ObMailAudience audience) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : message.payload().entrySet()) {
            String name = entry.getKey();
            if (name == null || RESERVED.contains(name)) {
                continue;
            }
            asText(entry.getValue()).ifPresent(text -> values.put(name, text));
        }

        event.ifPresent(e -> {
            for (String required : e.requiredVariables()) {
                if (blank(values.get(required))) {
                    // Not an error and not a stop — see the class note. Logged
                    // because it is a defect in whatever queued the row, and the
                    // only other trace is a mail that reads oddly.
                    log.warn("ob-mail: event={} id={} is missing required variable {}",
                            e.key(), message.id(), required);
                }
            }
        });

        // CP-03 shows a client step status and nothing else — no owner names.
        // Enforced here rather than trusted to the templates, because the facts
        // table is built from the payload and would otherwise print an owner to
        // a client the moment a staff-facing enqueuer reused the same payload.
        if (audience == ObMailAudience.CLIENT) {
            values.remove("owner_name");
        }
        return values;
    }

    private static Optional<String> asText(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String s) {
            return s.isBlank() ? Optional.empty() : Optional.of(s);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return Optional.of(String.valueOf(value));
        }
        // Maps, lists and anything else: not printable in a sentence.
        return Optional.empty();
    }

    // ────────────────────────────────────────────────────────────────  subject

    /**
     * The interpolated subject, or the static one.
     *
     * <p>A subject is one line and every word in it is load-bearing, so a
     * half-resolved one — "Overdue by : Data migration" — is worse than a
     * shorter true statement. The template carries both for exactly this.
     */
    private String subjectOf(ObMailTemplate template, Map<String, String> values) {
        return resolvesFully(template.subject(), values)
                ? substitute(template.subject(), values, false)
                : template.fallbackSubject();
    }

    // ───────────────────────────────────────────────────────────────────  body

    /** Substitute paragraph by paragraph, dropping any that lost a value. */
    private String bodyOf(ObMailTemplate template, Map<String, String> values) {
        String source = template.body();
        Matcher paragraphs = PARAGRAPH.matcher(source);
        StringBuilder out = new StringBuilder();
        boolean matchedAny = false;
        boolean keptAny = false;
        int cursor = 0;
        while (paragraphs.find()) {
            matchedAny = true;
            out.append(source, cursor, paragraphs.start());
            String paragraph = paragraphs.group();
            if (resolvesFully(paragraph, values)) {
                out.append(substitute(paragraph, values, true));
                keptAny = true;
            }
            cursor = paragraphs.end();
        }
        if (!matchedAny) {
            // A body that is not marked up in paragraphs is treated as one
            // block. No template ships that way, but a B-113 row might.
            return resolvesFully(source, values) ? substitute(source, values, true) : "";
        }
        out.append(source, cursor, source.length());
        return keptAny ? out.toString() : "";
    }

    /** True when every placeholder in {@code text} has a non-blank value. */
    private static boolean resolvesFully(String text, Map<String, String> values) {
        if (text == null) {
            return false;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (blank(values.get(matcher.group(1)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replace every {@code {{name}}}.
     *
     * @param escapeValues true for the HTML body, false for the plain subject
     */
    private static String substitute(String text, Map<String, String> values, boolean escapeValues) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            String replacement = escapeValues ? escape(value) : value;
            // Quoted: a value containing $ or \ is otherwise read as a group
            // reference and either corrupts the output or throws. A client name
            // with an ampersand is common and a currency sign is not rare.
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ─────────────────────────────────────────────────────────────────  layout

    private String layout(String body, ObMailTemplate template, ObOutboxMessage message,
                          ObMailAudience audience, Map<String, String> values) {
        Context model = model(body, message, audience, values);
        model.setVariable("chip_label", template.urgency().label());
        model.setVariable("chip_background", template.urgency().background());
        model.setVariable("chip_text", template.urgency().text());
        // A template with no action label gets no button and therefore no URL.
        // SIGNOFF_OTP is the case: the reader already has the page open.
        model.setVariable("action_label", nullToEmpty(template.actionLabel()));
        model.setVariable("action_url", template.actionLabel() == null
                ? ""
                : links.actionUrl(message, audience, values.get("action_url")));
        return process(model, body);
    }

    /**
     * Everything the layout needs that does not depend on which template was
     * chosen — so the generic notice below renders into the same chrome rather
     * than into a second, untested one.
     *
     * <p>Values go in raw, not escaped: the layout reads them through
     * {@code th:text}, which escapes on output, and escaping here as well would
     * print {@code &amp;amp;} to the reader. {@code body} is the one exception —
     * already substituted and escaped, and therefore the only {@code th:utext}
     * in the file.
     */
    private Context model(String body, ObOutboxMessage message, ObMailAudience audience,
                          Map<String, String> values) {
        Context model = new Context();
        for (String fact : FACTS) {
            model.setVariable(fact, values.getOrDefault(fact, ""));
        }
        model.setVariable("body", body);
        model.setVariable("recipient_name", nullToEmpty(message.details().name()));
        model.setVariable("is_client", audience == ObMailAudience.CLIENT);
        return model;
    }

    private String process(Context model, String body) {
        try {
            return thymeleaf.process(LAYOUT, model);
        } catch (RuntimeException e) {
            // A broken layout must not cost the mail. The body is already
            // correct and readable on its own; losing the chrome is cosmetic
            // and losing a sign-off request is not.
            log.error("ob-mail: layout {} failed to render; sending the body unwrapped", LAYOUT, e);
            return body;
        }
    }

    // ─────────────────────────────────────────────────────────────────  generic

    /**
     * What is sent when the catalogue has nothing for this row.
     *
     * <p>Two versions, and the difference is the point. Staff are told the event
     * key, because they are the only people who can get the template written and
     * an unnamed "something happened" is unreportable. A client is told nothing
     * internal at all — an event code in a client's inbox is a leak of our
     * vocabulary and means nothing to them anyway.
     */
    private ObMailContent generic(ObOutboxMessage message, ObMailAudience audience,
                                  Map<String, String> values) {
        String client = values.getOrDefault("client_name", "");
        boolean staff = audience == ObMailAudience.STAFF;

        String subject = staff
                ? "EduTrack onboarding update" + (client.isBlank() ? "" : " — " + client)
                : "An update on your onboarding";

        String body = staff
                ? "<p>There is an onboarding update this build has no wording for: <strong>"
                        + escape(nullToEmpty(message.eventKey())) + "</strong>. Open the client to "
                        + "see what changed, and let the platform team know the template is "
                        + "missing.</p>"
                : "<p>There is an update on your onboarding. Open the portal to see where "
                        + "things stand.</p>";

        Context model = model(body, message, audience, values);
        model.setVariable("chip_label", "");
        model.setVariable("chip_background", "");
        model.setVariable("chip_text", "");
        model.setVariable("action_label", staff ? "Open the client" : "View your onboarding");
        model.setVariable("action_url", links.actionUrl(message, audience, values.get("action_url")));
        return new ObMailContent(subject, process(model, body));
    }

    // ───────────────────────────────────────────────────────────────── helpers

    /**
     * The five characters that matter, by hand.
     *
     * <p>The same eleven lines {@code MailRenderer} carries, and for the same
     * reasons it gives: Spring's {@code HtmlUtils} lives in {@code spring-web}
     * and this module has no web layer, and Thymeleaf's {@code unbescape} is a
     * transitive dependency that can vanish in a minor upgrade and take the
     * escaping with it. Duplicated rather than made public over there — this is
     * Stream D's file and a shared escaper is not worth a cross-stream edit.
     *
     * <p>Ampersand first, or it re-escapes the ampersands the later replacements
     * introduce and turns {@code <} into {@code &amp;lt;}. Both quote forms,
     * because a value can land inside an attribute.
     *
     * <p>Package-private rather than private since B-114: {@link ObDigestBody}
     * builds the one body this class does not substitute, and its cells are user
     * text with the same problem. One escaper in the package, not a fourth copy
     * of the same eleven lines.
     */
    static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
