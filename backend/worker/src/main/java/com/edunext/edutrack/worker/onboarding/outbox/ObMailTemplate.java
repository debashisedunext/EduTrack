package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;

import java.util.Arrays;
import java.util.Optional;

/**
 * B-111 · the wording of every onboarding mail, one constant per
 * (event, audience).
 *
 * <h2>Constants now, configuration later — and that is the task boundary</h2>
 *
 * <p>B-113 is "OB-11 and OB-12 — TAT settings and email templates … as
 * configuration rather than constants", so the storage decision is that task's
 * and the wording is this one's. What B-113 replaces is
 * {@link #forEvent(ObNotificationEvent, ObMailAudience)}: a database lookup
 * falling back to these constants, exactly as {@code MailRenderer} treats
 * B-022's rows on the ticketing side. Nothing else about rendering moves.
 *
 * <p><strong>Not stored in {@code notification_templates}.</strong> That table
 * is keyed (event_code, channel) over the ticketing
 * {@link com.edunext.edutrack.domain.notifications.NotificationEvent} vocabulary,
 * its bodies are validated against
 * {@link com.edunext.edutrack.domain.notifications.MergeTag}'s 22 ticket tags,
 * and B-022's screen lists every row it holds. Onboarding events resolve none
 * of those tags and belong on OB-12, not on S-15. Putting them there would show
 * an Admin twenty rows of onboarding wording on a ticketing screen, validated
 * against a tag list none of it uses.
 *
 * <h2>The placeholder dialect is B-022's, not a second one</h2>
 *
 * <p>{@code {{variable}}}, matched by {@link
 * com.edunext.edutrack.domain.notifications.MergeTag#PLACEHOLDER}. The names
 * come from {@link ObNotificationEvent}'s declared variables rather than from
 * {@code MergeTag} — an onboarding mail has a {@code step_title} and no
 * {@code ticket_id} — but the syntax an Admin will eventually type on OB-12 is
 * the syntax they already type on S-15.
 *
 * <h2>Two subjects per template</h2>
 *
 * <p>{@link #subject()} interpolates; {@link #fallbackSubject()} does not.
 * Rendering a value that is absent produces nothing (D-029's rule, kept), which
 * is right in a body — a fact with no value simply does not appear — and wrong
 * in a subject, where it leaves "Onboarding underway for " with the sentence
 * hanging. So a template whose subject placeholders are not all present uses the
 * static line instead. The mail still goes; §7.7 makes that non-negotiable.
 *
 * <h2>What a client-facing template may say</h2>
 *
 * <p>CP-03 shows a client step status and nothing else — no owner names, no
 * internal comments, no block reasons. The CLIENT constants below hold to that
 * even where the payload carries more: an internal reason that reaches a client
 * by mail has leaked whether or not the portal would have shown it.
 */
enum ObMailTemplate {

    // ─────────────────────────────────────────────── the client's own account

    LOGIN_CREATED(ObNotificationEvent.CLIENT_LOGIN_CREATED, ObMailAudience.CLIENT,
            "Your EduTrack onboarding portal is ready — {{client_name}}",
            "Your EduTrack onboarding portal is ready",
            """
            <p>An onboarding portal has been set up for <strong>{{client_name}}</strong>.
            You can use it to track every step of your implementation, submit what we
            need from you, and sign off each milestone.</p>
            <p>Your username is <strong>{{portal_username}}</strong>. Use the button
            below to set your password — the link works once and then expires.</p>
            """,
            "Set your password", Urgency.NONE),

    PASSWORD_RESET(ObNotificationEvent.CLIENT_PASSWORD_RESET, ObMailAudience.CLIENT,
            "Reset your EduTrack portal password",
            "Reset your EduTrack portal password",
            """
            <p>We received a request to reset the portal password for
            <strong>{{client_name}}</strong>. Use the button below to choose a new one.</p>
            <p>If this was not you, you can ignore this mail — nothing has changed and
            the link expires on its own.</p>
            """,
            "Choose a new password", Urgency.NONE),

    // ─────────────────────────────────────────────────────────── prerequisites

