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
    TICKET_ASSIGNED(Category.ASSIGNMENT),
    HANDOFF_RECEIVED(Category.ASSIGNMENT),
    QA_FAILED_REWORK(Category.ASSIGNMENT),
    DEPLOYMENT_DONE_VERIFY(Category.ASSIGNMENT),
    TICKET_REASSIGNED_AWAY(Category.ASSIGNMENT),
    TICKET_REOPENED(Category.ASSIGNMENT),
    NEW_UNASSIGNED_TICKET(Category.ASSIGNMENT),

    // ── escalations: something is late, failed, or has got worse ───────────
    SLA_BREACHED(Category.ESCALATION),
    SLA_80_PERCENT_ELAPSED(Category.ESCALATION),
    STAGE_SLA_BREACHED(Category.ESCALATION),
    LEVEL_RAISED_CRITICAL(Category.ESCALATION),
    ITERATION_LIMIT_REACHED(Category.ESCALATION),
    DEPLOYMENT_FAILED(Category.ESCALATION),

    // ── status requests (D-055/D-056) ──────────────────────────────────────
    STATUS_REQUESTED(Category.STATUS_REQUEST),
    STATUS_REQUEST_ANSWERED(Category.STATUS_REQUEST),

    // ── mentions (D-052) ───────────────────────────────────────────────────
    MENTIONED(Category.MENTION),

    // ── everything else: real notifications, no tab of their own ───────────
    TICKET_CLOSED(Category.OTHER),
    COMMENT_ADDED(Category.OTHER),
    COMMENT_MARKED_CLIENT_VISIBLE(Category.OTHER),
    ATTACHMENT_ADDED(Category.OTHER),
    PRIORITY_CHANGED(Category.OTHER),
    DAILY_DIGEST(Category.OTHER),
    WEEKLY_MANAGER_SUMMARY(Category.OTHER),

    /** D-033. Operational rather than §11: a mail this system gave up on. */
    MAIL_DELIVERY_FAILED(Category.OTHER),

    /** D-034. An address the provider told us to stop writing to. */
    EMAIL_ADDRESS_SUPPRESSED(Category.OTHER);

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

    private final Category category;

    NotificationEvent(Category category) {
        this.category = category;
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
        return category == Category.ASSIGNMENT || category == Category.ESCALATION;
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
