package com.edunext.edutrack.domain.notifications.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * D-045 · delivering one encrypted message to one browser.
 *
 * <p>The push service is a third party we cannot see into: it answers a status
 * code and nothing else. The whole job of this class is to turn that code into
 * one of three answers the caller can act on — delivered, this browser is gone,
 * or try again later — because everything else it might do is guesswork.
 */
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    /**
     * How long the push service should hold a message for a browser that is
     * offline, in seconds.
     *
     * <p>Four hours rather than the day a push service will happily keep it. A
     * ticket alert that surfaces the next morning is noise — the bell already
     * has it, and D-046 replays anything missed the moment the tab opens. Push
     * is the interrupt; the record lives elsewhere.
     */
    private static final int TTL_SECONDS = 4 * 60 * 60;

    private final PushEncryption encryption;
    private final VapidSigner vapid;
    private final PushKeys keys;
    private final HttpClient http;

    public WebPushSender(PushEncryption encryption, VapidSigner vapid, PushKeys keys) {
        this(encryption, vapid, keys, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    WebPushSender(PushEncryption encryption, VapidSigner vapid, PushKeys keys, HttpClient http) {
        this.encryption = encryption;
        this.vapid = vapid;
        this.keys = keys;
        this.http = http;
    }

    /**
     * @param payload JSON the service worker will read; keep it short, and
     *                assume a proxy may log its length
     * @return what the caller should do about this subscription next
     */
    public Result send(String endpoint, String p256dh, String authSecret, String payload) {
        if (!keys.configured()) {
            // A deployment with no VAPID pair cannot push and should not
            // pretend to. Silent rather than noisy: this is the normal state of
            // a developer machine, not a fault.
            return Result.NOT_CONFIGURED;
        }

        byte[] body;
        String authorization;
        try {
            body = encryption.encrypt(payload,
                    Base64.getUrlDecoder().decode(p256dh),
                    Base64.getUrlDecoder().decode(authSecret));
            authorization = vapid.authorizationHeader(
                    endpoint, keys.subject(), keys.privateKey(), keys.publicKey());
        } catch (RuntimeException e) {
            // Bad key material on a stored subscription, or a payload too big.
            // Neither is retryable and neither is the push service's fault.
            log.warn("push: could not prepare a message for {}", origin(endpoint), e);
            return Result.UNDELIVERABLE;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("TTL", String.valueOf(TTL_SECONDS))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                // RFC 8030 §5.3. "normal" wakes a sleeping device but does not
                // claim the priority of a call — an SLA breach is important to
                // the business and not to the battery.
                .header("Urgency", "normal")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return interpret(response.statusCode(), endpoint);
        } catch (java.io.IOException e) {
            log.warn("push: {} did not answer", origin(endpoint), e);
            return Result.RETRYABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.RETRYABLE;
        }
    }

    private static Result interpret(int status, String endpoint) {
        // RFC 8030 §5: 201 is the specified success. 200 and 202 are accepted
        // too because push services differ and none of them means anything
        // other than "we have it".
        if (status == 201 || status == 200 || status == 202) {
            return Result.DELIVERED;
        }
        // 404: the subscription never existed here. 410: it did and is now
        // expired — the browser cleared its data, the user revoked permission,
        // or the service rotated it. Both mean the row is dead, and keeping it
        // means pushing into a void forever.
        if (status == 404 || status == 410) {
            return Result.GONE;
        }
        if (status == 429 || status >= 500) {
            log.info("push: {} answered {}, will try again", origin(endpoint), status);
            return Result.RETRYABLE;
        }
        // 400 malformed, 401/403 our VAPID token was rejected, 413 too large.
        // All are ours to fix and none improves by repeating.
        log.warn("push: {} rejected the message with {}", origin(endpoint), status);
        return Result.UNDELIVERABLE;
    }

    /** Never log the full endpoint — its path is the subscription's secret. */
    private static String origin(String endpoint) {
        try {
            return VapidSigner.audienceOf(endpoint);
        } catch (RuntimeException e) {
            return "an unparseable endpoint";
        }
    }

    /** What the caller should do about this subscription next. */
    public enum Result {
        DELIVERED,
        /** Delete the subscription: this browser will never accept another. */
        GONE,
        /** Keep it. The push service is busy or broken, not the subscription. */
        RETRYABLE,
        /** Keep it, but nothing will help until somebody looks. */
        UNDELIVERABLE,
        /** No VAPID pair on this deployment. */
        NOT_CONFIGURED
    }
}
