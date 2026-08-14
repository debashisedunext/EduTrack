package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.domain.notifications.push.PushKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Optional;

/**
 * D-045 · registering and forgetting a browser.
 *
 * <p>Sending is <strong>not</strong> here — that is the second half of D-045
 * and needs RFC 8291's encryption. This is the half that decides who has
 * agreed, which is worth landing on its own: it is the part with the privacy
 * decisions in it, and the sender is untestable until there is something to
 * send to.
 */
@Service
class PushSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionService.class);

    /** Uncompressed P-256 point: 0x04 followed by two 32-byte coordinates. */
    private static final int P256_PUBLIC_KEY_BYTES = 65;

    /** RFC 8291 §3.1 — the auth secret is exactly 16 bytes. */
    private static final int AUTH_SECRET_BYTES = 16;

    private final PushSubscriptionRepository subscriptions;
    private final PushKeys keys;

    PushSubscriptionService(PushSubscriptionRepository subscriptions, PushKeys keys) {
        this.subscriptions = subscriptions;
        this.keys = keys;
    }

    /** Empty when this deployment has no VAPID pair configured. */
    Optional<String> publicKey() {
        return keys.configured() ? Optional.of(keys.publicKey()) : Optional.empty();
    }

    /**
     * @return the field that failed validation, or empty when it saved
     */
    @Transactional
    Optional<String> subscribe(long userId, PushDtos.PushSubscriptionRequest request) {
        if (request == null || request.endpoint() == null || request.endpoint().isBlank()) {
            return Optional.of("endpoint");
        }
        if (request.keys() == null) {
            return Optional.of("keys");
        }
        // Length-checked rather than merely decodable. A p256dh of the wrong
        // size decodes happily and then fails inside the ECDH at send time,
        // where the only symptom is a push that never arrives — which is
        // indistinguishable from a browser that went away.
        if (!isBase64UrlOfLength(request.keys().p256dh(), P256_PUBLIC_KEY_BYTES)) {
            return Optional.of("keys.p256dh");
        }
        if (!isBase64UrlOfLength(request.keys().auth(), AUTH_SECRET_BYTES)) {
            return Optional.of("keys.auth");
        }
        if (request.endpoint().length() > 500) {
            return Optional.of("endpoint");
        }

        subscriptions.save(userId, request.endpoint(),
                request.keys().p256dh(), request.keys().auth(),
                trimToColumn(request.userAgent()));
        return Optional.empty();
    }

    @Transactional
    void unsubscribe(long userId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        int deleted = subscriptions.delete(userId, endpoint);
        if (deleted == 0) {
            // Not an error. A browser whose subscription the push service
            // expired — and which the sender therefore already dropped — is
            // asking for a state that already holds.
            log.debug("push: nothing to unsubscribe for user {}", userId);
        }
    }

    private static boolean isBase64UrlOfLength(String value, int expectedBytes) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(value).length == expectedBytes;
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    /**
     * The column is 255 and a user agent can exceed it. Truncating beats
     * rejecting: the string is a label to help somebody recognise their own
     * laptop, and refusing a subscription over it would deny push to whichever
     * browser happens to be most verbose about itself.
     */
    private static String trimToColumn(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() <= 255 ? userAgent : userAgent.substring(0, 255);
    }

}
