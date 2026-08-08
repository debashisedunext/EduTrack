package com.edunext.edutrack.api.feature.auth;

import java.time.Instant;

/**
 * A-023 · what Redis holds for one refresh token. Blueprint §10.1.
 *
 * <p>Note what is <b>not</b> here: the token itself. The opaque value exists in
 * exactly two places — the {@code Set-Cookie} header on the login response, and
 * the browser's cookie jar. Redis holds only {@code SHA-256(value)} as the key
 * (see {@link RefreshTokenStore}) and this record as the payload, so a Redis
 * snapshot, an {@code RDB} file on a backup volume or a stray {@code KEYS *} in
 * someone's debugging session yields no working session. This is the same
 * posture A-027 requires of reset tokens — "single-use, 30-min TTL, hashed at
 * rest".
 *
 * @param jti               identifies this token in logs and in A-025's logout.
 *                          §10.1 names it; the value is never derived from the
 *                          token, so knowing a {@code jti} does not let anyone
 *                          reconstruct the credential.
 * @param userId            who the token authenticates. The refresh endpoint
 *                          (A-024) re-reads the user's scope from this rather
 *                          than trusting stale claims, so a role change or a
 *                          deactivation takes effect at the next refresh instead
 *                          of after seven days.
 * @param familyId          the login this token descends from. A-024 rotates a
 *                          token by issuing a successor in the same family;
 *                          presenting an already-consumed token means the token
 *                          was copied, and the response is to revoke the whole
 *                          family. Stamped <b>here</b>, at login, because that
 *                          is where a family is born — a token issued without
 *                          one could never be joined to its siblings afterwards.
 * @param deviceFingerprint {@link #fingerprintOf(String)} of the {@code
 *                          User-Agent} that logged in.
 * @param issuedAt          UTC, per CLAUDE.md. For a rotated token this is when
 *                          <i>this link</i> was minted, which is also the
 *                          session's last activity — see
 *                          {@link #isIdleAt(Instant, java.time.Duration)}.
 * @param expiresAt         UTC. Redis expires the key on its own at this
 *                          instant, so the field is for A-024's benefit rather
 *                          than a thing anything has to sweep.
 * @param sessionExpiresAt  A-025 · UTC, the §10.1 absolute cap — login plus 12
 *                          hours. Stamped at login and <b>inherited unchanged by
 *                          every rotation</b>, exactly like {@code familyId}: it
 *                          describes the session, not the token. A successor
 *                          that recomputed it would push the deadline forward on
 *                          every refresh and the cap would never be reached,
 *                          which is the same as not having one.
 */
record StoredRefreshToken(
        String jti,
        long userId,
        String familyId,
        String deviceFingerprint,
        Instant issuedAt,
        Instant expiresAt,
        Instant sessionExpiresAt
) {

    /**
     * The "device-bound" half of A-023 — a hash of the {@code User-Agent}.
     *
     * <p><b>Why not the IP address.</b> The obvious binding is the wrong one. A
     * phone moving between wifi and cellular changes IP mid-session, a corporate
     * NAT pool rotates its egress address, and a VPN reconnect looks identical
     * to theft — so an IP-bound refresh token logs honest users out several
     * times a day, and the fix everyone reaches for is to stop enforcing the
     * binding at all. A User-Agent is stable for as long as the browser is, and
     * changes precisely when the cookie has been replayed from somewhere else,
     * which is the signal worth having.
     *
     * <p>It is a weak binding and is not claimed to be more: an attacker
     * holding the cookie can copy the header too. What it defends against is
     * the realistic case — a token lifted into a different tool or a different
     * browser — not a determined replay.
     *
     * <p>Hashed rather than stored raw: equality is the only operation ever
     * performed on it, and a plaintext User-Agent is a fingerprinting datum
     * sitting in a cache for seven days for no operational benefit.
     */
    static String fingerprintOf(String userAgent) {
        return Digests.sha256Hex(userAgent == null ? "" : userAgent.trim());
    }

    /**
     * A-024's hook. Present now, with its own test, so that the check the
     * binding exists for is a method call away rather than something the next
     * task has to remember to invent.
     *
     * <p>Plain equality, not a constant-time compare: this is a hash of a
     * request header the caller already sent, not a secret being verified.
     */
    boolean matchesDevice(String userAgent) {
        return deviceFingerprint.equals(fingerprintOf(userAgent));
    }

    /**
     * A-025 · has the session passed §10.1's 12-hour absolute cap?
     *
     * <p>Separate from {@link #expiresAt}, which bounds this one token. This
     * bounds the whole chain and cannot be extended by using it.
     */
    boolean isSessionExpiredAt(Instant now) {
        return !sessionExpiresAt.isAfter(now);
    }

    /**
     * A-025 · has the session been idle past §10.1's 30-minute window?
     *
     * <p><b>Measured from {@link #issuedAt}, which for a rotated token is the
     * moment of the last successful refresh</b> — so the field doubles as "last
     * activity" without a second one to keep in step. That equivalence holds
     * only because rotation mints a fresh record on every use; if a future change
     * ever updates a token in place instead, this needs its own {@code lastUsedAt}
     * and the two must not be conflated.
     *
     * <p>A client renewing on the natural 15-minute access-token cadence never
     * approaches this. Reaching it means nobody used the session for half an
     * hour, which is the case §10.1 wants closed.
     */
    boolean isIdleAt(Instant now, java.time.Duration idleTimeout) {
        return issuedAt.plus(idleTimeout).isBefore(now);
    }
}
