package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.security.ClientAddress;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * A-120 · the one door onto the public sign-off surface.
 *
 * <h2>Every public route goes through here, and that is the point</h2>
 *
 * <p>The contract lists four properties that "hold across all four operations
 * and are stated once here rather than four times below". Stating them once in
 * prose is not the same as enforcing them once in code: four controllers each
 * remembering to rate-limit, and each remembering to answer the same 401, is
 * four chances to get it wrong and one review away from a route that answers
 * "expired" to a caller who is probing.
 *
 * <p>So the properties live here. {@link #require} is the only way to turn a
 * presented token into an {@link ObSignoff}, and it applies the limit and the
 * refusal on the way past. A-121's OTP routes and the accept/object routes call
 * it; none of them decides any of this for itself.
 *
 * <h2>The order matters: limit first, look up second</h2>
 *
 * <p>Rate limiting before resolution is what stops the 429 becoming its own
 * oracle. If the token were resolved first, an invalid one could be refused
 * without spending budget — and a caller would learn, from the fact that they
 * were never throttled, that none of their guesses had ever matched. Spending
 * first makes a wrong token cost exactly what a right one costs.
 *
 * <h2>Timing is not addressed here, and that is honest rather than overlooked</h2>
 *
 * <p>The lookup is a hash comparison performed by MySQL on an indexed column,
 * so a hit and a miss do not take usefully different times at this scale. What
 * this class does not do is equalise the work <em>after</em> resolution — an
 * accepted token goes on to do real work and a refused one does not. That
 * difference is observable in principle and is not worth chasing: the token is
 * 256 bits, so the timing channel would have to distinguish guesses nobody can
 * make in the first place.
 */
@Component
public class PublicSignoffAccess {

    private final ObSignoffTokens tokens;
    private final ObSignoffRateLimiter rateLimiter;

    PublicSignoffAccess(ObSignoffTokens tokens, ObSignoffRateLimiter rateLimiter) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Resolves a presented token, or refuses.
     *
     * @throws SignoffRateLimitedException  if this token or this source has
     *                                      spent its budget — raised before the
     *                                      token is looked at, so it says
     *                                      nothing about whether one exists
     * @throws InvalidSignoffTokenException for every other failure, identically
     */
    public ObSignoff require(String token, HttpServletRequest request) {
        Optional<Duration> retryAfter = rateLimiter.checkAndSpend(token, source(request));
        if (retryAfter.isPresent()) {
            throw new SignoffRateLimitedException(retryAfter.get());
        }
        return tokens.resolve(token).orElseThrow(InvalidSignoffTokenException::new);
    }

    /**
     * Spends the budget and reports whether the token resolves, without
     * throwing on a miss.
     *
     * <p>For {@code requestObSignoffOtp}, which the contract requires to answer
     * {@code 202} "for an unknown, expired, cancelled or already-signed token
     * too — deliberately indistinguishable from the successful case". A route
     * that must not distinguish cannot be built on a method that throws, and
     * building it on a caught exception invites somebody to log the difference.
     *
     * <p>The rate limit still applies, and still throws: being told to slow
     * down is not the same as being told whether a token is real.
     */
    public Optional<ObSignoff> resolveQuietly(String token, HttpServletRequest request) {
        Optional<Duration> retryAfter = rateLimiter.checkAndSpend(token, source(request));
        if (retryAfter.isPresent()) {
            throw new SignoffRateLimitedException(retryAfter.get());
        }
        return tokens.resolve(token);
    }

    /**
     * The rate-limit key for "who is asking".
     *
     * <p>{@code ClientAddress} is A-074's, which already reads the proxy
     * headers the deployment trusts rather than {@code getRemoteAddr} — using
     * the raw socket address here would key every request behind a load
     * balancer to the same bucket and turn the per-source budget into a global
     * one.
     */
    private static String source(HttpServletRequest request) {
        return request == null ? ClientAddress.UNKNOWN : ClientAddress.of(request);
    }
}
