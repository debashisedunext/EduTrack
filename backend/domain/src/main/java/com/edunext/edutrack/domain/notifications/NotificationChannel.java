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
 *   <li><b>Browser push</b> arrives with D-045, which needs a subscription
 *       before it can need a preference. Declaring it here now would put a
 *       switch in the matrix that does nothing.
 *   <li><b>Teams / Slack / WhatsApp</b> are marked optional and have no owner.
 * </ul>
 *
 * <p>So the matrix is in-app popup versus email, and what a preference turns
 * off is the <em>delivery</em>, never the notification row itself.
 */
public enum NotificationChannel {

    /** The D-043 toast. Silencing it does not stop the bell entry being written. */
    IN_APP,

    /** The §4B.6 mail engine. Subject to D-036 — see {@code isMandatoryMail}. */
    EMAIL;

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
