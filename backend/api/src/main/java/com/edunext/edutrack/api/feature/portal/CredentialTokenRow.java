package com.edunext.edutrack.api.feature.portal;

import java.time.Instant;

/**
 * A-130 · one row of {@code client_credential_tokens}, as the redemption path
 * reads it.
 *
 * <p>The token hash is deliberately <b>not</b> a component. It is what the
 * lookup was keyed on, so a caller already holds it, and carrying it back out
 * would put a credential-shaped value into logs, stack traces and
 * {@code toString()} for no gain.
 *
 * @param usedAt when this link was spent, or null while it is still live.
 *               Judged by {@code PortalCredentialService} rather than by the
 *               query, so an expired link and a spent one can be told apart in
 *               a log even though the caller is told the same thing.
 */
record CredentialTokenRow(long id, long clientAccountId, String purpose,
                          Instant expiresAt, Instant usedAt) {

    boolean spent() {
        return usedAt != null;
    }

    boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** Live, unspent and unexpired — the only state a redemption may proceed from. */
    boolean redeemableAt(Instant now) {
        return !spent() && !expiredAt(now);
    }
}
