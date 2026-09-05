package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;

import java.util.Arrays;
import java.util.Optional;

/**
 * B-112 · the wording of every onboarding bell entry, one constant per event.
 *
 * <h2>Staff wording only, because there is only a staff bell</h2>
 *
 * <p>{@link ObMailTemplate} carries a constant per (event, <em>audience</em>)
 * because §7's mail goes to client SPOCs as well as to staff. This one does
 * not, and the reason is in the migration: {@code ob_notifications} has a
 * single {@code recipient_user_id}, OB-13 is a staff screen, and §9's portal
 * screens have no notification centre for a client entry to appear on. An
 * {@code IN_APP} row addressed to a contact is refused by
 * {@link InAppChannelAdapter} rather than written somewhere nobody looks.
 *
 * <p>So every event below is worded <em>for the staff reader</em>, including
 * the ones whose mail only ever goes to a client. That is not padding: a
 * verifier watching a client's onboarding wants to see that a prerequisite
 * came back, and the queue admits any (event, channel, recipient) combination
 * an enqueuer chooses. A catalogue with holes would mean the generic notice
 * for a case somebody deliberately queued.
 *
 * <h2>Two lines, so a lost value falls back rather than dropping</h2>
 *
 * <p>B-111 substitutes mail bodies paragraph by paragraph and drops any
 * paragraph that lost a value — right for prose, where the rest of the mail
 * still reads. A bell entry is a title and one line; dropping the line leaves
 * an entry that says less than the event it announces, and dropping the title
 * leaves nothing at all. So both carry a static fallback — the rule
 * {@link ObMailTemplate#fallbackSubject()} already applies to subjects, applied
 * to both halves here for the same reason: each is one line and every word in
 * it is load-bearing.
 *
 * <p>Plain text, not HTML. The bell renders these as text; a template that
 * smuggled markup in would be either escaped or, worse, not.
 *
 * <h2>No {@code otp_code}, and it is asserted rather than remembered</h2>
 *
 * <p>{@link ObNotificationEvent#SIGNOFF_OTP} declares one, and a one-time
 * password belongs in the mail it was minted for and nowhere else — least of
 * all in a list that sits open on a shared screen and is still readable
 * tomorrow. The constant below says a code was sent and does not repeat it,
 * and {@code ObInAppTemplateCatalogueTest} fails the build if any template ever
 * references it. The same shape as B-111's rule that no client-facing template
 * names an owner.
 *
 * <h2>Constants now, configuration later</h2>
 *
 * <p>{@link ObMailTemplate}'s own boundary: B-113 owns OB-12 and replaces
 * {@link #forEvent(ObNotificationEvent)} with a lookup falling back to these.
 * Nothing else about rendering moves.
 */
enum ObInAppTemplate {

    // ─────────────────────────────────────────────── the client's own account

    LOGIN_CREATED(ObNotificationEvent.CLIENT_LOGIN_CREATED,
            "Portal login created for {{client_name}}",
            "A client portal login was created",
            "They can now sign in as {{portal_username}} and follow their implementation.",
            "The client can now sign in and follow their implementation."),

    PASSWORD_RESET(ObNotificationEvent.CLIENT_PASSWORD_RESET,
            "Portal password reset for {{client_name}}",
            "A client portal password was reset",
            "A reset link has gone to their primary contact.",
            "A reset link has gone to the primary contact."),

    // ─────────────────────────────────────────────────────────── prerequisites

    PREREQ_SUBMITTED(ObNotificationEvent.PREREQ_SUBMITTED,
            "Ready to verify: {{prereq_title}}",
            "A prerequisite is ready to verify",
            "{{client_name}} has submitted it. Their clock is paused while it waits with us.",
            "A client has submitted it. Their clock is paused while it waits with us."),

    PREREQ_VERIFIED(ObNotificationEvent.PREREQ_VERIFIED,
            "Verified: {{prereq_title}}",
            "A prerequisite has been verified",
            "The submission from {{client_name}} passed verification.",
            "The submission passed verification."),

    PREREQ_RETURNED(ObNotificationEvent.PREREQ_RETURNED,
            "Returned to {{client_name}}: {{prereq_title}}",
            "A prerequisite has been returned to the client",
            "It is back with them for a change, and their clock is running again.",
            "It is back with the client for a change, and their clock is running again."),

    PREREQ_TAT_REMINDER(ObNotificationEvent.PREREQ_TAT_REMINDER,
            "Still outstanding: {{prereq_title}} — {{client_name}}",
            "A client prerequisite is still outstanding",
            "Due {{due_on}}. The gate stays shut until every mandatory task clears.",
            "The gate stays shut until every mandatory task clears."),

    // ──────────────────────────────────────────────────── the journey's steps

    GATE_OPENED(ObNotificationEvent.GATE_OPENED,
            "{{client_name}} has cleared prerequisites",
            "A client has cleared their prerequisites",
            "Their journeys have started and the first services are now running.",
            "Their journeys have started and the first services are now running."),

    STEP_ASSIGNED(ObNotificationEvent.STEP_ASSIGNED,
            "{{step_title}} — {{client_name}}",
            "A service has been assigned to you",
            "Assigned to you, due {{due_on}}.",
            "It is assigned to you."),

    TAT_REMINDER(ObNotificationEvent.TAT_REMINDER,
            "Due {{due_on}}: {{step_title}}",
            "One of your services is approaching its TAT",
            "{{client_name}} is waiting on this one.",
            "A client is waiting on this one."),

