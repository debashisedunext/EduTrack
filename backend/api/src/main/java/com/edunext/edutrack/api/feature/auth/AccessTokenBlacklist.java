package com.edunext.edutrack.api.feature.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * A-025 · the short-lived Redis blacklist of revoked access token {@code jti}s.
 * Blueprint §10.1: "add access jti to a short-lived Redis blacklist".
 *
 * <p><b>Why this has to exist at all.</b> A JWT is self-describing: it is valid
 * because it is signed and unexpired, not because anything server-side still
 * says so. Logging out deletes the refresh token, which stops the session being
 * <i>renewed</i> — but the access token already in the client's hands keeps
 * working for up to its remaining fifteen minutes. On a shared machine that is
 * fifteen minutes of someone else's session after they pressed Sign out, which
 * is exactly the window a user believes they just closed. The blacklist is the
 * one piece of server-side state that can contradict a signature.
 *
 * <p><b>The TTL is the token's own remaining life, and that is the whole trick.</b>
 * An entry only has to outlive the token it revokes; one second later the token
 * is refused for being expired anyway. So the blacklist is self-limiting — it
 * can never hold more than fifteen minutes of logouts no matter how many people
 * sign out, and it needs no sweeper. A blacklist with a fixed or generous TTL
 * grows without bound and eventually becomes the thing that pages someone.
 *
 * <p><b>Keyed by {@code jti}, not by the token.</b> The {@code jti} is already a
 * random UUID minted per token by {@code AccessTokenIssuer} and is useless to
 * anyone who holds it — unlike the token itself, which is a bearer credential
 * and must not be written into a cache that gets snapshotted and backed up. This
 * is the same reasoning that makes {@link RefreshTokenStore} key on a hash
 * rather than on the refresh value, reached from the other direction: there,
 * hashing was needed because the value <i>was</i> the credential; here a
 * non-credential identifier already exists, so no hashing is required.
 *
 * <p><b>Nothing reads this yet.</b> The reader is A-032's filter chain, which
 * will consult {@link #isRevoked} on every authenticated request. Until it
 * lands, logging out correctly poisons the token and no request notices — the
 * same "hook built, enforcement later" shape as A-023's device fingerprint,
 * which A-024 was then able to enforce without inventing it. Called out here
 * because a blacklist that is written and never read looks like a bug on
 * reading the code, and is instead a deliberate half of a two-task change.
 */
@Component
class AccessTokenBlacklist {

    /**
     * Namespaced like every other key this application owns — the Redis instance
     * is shared with the realtime relay, ShedLock and the refresh registry.
     */
    static final String KEY_PREFIX = "edutrack:access-blacklist:";

    /** Presence is the whole signal; the value is never read. */
    private static final String REVOKED = "1";

    private final StringRedisTemplate redis;

    AccessTokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Revokes one access token for whatever remains of its natural life.
     *
     * <p>A token already at or past its expiry is <b>not</b> written. It is not
     * an error and not worth a branch at the call site — it is simply a no-op,
     * because a zero or negative TTL is rejected by Redis and an entry that
     * outlives the token would be pointless anyway. The token is already
     * unusable; the blacklist has nothing to add.
     *
     * @param jti       the {@code jti} claim of the token being revoked
     * @param expiresAt the token's {@code exp}, which becomes the entry's TTL
     */
    void revoke(String jti, Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        redis.opsForValue().set(keyFor(jti), REVOKED, remaining);
    }

    /**
     * A-032's hook, present with its own test so the filter chain does not have
     * to invent it.
     *
     * <p>Note the direction this answers in: it reports <i>revoked</i>, and the
     * caller must treat an exception as un-answerable rather than as "not
     * revoked". A Redis outage that silently reads as false would un-revoke
     * every logged-out token at once, which is the failure mode this whole
     * class exists to prevent.
     */
    boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(keyFor(jti)));
    }

    /** Exposed so tests assert on the real key rather than on a second copy of the rule. */
    static String keyFor(String jti) {
        return KEY_PREFIX + jti;
    }
}
