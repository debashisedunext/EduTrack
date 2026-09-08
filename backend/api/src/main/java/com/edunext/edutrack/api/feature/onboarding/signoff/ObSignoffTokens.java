package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.auth.Digests;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A-120 · resolving a sign-off link's token, and refusing every way it can be
 * wrong with the same answer.
 *
 * <h2>One outcome for four different failures</h2>
 *
 * <p>The contract states this once for the whole public surface and it is the
 * property this class exists to hold: <b>a bad token, an expired token, a
 * cancelled sign-off and a token that never existed all answer the same
 * thing.</b> Distinguishing them turns the surface into an oracle — somebody
 * holding a list of guesses learns which ones name a real sign-off, which is
 * enumeration however carefully the message is worded.
 *
 * <p>So this returns {@link Optional#empty()} for all of them, and the callers
 * have nothing to branch on. The caller who deserves a specific answer, the
 * real contact, has an email telling them what to do.
 *
 * <h2>The plaintext is never stored, and never logged</h2>
 *
 * <p>{@code ob_signoffs.token_hash} is a SHA-256 and A-107's migration says
 * why: our own database must not be able to yield a working link. This class is
 * the only thing that turns a plaintext back into a row, it hashes on the way
 * in, and it does not log the token on any path — an expired-token warning
 * carrying the value would put a live-until-yesterday credential in a log file
 * that outlives it.
 *
 * <h2>Why the expiry is checked here rather than in a query</h2>
 *
 * <p>A {@code where token_hash = ? and token_expires_at > now()} would be one
 * query and would answer empty for an expired token, which is the right
 * outcome. It is not used because the same predicate then has to be repeated in
 * every finder that ever looks a token up, and the one that forgets it is a
 * working link that never expires. One lookup, one place that judges freshness.
 *
 * <p>The clock is injected for the reason {@code ObReportService}'s was, the
 * hard way: a default window cannot be asserted against a clock that only moves
 * forwards, and reading {@code Instant.now()} inside a method that a test needs
 * to place either side of an expiry makes that test a date-dependent flake.
 */
@Component
public class ObSignoffTokens {

    private final ObSignoffRepository signoffs;
    private final Clock clock;

    /**
     * <p><b>{@code @Autowired} is load-bearing, not decoration.</b> This class
     * has two constructors and no {@code Clock} bean exists in the context, so
     * without an explicit choice Spring falls back to a no-arg constructor that
     * is not here — and the whole application context fails to start, taking
     * every {@code @SpringBootTest} with it. The failure reads "No default
     * constructor found", which names the constructor that is missing rather
     * than the ambiguity that caused it to be looked for.
     */
    @Autowired
    ObSignoffTokens(ObSignoffRepository signoffs) {
        this(signoffs, Clock.systemUTC());
    }

    /**
     * Test seam, and the reason is {@code ObReportService}'s, learned the hard
     * way: an expiry cannot be asserted against a clock that only moves
     * forwards, and a test that has to sit either side of one becomes a
     * date-dependent flake the day it is written.
     */
    ObSignoffTokens(ObSignoffRepository signoffs, Clock clock) {
        this.signoffs = signoffs;
        this.clock = clock;
    }

    /**
     * The sign-off a link addresses, if the link is usable at all.
     *
     * @param token the plaintext from the request body — never a path or query
     *              parameter, for the reason the contract gives: a URL carrying
     *              it lands in browser history, in the {@code Referer} of every
     *              asset the page loads, and in every access log between here
     *              and the client
     * @return empty for an unknown, malformed, expired, cancelled or
     *         already-decided token, indistinguishably
     */
    @Transactional(readOnly = true)
    public Optional<ObSignoff> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return signoffs.findByTokenHash(Digests.sha256Hex(token.trim()))
                .filter(this::isUsable);
    }

    /**
     * Whether a resolved row can still be acted on.
     *
     * <p>{@code PENDING} only. A {@code SIGNED} or {@code OBJECTED} sign-off
     * has had its decision made and the token is spent — "single-use" is
     * enforced by the status, not by deleting the hash, so the row still says
     * what happened and when. A {@code CANCELLED} one was withdrawn by the
     * organisation and must read exactly like a token that never existed.
     */
    private boolean isUsable(ObSignoff signoff) {
        Instant now = clock.instant();
        return signoff.getStatus() == ObSignoffStatus.PENDING
                && signoff.getTokenExpiresAt() != null
                && signoff.getTokenExpiresAt().isAfter(now);
    }
}