    PREREQ_SUBMITTED(ObNotificationEvent.PREREQ_SUBMITTED, ObMailAudience.STAFF,
            "Ready to verify: {{prereq_title}} — {{client_name}}",
            "A prerequisite is ready to verify",
            """
            <p>{{client_name}} has submitted <strong>{{prereq_title}}</strong> for
            verification. Open the client to review what they sent and either verify it
            or return it with a comment.</p>
            <p>Their onboarding clock is paused while this waits with us.</p>
            """,
            "Review the submission", Urgency.ATTENTION),

    PREREQ_VERIFIED(ObNotificationEvent.PREREQ_VERIFIED, ObMailAudience.CLIENT,
            "Verified: {{prereq_title}}",
            "One of your onboarding tasks has been verified",
            """
            <p>Thank you — <strong>{{prereq_title}}</strong> has been verified and is
            complete. Nothing further is needed from you on this one.</p>
            """,
            "View your onboarding", Urgency.NONE),

    PREREQ_RETURNED(ObNotificationEvent.PREREQ_RETURNED, ObMailAudience.CLIENT,
            "Needs another look: {{prereq_title}}",
            "One of your onboarding tasks needs another look",
            """
            <p>We have returned <strong>{{prereq_title}}</strong> for a small change
            before it can be verified.</p>
            <p>{{return_reason}}</p>
            <p>Open the task to update it and submit it again.</p>
            """,
            "Open the task", Urgency.ATTENTION),

    PREREQ_TAT_REMINDER(ObNotificationEvent.PREREQ_TAT_REMINDER, ObMailAudience.CLIENT,
            "Reminder: {{prereq_title}} is due {{due_on}}",
            "A reminder about one of your onboarding tasks",
            """
            <p>We are still waiting on <strong>{{prereq_title}}</strong> to move your
            onboarding forward. Everything after it is held until this is in.</p>
            <p>If something about the task is unclear, reply to your implementation
            manager and we will sort it out with you.</p>
            """,
            "Open the task", Urgency.ATTENTION),

    GATE_OPENED_CLIENT(ObNotificationEvent.GATE_OPENED, ObMailAudience.CLIENT,
            "Your onboarding has started — {{client_name}}",
            "Your onboarding has started",
            """
            <p>Everything we needed from you is in, and your implementation is now
            underway. You can follow each stage in the portal and you will hear from us
            at every sign-off.</p>
            <p>Nothing is waiting on you right now.</p>
            """,
            "View your onboarding", Urgency.NONE),

    GATE_OPENED_STAFF(ObNotificationEvent.GATE_OPENED, ObMailAudience.STAFF,
            "Prerequisites cleared — {{client_name}} is underway",
            "A client's prerequisites have cleared",
            """
            <p>Every mandatory prerequisite for <strong>{{client_name}}</strong> is
            verified, so their journeys have started and the clock is running.</p>
            <p>The first step is <strong>{{first_step_title}}</strong>.</p>
            """,
            "Open the client", Urgency.NONE),

    // ──────────────────────────────────────────────────── the journey's steps

    STEP_ASSIGNED(ObNotificationEvent.STEP_ASSIGNED, ObMailAudience.STAFF,
            "{{step_title}} is yours — {{client_name}}",
            "An onboarding step has been assigned to you",
            """
            <p><strong>{{step_title}}</strong> has activated on {{client_name}}'s
            onboarding and you own it.</p>
            <p>{{step_description}}</p>
            """,
            "Open the step", Urgency.NONE),

    TAT_REMINDER(ObNotificationEvent.TAT_REMINDER, ObMailAudience.STAFF,
            "Due {{due_on}}: {{step_title}} — {{client_name}}",
            "An onboarding step of yours is due soon",
            """
            <p><strong>{{step_title}}</strong> on {{client_name}}'s onboarding is
            approaching its turnaround time. Closing it today keeps the journey off the
            at-risk board.</p>
            <p>If it is blocked or waiting on the client, record that on the step so the
            clock reflects it.</p>
            """,
            "Open the step", Urgency.ATTENTION),

    TAT_BREACHED(ObNotificationEvent.TAT_BREACHED, ObMailAudience.STAFF,
            "Overdue by {{overdue_by}}: {{step_title}} — {{client_name}}",
            "An onboarding step of yours is overdue",
            """
            <p><strong>{{step_title}}</strong> on {{client_name}}'s onboarding has
            passed its turnaround time and the journey is now showing red.</p>
            <p>Close it, or record the block or client wait that is holding it — an
            unexplained overrun escalates to your manager next.</p>
            """,
            "Open the step", Urgency.BREACH),

