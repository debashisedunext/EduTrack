package com.edunext.edutrack.domain.notifications;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-022 · who a notification goes to — blueprint §11's "To" column as a closed
 * vocabulary.
 *
 * <p><b>Added by Stream B, in Stream D's package, on the same footing as
 * {@link MergeTag} and for the same reason:</b> {@code api} validates what S-15
 * saves and {@code worker} resolves it to addresses at send time, and neither
 * module can see the other. Needs Debashis's sign-off — D-040 is the task that
 * turns each of these into an actual query.
 *
 * <h2>Why this is not a foreign key into {@code roles}</h2>
 *
 * <p>Blueprint §4B.6 asks for a "per-role recipient list", and taking that
 * literally produces the wrong table. §11's own "To" column names ten things,
 * and exactly two of them — {@link #PROJECT_MANAGER} and
 * {@link #REPORTING_MANAGER} — are anything like a role code. The other eight
 * are <em>positions relative to one ticket</em>, resolved per send:
 * {@link #ASSIGNEE} is a column on {@code tickets}, {@link #WATCHERS} is a join
 * table, {@link #MENTIONED_USER} comes out of the comment that triggered the
 * event, and {@link #CLIENT_CONTACT} is not a platform user at all — which is
 * why {@code email_log.to_user_id} is nullable.
 *
 * <p>A join onto {@code roles} could carry two of the ten. The eight that did
 * not fit would need somewhere second to live, and one list would become two
 * that have to be read together and kept in step. So all ten are here.
 *
 * <p><b>{@link #PROJECT_MANAGER} and {@link #REPORTING_MANAGER} are still not
 * role lookups even though they share a spelling with one.</b> §11 means "the
 * PM <em>of this ticket's project</em>" and "the reporting manager <em>of this
 * ticket's assignee</em>" — two joins, both starting from the ticket. Mailing
 * everybody holding the PM role on a breach would notify every project's manager
 * about one project's ticket.
 *
 * <h2>Resolution is D-040's, not this enum's</h2>
 *
 * <p>What is fixed here is the vocabulary, so that the master and the sender
 * cannot disagree about the spelling — the same division {@link
 * NotificationEvent} draws in its own javadoc. Every value resolves to zero
 * recipients under some conditions (an unassigned ticket has no assignee, an
 * internal ticket has no client contact), and that is a normal send of nothing
 * rather than an error.
 */
public enum NotificationRecipient {

    /** The ticket's current assignee. Empty on an unassigned ticket. */
    ASSIGNEE,

    /**
     * Whoever owns the ribbon's current stage — §11's "new stage owner".
     *
     * <p>Usually the assignee and deliberately not the same token. A handoff
     * notifies the person the ticket has moved <em>to</em>, which at the moment
     * the event fires may not yet be who {@code tickets.assigned_to} names.
     */
    STAGE_OWNER,

    /** Who the ticket moved away from — §11's "reassigned away" row. */
    PREVIOUS_ASSIGNEE,

    /** Who raised it. Not the assignee, and not always a colleague. */
    REPORTER,

    /** The PM of <em>this ticket's</em> project — one person, not the role. */
    PROJECT_MANAGER,

    /** The reporting manager of <em>this ticket's assignee</em> — §6's rung. */
    REPORTING_MANAGER,

    /** The support desk members mapped to this ticket's project. */
    SUPPORT_DESK,

    /** Everybody watching the ticket, however they came to be watching it. */
    WATCHERS,

    /** The user named in the {@code @mention} that raised the event. */
    MENTIONED_USER,

    /**
     * The client's contacts who have opted in to notifications.
     *
     * <p>The one value that reaches outside the organisation, and the reason
     * {@code email_log.to_user_id} is nullable. A template carrying this is a
     * template whose wording is read by a customer — S-15 says so on the row.
     */
    CLIENT_CONTACT,

    /**
     * Whoever asked for the thing being answered — the manager who raised a
     * status request (D-055), notified when it is replied to.
     */
    REQUESTER,

    /** Every active user. The daily digest, and nothing else so far. */
    ALL_USERS,

    /**
     * Every user holding the Admin role.
     *
     * <p>The one value that <em>is</em> a role lookup, and it is here rather
     * than as a role foreign key because it is one member of a vocabulary whose
     * other twelve are not. §4B.6 gives it two jobs — a suppressed address and a
     * bounce webhook both "alert the Admin" — and A-044's chain-verification
     * alarm is a third.
     */
    ADMIN;

    public static Optional<NotificationRecipient> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /**
     * Splits the stored {@code recipients} column.
     *
     * <p><b>Tolerant on read, strict on write</b> — the rule {@link
     * NotificationEvent} states and this follows. A code this build does not
     * know is skipped rather than thrown on, so a template written by a newer
     * deploy still sends to the recipients that <em>are</em> understood instead
     * of failing the whole send. The write side refuses the unknown token in the
     * first place, which is where a typo can still be corrected by the person
     * who made it.
     *
     * <p>De-duplicated and insertion-ordered: {@code ASSIGNEE,ASSIGNEE} is one
     * recipient, and one mail.
     */
    public static List<NotificationRecipient> parse(String stored) {
        Set<NotificationRecipient> found = new LinkedHashSet<>();
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        for (String token : stored.split(",")) {
            of(token).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /** The column form — {@code ASSIGNEE,PROJECT_MANAGER}, no spaces. */
    public static String join(List<NotificationRecipient> recipients) {
        List<String> codes = new ArrayList<>(recipients.size());
        for (NotificationRecipient recipient : new LinkedHashSet<>(recipients)) {
            codes.add(recipient.name());
        }
        return String.join(",", codes);
    }
}
