package com.edunext.edutrack.api.feature.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A-023 · the refresh token's home in Redis. Blueprint §10.1, "store refresh
 * jti in Redis (device-bound)".
 *
 * <p><b>Redis and not MySQL,</b> for the reason the blueprint picks it: the key
 * carries its own TTL, so a token expires without anything having to sweep for
 * it. A {@code refresh_tokens} table would need a scheduled delete, and the day
 * that job fails is the day expired sessions quietly keep working — a failure
 * that is invisible until it is audited. Here, expiry is the storage layer's
 * job and cannot silently stop happening.
 *
 * <p><b>The key is {@code SHA-256(token)}, not the token.</b> Redis persists to
 * disk, is backed up, and is the thing most likely to be inspected with a
 * console open during an incident. Keying on the raw value would mean anyone
 * who ever sees that data holds seven days of live sessions. Hashing costs a
 * microsecond and removes the entire class of exposure — the same reasoning
 * that stops us storing passwords, applied to a credential that is every bit as
 * usable. See {@link Digests} for why this is SHA-256 and not Argon2id.
 *
 * <p><b>There is no {@code update}.</b> A refresh token is never edited; A-024
 * rotates by writing a new key and revoking the old one. The read half is here
 * because a store that cannot be read back cannot be proven to have written
 * anything — {@code RefreshTokenStoreIT} uses it, and A-024/A-025 are its
 * production callers.
 *
 * <p><b>A-024 · three key spaces, one meaning each.</b> Rotation needs to tell
 * three states apart, and conflating any two of them breaks the guarantee:
 *
 * <ul>
 *   <li>{@code edutrack:refresh:<h>} — a <i>live</i> token. Present means
 *       presentable.</li>
 *   <li>{@code edutrack:refresh-consumed:<h>} — this token was rotated away, and
 *       here is the family it belonged to. <b>This is what makes theft
 *       detectable at all.</b> The obvious rotation deletes the old key, and a
 *       deleted key is indistinguishable from one that never existed — so a
 *       stolen token replayed after the victim's browser has rotated looks
 *       exactly like a typo, and §10.1's whole reuse rule becomes unenforceable.
 *       The marker is the memory that the token was real.</li>
 *   <li>{@code edutrack:refresh-family-revoked:<familyId>} — a tombstone. Once
 *       reuse is detected, every <i>other</i> member of the family is still
 *       sitting in Redis, live and valid. Enumerating and deleting them would
 *       need a family→members index that has to be kept in step with every
 *       rotation, and the day it drifts a revoked token keeps working. A
 *       tombstone inverts that: nothing has to be found, and the check is
 *       positive — a family is usable only while no tombstone says otherwise.</li>
 * </ul>
 *
 * <p>Distinct prefixes rather than nesting under {@link #KEY_PREFIX}, so no
 * hashed key can ever collide with a marker key, and so a {@code SCAN} during an
 * incident reads as three separate things.
 */
@Component
class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    /**
     * Namespaced because this Redis is shared — D-012's realtime relay channel
     * and the worker's ShedLock keys live in the same keyspace, and an
     * un-prefixed key is how two subsystems come to collide over one name.
     */
    static final String KEY_PREFIX = "edutrack:refresh:";

    /** A-024 · "this token was rotated away". Value is the family id. */
    static final String CONSUMED_PREFIX = "edutrack:refresh-consumed:";

    /** A-024 · "this family was revoked". Value is a marker; only presence matters. */
    static final String FAMILY_REVOKED_PREFIX = "edutrack:refresh-family-revoked:";

    /**
     * A-027 · "every session this user had before instant T is over". Value is
     * the cutoff, as epoch milliseconds.
     */
    static final String SESSIONS_REVOKED_PREFIX = "edutrack:sessions-revoked-before:";

    private static final String TOMBSTONE = "revoked";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    RefreshTokenStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /**
     * Writes the token with an expiry taken from the record itself, so the
     * Redis TTL and {@link StoredRefreshToken#expiresAt()} cannot disagree.
     *
     * <p>Throws on an unreachable broker rather than swallowing it — whether a
     * failure to persist should fail the login is a policy decision, and it is
     * made in {@link RefreshTokenIssuer}, not here.
     */
    void save(String tokenValue, StoredRefreshToken token) {
        Duration ttl = Duration.between(Instant.now(), token.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("refresh token expiresAt is not in the future");
        }
        redis.opsForValue().set(keyFor(tokenValue), write(token), ttl);
    }

    /** Empty when the token is unknown, already revoked, or has expired. */
    Optional<StoredRefreshToken> find(String tokenValue) {
        String stored = redis.opsForValue().get(keyFor(tokenValue));
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(stored, StoredRefreshToken.class));
        } catch (JsonProcessingException e) {
            // A record this process cannot read is a record it cannot honour.
            // Treated as absent — the caller re-authenticates — rather than
            // raised, so one malformed entry left by an older format during a
            // rolling deploy costs one login instead of a 500.
            log.warn("refresh: stored token could not be deserialised, treating as absent", e);
            return Optional.empty();
        }
    }

    // ── A-024 · rotation, reuse detection and family revocation ─────────────

    /**
     * Takes the token out of circulation and reports whether <i>this</i> caller
     * was the one who did it.
     *
     * <p><b>The single most important line in A-024.</b> Two requests carrying
     * the same cookie can arrive at once — two tabs waking together, or a client
     * retrying a refresh whose response was lost. If both read the token as live
     * and both rotate, one session silently forks into two, and the family
     * acquires a member nobody can account for. A read-then-delete cannot
     * prevent that: the gap between the two is exactly where the second request
     * fits.
     *
     * <p>{@code DEL} is atomic and reports whether it removed anything, so
     * exactly one caller can ever be told {@code true} for a given token. Every
     * other caller is, by definition, presenting a token that has already been
     * consumed — which is the same fact reuse detection acts on, and is handled
     * identically. Claiming is therefore the only place rotation is allowed to
     * begin.
     *
     * @return true if this call removed a live token; false if it was already gone
     */
    boolean claim(String tokenValue) {
        return Boolean.TRUE.equals(redis.delete(keyFor(tokenValue)));
    }

    /**
     * Records that a token was rotated away, so a later presentation of it is
     * recognisable as reuse rather than as an unknown value.
     *
     * <p>The marker outlives nothing: its TTL is what remained of the token's
     * own life, because after that point the token is refused as expired anyway
     * and there is no decision left for the marker to inform.
     *
     * <p>Only the family id is stored. Detection needs to answer one question —
     * "which family does this belong to?" — and anything else kept here would be
     * a second copy of state that can disagree with the live record.
     */
    void markConsumed(String tokenValue, String familyId, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // The token expired between being read and being rotated. Nothing to
            // remember: a presentation of it now is refused as expired regardless.
            return;
        }
        redis.opsForValue().set(CONSUMED_PREFIX + Digests.sha256Hex(tokenValue), familyId, ttl);
    }

    /** The family a consumed token belonged to, or empty if it was never issued. */
    Optional<String> findConsumedFamily(String tokenValue) {
        return Optional.ofNullable(
                redis.opsForValue().get(CONSUMED_PREFIX + Digests.sha256Hex(tokenValue)));
    }

    /**
     * Ends every session descending from one login. §10.1: "re-use of a consumed
     * refresh token ⇒ token theft ⇒ revoke the whole family and force re-login".
     *
     * <p>The TTL is the configured maximum refresh lifetime rather than the
     * family's actual remaining life. That is a deliberate over-estimate: the
     * exact deadline would have to be carried in the consumed marker, giving a
     * second copy of an expiry that can disagree with the live record, and a
     * tombstone that expires <i>early</i> resurrects a family that was revoked
     * for theft. One over-long tombstone costs a few bytes; one short tombstone
     * costs the guarantee.
     */
    void revokeFamily(String familyId, Duration ttl) {
        redis.opsForValue().set(FAMILY_REVOKED_PREFIX + familyId, TOMBSTONE, ttl);
    }

    /**
     * Checked on every refresh, before the token is honoured. Positive rather
     * than negative on purpose — a family is usable only while nothing says it
     * is not, so a lookup that fails to find the tombstone for the wrong reason
     * still has to say "revoked" is unknown rather than "not revoked" is proven.
     * (An unreachable Redis throws here and the refresh fails; see
     * {@link RefreshRotationService}.)
     */
    boolean isFamilyRevoked(String familyId) {
        return Boolean.TRUE.equals(redis.hasKey(FAMILY_REVOKED_PREFIX + familyId));
    }

    /**
     * Removes a live token without recording it as consumed — for refusals that
     * are <i>not</i> reuse (a revoked family, a deactivated user). Marking those
     * consumed would make the next presentation look like theft and revoke a
     * family for a reason that was never theft.
     */
    void discard(String tokenValue) {
        redis.delete(keyFor(tokenValue));
    }

    // ── A-027 · revoking every session a user has ───────────────────────────

    /**
     * Ends <b>all</b> of one user's sessions at once — every device, every
     * family — by recording an instant before which nothing of theirs is
     * honoured. The contract's promise on {@code POST /auth/reset-password}:
     * "Password changed; all sessions revoked."
     *
     * <h2>Why a cutoff and not a user→families index</h2>
     *
     * <p>The obvious implementation keeps a set of family ids per user, then
     * revokes each. {@link #revokeFamily} already rejects that shape for
     * families, and every word of its reasoning applies here with more force:
     * such an index has to be written at login and kept in step with every
     * rotation and expiry, and <b>the day it drifts, a session that was supposed
     * to be revoked keeps working</b> — silently, and precisely for the user who
     * just reset their password because they believed they were compromised.
     *
     * <p>A cutoff inverts it exactly as the family tombstone does: nothing has
     * to be enumerated, nothing has to be maintained, and the check is positive
     * — a token is honoured only while nothing says otherwise. One key per user,
     * written once, and it cannot fall out of step with a set it does not have.
     *
     * <h2>Why the comparison is against {@code issuedAt}</h2>
     *
     * <p>Every live token at the moment of the reset was issued before it, and
     * the only way to obtain one issued after is to log in again with the new
     * password. A rotation cannot manufacture one: rotating requires presenting
     * a predecessor, and the predecessor is itself refused by this check before
     * any successor is minted. So "issued before the cutoff" is exactly "belongs
     * to a session that existed when the password was reset".
     *
     * <p>The TTL is the maximum refresh lifetime — the longest any token this
     * could need to refuse can survive. Beyond that every affected token is
     * expired on its own terms and the marker has no decision left to inform.
     * Over-long is harmless; short would resurrect the very sessions this ended.
     */
    void revokeSessionsFor(long userId, Instant cutoff, Duration ttl) {
        redis.opsForValue().set(
                SESSIONS_REVOKED_PREFIX + userId, String.valueOf(cutoff.toEpochMilli()), ttl);
    }

    /**
     * The cutoff for this user, or empty if they have never had a bulk
     * revocation — which is almost everyone, almost always.
     *
     * <p>A malformed value is treated as <b>revoked at the epoch's end of time
     * rather than not revoked at all</b>: this is consulted on the refresh path,
     * and the only safe reading of "there is a revocation here but I cannot
     * parse it" is to refuse. Falling through to "not revoked" would un-revoke
     * every session the entry was written to end.
     */
    Optional<Instant> sessionsRevokedBefore(long userId) {
        String stored = redis.opsForValue().get(SESSIONS_REVOKED_PREFIX + userId);
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(stored)));
        } catch (NumberFormatException e) {
            log.warn("auth: unreadable session-revocation cutoff for user {} — "
                    + "refusing every refresh for them until it expires", userId);
            return Optional.of(Instant.MAX);
        }
    }

    /**
     * Exposed so tests can assert on the key shape without duplicating the
     * derivation and thereby proving only that two copies of the same mistake
     * agree.
     */
    static String keyFor(String tokenValue) {
        return KEY_PREFIX + Digests.sha256Hex(tokenValue);
    }

    private String write(StoredRefreshToken token) {
        try {
            return json.writeValueAsString(token);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("refresh token could not be serialised", e);
        }
    }
}
