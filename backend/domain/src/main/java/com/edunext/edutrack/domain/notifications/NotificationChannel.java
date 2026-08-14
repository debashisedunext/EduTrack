package com.edunext.edutrack.domain.notifications;

import java.util.Optional;

/**
 * D-042 · the ways a notification can reach somebody.
 *
 * <p>Blueprint §7.7 lists five delivery mechanics. Only two are preferences:
 *
 * <ul>
 *   <li><b>The bell badge</b> is not a channel. It counts what was written, and
 *       a preference that emptied it would be hiding the record rather than
 *       quieting a notification — S-26 is where you go to find what you missed.
 *   <li><b>Browser push</b> arrived with D-045's sending half, and is declared
 *       here now that a switch does something. It was deliberately absent
 *       until then: a preference the system cannot act on is worse than a
 *       missing one, because the user believes they have set it.
 *   <li><b>Teams / Slack / WhatsApp</b> are marked optional and have no owner.
 * </ul>
 *
 * <p>So the matrix is in-app popup, email and browser push, and what a
 * preference turns off is the <em>delivery</em>, never the notification row
 * itself.
 */
public enum NotificationChannel {

    /** The D-043 toast. Silencing it does not stop the bell entry being written. */
    IN_APP,

    /** The §4B.6 mail engine. Subject to D-036 — see {@code isMandatoryMail}. */
    EMAIL,

    /**
     * D-045 · the browsers a user has granted permission on.
     *
     * <p><strong>Never mandatory, and D-036's lock deliberately does not reach
     * it.</strong> §7.7 gives the guarantee to mail — "the guaranteed channel"
     * — because a push only reaches a browser that is still subscribed, on a
     * device that is switched on, for a permission the user can revoke in
     * their own settings without telling us. Locking a channel we cannot
     * promise would be a guarantee in name only. An escalation whose push is
     * switched off still sends its mail, which is the promise that was
     * actually made.
     */
    PUSH;

    /**
     * @return empty for a channel this build does not know, so a preference row
     *         written by a newer deploy is ignored rather than fatal — the same
     *         tolerance {@link NotificationEvent#of(String)} applies, for the
     *         same reason.
     */
    public static Optional<NotificationChannel> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
