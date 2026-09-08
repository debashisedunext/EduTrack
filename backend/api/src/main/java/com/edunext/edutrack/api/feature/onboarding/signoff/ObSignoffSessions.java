package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.OptionalLong;

/**
 * A-121 · the thing a verified OTP buys: a short-lived right to act on one
 * sign-off.
 *
 * <h2>Not a JWT, and not a principal</h2>
 *
 * <p>The contract is explicit and it is worth restating where somebody might
 * be tempted: this is "opaque, it is good for this one sign-off, it expires in
 * minutes, and it is not a principal — it authorises three operations on one
 * row and nothing else". A JWT here would be a credential the application
 * cannot withdraw, carrying claims that invite a filter to start treating its
 * bearer as a user. A CLIENT login that can read journeys is A-125's
 * {@code client_accounts}, which is a different thing with a different
 * lifetime.
 *
 * <p>So: a random string, a Redis key, and one number behind it. There is
 * nothing in the token to parse and nothing to trust in what a caller sends
 * back — the only fact recoverable from it is which sign-off it was minted
 * for, and only while the key lives.
 *
 * <h2>Redis rather than the row, which is the opposite of {@code otp_attempts}</h2>
 *
 * <p>A-107 puts the attempt counter in the table because "a lockout that
 * resets when the process restarts is not a lockout". The session is the
 * mirror image: it should <em>not</em> outlive an outage, its whole life is a
 * few minutes, and expiring it is Redis's default behaviour rather than a
 * sweeper somebody has to write. A column would need one.
 *
 * <p><b>Redis being unavailable fails the verify.</b> This is the one place on
 * the public surface that does not degrade open, and the asymmetry is
 * deliberate: {@code ObSignoffRateLimiter} allows the request when Redis is
 * down because refusing would look like every client's link having expired at
 * once, whereas minting a session we cannot store would hand back a token that
 * is guaranteed not to work. Failing the request says so honestly instead of
 * ten seconds later on the accept.
 *
 * <h2>The stored key is a hash of the token</h2>
 *
 * <p>{@code ObSignoffRateLimiter} hashes before keying for the same reason and
 * says it: a Redis dump should not yield working credentials any more than a
 * database dump does.
 */
@Component
public class ObSignoffSessions {

    static final String PREFIX = "edutrack:ob-signoff-session:";

    /**
     * Fifteen minutes. Long enough to read a checklist and decide, short
     * enough that a token left in a closed tab is not a standing right to
     * accept on the client's behalf tomorrow.
     */
    static final Duration TTL = Duration.ofMinutes(15);

    /** 256 bits, the size {@code ob_signoffs.token_hash} protects. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;

    ObSignoffSessions(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Mints a session for a sign-off and returns the plaintext, which is the
     * only time it exists outside the caller's hands.
     */
    public Minted mint(long signoffId) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);

        redis.opsForValue().set(key(token), Long.toString(signoffId), TTL);
        return new Minted(token, TTL);
    }

    /**
     * The sign-off a session token addresses, or empty.
     *
     * <p>Empty for unknown, expired and malformed alike — the accept, object
     * and csat routes answer "unknown, expired, or already used. One generic
     * body", and a resolver that distinguished them would make writing that
     * one body harder than writing three.
     *
     * <p>Public because B-115's accept and B-117's object consume what this
     * mints. They must not read the Redis key themselves: the prefix, the
     * hashing and the TTL are one decision, and a second reader is a second
     * place for them to drift.
     */
    public OptionalLong resolve(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return OptionalLong.empty();
        }
        String signoffId = redis.opsForValue().get(key(sessionToken.trim()));
        if (signoffId == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(signoffId));
        } catch (NumberFormatException e) {
            // Only reachable if something else wrote this key. Treated as no
            // session rather than a 500: the caller gets the same generic
            // refusal every other failure gets.
            return OptionalLong.empty();
        }
    }

    /**
     * Spends a session, so accepting twice on one token is not possible.
     *
     * <p>For B's accept and object to call once they have committed. Not called
     * here — verifying an OTP mints a session, it does not consume one.
     */
    public void invalidate(String sessionToken) {
        if (sessionToken != null && !sessionToken.isBlank()) {
            redis.delete(key(sessionToken.trim()));
        }
    }

    private static String key(String sessionToken) {
        return PREFIX + Digests.sha256Hex(sessionToken);
    }

    /** The plaintext and how long it is good for, which is what OB-09 needs. */
    public record Minted(String token, Duration ttl) {
    }
}
