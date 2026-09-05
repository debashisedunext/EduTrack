package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-114 · the one onboarding mail body that is built rather than substituted.
 *
 * <p>Which is exactly why it is tested on its own. Every other body reaches the
 * reader through {@link ObMailRenderer}'s substitution, where escaping happens
 * once, in one place, for every value. This file writes markup directly and
 * therefore owns that guarantee for its own cells — and the payload it reads is
 * JSON that a different process, in a different module, wrote.
 */
class ObDigestBodyTest {

    private final ObDigestBody body = new ObDigestBody();

    // ─────────────────────────────────────────────── the one that must not fail

    @Test
    @DisplayName("a client name containing markup arrives as text, not as markup")
    void everyCellIsEscaped() {
        String html = table(row(Map.of(
                "client", "<img src=x onerror=alert(1)>",
                "product", "<b>ERP</b>",
                "step", "Data migration & cutover",
                "owner", "O'Brien",
                "state", "Blocked",
                "stalled_for", "4 working days")));

        assertThat(html).doesNotContain("<img src=x").doesNotContain("<b>ERP</b>");
        assertThat(html)
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("&lt;b&gt;ERP&lt;/b&gt;")
                .contains("Data migration &amp; cutover")
                .contains("O&#39;Brien");
    }

    @Test
    @DisplayName("escaping is not doubled")
    void escapingIsNotDoubled() {
        String html = table(row(Map.of("client", "Marks & Spencer", "step", "UAT",
                "state", "Overdue", "stalled_for", "1 working day")));

        assertThat(html).contains("Marks &amp; Spencer").doesNotContain("&amp;amp;");
    }

    // ─────────────────────────────────────────────────────────── what it prints

    @Test
    @DisplayName("one row per stuck step, oldest first, in the order it was given")
    void rowsKeepTheirOrder() {
        String html = table(
                row(Map.of("client", "Alpha", "step", "One", "state", "Blocked",
                        "stalled_for", "9 working days")),
                row(Map.of("client", "Beta", "step", "Two", "state", "Overdue",
                        "stalled_for", "3 working days")));

        // The scheduler orders by stall age and the mail must not re-sort it:
        // the top of the list is what gets read.
        assertThat(html.indexOf("Alpha")).isLessThan(html.indexOf("Beta"));
    }

    @Test
    @DisplayName("the owner and the due date share a line under the service")
    void theMetaLineCarriesOwnerAndDue() {
        String html = table(row(Map.of("client", "Alpha", "step", "Data migration",
                "owner", "Ravi Kumar", "due_on", "22 Sep 2026",
                "state", "Overdue", "stalled_for", "3 working days")));

        assertThat(html).contains("Ravi Kumar · due 22 Sep 2026");
    }

    @Test
    @DisplayName("a missing owner does not leave a stray separator")
    void anAbsentOwnerLeavesNoDangle() {
        // "· due 22 Sep 2026" with nothing in front of it is the mail-shaped
        // version of the bug D-030's facts table exists to avoid.
        String html = table(row(Map.of("client", "Alpha", "step", "Data migration",
                "due_on", "22 Sep 2026", "state", "Overdue", "stalled_for", "3 working days")));

        assertThat(html).contains("due 22 Sep 2026").doesNotContain("· due");
    }

    @Test
    @DisplayName("overdue is the only state printed in the alert colour")
    void onlyOverdueIsRed() {
        // Blocked and waiting-on-client are facts about where the work sits, not
        // failures. Colouring all three red is how a reader stops seeing any.
        assertThat(table(row(Map.of("client", "A", "step", "s", "state", "Overdue",
                "stalled_for", "3 working days")))).contains("#b91c1c");
        assertThat(table(row(Map.of("client", "A", "step", "s", "state", "Waiting on client",
                "stalled_for", "3 working days")))).doesNotContain("#b91c1c");
        assertThat(table(row(Map.of("client", "A", "step", "s", "state", "Blocked",
                "stalled_for", "3 working days")))).doesNotContain("#b91c1c");
    }

    @Test
    @DisplayName("a truncated list says how many it left out")
    void theRemainderIsAdmittedTo() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(row(Map.of("client", "C" + i, "step", "s", "state", "Blocked",
                    "stalled_for", "3 working days")));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stuck_count", 31);
        payload.put(ObNotificationEvent.STUCK_ROWS, rows);

