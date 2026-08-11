package com.edunext.edutrack.api.feature.notifications;

/**
 * D-045 · the wire shapes for push subscription.
 *
 * <p>The request mirrors what {@code PushSubscription.toJSON()} produces in the
 * browser, nesting and all. Flattening it would mean the client had to
 * rearrange the object the Web Push API just handed it, which is an invitation
 * to put {@code auth} where {@code p256dh} belongs.
 */
final class PushDtos {

    private PushDtos() {
    }

    record PushPublicKeyResponse(Data data) {
        record Data(String publicKey) {
        }

        static PushPublicKeyResponse of(String publicKey) {
            return new PushPublicKeyResponse(new Data(publicKey));
        }
    }

    record PushSubscriptionRequest(String endpoint, Keys keys, String userAgent) {
        record Keys(String p256dh, String auth) {
        }
    }
}