    TAT_BREACHED(ObNotificationEvent.TAT_BREACHED,
            "Overdue by {{overdue_by}}: {{step_title}}",
            "One of your services has passed its TAT",
            "The onboarding for {{client_name}} is held up until this closes.",
            "The onboarding is held up until this closes."),

    ESCALATION_RAISED(ObNotificationEvent.ESCALATION_RAISED,
            "Escalated to {{escalation_level}}: {{step_title}}",
            "A service has been escalated",
            "{{client_name}}. It has been overdue long enough to climb the matrix.",
            "It has been overdue long enough to climb the escalation matrix."),

    STEP_SKIPPED(ObNotificationEvent.STEP_SKIPPED,
            "Skipped: {{step_title}} — {{client_name}}",
            "A non-mandatory service was skipped",
            "Skipped by {{skipped_by}}.",
            "A non-mandatory service was skipped on this journey."),

    JOURNEY_UNBLOCKED(ObNotificationEvent.JOURNEY_UNBLOCKED,
            "{{product_name}} has started for {{client_name}}",
            "A held journey has started",
            "What it was waiting on is complete, so its first service is now yours.",
            "What it was waiting on is complete, so its first service is now yours."),

    // ───────────────────────────────────────────────────────── the daily digest

    /**
     * B-114's digest, worded as a bell entry.
     *
     * <p><strong>Nothing queues this on IN_APP today, and the constant is not
     * padding.</strong> B-114 sends the digest by mail only. But the catalogue
     * has no holes by design — the queue admits any (event, channel,
     * recipient) an enqueuer chooses, and the alternative to a wording here is
     * the generic notice for a case somebody deliberately queued. The class
     * note makes the same argument for the events whose mail only ever reaches
     * a client.
     *
     * <p>The count is the whole entry, because the list is not something a
     * bell row can hold: {@code ObDigestBody}'s table lives in the mail, and a
     * bell entry's job is to get the reader to the module. Neither line
     * references {@link ObNotificationEvent#STUCK_ROWS} — a JSON array
     * rendered into one line would print as {@code [{client=…}]}.
     */
    MANAGER_DIGEST(ObNotificationEvent.MANAGER_DIGEST,
            "{{stuck_count}} stuck across {{client_count}} client(s)",
            "Onboarding work has stopped moving",
            "None of it has raised an alert of its own — it has been still for longer than {{threshold}}.",
            "None of it has raised an alert of its own. The mail lists what and for how long."),

    // ───────────────────────────────────────────── the client's own escalation

    CLIENT_ESCALATION_RAISED(ObNotificationEvent.CLIENT_ESCALATION_RAISED,
            "{{client_name}} has raised an escalation",
            "A client has raised an escalation",
            "{{escalation_comment}}",
            "They raised it from the portal, against a running service."),

    CLIENT_ESCALATION_RESOLVED(ObNotificationEvent.CLIENT_ESCALATION_RESOLVED,
            "Escalation resolved: {{client_name}}",
            "A client escalation has been resolved",
            "They have been told it is closed.",
            "The client has been told it is closed."),

    // ──────────────────────────────────────────────────── sign-off and go-live

    SIGNOFF_REQUESTED(ObNotificationEvent.SIGNOFF_REQUESTED,
            "Sign-off requested: {{step_title}}",
            "A sign-off has been requested",
            "{{client_name}} has the link. The service waits until they answer.",
            "The client has the link. The service waits until they answer."),

    /**
     * Says a code went out and deliberately does not carry it. See the class
     * note; {@code ObInAppTemplateCatalogueTest} enforces it.
     */
    SIGNOFF_OTP(ObNotificationEvent.SIGNOFF_OTP,
            "One-time code sent to {{client_name}}",
            "A sign-off one-time code was sent",
            "It reaches them by mail and expires on its own.",
            "It reaches them by mail and expires on its own."),

    SIGNOFF_OBJECTED(ObNotificationEvent.SIGNOFF_OBJECTED,
            "Objection on {{step_title}} — {{client_name}}",
            "A client has objected instead of signing",
            "The service is back in progress and our clock has resumed.",
            "The service is back in progress and our clock has resumed."),

    GO_LIVE(ObNotificationEvent.GO_LIVE,
            "{{client_name}} is live",
            "A client has gone live",
            "Every journey is complete and signed off. Handover to support can begin.",
            "Every journey is complete and signed off. Handover to support can begin.");

    private final ObNotificationEvent event;
    private final String title;
    private final String fallbackTitle;
    private final String body;
    private final String fallbackBody;

    ObInAppTemplate(ObNotificationEvent event,
                    String title,
                    String fallbackTitle,
                    String body,
                    String fallbackBody) {
        this.event = event;
        this.title = title;
        this.fallbackTitle = fallbackTitle;
        this.body = body;
        this.fallbackBody = fallbackBody;
    }

    ObNotificationEvent event() {
        return event;
    }

    /** Interpolated when every placeholder resolves. {@code ob_notifications.title} is 200. */
    String title() {
        return title;
    }

    /** Used instead when it does not. No placeholders, by construction. */
    String fallbackTitle() {
        return fallbackTitle;
    }

    String body() {
        return body;
    }

    String fallbackBody() {
        return fallbackBody;
    }

    /**
     * @return empty for an event this build has no wording for — the renderer
     *         writes the generic notice rather than nothing, on
     *         {@link ObNotificationEvent#of(String)}'s tolerant-read contract
     */
    static Optional<ObInAppTemplate> forEvent(ObNotificationEvent event) {
        return Arrays.stream(values())
                .filter(t -> t.event == event)
                .findFirst();
    }
}
