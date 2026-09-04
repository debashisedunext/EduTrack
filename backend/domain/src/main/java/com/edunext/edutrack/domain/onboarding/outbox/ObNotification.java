package com.edunext.edutrack.domain.onboarding.outbox;

import java.util.Map;

/**
 * B-110 · one onboarding notification to queue.
 *
 * <p>This is the <em>event</em>, not the message. A-107's migration is explicit
 * that {@code payload} holds "the rendered message's variables, not the
 * rendered message": B-111 renders from a template at send time, so a template
 * correction reaches everything still queued. Callers therefore hand over the
 * facts — client name, step title, due date, a link — and never a subject line
 * or a body.
 *
 * @param eventKey   which §7 event this is, e.g. {@code SIGNOFF_REQUESTED}.
 *                   Free text at this layer, bounded by the column (60); B-111
 *                   and B-113 own the catalogue and its templates
 * @param channel    how it should leave
 * @param recipient  who it is for — a staff user or a client contact
 * @param obClientId context for the deep link and the dashboard drill-down;
 *                   nullable, a "client login created" notice may have no
 *                   client yet and a digest has no single one
 * @param journeyId  nullable for the same reason
 * @param stepId     nullable for the same reason
 * @param payload    template variables, stored as JSON; may be empty
 * @param dedupeKey  what makes two queued copies of this event the same event.
 *                   Unique among rows still in the queue only, so a genuine
 *                   repeat later — a second TAT reminder — is allowed once the
 *                   first has left. Callers compose it from the event and the
 *                   thing it is about, e.g. {@code TAT_REMINDER:EMAIL:step:412:contact:9}
 */
public record ObNotification(
        String eventKey,
        ObChannel channel,
        ObRecipient recipient,
        Long obClientId,
        Long journeyId,
        Long stepId,
        Map<String, Object> payload,
        String dedupeKey) {

    /** {@code ob_notification_outbox.event_key VARCHAR(60)}. */
    static final int EVENT_KEY_MAX = 60;
    /** {@code ob_notification_outbox.dedupe_key VARCHAR(200)}. */
    static final int DEDUPE_KEY_MAX = 200;

    public ObNotification {
        if (eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException("eventKey is required");
        }
        if (eventKey.length() > EVENT_KEY_MAX) {
            throw new IllegalArgumentException("eventKey exceeds " + EVENT_KEY_MAX + " characters");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        if (recipient == null) {
            throw new IllegalArgumentException("recipient is required");
        }
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey is required");
        }
        if (dedupeKey.length() > DEDUPE_KEY_MAX) {
            throw new IllegalArgumentException("dedupeKey exceeds " + DEDUPE_KEY_MAX + " characters");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /**
     * The common shape: an event about one step, to one recipient, deduped on
     * exactly that. Two scanner passes overlapping on the same step and the
     * same person produce one row.
     */
    public static ObNotification aboutStep(String eventKey, ObChannel channel, ObRecipient recipient,
                                           long obClientId, long journeyId, long stepId,
                                           Map<String, Object> payload) {
        return new ObNotification(eventKey, channel, recipient, obClientId, journeyId, stepId, payload,
                dedupeKeyFor(eventKey, channel, "step", stepId, recipient));
    }

    /** An event about a client as a whole — a login created, an escalation raised. */
    public static ObNotification aboutClient(String eventKey, ObChannel channel, ObRecipient recipient,
                                             long obClientId, Map<String, Object> payload) {
        return new ObNotification(eventKey, channel, recipient, obClientId, null, null, payload,
                dedupeKeyFor(eventKey, channel, "client", obClientId, recipient));
    }

    /**
     * The dedupe convention the two factories share, public so a caller with
     * an unusual subject — a digest, a prerequisite — stays consistent with it
     * rather than inventing a second format.
     */
    public static String dedupeKeyFor(String eventKey, ObChannel channel, String subjectKind,
                                      long subjectId, ObRecipient recipient) {
        String who = switch (recipient) {
            case ObRecipient.Staff s -> "user:" + s.userId();
            case ObRecipient.Client c -> "contact:" + c.contactId();
        };
        return eventKey + ':' + channel.name() + ':' + subjectKind + ':' + subjectId + ':' + who;
    }
}
