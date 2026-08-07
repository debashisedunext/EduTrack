package com.edunext.edutrack.api.feature.notifications.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies {@code X-Webhook-Signature} on the provider webhooks.
 *
 * <p>This endpoint is unauthenticated by necessity — the mail provider has no
 * EduTrack session — so the signature is the <em>only</em> thing standing
 * between the internet and an API that marks addresses undeliverable. Without
 * it, anyone who can reach the URL can suppress the CEO's address and silently
 * stop every escalation mail to them. That is a denial-of-service with no error
 * anywhere, which is precisely the failure blueprint §17 wants to be provable
 * rather than deniable.
 *
 * <p>Three properties matter here, and each is easy to get wrong:
 *
 * <ul>
 *   <li><strong>Fail closed.</strong> No configured secret means every request
 *       is rejected. The tempting alternative — skip verification when
 *       unconfigured, "just for local" — ships an open endpoint the first time
 *       someone forgets the environment variable in production.</li>
 *   <li><strong>Constant-time comparison.</strong> {@code String.equals} returns
 *       on the first differing byte, which leaks how much of a guessed
 *       signature was right and makes the secret recoverable byte by byte.</li>
 *   <li><strong>The raw body.</strong> The HMAC covers the exact bytes
 *       received, not a re-serialised object — Jackson may reorder keys or
 *       change spacing, and the digest would never match.</li>
 * </ul>
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);

    private final String secret;

    public WebhookSignatureVerifier(@Value("${edutrack.webhooks.email.secret:}") String secret) {
        this.secret = secret;
    }

    /**
     * @param rawBody   the exact bytes of the request body
     * @param signature the {@code X-Webhook-Signature} header, hex-encoded
     * @return true only if the signature is a valid HMAC-SHA256 of the body
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (secret == null || secret.isBlank()) {
            log.error("webhook: edutrack.webhooks.email.secret is not configured — "
                    + "rejecting every webhook. Set it, or the provider cannot reach us.");
            return false;
        }
        if (signature == null || signature.isBlank() || rawBody == null) {
            return false;
        }

        byte[] expected;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            expected = mac.doFinal(rawBody);
        } catch (Exception e) {
            log.error("webhook: could not compute signature", e);
            return false;
        }

        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(stripPrefix(signature));
        } catch (IllegalArgumentException e) {
            // Not hex at all — a malformed signature is a failed signature.
            return false;
        }

        return MessageDigest.isEqual(expected, presented);
    }

    /** Providers commonly prefix the digest, e.g. {@code sha256=abc…}. */
    private static String stripPrefix(String signature) {
        int separator = signature.indexOf('=');
        return separator >= 0 ? signature.substring(separator + 1).trim() : signature.trim();
    }
}
