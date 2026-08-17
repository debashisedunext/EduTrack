package com.edunext.edutrack.domain.notifications;

import java.util.Optional;

/**
 * The §11 notification matrix as a closed vocabulary.
 *
 * <p>D-041 needs this because S-26's tabs — All / Mentions / Assignments /
 * Escalations / Status requests — are a filter on {@code event_code}. With free
 * strings at the write side, one typo puts a notification in "All" and nowhere
 * else, permanently and silently. An enum makes that a compile error.
 *
 * <p><strong>Strict on write, tolerant on read.</strong> Producers must name an
 * event that exists here; readers go through {@link #of(String)}, which returns
 * empty rather than throwing. A row written by an older deploy, or by a code
 * this enum has since dropped, must still render in the bell — losing the
 * notification is worse than losing its tab.
 *
 * <p><strong>D-040 owns the producers, not this list.</strong> Wiring each event
 * to its trigger, recipients and template is that task. What is fixed here is
 * only the spelling, so the tabs and the producers cannot disagree about it.
 */
public enum NotificationEvent {

    // ── assignments: who is responsible, or what is expected of them ────────
    TICKET_ASSIGNED(Category.ASSIGNMENT, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    HANDOFF_RECEIVED(Category.ASSIGNMENT, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    QA_FAILED_REWORK(Category.ASSIGNMENT, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    DEPLOYMENT_DONE_VERIFY(Category.ASSIGNMENT, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    TICKET_REASSIGNED_AWAY(Category.ASSIGNMENT, Col.QUIET, Col.RINGS_BELL, Mail.NEVER),
    TICKET_REOPENED(Category.ASSIGNMENT, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    NEW_UNASSIGNED_TICKET(Category.ASSIGNMENT, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),

    // ── escalations: something is late, failed, or has got worse ───────────
    SLA_BREACHED(Category.ESCALATION, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    SLA_80_PERCENT_ELAPSED(Category.ESCALATION, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),
    STAGE_SLA_BREACHED(Category.ESCALATION, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    LEVEL_RAISED_CRITICAL(Category.ESCALATION, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    ITERATION_LIMIT_REACHED(Category.ESCALATION, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),
    DEPLOYMENT_FAILED(Category.ESCALATION, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),

    /**
     * A-044 · the nightly verifier found a hash chain that does not verify.
     *
     * <p><b>Added by Stream A, and it needs Debashis's sign-off</b> — this file
     * is Stream D's. The alternative was to raise chain breaks under
     * {@code MAIL_DELIVERY_FAILED}, which is what was reachable and would have
     * filed a possible tampering alert as an email problem, in the wrong
     * category, under the wrong preference.
     *
     * <p>{@code ESCALATION} rather than {@code OTHER} because that is what it
     * is: PLAN.md §3.5 names the chain as the backstop for the case where the
     * immutability triggers have been defeated. <b>One thing to decide
     * together:</b> D-042's notification preferences let a user mute a
     * category, and this is an alarm that arguably nobody should be able to
     * mute. Left as an ordinary escalation for now rather than inventing an
     * unmutable class unilaterally.
     */
    CHAIN_VERIFICATION_FAILED(Category.ESCALATION, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),

    // ── status requests (D-055/D-056) ──────────────────────────────────────
    STATUS_REQUESTED(Category.STATUS_REQUEST, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    STATUS_REQUEST_ANSWERED(Category.STATUS_REQUEST, Col.POPS_UP, Col.RINGS_BELL, Mail.NEVER),

    // ── mentions (D-052) ───────────────────────────────────────────────────
    MENTIONED(Category.MENTION, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),

    // ── everything else: real notifications, no tab of their own ───────────
    TICKET_CLOSED(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),
    COMMENT_ADDED(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),
    COMMENT_MARKED_CLIENT_VISIBLE(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),
    ATTACHMENT_ADDED(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.OPT_IN),
    PRIORITY_CHANGED(Category.OTHER, Col.POPS_UP, Col.RINGS_BELL, Mail.ALWAYS),
    /**
     * D-022. Not in blueprint §11's list of 24, and added rather than
     * borrowed: nothing there means "nobody has touched this in a while".
     * {@code SLA_80_PERCENT_ELAPSED} was the near miss and is wrong — that
     * one is about a deadline approaching, while a ticket can go quiet for a
     * fortnight with its deadline still months away.
     *
     * <p><b>{@code OTHER}, so it is opt-out.</b> An escalation category would
     * make the mail unsuppressable under D-036, and a reminder nobody can
     * switch off is how a mailbox rule gets written that hides the alerts that
     * do matter. Nothing has gone wrong when this fires — that is the point of
     * sending it.
     */
    STALE_TICKET_NUDGE(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS),

    DAILY_DIGEST(Category.OTHER, Col.QUIET, Col.NO_BELL, Mail.ALWAYS),
    WEEKLY_MANAGER_SUMMARY(Category.OTHER, Col.QUIET, Col.NO_BELL, Mail.ALWAYS),

    /** D-033. Operational rather than §11: a mail this system gave up on. */
    MAIL_DELIVERY_FAILED(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.NEVER),

    /** D-034. An address the provider told us to stop writing to. */
    EMAIL_ADDRESS_SUPPRESSED(Category.OTHER, Col.QUIET, Col.RINGS_BELL, Mail.ALWAYS);

    /**
     * What kind of thing happened, which is what S-26's tabs group on.
     *
     * <p>The split is by <em>why the event exists</em>, not by who receives it:
     * an assignment changes who is responsible or what is expected of them; an
     * escalation exists because something is late, failed or got worse. That
     * rule is what decides the awkward cases — "QA failed, sent for rework"
     * hands work back to a developer, so it is an assignment, while "deployment
     * failed" reports a failure to three people, so it is an escalation.
     *
     * <p>{@code OTHER} is not a gap. Those events are worth a bell entry and
     * are not worth a tab, and inventing tabs S-26 does not have would be a
     * change to the screen rather than to this enum.
     */
    public enum Category {
        MENTION, ASSIGNMENT, ESCALATION, STATUS_REQUEST, OTHER
    }

    /**
     * D-040 · readable names for §11's popup and bell columns.
     *
     * <p>Booleans at a call site are unreadable — {@code (Category.OTHER, false,
     * true, Mail.ALWAYS)} tells you nothing about which column is which, and a
     * transposed pair is invisible in review. Named constants make each
     * declaration read as the row it transcribes.
     */
    private static final class Col {
        // In a holder rather than on the enum itself: an enum constant may not
        // reference a static field of its own enum, however it is ordered.
        private static final boolean POPS_UP = true;
        private static final boolean QUIET = false;
        private static final boolean RINGS_BELL = true;
        private static final boolean NO_BELL = false;

        private Col() {
        }
    }

    /**
     * D-040 · §11's Email column, which has three states rather than two.
     *
     * <p>The table marks most rows ✅, two rows —, and "Attachment added"
     * <em>opt</em>. Modelling that last one as a boolean would force a choice
     * between mailing everybody about every attachment or never mailing anybody,
     * and both are wrong.
     */
    public enum Mail {
        /** ✅ — sent unless the user has switched it off, where that is allowed. */
        ALWAYS,
        /** opt — off by default; the user has to ask for it. */
        OPT_IN,
        /** — — this event has no email channel at all. */
        NEVER
    }

    private final Category category;
    private final boolean popsUp;
    private final boolean ringsBell;
    private final Mail mail;

    NotificationEvent(Category category, boolean popsUp, boolean ringsBell, Mail mail) {
        this.category = category;
        this.popsUp = popsUp;
        this.ringsBell = ringsBell;
        this.mail = mail;
    }

    public Category category() {
        return category;
    }

    /**
     * D-036 · the mail this user cannot switch off.
     *
     * <p>Blueprint §4B.6: <em>"assignment, handoff, escalation and breach mails
     * ignore user preferences. Everything else respects them."</em> Those four
     * are not a list of event codes to keep in step by hand — they are exactly
     * {@link Category#ASSIGNMENT} and {@link Category#ESCALATION}, which is why
     * the rule is stated over the category. A new escalation event added to
     * this enum is mandatory the moment it is declared, with nothing else to
     * remember.
     *
     * <p><strong>{@link Category#STATUS_REQUEST} was missing, and D-055 found
     * it.</strong> That prose sentence is a summary of §4B.6's table, and the
     * table is the precise version: its "can be disabled" column marks
     * <em>"Status requested by manager → Assignee →
     * {@code [CRM-26-00347] Status requested}"</em> as <strong>❌ never</strong>,
     * exactly like the four the sentence lists. The sentence simply did not
     * enumerate it. Checked row by row against the whole table, that is the
     * only place the category rule and the table disagreed — every other ❌
     * never is an assignment or an escalation, and every ✅ is neither.
     *
     * <p>The category has a second member, {@code STATUS_REQUEST_ANSWERED},
     * and §11 gives that one no email at all — a dash in the Email column of
     * the "Reply to status request" row. Whether an event that sends nothing is
     * "mandatory" is moot, so covering the category rather than naming the one
     * code keeps the rule stated the way D-036 argued it should be.
     *
     * <p><strong>Mail only, deliberately.</strong> §7.7 calls mail "the
     * guaranteed channel" and gives the guarantee to it: an in-app toast only
     * reaches somebody who is logged in, so it was never the thing making an
     * assignment impossible to miss. Locking the in-app channel too would take
     * away a real preference — quieting toasts while keeping the mail — to
     * protect a channel that was never the guarantee. The bell entry is still
     * written either way; what a preference silences is the popup, never the
     * record.
     */
    public boolean isMandatoryMail() {
        // D-040 added the second clause, and it is what makes the sentence true
        // rather than nearly true. "Cannot be switched off" is a statement about
        // mail that exists; for an event §11 gives no email column at all there
        // is nothing to switch off, and claiming otherwise would have
        // OutboxEnqueuer force-queue a mail the blueprint says never to send.
        //
        // Two events are in exactly that position, and the paragraph above
        // already reasoned about one of them (STATUS_REQUEST_ANSWERED) as moot.
        // TICKET_REASSIGNED_AWAY is the other and was not moot: an ASSIGNMENT
        // whose §11 row reads bell-only, so the category rule alone would have
        // mailed the *previous* assignee about a ticket that is no longer
        // theirs, unsuppressably. §4B.6's table does not contradict this — it
        // has no "reassigned away" row at all; its "Reassigned within a stage"
        // row is about the person picking the ticket up, who does get mandatory
        // mail as TICKET_ASSIGNED.
        return mail == Mail.ALWAYS
                && (category == Category.ASSIGNMENT
                || category == Category.ESCALATION
                || category == Category.STATUS_REQUEST);
    }

    /**
     * D-040 · does §11 give this event an in-app popup?
     *
     * <p>A dash in that column is a product decision, not an absence: "Comment
     * added" and "80% SLA elapsed" are deliberately quiet, because a toast for
     * every comment is how somebody learns to dismiss toasts without reading
     * them. It is <em>not</em> a preference — {@link NotificationPreferences}
     * answers the separate question of whether this particular user wants the
     * ones §11 does allow.
     */
    public boolean popsUp() {
        return popsUp;
    }

    /**
     * D-040 · does this event leave a bell entry?
     *
     * <p>True for everything except the two digests, which are mail by
     * definition — a "daily digest" bell entry would be a summary of things the
     * bell is already holding individually.
     */
    public boolean ringsBell() {
        return ringsBell;
    }

    /** D-040 · §11's Email column for this event. */
    public Mail mail() {
        return mail;
    }

    /**
     * @return empty for a code this build does not know — never an exception.
     *         The bell must render a row written by an older deploy.
     */
    public static Optional<NotificationEvent> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
