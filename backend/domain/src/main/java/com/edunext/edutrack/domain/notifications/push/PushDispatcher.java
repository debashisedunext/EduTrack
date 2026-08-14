package com.edunext.edutrack.domain.notifications.push;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import com.edunext.edutrack.domain.notifications.NotificationRaised;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * D-045 · the sending half — a notification reaches the browsers that asked for
 * it, and a browser that has gone away stops being asked.
 *
 * <h2>After commit, never inside</h2>
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT}. A push is an HTTP request to a third
 * party: doing it inside the business transaction holds row locks open across
 * somebody else's network, and a transaction that then rolled back would already
 * have interrupted somebody about work that did not happen. The bell entry is
 * the record; this is an interrupt about it, and an interrupt that arrives for a
 * thing that never occurred is worse than one that never arrives.
 *
 * <p>{@code fallbackExecution = false} is the default and is right: a
 * notification written outside any transaction still publishes the event, and
 * Spring simply runs the listener inline. What must not happen is a listener
 * firing for a transaction that rolled back.
 *
 * <h2>A dead subscription is deleted, not retried</h2>
 *
 * <p>404 and 410 from a push service are the service telling us this browser
 * will never accept another message — the user cleared their data, revoked
 * permission, or the service rotated the endpoint. Without acting on that the
 * table fills with browsers that no longer exist, every one of which costs an
 * HTTP round trip on every notification forever. That is the specific failure
 * the backlog names for this half of the task.
 *
 * <p>Everything else is left alone. A 429 or a 5xx is the service being busy, a
 * 4xx of ours is a bug to fix, and deleting somebody's subscription because a
 * push service had a bad minute would silently opt them out of a channel they
 * chose.
 *
 * <h2>Failure never propagates</h2>
 *
 * <p>The notification is already written and is the record. A browser being
 * unreachable must not surface as an error in the request that raised it —
 * §7.7 makes mail the guaranteed channel, not this one. But every drop is
 * logged, because "a missed alert should be provable rather than deniable"
 * (§17) applies to the channel that did not deliver as much as to the one that
 * did.
 */
@Component
@Lazy
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final PushSubscriptions subscriptions;
    private final NotificationPreferences preferences;
    private final WebPushSender sender;

    PushDispatcher(PushSubscriptions subscriptions,
                   NotificationPreferences preferences,
                   WebPushSender sender) {
        this.subscriptions = subscriptions;
        this.preferences = preferences;
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(NotificationRaised raised) {
        try {
            deliver(raised);
        } catch (RuntimeException e) {
            // An AFTER_COMMIT listener that throws cannot undo the commit, but
            // it can abort whatever else is listening. Nothing about a browser
            // is worth that.
            log.error("push: could not deliver notification {} to user {}",
                    raised.notificationId(), raised.userId(), e);
        }
    }

    private void deliver(NotificationRaised raised) {
        // D-042. Push is a real preference, unlike mail: §7.7 gives the
        // guarantee to email, so nothing here is mandatory and D-036's lock
        // deliberately does not extend to this channel. The bell entry is
        // already written either way — a preference silences the interrupt,
        // never the record.
        if (!preferences.allows(raised.userId(), raised.event().name(), NotificationChannel.PUSH)) {
            return;
        }

        List<PushSubscriptions.Subscription> browsers = subscriptions.forUser(raised.userId());
        if (browsers.isEmpty()) {
            return;
        }

        String payload = payloadOf(raised);
        for (PushSubscriptions.Subscription browser : browsers) {
            dispatch(raised, browser, payload);
        }
    }

    private void dispatch(NotificationRaised raised,
                          PushSubscriptions.Subscription browser,
                          String payload) {
        WebPushSender.Result result = sender.send(
                browser.endpoint(), browser.p256dh(), browser.authSecret(), payload);

        switch (result) {
            case DELIVERED -> subscriptions.touch(browser.endpoint());
            case GONE -> {
                // The whole reason this half of the task exists.
                boolean removed = subscriptions.deleteByEndpoint(browser.endpoint());
                log.info("push: a subscription was reported gone and {}",
                        removed ? "has been deleted" : "had already been removed");
            }
            case RETRYABLE -> log.info(
                    "push: notification {} deferred by the push service; the bell entry stands",
                    raised.notificationId());
            case UNDELIVERABLE -> log.warn(
                    "push: notification {} was refused and will not be retried", raised.notificationId());
            case NOT_CONFIGURED -> {
                // No VAPID pair. The normal state of a developer machine, and
                // logging it per notification would drown the log.
            }
        }
    }

    /**
     * What the service worker reads.
     *
     * <p><strong>Carries the title and body that are already in the bell
     * entry, and nothing more.</strong> No ticket code beyond what the title
     * says, no comment text, no client name — a push is rendered by the
     * operating system, outside the application, and it lands on a lock screen
     * that anybody standing nearby can read. D-052 makes the same argument for
     * a mention notification carrying no message text, and the surface here is
     * less private than a bell.
     *
     * <p>Hand-built rather than run through Jackson because the shape is four
     * fields and adding a serialiser to {@code domain} for it would invite the
     * next person to put an entity in here.
     */
    private static String payloadOf(NotificationRaised raised) {
        return "{\"id\":" + raised.notificationId()
                + ",\"title\":" + quote(raised.title())
                + ",\"body\":" + quote(raised.body())
                + ",\"link\":" + quote(raised.linkUrl()) + "}";
    }

    /** JSON string escaping for text that is ours but is not trusted to be quote-free. */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