    ESCALATION_RAISED(ObNotificationEvent.ESCALATION_RAISED, ObMailAudience.STAFF,
            "Escalated to {{escalation_level}}: {{step_title}} — {{client_name}}",
            "An onboarding step has been escalated",
            """
            <p><strong>{{step_title}}</strong> on {{client_name}}'s onboarding has been
            escalated to <strong>{{escalation_level}}</strong> after running past its
            turnaround time.</p>
            <p>It stays escalated until the step is closed or the delay is recorded
            against a block or a client wait.</p>
            """,
            "Open the step", Urgency.BREACH),

    STEP_SKIPPED(ObNotificationEvent.STEP_SKIPPED, ObMailAudience.STAFF,
            "Skipped: {{step_title}} — {{client_name}}",
            "A non-mandatory onboarding step was skipped",
            """
            <p>{{skipped_by}} skipped <strong>{{step_title}}</strong> on
            {{client_name}}'s onboarding. Only non-mandatory steps can be skipped, and
            the reason is recorded on the journey.</p>
            <p>{{skip_reason}}</p>
            """,
            "Open the client", Urgency.NONE),

    JOURNEY_UNBLOCKED(ObNotificationEvent.JOURNEY_UNBLOCKED, ObMailAudience.STAFF,
            "Now unblocked: {{product_name}} for {{client_name}}",
            "A held onboarding journey has unblocked",
            """
            <p><strong>{{product_name}}</strong> for {{client_name}} has started and its
            clock is running.</p>
            <p>It was held until {{depends_on_product}} completed, and that is now done.</p>
            <p>The first step is <strong>{{first_step_title}}</strong>.</p>
            """,
            "Open the client", Urgency.NONE),

    // ───────────────────────────────────────────── the client's own escalation

    CLIENT_ESCALATION_RAISED(ObNotificationEvent.CLIENT_ESCALATION_RAISED, ObMailAudience.STAFF,
            "Client escalation: {{client_name}}",
            "A client has raised an escalation",
            """
            <p><strong>{{client_name}}</strong> has raised an escalation from the portal
            against their onboarding. In their words:</p>
            <p>{{escalation_comment}}</p>
            <p>It shows as a red chip on their onboarding until somebody resolves it.</p>
            """,
            "Open the client", Urgency.BREACH),

    CLIENT_ESCALATION_RESOLVED(ObNotificationEvent.CLIENT_ESCALATION_RESOLVED, ObMailAudience.CLIENT,
            "Your escalation has been resolved — {{client_name}}",
            "Your escalation has been resolved",
            """
            <p>Thank you for flagging it. The escalation you raised on your onboarding
            has been resolved.</p>
            <p>{{resolution_note}}</p>
            <p>If it is not settled from where you are sitting, raise it again and it
            will come straight back to us.</p>
            """,
            "View your onboarding", Urgency.NONE),

    // ───────────────────────────────────────────────────── sign-off and go-live

    SIGNOFF_REQUESTED(ObNotificationEvent.SIGNOFF_REQUESTED, ObMailAudience.CLIENT,
            "Your sign-off is needed: {{step_title}}",
            "Your sign-off is needed on your onboarding",
            """
            <p><strong>{{step_title}}</strong> is complete and needs your sign-off before
            we carry on.</p>
            <p>The button below opens the sign-off page. We will send a one-time code to
            confirm it is you at the moment you sign. If something is not right, you can
            raise an objection there instead and it comes back to us.</p>
            """,
            "Review and sign off", Urgency.ATTENTION),

    SIGNOFF_OTP(ObNotificationEvent.SIGNOFF_OTP, ObMailAudience.CLIENT,
            "Your sign-off code",
            "Your sign-off code",
            """
            <p>Your one-time code is <strong>{{otp_code}}</strong>.</p>
            <p>Enter it on the sign-off page you already have open. We will never ask
            you for this code by phone or by reply.</p>
            """,
            null, Urgency.NONE),

    SIGNOFF_OBJECTED(ObNotificationEvent.SIGNOFF_OBJECTED, ObMailAudience.STAFF,
            "Objection raised: {{step_title}} — {{client_name}}",
            "A client has objected instead of signing off",
            """
            <p>{{client_name}} has raised an objection on <strong>{{step_title}}</strong>
            rather than signing it off, so the step is back in progress and our clock is
            running again.</p>
            <p>{{objection_reason}}</p>
            """,
            "Open the step", Urgency.BREACH),

