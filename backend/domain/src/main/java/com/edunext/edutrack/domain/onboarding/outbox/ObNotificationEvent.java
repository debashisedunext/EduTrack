package com.edunext.edutrack.domain.onboarding.outbox;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-111 · the onboarding notification catalogue — every event
 * {@code ob_notification_outbox.event_key} may carry, and the payload
 * variables each one promises.
 *
 * <p>{@link ObNotification#eventKey()} is a String because A-107's column is
 * one, and B-110 left the catalogue to this task deliberately: "free text at
 * this layer, bounded by the column (60); B-111 and B-113 own the catalogue
 * and its templates". This is that catalogue. An enqueuer writes
 * {@code ObNotificationEvent.TAT_BREACHED.key()} rather than the string
 * {@code "TAT_BREACHED"}, so a typo is a compile error instead of a mail
 * nothing has wording for.
 *
 * <h2>It lives in domain because two modules need the same list</h2>
 *
 * <p>Exactly {@link com.edunext.edutrack.domain.notifications.MergeTag}'s
 * position on the ticketing side. The enqueuers are in {@code api} and in
 * {@code worker} — C's TAT scanner, B's client and sign-off features, D-102's
 * escalation events — and the renderer that has to have wording for each of
 * them is in {@code worker}. A copy in each is a catalogue that drifts, and it
 * drifts in the direction where something is queued that no template covers.
 *
 * <h2>The variables are a contract, and it is checked from both ends</h2>
 *
 * <p>{@link #requiredVariables()} is what a template may rely on and therefore
 * what an enqueuer must put in the payload; {@link #optionalVariables()} is
 * what it may add. The catalogue test in {@code worker} asserts no template
 * references a name outside those two sets — which is how
 * "template says {@code step_title}, enqueuer sends {@code stepTitle}" is
 * caught at build time rather than in somebody's inbox.
 *
 * <p><strong>An absent variable is never an error.</strong> Rendering follows
 * the rule D-029 settled for ticket mail: a missing value renders as nothing,
 * never as {@code {{braces}}} and never as a refusal to send. "Required" here
 * means the wording reads badly without it, so the renderer falls back to a
 * static subject — not that the mail is withheld. §7.7's guarantee is that
 * mail goes.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No manager digest. B-114 composes a list of stuck journeys, and a list is
 * not a {@code {{variable}}} — that task owns its own body and its own entry
 * here if it wants one.
 *
 * <p>No IN_APP titles. B-112 decided what an onboarding bell entry looks like
 * and put the wording in {@code worker}'s {@code ObInAppTemplate}, beside the
 * mail wording it mirrors — what came back here is {@link #category()} alone,
 * because a tab is a property of the event and not of one screen's copy.
 */
public enum ObNotificationEvent {

    // ─────────────────────────────────────────────── the client's own account
    //
    // §7's "client login created / password reset". B-126 owns the sending.

    /**
     * A portal login now exists for this client's primary SPOC.
     *
     * <p><strong>No password variable, and that is deliberate.</strong> The
     * payload is stored as JSON on a queue row that outlives the send, so a
     * temporary password in it is a live credential sitting in the database
     * indefinitely — the shape STREAM-A-PLATFORM.md:127 already flags for the
     * ticketing reset link. The mail carries the username and a single-use
     * set-password link that B-126 mints; the credential itself never enters
     * the queue.
     */
    CLIENT_LOGIN_CREATED(ObCategory.UPDATE,
            vars("client_name", "portal_username", "action_url"),
            vars("contact_name")),

    /** A portal password reset the SPOC asked for. The link is the whole mail. */
    CLIENT_PASSWORD_RESET(ObCategory.UPDATE,
            vars("client_name", "action_url"),
            vars("contact_name", "link_expires_in")),

    // ─────────────────────────────────────────────────────────── prerequisites
    //
    // §5 item 4's flow: submitted → verified, or returned with a comment.

    /** A client has submitted a prerequisite task. Goes to the verifier. */
    PREREQ_SUBMITTED(ObCategory.ASSIGNMENT,
            vars("client_name", "prereq_title", "action_url"),
            vars("submitted_by", "product_name")),

    /** A prerequisite passed verification. Goes to the SPOC. */
    PREREQ_VERIFIED(ObCategory.UPDATE,
            vars("client_name", "prereq_title"),
            vars("action_url", "verified_by")),

    /** A prerequisite came back for more work. The reason is the point of the mail. */
    PREREQ_RETURNED(ObCategory.UPDATE,
            vars("client_name", "prereq_title", "action_url"),
            vars("return_reason", "returned_by")),

    /** A prerequisite is due or overdue. Client-attributed time, so it goes to the SPOC. */
    PREREQ_TAT_REMINDER(ObCategory.REMINDER,
            vars("client_name", "prereq_title", "due_on"),
            vars("action_url", "overdue_by")),

    /**
     * The prerequisite gate has cleared and the client's journeys have started
     * (§5.3). Goes to the SPOC <em>and</em> to the service owners, which is why
     * this event has wording for both audiences.
     */
    GATE_OPENED(ObCategory.UPDATE,
            vars("client_name", "action_url"),
            vars("journey_count", "product_names", "first_step_title")),

    // ──────────────────────────────────────────────────── the journey's steps

    /** A step has activated and somebody owns it. Goes to that owner. */
    STEP_ASSIGNED(ObCategory.ASSIGNMENT,
            vars("client_name", "step_title", "due_on", "action_url"),
            vars("product_name", "step_description")),

    /** A step is approaching its TAT. Goes to the owner while they can still act. */
    TAT_REMINDER(ObCategory.REMINDER,
            vars("client_name", "step_title", "due_on", "action_url"),
            vars("product_name", "time_left")),

    /** A step has passed its TAT. The overrun is the subject line. */
    TAT_BREACHED(ObCategory.ESCALATION,
            vars("client_name", "step_title", "overdue_by", "action_url"),
            vars("product_name", "due_on", "owner_name")),

    /**
     * The scanner's L1 → L2 → L3 ladder (§5.11). {@code escalation_level} is
     * the rung, and it is required: "escalated" without saying how far tells a
     * manager nothing they can act on.
     */
    ESCALATION_RAISED(ObCategory.ESCALATION,
            vars("client_name", "step_title", "escalation_level", "action_url"),
            vars("product_name", "overdue_by", "owner_name")),

    /** A non-mandatory step was skipped. Goes to the manager, with the reason. */
    STEP_SKIPPED(ObCategory.UPDATE,
            vars("client_name", "step_title", "skipped_by", "action_url"),
            vars("product_name", "skip_reason")),

    /** A held journey's dependency has completed and it has started (§5 item 5). */
    JOURNEY_UNBLOCKED(ObCategory.ASSIGNMENT,
            vars("client_name", "product_name", "action_url"),
            vars("depends_on_product", "first_step_title")),

    // ───────────────────────────────────────────── the client's own escalation
    //
    // A-128's ob_client_escalations, raised from the portal. §7 marks this one
    // immediate and unmutable — it is the client telling us something is wrong.

    /** Raised by the client from the portal. Goes to the manager and the service owner. */
    CLIENT_ESCALATION_RAISED(ObCategory.ESCALATION,
            vars("client_name", "escalation_comment", "action_url"),
            vars("product_name", "raised_by", "raised_at")),

    /** Resolved by staff. Goes back to the SPOC who raised it. */
    CLIENT_ESCALATION_RESOLVED(ObCategory.UPDATE,
            vars("client_name", "action_url"),
            vars("product_name", "resolution_note", "resolved_by")),

    // ───────────────────────────────────────────────────── sign-off and go-live

    /**
     * A sign-off request (§8). {@code action_url} carries A-121's one-time
     * token and is therefore required — a sign-off mail with no link is a mail
     * the recipient cannot act on at all.
     */
    SIGNOFF_REQUESTED(ObCategory.UPDATE,
            vars("client_name", "step_title", "action_url"),
            vars("product_name", "requested_by", "link_expires_in")),

    /**
     * The one-time password for a sign-off in progress (§8: "sent separately at
     * the moment of signing").
     *
     * <p><strong>The code is in the payload, and A-121 owns how long it stays
     * there.</strong> Unlike {@link #CLIENT_LOGIN_CREATED} there is no
     * link-shaped alternative — an OTP that is not in the mail is not an OTP.
     * A sent row keeps its payload, so the code remains readable in
     * {@code ob_notification_outbox} after it has expired for every other
     * purpose. Raised with A-121 rather than decided here.
     */
    SIGNOFF_OTP(ObCategory.UPDATE,
            vars("client_name", "otp_code"),
            vars("step_title", "otp_expires_in")),

    /** A client objected instead of signing (§8, B-117). Goes to the step owner. */
    SIGNOFF_OBJECTED(ObCategory.ESCALATION,
            vars("client_name", "step_title", "action_url"),
            vars("product_name", "objection_reason", "objected_by")),

    /** Go-live (§5.9). Goes to the SPOC and to the staff who got them there. */
    GO_LIVE(ObCategory.UPDATE,
            vars("client_name"),
            vars("action_url", "live_on", "product_names", "support_contact"));

    /** {@code ob_notification_outbox.event_key VARCHAR(60)} — {@link ObNotification#EVENT_KEY_MAX}. */
    private static final int KEY_MAX = ObNotification.EVENT_KEY_MAX;

    private final ObCategory category;
    private final Set<String> requiredVariables;
    private final Set<String> optionalVariables;

    ObNotificationEvent(ObCategory category,
                        Set<String> requiredVariables,
                        Set<String> optionalVariables) {
        this.category = category;
        this.requiredVariables = requiredVariables;
        this.optionalVariables = optionalVariables;
    }

    /**
     * B-112 · what kind of thing happened, which is what OB-13's tabs group on
     * and what {@code ob_notifications.category} stores.
     *
     * <p>Declared on the event rather than chosen by the enqueuer, for
     * {@link #key()}'s reason: a category picked at the call site is a category
     * two enqueuers of the same event will eventually disagree about, and the
     * disagreement shows up as one TAT breach under Escalations and the next
     * under All.
     */
    public ObCategory category() {
        return category;
    }

    /**
     * The value stored in {@code event_key}.
     *
     * <p>The constant's own name, with no mapping table. A second spelling is
     * one more thing that can disagree with the database, and the rows already
     * written by A-101's fixture corpus and quoted in the migration's column
     * comment are these names.
     */
    public String key() {
        return name();
    }

    /** What the wording relies on, and therefore what an enqueuer must supply. */
    public Set<String> requiredVariables() {
        return requiredVariables;
    }

    /** What a template may use if the enqueuer has it. */
    public Set<String> optionalVariables() {
        return optionalVariables;
    }

    /** Every variable name this event admits, required or not. */
    public Set<String> variables() {
        Set<String> all = new LinkedHashSet<>(requiredVariables);
        all.addAll(optionalVariables);
        return Set.copyOf(all);
    }

    /**
     * Tolerant read, {@link ObChannel#of(String)}'s contract: a row carrying an
     * event this build has never heard of is <em>rendered generically</em>, not
     * thrown on. A queue drained by an older worker than the one that filled it
     * is a normal deploy, not a corruption.
     */
    public static Optional<ObNotificationEvent> of(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(key.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static Set<String> vars(String... names) {
        return Set.of(names);
    }

    static int keyMax() {
        return KEY_MAX;
    }

    /** All of them, in declaration order — for the catalogue test and for B-113's seed. */
    public static Set<ObNotificationEvent> all() {
        return new LinkedHashSet<>(Arrays.asList(values()));
    }
}
