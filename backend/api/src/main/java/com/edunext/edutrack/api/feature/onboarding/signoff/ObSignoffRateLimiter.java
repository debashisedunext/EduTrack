package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * A-120 · the public sign-off surface's budget. Two of them, per token and per
 * source.
 *
 * <h2>Why two dimensions and not one</h2>
 *
 * <p>They stop different attacks and either alone leaves the other open.
 *
 * <p><b>Per token</b> bounds what somebody holding one leaked link can do with
 * it — request code after code, or grind the OTP. A per-source limit does not
 * bound this at all, because the attacker controls how many sources they come
 * from.
 *
 * <p><b>Per source</b> bounds enumeration: walking a list of guessed tokens to
 * learn which ones exist. A per-token limit does nothing here, since every
 * guess is a different token and each gets its own fresh budget — which is
 * exactly how a per-token-only limiter reads as protection while allowing
 * unlimited scanning.
 *
 * <p>{@code LoginRateLimiter} needs the same pair for the same reason, and its
 * note records the asymmetry: login cannot tell one caller from another except
 * by address. Here the token is the better key of the two, and both are cheap.
 *
 * <h2>Tighter than the authenticated surfaces, on purpose</h2>
 *
 * <p>This is the only unauthenticated tree in the module, it is reachable by
 * anybody with the URL, and a legitimate user hits it a handful of times in a
 * sitting: open the link, ask for a code, type it, decide. Nothing about the
 * honest path needs a generous budget, so the limits are set for the honest
 * path rather than padded for a caller who has no reason to exist.
 *
 * <h2>Both budgets are spent before either is judged</h2>
 *
 * <p>The ordering {@code PasswordResetRateLimiter} settled on. Returning early
 * on the first refusal would leave the second counter unspent, so an attacker
 * who trips the cheap limit deliberately never accumulates against the
 * expensive one — and the limit that was supposed to bound them never fills.
 *
 * <h2>Redis being down allows the request</h2>
 *
 * <p>Same call the auth limiters make, and the same discomfort. Failing closed
 * would make a Redis outage look like every client's sign-off link having
 * expired at once, on the one surface where the person affected is a customer
 * with no support channel except the contact who sent it. Logged at WARN so the
 * degradation is visible rather than silent.
 */
@Component
public class ObSignoffRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ObSignoffRateLimiter.class);

    static final String TOKEN_PREFIX = "edutrack:ob-signoff-rate:token:";
    static final String SOURCE_PREFIX = "edutrack:ob-signoff-rate:source:";

    /**
     * Ten actions against one link in fifteen minutes. The honest path is: open
     * it, request a code, verify, decide — four, with room to mistype twice.
     */
    static final int MAX_PER_TOKEN = 10;
    static final Duration TOKEN_WINDOW = Duration.ofMinutes(15);

    /**
     * Thirty from one address in fifteen minutes. Above a household or an
     * office behind one NAT, below anything that could walk a token space.
     */
    static final int MAX_PER_SOURCE = 30;
    static final Duration SOURCE_WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    ObSignoffRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Spends one unit of each budget and reports the wait if either is gone.
     *
     * @param token  the plaintext from the body — <b>hashed before it is used
     *               as a key</b>, so a Redis dump does not yield working links
     *               any more than a database dump does
     * @param source the caller's address, from {@code ClientAddress}
     * @return empty when the request may proceed; otherwise how long to wait,
     *         for the {@code Retry-After} the contract promises
     */
    public Optional<Duration> checkAndSpend(String token, String source) {
        try {
            Optional<Duration> tokenRetry = spend(
                    TOKEN_PREFIX + Digests.sha256Hex(token == null ? "" : token.trim()),
                    MAX_PER_TOKEN, TOKEN_WINDOW);
            Optional<Duration> sourceRetry = spend(
                    SOURCE_PREFIX + Digests.sha256Hex(source == null ? "" : source),
                    MAX_PER_SOURCE, SOURCE_WINDOW);

            if (tokenRetry.isEmpty()) {
                return sourceRetry;
            }
            if (sourceRetry.isEmpty()) {
                return tokenRetry;
            }
            // The longer of the two: retrying sooner is refused again, and an
            // accurate Retry-After is the difference between a client that backs
            // off and one that hammers.
            return Optional.of(
                    tokenRetry.get().compareTo(sourceRetry.get()) >= 0 ? tokenRetry.get() : sourceRetry.get());
        } catch (DataAccessException e) {
            log.warn("ob-signoff: rate limiting unavailable — allowing the request", e);
            return Optional.empty();
        }
    }

    private Optional<Duration> spend(String key, int limit, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return Optional.empty();
        }

        Long ttlSeconds = redis.getExpire(key);
        if (count == 1 || ttlSeconds == null || ttlSeconds < 0) {
            redis.expire(key, window);
            ttlSeconds = window.toSeconds();
        }

        if (count <= limit) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(Math.max(ttlSeconds, 1)));
    }
}