    GO_LIVE_CLIENT(ObNotificationEvent.GO_LIVE, ObMailAudience.CLIENT,
            "You are live — welcome aboard, {{client_name}}",
            "You are live — welcome aboard",
            """
            <p>Every step of your implementation is complete and signed off, so you are
            live. Thank you for the time your team put into it.</p>
            <p>From here, {{support_contact}} is who to reach for anything you need.</p>
            """,
            "View your onboarding", Urgency.NONE),

    GO_LIVE_STAFF(ObNotificationEvent.GO_LIVE, ObMailAudience.STAFF,
            "Live: {{client_name}}",
            "A client has gone live",
            """
            <p><strong>{{client_name}}</strong> is live — every mandatory step complete
            and every sign-off accepted.</p>
            <p>The support handover note is on the client record.</p>
            """,
            "Open the client", Urgency.NONE);

    /**
     * How loudly the layout says this. A property of the template rather than
     * of the payload, so no enqueuer has to send an urgency and none can send a
     * wrong one: a breach mail is a breach mail whoever queued it.
     *
     * <p>Colours are blueprint §12.1's, the same hex pairs {@code LevelChip}
     * uses on the ticketing side — soft background, solid text, never a heavy
     * solid block. A red band across the top of an alert is what makes people
     * filter the alert.
     */
    enum Urgency {

        /** No chip at all. Most mail is information, not an alarm. */
        NONE(null, null, null),

        /** §12.1 High. Something is due, or waiting on somebody. */
        ATTENTION("Action needed", "#FFFBEB", "#B45309"),

        /** §12.1 Critical. A turnaround time has already been missed. */
        BREACH("Overdue", "#FEF2F2", "#B91C1C");

        private final String label;
        private final String background;
        private final String text;

        Urgency(String label, String background, String text) {
            this.label = label;
            this.background = background;
            this.text = text;
        }

        String label() {
            return label == null ? "" : label;
        }

        String background() {
            return background == null ? "" : background;
        }

        String text() {
            return text == null ? "" : text;
        }
    }

    private final ObNotificationEvent event;
    private final ObMailAudience audience;
    private final String subject;
    private final String fallbackSubject;
    private final String body;
    private final String actionLabel;
    private final Urgency urgency;

    ObMailTemplate(ObNotificationEvent event, ObMailAudience audience,
                   String subject, String fallbackSubject, String body,
                   String actionLabel, Urgency urgency) {
        this.event = event;
        this.audience = audience;
        this.subject = subject;
        this.fallbackSubject = fallbackSubject;
        this.body = body;
        this.actionLabel = actionLabel;
        this.urgency = urgency;
    }

    ObNotificationEvent event() {
        return event;
    }

    ObMailAudience audience() {
        return audience;
    }

    /** May contain placeholders; used only when every one of them resolves. */
    String subject() {
        return subject;
    }

    /** Never contains a placeholder. What is sent when the interpolated one would read broken. */
    String fallbackSubject() {
        return fallbackSubject;
    }

    /** An HTML fragment. Ours, not an Admin's — the values inside it are escaped, the markup is not. */
    String body() {
        return body;
    }

    /**
     * The button's words, or null for a mail that should not carry one.
     *
     * <p>{@link #SIGNOFF_OTP} is the null case and shows why it exists: the
     * recipient already has the sign-off page open, and a second button in the
     * mail carrying the code invites them to start again in a new tab, where the
     * code they were sent no longer matches the attempt they abandoned.
     */
    String actionLabel() {
        return actionLabel;
    }

    Urgency urgency() {
        return urgency;
    }

    /**
     * The wording for this event and this reader.
     *
     * <p>Empty when the catalogue has nothing — a queue row for an event added
     * after this build, or an audience an event was never written for.
     * {@link ObMailRenderer} sends a generic notice in that case rather than
     * dropping the mail.
     */
    static Optional<ObMailTemplate> forEvent(ObNotificationEvent event, ObMailAudience audience) {
        return Arrays.stream(values())
                .filter(t -> t.event == event && t.audience == audience)
                .findFirst();
    }
}
