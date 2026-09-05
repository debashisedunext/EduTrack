package com.edunext.edutrack.domain.onboarding.outbox;

import java.util.Locale;
import java.util.Optional;

/**
 * B-112 · what kind of onboarding event this is — OB-13's tab vocabulary and
 * the value {@code ob_notifications.category} stores.
 *
 * <h2>The split is by why the event exists, not by who receives it</h2>
 *
 * <p>Deliberately the same rule
 * {@link com.edunext.edutrack.domain.notifications.NotificationEvent.Category}
 * states for S-26, because it is the rule that decides the awkward cases. An
 * {@link #ASSIGNMENT} changes who is responsible or what is expected of them;
 * an {@link #ESCALATION} exists because something is late, refused or has got
 * worse; a {@link #REMINDER} is a deadline approaching that nobody has missed
 * yet.
 *
 * <p>That is what separates {@link ObNotificationEvent#TAT_REMINDER} from
 * {@link ObNotificationEvent#TAT_BREACHED} — the same step and the same owner,
 * but one is still actionable and the other is already a failure, and a bell
 * that files them together is a bell where "what is on fire" cannot be
 * answered. It is also what puts {@link ObNotificationEvent#PREREQ_SUBMITTED}
 * under Assignments although nothing was assigned: something is now expected
 * of the verifier, which is what the category means.
 *
 * <h2>A separate vocabulary from S-26's, and not by accident</h2>
 *
 * <p>Ticketing's tabs are Mentions / Assignments / Escalations / Status
 * requests. Two of those have no onboarding meaning at all and this module has
 * a tab they do not — a TAT reminder is the module's most common notification
 * and would land in S-26's "everything else". Reusing that enum would have
 * meant either four tabs that half fit or an edit to Stream D's file; the
 * events are already a separate catalogue ({@link ObNotificationEvent}), and
 * this is that separation followed through.
 *
 * <h2>UPDATE is the no-tab bucket, and it is not a gap</h2>
 *
 * <p>OB-13 shows <b>All · Assignments · Escalations · Reminders</b>. Everything
 * else — a gate opening, a go-live, a prerequisite verified, a client
 * escalation resolved — is worth a bell entry and is not worth a tab of its
 * own, and inventing tabs the screen does not have would be a change to the
 * screen rather than to this enum. Those appear under All, which is what All is
 * for. It is named for what it holds rather than {@code OTHER}, because unlike
 * S-26's genuine grab-bag every member of it is the same thing: the onboarding
 * moved.
 */
public enum ObCategory {

    /** Something is now expected of the reader. */
    ASSIGNMENT,

    /** Something is late, refused, or has got worse. */
    ESCALATION,

    /** A deadline is approaching and has not yet been missed. */
    REMINDER,

    /** Progress worth knowing about. No tab; see the class note. */
    UPDATE;

    /**
     * Tolerant read, {@link ObChannel#of(String)}'s contract: a stored value
     * this build does not know is rendered under All rather than thrown on.
     * Losing the tab is better than losing the notification.
     */
    public static Optional<ObCategory> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
