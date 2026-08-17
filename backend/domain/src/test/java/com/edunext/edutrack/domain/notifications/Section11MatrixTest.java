package com.edunext.edutrack.domain.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.Map;

import static com.edunext.edutrack.domain.notifications.NotificationEvent.Mail;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-040 · blueprint §11's table, transcribed, and asserted against the enum.
 *
 * <p><strong>Transcribed rather than derived.</strong> The table below is typed
 * out from the blueprint by hand and compared to what the code declares. A test
 * that computed the expectation from {@code NotificationEvent} would pass no
 * matter what the enum said, which is the failure mode D-036 hit when its rule
 * was written from §4B.6's prose summary instead of §4B.6's table — and the
 * prose had quietly dropped a row.
 *
 * <p>So this is deliberately dumb and deliberately duplicated. When it fails,
 * exactly one of two things is true: the enum drifted from the blueprint, or the
 * blueprint changed and nobody updated this file. Both are worth stopping for.
 */
class Section11MatrixTest {

    /** One row of §11: popup, bell, email. */
    private record Row(boolean popup, boolean bell, Mail mail) {
    }

    private static final boolean YES = true;
    private static final boolean DASH = false;

    /**
     * The twenty-three rows of §11, in the order the blueprint prints them.
     *
     * <p>Every event here is one the blueprint names. Events this system added
     * for its own operations — the chain verifier's alarm, the stale-ticket
     * nudge, a mail that gave up — are deliberately absent and are covered by
     * {@link #everyEventIsDeclared} instead, which only insists they were
     * decided rather than defaulted.
     */
    private static Map<NotificationEvent, Row> section11() {
        Map<NotificationEvent, Row> table = new EnumMap<>(NotificationEvent.class);
        //                                                              popup  bell   email
        table.put(NotificationEvent.TICKET_ASSIGNED,          new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.HANDOFF_RECEIVED,         new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.QA_FAILED_REWORK,         new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.DEPLOYMENT_DONE_VERIFY,   new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.DEPLOYMENT_FAILED,        new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.STAGE_SLA_BREACHED,       new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.ITERATION_LIMIT_REACHED,  new Row(DASH, YES,  Mail.ALWAYS));
        table.put(NotificationEvent.TICKET_REASSIGNED_AWAY,   new Row(DASH, YES,  Mail.NEVER));
        table.put(NotificationEvent.TICKET_REOPENED,          new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.LEVEL_RAISED_CRITICAL,    new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.SLA_BREACHED,             new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.SLA_80_PERCENT_ELAPSED,   new Row(DASH, YES,  Mail.ALWAYS));
        table.put(NotificationEvent.STATUS_REQUESTED,         new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.STATUS_REQUEST_ANSWERED,  new Row(YES,  YES,  Mail.NEVER));
        table.put(NotificationEvent.MENTIONED,                new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.TICKET_CLOSED,            new Row(DASH, YES,  Mail.ALWAYS));
        table.put(NotificationEvent.COMMENT_ADDED,            new Row(DASH, YES,  Mail.ALWAYS));
        table.put(NotificationEvent.COMMENT_MARKED_CLIENT_VISIBLE, new Row(DASH, YES, Mail.ALWAYS));
        table.put(NotificationEvent.ATTACHMENT_ADDED,         new Row(DASH, YES,  Mail.OPT_IN));
        table.put(NotificationEvent.PRIORITY_CHANGED,         new Row(YES,  YES,  Mail.ALWAYS));
        table.put(NotificationEvent.NEW_UNASSIGNED_TICKET,    new Row(DASH, YES,  Mail.ALWAYS));
        table.put(NotificationEvent.DAILY_DIGEST,             new Row(DASH, DASH, Mail.ALWAYS));
        table.put(NotificationEvent.WEEKLY_MANAGER_SUMMARY,   new Row(DASH, DASH, Mail.ALWAYS));
        return table;
    }

    @Test
    @DisplayName("every §11 row matches what the enum declares")
    void theMatrixMatchesTheBlueprint() {
        assertThat(section11()).allSatisfy((event, expected) -> {
            assertThat(event.popsUp())
                    .as("%s in-app popup", event)
                    .isEqualTo(expected.popup());
            assertThat(event.ringsBell())
                    .as("%s bell", event)
                    .isEqualTo(expected.bell());
            assertThat(event.mail())
                    .as("%s email", event)
                    .isEqualTo(expected.mail());
        });
    }

    @Test
    @DisplayName("§11 has 23 rows and all 23 are declared")
    void theWholeTableIsCovered() {
        // The backlog says "24 events". The table prints 23 rows; the count in
        // the task title includes the header or an event the blueprint dropped
        // between drafts. Pinned at what the document actually contains, so a
        // future reader compares against the table rather than the memory of a
        // number.
        assertThat(section11()).hasSize(23);
    }

    // ───────────────────────────────────── the rules that span the whole enum

    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    @DisplayName("no event is silently defaulted — every one reaches somebody")
    void everyEventIsDeclared(NotificationEvent event) {
        // An event with no popup, no bell and no mail is one nobody will ever
        // see. That is never a deliberate state, and it is what a careless
        // declaration produces.
        boolean reachesSomebody = event.popsUp() || event.ringsBell() || event.mail() != Mail.NEVER;
        assertThat(reachesSomebody)
                .as("%s reaches nobody on any channel", event)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    @DisplayName("a popup always leaves a bell entry behind it")
    void nothingPopsWithoutBeingRecorded(NotificationEvent event) {
        // D-043's rule, stated over the whole enum: the toast is transient and
        // the bell is the record. An event that popped and left nothing would
        // be unrecoverable the moment somebody looked away — S-26 is where you
        // go to find what you missed, and it reads the bell.
        if (event.popsUp()) {
            assertThat(event.ringsBell())
                    .as("%s pops up but leaves no bell entry", event)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    @DisplayName("mandatory mail is never claimed for an event that sends none")
    void mandatoryMailImpliesMail(NotificationEvent event) {
        // The contradiction D-040 found. TICKET_REASSIGNED_AWAY is an
        // ASSIGNMENT, so the category rule alone made isMandatoryMail() true —
        // while §11 gives its row a dash in the Email column. OutboxEnqueuer
        // would have force-queued mail to the *previous* assignee, about a
        // ticket that is no longer theirs, with no way for them to stop it.
        if (event.isMandatoryMail()) {
            assertThat(event.mail())
                    .as("%s is mandatory mail but §11 gives it no email channel", event)
                    .isEqualTo(Mail.ALWAYS);
        }
    }

    @Test
    @DisplayName("the two digests are mail only, by definition")
    void digestsRingNoBell() {
        // A "daily digest" bell entry would summarise things the bell is
        // already holding one by one.
        assertThat(NotificationEvent.DAILY_DIGEST.ringsBell()).isFalse();
        assertThat(NotificationEvent.WEEKLY_MANAGER_SUMMARY.ringsBell()).isFalse();
    }
}
