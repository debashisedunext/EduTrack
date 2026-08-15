package com.edunext.edutrack.domain.notifications;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B-022 · the placeholders a notification template may contain.
 *
 * <p><b>Added by Stream B, in Stream D's package, and it needs Debashis's
 * sign-off</b> — the same position A-044 was in when it added
 * {@code CHAIN_VERIFICATION_FAILED} to {@link NotificationEvent}, and for the
 * same structural reason. This has to live in {@code domain} because two modules
 * need the identical list and neither can see the other: {@code api} validates
 * what an Admin types on S-15, and {@code worker} substitutes it when D-010
 * renders. A copy in each is a catalogue that drifts, and it drifts in the
 * direction where the screen accepts a tag the renderer leaves as literal braces
 * in a client-facing mail.
 *
 * <h2>Why the master validates these at all</h2>
 *
 * <p>Blueprint §4B.6 names five tags — {@code ticket_id}, {@code assignee},
 * {@code stage}, {@code client}, {@code planned_close} — and says templates are
 * "editable by an Admin without a code release". That sentence is the whole
 * point of the screen and it is also the hazard: an Admin who types
 * {@code {{ticketId}}} has written something that is not a tag, and nothing
 * about the save tells them so. The mail goes out with {@code {{ticketId}}}
 * printed in it, to a client, and the first person to notice is the client.
 *
 * <p>So an unknown tag is a 400 at save time naming the ones that exist. The
 * check is over the template body rather than over a picker, because an Admin
 * pasting wording from a document will not have used the picker.
 *
 * <h2>Nothing here is required</h2>
 *
 * <p>A template may use none of these — {@code CHAIN_VERIFICATION_FAILED}'s
 * mail is entirely static prose — and may repeat one. What is refused is a
 * placeholder that resolves to nothing, never the absence of one.
 *
 * <p>The five §4B.6 names are spelled exactly as the blueprint spells them.
 * The rest are the fields §4B.6 lists as a mail's contents ("the level chip,
 * project, client, current stage, planned close date, who acted and what they
 * said") plus what the §4B.6 subject table interpolates — an iteration count, a
 * cycle number, an overdue duration.
 */
public enum MergeTag {

    // ── the five blueprint §4B.6 names by hand ──────────────────────────────
    /** The ticket code, e.g. {@code CRM-26-00347}. Never the numeric id. */
    TICKET_ID("ticket_id"),
    ASSIGNEE("assignee"),
    STAGE("stage"),
    CLIENT("client"),
    PLANNED_CLOSE("planned_close"),

    // ── what §4B.6 says a mail body carries ─────────────────────────────────
    TICKET_TITLE("ticket_title"),
    /** The deep link behind the "Open ticket" button. */
    TICKET_URL("ticket_url"),
    PROJECT("project"),
    LEVEL("level"),
    STATUS("status"),

    /**
     * Who did the thing this notification is about — §4B.6's "who acted".
     *
     * <p>Distinct from {@link #ASSIGNEE} on purpose, and the pair is easy to
     * conflate: on a handoff the actor is the person letting go and the assignee
     * is the person picking up, so a template that used one for the other would
     * tell somebody they had handed a ticket to themselves.
     */
    ACTOR("actor"),

    /** Who this copy is addressed to — the digest's "Good morning …". */
    RECIPIENT("recipient"),

    /** §4B.6's "what they said": the comment, handoff note or reason. */
    COMMENT("comment"),

    ITERATION("iteration"),
    CYCLE("cycle"),

    /** A humanised duration, e.g. "3 days" — never a raw timestamp. */
    OVERDUE_BY("overdue_by"),
    SLA_DUE("sla_due"),

    /** The organisation's name, for mail that belongs to no ticket. */
    ORG("org");

    /**
     * {@code {{ name }}} — braces doubled, optional inner whitespace, and the
     * name captured.
     *
     * <p>Whitespace is tolerated because {@code {{ ticket_id }}} is what
     * somebody pasting from a document produces and refusing it would be a
     * refusal about spacing rather than about spelling. The renderer has to
     * tolerate it identically, which is the second reason this pattern lives
     * here rather than in the validator.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private final String tag;

    MergeTag(String tag) {
        this.tag = tag;
    }

    /** The name as it appears between the braces — {@code ticket_id}. */
    public String tag() {
        return tag;
    }

    /** {@code {{ticket_id}}}, ready to insert into a body. */
    public String placeholder() {
        return "{{" + tag + "}}";
    }

    public static Optional<MergeTag> of(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        String wanted = tag.trim().toLowerCase(Locale.ROOT);
        for (MergeTag candidate : values()) {
            if (candidate.tag.equals(wanted)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Every {@code {{tag}}} in the text that this build does not know.
     *
     * <p>Insertion-ordered and de-duplicated: a body that misspells the same tag
     * four times is one mistake, and reporting it four times would bury the
     * second mistake underneath it. Null and blank text yield an empty set —
     * {@code subject_template} is null on every in-app template, and a caller
     * should not have to guard that.
     *
     * @return the offending names without their braces, in the order they first
     *         appear, so the message reads in the order the Admin will scan
     */
    public static Set<String> unknownIn(String text) {
        Set<String> unknown = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return unknown;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (of(name).isEmpty()) {
                unknown.add(name);
            }
        }
        return unknown;
    }
}
