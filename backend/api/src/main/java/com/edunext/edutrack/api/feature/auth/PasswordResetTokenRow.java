package com.edunext.edutrack.api.feature.auth;

import java.time.Instant;

/**
 * A-027 · one row of {@code password_reset_tokens}, as redemption needs it.
 *
 * <p><b>The hash is not a field.</b> It was the lookup key, and carrying it
 * forward would put a value derived from a live credential into an object that
 * gets logged, compared and eventually serialised by someone. Nothing after the
 * lookup has any use for it.
 *
 * @param expiresAt UTC. 30 minutes after issue, per §10.3.
 * @param usedAt    UTC, or null while the token is still redeemable. This is
 *                  what makes the token single-use — see
 *                  {@link #isRedeemableAt(Instant)}.
 */
record PasswordResetTokenRow(
        long id,
        long userId,
        Instant expiresAt,
        Instant usedAt
) {

    /** Redeemed already. A second click on the same emailed link. */
    boolean isUsed() {
        return usedAt != null;
    }

    /**
     * Past its 30 minutes. Expiry is stored rather than swept, so a token
     * becomes unusable without anything having to run — the same property
     * {@code AuthUserRow#isLockedAt} relies on for lockouts.
     */
    boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    boolean isRedeemableAt(Instant now) {
        return !isUsed() && !isExpiredAt(now);
    }
}
