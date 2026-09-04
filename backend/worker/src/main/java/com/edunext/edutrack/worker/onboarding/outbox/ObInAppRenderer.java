package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.onboarding.outbox.ObCategory;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B-112 · turns one claimed {@code IN_APP} queue row into the title, body,
 * category and link a bell entry is made of.
 *
 * <p>{@link ObMailRenderer}'s smaller sibling, and deliberately not a mode of
 * it. That class resolves an audience, chooses between two template sets,
 * strips owner names for a client, escapes HTML, drops paragraphs and pushes
 * the result through a Thymeleaf layout with a chip and a button. A bell entry
 * has one reader, no markup, two lines and no layout — folding it in would have
 * meant a second branch through every one of those steps to reach a method that
 * fits on a page.
 *
 * <p>What <em>is</em> shared is the part that must not diverge: the placeholder
 * dialect ({@link MergeTag#PLACEHOLDER}, B-022's {@code {{tag}}}), the
 * stringify rule for JSON payload values, and the "absent renders as nothing,
 * never as braces" guarantee D-029 settled. Those are copied here as the same
 * five lines rather than lifted into a shared base class, because a base class
 * over two renderers with different outputs is how the mail renderer acquires a
 * boolean parameter.
 */
@Component
class ObInAppRenderer {

    private static final Logger log = LoggerFactory.getLogger(ObInAppRenderer.class);

    /** {@code ob_notifications.title}. Longer than any constant; a payload value can still overflow it. */
    static final int TITLE_MAX = 200;

    /** B-022's dialect, shared with {@link ObMailRenderer} so OB-12 has one syntax. */
    private static final Pattern PLACEHOLDER = MergeTag.PLACEHOLDER;

    /**
     * What a row whose event this build has never heard of says. Rendering it
     * generically rather than skipping it is
     * {@link ObNotificationEvent#of(String)}'s contract: a queue filled by a
     * newer deploy than the worker draining it is an ordinary rollout.
     */
    private static final String GENERIC_TITLE = "Onboarding update";

    private final ObMailLinks links;

    ObInAppRenderer(ObMailLinks links) {
        this.links = links;
    }

    /**
     * One bell entry, ready to insert. Never null, whatever the row holds.
     */
    ObInAppContent render(ObOutboxMessage message) {
        Optional<ObNotificationEvent> event = ObNotificationEvent.of(message.eventKey());
        Map<String, String> values = valuesFor(message);
        String link = links.inAppPath(message);

        Optional<ObInAppTemplate> template = event.flatMap(ObInAppTemplate::forEvent);
        if (template.isEmpty()) {
            // WARN for ObMailRenderer's reason: every queued event is one
            // somebody chose to raise, so a row with no wording is a hole in
            // the catalogue rather than an event that legitimately has none.
            log.warn("ob-inapp: no template for event={} (id={}), writing the generic entry",
                    message.eventKey(), message.id());
            return new ObInAppContent(
                    GENERIC_TITLE,
                    genericBody(values),
                    event.map(ObNotificationEvent::category).orElse(ObCategory.UPDATE),
                    link);
        }

        ObInAppTemplate chosen = template.get();
        return new ObInAppContent(
                truncate(resolve(chosen.title(), chosen.fallbackTitle(), values)),
                resolve(chosen.body(), chosen.fallbackBody(), values),
                chosen.event().category(),
                link);
    }

    /**
     * The interpolated line, or the static one.
     *
     * <p>All or nothing per line, unlike the mail body's paragraph-by-paragraph
     * rule. A bell entry is two lines: dropping one leaves an entry that says
     * less than the event it announces, and a half-resolved
     * "Overdue by : Data migration" is worse than a true shorter sentence.
     */
    private static String resolve(String text, String fallback, Map<String, String> values) {
        return resolvesFully(text, values) ? substitute(text, values) : fallback;
    }

    /**
     * The payload as printable strings.
     *
     * <p>Stringified rather than trusted to be strings, {@link ObMailRenderer}'s
     * reason: {@code payload} is JSON an enqueuer wrote, so an escalation level
     * of {@code 2} arrives as a number. Objects and arrays are dropped —
     * {@code {a=1}} in a notification is worse than a shorter sentence.
     */
    private static Map<String, String> valuesFor(ObOutboxMessage message) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : message.payload().entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            asText(entry.getValue()).ifPresent(text -> values.put(name, text));
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
        return Optional.empty();
    }

    /**
     * The generic entry names the client when the payload has one, because
     * "Onboarding update" with no subject is a row nobody can act on and
     * everybody has to open.
     */
    private static String genericBody(Map<String, String> values) {
        String client = values.get("client_name");
        return client == null || client.isBlank()
                ? "Something has changed on a client onboarding. Open it for the detail."
                : "Something has changed on the onboarding for " + client + ".";
    }

    private static boolean resolvesFully(String text, Map<String, String> values) {
        if (text == null) {
            return false;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String substitute(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            // Quoted for ObMailRenderer's reason: a client name containing $ or
            // a backslash is otherwise read as a group reference.
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * A payload value can push a resolved title past the column. Truncating
     * here rather than letting MySQL do it: strict mode would fail the insert,
     * losing the whole notification over a long client name.
     */
    private static String truncate(String title) {
        if (title.length() <= TITLE_MAX) {
            return title;
        }
        return title.substring(0, TITLE_MAX - 1) + "…";
    }
}
