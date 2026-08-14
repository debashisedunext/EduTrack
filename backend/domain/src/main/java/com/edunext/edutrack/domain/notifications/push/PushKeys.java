package com.edunext.edutrack.domain.notifications.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-045 · the VAPID pair (RFC 8292).
 *
 * <p>Configuration, never committed: {@code edutrack.push.private-key} belongs
 * in the same place as every other secret, and CLAUDE.md's "never commit"
 * list is why there is no default here.
 *
 * <p>Both halves are optional so that a deployment without push configured
 * still starts. That is the normal case for a developer machine, and a startup
 * failure for a feature nobody has switched on would be the wrong trade —
 * {@link #configured()} is what the endpoints ask before answering.
 *
 * @param publicKey  base64url, unpadded, the uncompressed P-256 point. Shipped
 *                   to every browser as {@code applicationServerKey}
 * @param privateKey base64url of the private scalar. Never leaves the server
 * @param subject    the {@code sub} claim of the VAPID JWT — a {@code mailto:}
 *                   or {@code https:} the push service can use to contact us
 *                   about a misbehaving sender
 */
@ConfigurationProperties(prefix = "edutrack.push")
public record PushKeys(String publicKey, String privateKey, String subject) {

    public boolean configured() {
        return publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
