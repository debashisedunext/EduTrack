package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B-114 · the digest's table, which is the one onboarding mail body that is not
 * a template.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@link ObMailRenderer} substitutes {@code {{variables}}} into wording and
 * prunes paragraphs that lost a value. That is the right engine for nineteen of
 * the twenty §7 events, because each of them is about one step and every fact
 * it carries is a single value. A digest is about many steps across many
 * clients, and <strong>there is no placeholder that prints a table</strong>.
 * Nothing in the substitution dialect loops, and giving it a loop would mean
 * inventing a second template language for one mail.
 *
 * <p>So the rows travel as structured data — a JSON array in the payload under
 * {@link ObNotificationEvent#STUCK_ROWS} — and this class turns them into
 * markup. {@link ObManagerDigestScheduler}'s prose still comes from
 * {@link ObMailTemplate#MANAGER_DIGEST} through the normal path; this is
 * appended to it.
 *
 * <h2>The escaping rule does not get an exception here</h2>
 *
 * <p>The markup below is ours and the values in it are not: a client name, a
 * service name and an owner's name are all user text, and the layout prints
 * this body through the file's one {@code th:utext}. So every cell goes through
 * {@link ObMailRenderer#escape}, the same escaper the substitution path uses,
 * and the only thing that reaches the reader unescaped is the table this file
 * writes literally. A client named {@code <img onerror=…>} arrives as
 * characters in a manager's inbox exactly as it does in a client's.
 *
 * <p>The payload is JSON somebody else wrote, so nothing about its shape is
 * assumed. A row that is not an object, a value that is not a scalar, an array
 * that is not there at all — each is skipped, and an empty result means the
 * renderer sends the prose alone rather than a mail with a broken table in it.
 */
@Component
class ObDigestBody {

    /**
     * The columns, in the order they are printed, paired with the payload key
     * each reads.
     *
     * <p>"Stuck for" is last and "Client" first because a manager scans the
     * left column for whose client it is and the right column for how bad it
     * is; the two facts they act on are at the two ends rather than buried in
     * the middle.
     */
    private static final List<String> HEADINGS = List.of("Client", "Service", "State", "Stuck for");

    /** §12.1's muted text, the same value the layout uses for its own labels. */
    private static final String MUTED = "#7c859c";

    /** §12.1's hairline. */
    private static final String RULE = "#eceff6";

    /** §12.1 Critical text — a state column, not a band across the mail. */
    private static final String CRITICAL = "#b91c1c";

    /**
     * The table for this message, or empty when there is nothing to draw.
     *
     * <p>Empty rather than an exception on every malformed shape: a digest that
     * arrives as prose with no table is a poor mail, and a digest that does not
     * arrive is a manager who thinks nothing is stuck.
     */
    Optional<String> tableFor(ObOutboxMessage message) {
        if (!ObNotificationEvent.MANAGER_DIGEST.key().equals(message.eventKey())) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = rowsIn(message.payload());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(render(rows, remainderIn(message.payload(), rows.size())));
    }

    // ─────────────────────────────────────────────────────────────── the shape

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsIn(Map<String, Object> payload) {
        if (!(payload.get(ObNotificationEvent.STUCK_ROWS) instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .toList();
    }

    /**
     * How many stuck steps were left out of the array.
     *
     * <p>The scheduler caps what it puts in the payload — a manager with two
     * hundred stuck steps needs a conversation rather than a longer mail, and
     * the queue row should not carry two hundred objects either. {@code
     * stuck_count} is the true total, so the difference is what the table has
     * to admit to. Never negative: a payload whose count disagrees with its own
     * array is a bug somewhere else and not worth printing "-3 more" over.
     */
    private static int remainderIn(Map<String, Object> payload, int shown) {
        if (!(payload.get("stuck_count") instanceof Number total)) {
            return 0;
        }
        return Math.max(total.intValue() - shown, 0);
    }

    // ────────────────────────────────────────────────────────────────  markup

    /**
     * Deliberately 2004-era HTML, for the reasons {@code mail/onboarding.html}
     * gives at length: Outlook renders with Word's engine and Gmail strips
     * {@code <style>}, so this is a table with inline styles and should stay
     * one however wrong it looks beside the app's own markup.
     */
    private static String render(List<Map<String, Object>> rows, int remainder) {
        StringBuilder html = new StringBuilder(256 + rows.size() * 256);
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"margin-top:6px;font-size:13px;border-collapse:collapse;\">");

        html.append("<tr>");
        for (String heading : HEADINGS) {
            html.append("<td style=\"padding:6px 8px 6px 0;color:").append(MUTED)
                    .append(";font-size:11px;font-weight:700;letter-spacing:.04em;")
                    .append("text-transform:uppercase;border-bottom:1px solid ").append(RULE)
                    .append(";\">").append(heading).append("</td>");
        }
        html.append("</tr>");

        for (Map<String, Object> row : rows) {
            html.append(renderRow(row));
        }

        if (remainder > 0) {
            html.append("<tr><td colspan=\"").append(HEADINGS.size())
                    .append("\" style=\"padding:8px 8px 0 0;color:").append(MUTED)
                    .append(";font-size:12px;\">and ").append(remainder)
                    .append(" more — open the module to see the rest.</td></tr>");
        }
        return html.append("</table>").toString();
    }

    private static String renderRow(Map<String, Object> row) {
        String client = text(row, "client");
        String product = text(row, "product");
        String step = text(row, "step");
        String owner = text(row, "owner");
        String dueOn = text(row, "due_on");
        String state = text(row, "state");
        String stalledFor = text(row, "stalled_for");

        StringBuilder cell = new StringBuilder();
        cell.append("<tr>");

        // Client, with the product under it: two facts that are always read
        // together and would otherwise cost a column each.
        cell.append(td()).append(strong(client));
        if (!product.isEmpty()) {
            cell.append(sub(product));
        }
        cell.append("</td>");

        cell.append(td()).append(escape(step));
        String meta = owner.isEmpty() ? "" : owner;
        if (!dueOn.isEmpty()) {
            meta = meta.isEmpty() ? "due " + dueOn : meta + " · due " + dueOn;
        }
        if (!meta.isEmpty()) {
            cell.append(sub(meta));
        }
        cell.append("</td>");

        // Overdue is the state that is our fault and reads in the alert colour.
        // Blocked and waiting-on-client are facts about where the work sits,
        // and colouring all three red is how a reader stops seeing any of them.
        boolean overdue = "Overdue".equals(state);
        cell.append("<td style=\"padding:8px 8px 8px 0;vertical-align:top;border-bottom:1px solid ")
                .append(RULE).append(";")
                .append(overdue ? "color:" + CRITICAL + ";font-weight:600;" : "")
                .append("\">").append(escape(state)).append("</td>");

        cell.append(td()).append(escape(stalledFor)).append("</td>");
        return cell.append("</tr>").toString();
    }

    private static String td() {
        return "<td style=\"padding:8px 8px 8px 0;vertical-align:top;border-bottom:1px solid "
                + RULE + ";\">";
    }

    private static String strong(String value) {
        return "<strong>" + escape(value) + "</strong>";
    }

    private static String sub(String value) {
        return "<div style=\"color:" + MUTED + ";font-size:12px;margin-top:2px;\">"
                + escape(value) + "</div>";
    }

    /**
     * One cell's value as text.
     *
     * <p>Scalars only, exactly as {@code ObMailRenderer} treats payload values:
     * a nested object printed as {@code {a=1}} in a manager's inbox is worse
     * than an empty cell.
     */
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof String s) {
            return s.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "";
    }

    private static String escape(String value) {
        return ObMailRenderer.escape(value);
    }
}