        // Showing 25 of 31 without saying so implies 25 is all of them, which is
        // the one reading that leaves a manager worse off than no digest.
        assertThat(body.tableFor(digest(payload)).orElseThrow()).contains("and 6 more");
    }

    @Test
    @DisplayName("a count that matches the rows adds no remainder line")
    void nothingIsSaidWhenNothingIsLeftOut() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stuck_count", 2);
        payload.put(ObNotificationEvent.STUCK_ROWS, List.of(
                row(Map.of("client", "A", "step", "s", "state", "Blocked", "stalled_for", "3 working days")),
                row(Map.of("client", "B", "step", "s", "state", "Blocked", "stalled_for", "3 working days"))));

        assertThat(body.tableFor(digest(payload)).orElseThrow()).doesNotContain("more");
    }

    // ────────────────────────────────────────────── payloads written elsewhere

    @Test
    @DisplayName("only the digest gets a table")
    void otherEventsAreUntouched() {
        // The renderer calls this for every message. Anything else returning a
        // table would append a list to a sign-off request.
        ObOutboxMessage breach = new ObOutboxMessage(
                1, ObNotificationEvent.TAT_BREACHED.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(5), details(), 7L, 8L, 9L,
                Map.of(ObNotificationEvent.STUCK_ROWS,
                        List.of(row(Map.of("client", "A", "step", "s")))), 0);

        assertThat(body.tableFor(breach)).isEmpty();
    }

    @Test
    @DisplayName("a digest with no rows draws nothing rather than an empty table")
    void anEmptyListDrawsNothing() {
        assertThat(body.tableFor(digest(Map.of()))).isEmpty();
        assertThat(body.tableFor(digest(Map.of(ObNotificationEvent.STUCK_ROWS, List.of())))).isEmpty();
    }

    @Test
    @DisplayName("a payload of the wrong shape is skipped, not thrown on")
    void malformedPayloadsAreSurvivable() {
        // A digest that arrives as prose with no table is a poor mail. A digest
        // that does not arrive is a manager who thinks nothing is stuck.
        assertThat(body.tableFor(digest(Map.of(ObNotificationEvent.STUCK_ROWS, "not a list"))))
                .isEmpty();

        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put(ObNotificationEvent.STUCK_ROWS, List.of(
                "a string where an object should be",
                row(Map.of("client", "Alpha", "step", "s", "state", "Blocked",
                        "stalled_for", "3 working days"))));
        assertThat(body.tableFor(digest(mixed)).orElseThrow()).contains("Alpha");
    }

    @Test
    @DisplayName("a nested value in a cell prints as nothing, never as a map")
    void nonScalarCellsAreDropped() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("client", Map.of("name", "Alpha"));
        row.put("step", "Data migration");
        row.put("state", "Blocked");
        row.put("stalled_for", "3 working days");

        // ObMailRenderer makes the same call for the same reason: "{a=1, b=2}"
        // in somebody's inbox is worse than an empty cell.
        assertThat(body.tableFor(digest(Map.of(ObNotificationEvent.STUCK_ROWS, List.of(row))))
                .orElseThrow())
                .doesNotContain("name=Alpha")
                .contains("Data migration");
    }

    @Test
    @DisplayName("a stuck_count that disagrees with its own rows never prints a negative")
    void aNonsensicalCountIsIgnored() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stuck_count", 1);
        payload.put(ObNotificationEvent.STUCK_ROWS, List.of(
                row(Map.of("client", "A", "step", "s", "state", "Blocked", "stalled_for", "3 working days")),
                row(Map.of("client", "B", "step", "s", "state", "Blocked", "stalled_for", "3 working days"))));

        assertThat(body.tableFor(digest(payload)).orElseThrow()).doesNotContain("more");
    }

    // ───────────────────────────────────────────────────────────────── fixtures

    @SafeVarargs
    private String table(Map<String, Object>... rows) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stuck_count", rows.length);
        payload.put(ObNotificationEvent.STUCK_ROWS, List.of(rows));
        return body.tableFor(digest(payload)).orElseThrow();
    }

    private static Map<String, Object> row(Map<String, Object> values) {
        return new LinkedHashMap<>(values);
    }

    private static ObOutboxMessage digest(Map<String, Object> payload) {
        // No client, journey or step id — a digest is about many of each, which
        // is what makes it the one event that cannot use ObNotification's
        // step-shaped factory.
        return new ObOutboxMessage(
                42, ObNotificationEvent.MANAGER_DIGEST.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(5), details(), null, null, null, payload, 0);
    }

    private static ObOutboxMessage.RecipientDetails details() {
        return new ObOutboxMessage.RecipientDetails(
                "Meera Iyer", "meera@edunext.test", null, false, true);
    }
}
