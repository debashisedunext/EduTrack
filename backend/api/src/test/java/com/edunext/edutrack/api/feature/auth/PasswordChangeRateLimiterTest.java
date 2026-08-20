package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-074 · the budget for wrong {@code currentPassword} guesses.
 */
class PasswordChangeRateLimiterTest {

    private static final long USER = 42L;
    private static final String KEY = PasswordChangeRateLimiter.KEY_PREFIX + USER;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private PasswordChangeRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        limiter = new PasswordChangeRateLimiter(redis);
    }

    @Nested
    @DisplayName("check")
    class Check {

        @Test
        @DisplayName("allows a caller who has never failed")
        void allowsAFreshCaller() {
            when(values.get(KEY)).thenReturn(null);
            assertThat(limiter.check(USER)).isEmpty();
        }

        @Test
        @DisplayName("allows a caller below the limit")
        void allowsBelowTheLimit() {
            when(values.get(KEY)).thenReturn(String.valueOf(PasswordChangeRateLimiter.MAX_FAILURES - 1));
            assertThat(limiter.check(USER)).isEmpty();
        }

        @Test
        @DisplayName("refuses at the limit, reporting the remaining window")
        void refusesAtTheLimit() {
            when(values.get(KEY)).thenReturn(String.valueOf(PasswordChangeRateLimiter.MAX_FAILURES));
            when(redis.getExpire(KEY)).thenReturn(300L);

            assertThat(limiter.check(USER)).contains(Duration.ofSeconds(300));
        }

        /**
         * A {@code Retry-After} of zero tells a client to retry immediately,
         * which it does, and is refused again. Never report less than a second.
         */
        @Test
        @DisplayName("never reports a zero Retry-After")
        void retryAfterIsNeverZero() {
            when(values.get(KEY)).thenReturn("9");
            when(redis.getExpire(KEY)).thenReturn(0L);

            assertThat(limiter.check(USER)).contains(Duration.ofSeconds(1));
        }

        /**
         * A key with no TTL would otherwise refuse this account for ever. Redis
         * can return -1 for a key that exists without an expiry — an
         * {@code EXPIRE} that failed after the {@code INCR} — and the honest
         * response is to report the full window rather than a negative duration.
         */
        @Test
        @DisplayName("a key with no expiry reports the full window, not a negative one")
        void missingTtlFallsBackToTheWindow() {
            when(values.get(KEY)).thenReturn("9");
            when(redis.getExpire(KEY)).thenReturn(-1L);

            assertThat(limiter.check(USER)).contains(PasswordChangeRateLimiter.WINDOW);
        }

        @Test
        @DisplayName("a value we did not write allows the attempt rather than refusing on it")
        void unreadableCounterAllows() {
            when(values.get(KEY)).thenReturn("not-a-number");
            assertThat(limiter.check(USER)).isEmpty();
        }

        /**
         * Refusing every password change while Redis is down would block the one
         * action a user takes when they think they are compromised, to defend
         * against an attacker who must already hold a valid token.
         */
        @Test
        @DisplayName("fails open when Redis is unreachable")
        void failsOpen() {
            when(values.get(KEY)).thenThrow(new QueryTimeoutException("redis is down"));
            assertThat(limiter.check(USER)).isEmpty();
        }

        /**
         * The counter is spent by {@link PasswordChangeRateLimiter#recordFailure}
         * once a guess is known to be wrong — never by the check itself.
         * Spending here would ration legitimate password changes rather than
         * bounding wrong guesses, and would refuse an administrator rotating
         * several in a sitting.
         */
        @Test
        @DisplayName("reads without spending — a correct change costs nothing")
        void checkDoesNotSpend() {
            when(values.get(KEY)).thenReturn(null);
            limiter.check(USER);

            verify(values, never()).increment(anyString());
        }
    }

    @Nested
    @DisplayName("recordFailure")
    class RecordFailure {

        @Test
        @DisplayName("sets the window on the first failure")
        void firstFailureSetsTheWindow() {
            when(values.increment(KEY)).thenReturn(1L);
            when(redis.getExpire(KEY)).thenReturn(-1L);

            limiter.recordFailure(USER);

            verify(redis).expire(KEY, PasswordChangeRateLimiter.WINDOW);
        }

        /**
         * A fixed window, not a sliding one. Re-expiring on every failure would
         * let a caller sitting exactly at the limit hold the key alive for ever,
         * which is the opposite of what a window is for.
         */
        @Test
        @DisplayName("does not extend the window on later failures")
        void laterFailuresDoNotSlideTheWindow() {
            when(values.increment(KEY)).thenReturn(3L);
            when(redis.getExpire(KEY)).thenReturn(400L);

            limiter.recordFailure(USER);

            verify(redis, never()).expire(eq(KEY), any(Duration.class));
        }

        @Test
        @DisplayName("survives Redis being unreachable")
        void survivesRedisFailure() {
            when(values.increment(KEY)).thenThrow(new QueryTimeoutException("redis is down"));
            limiter.recordFailure(USER);   // must not propagate
        }
    }

    @Nested
    @DisplayName("recordSuccess")
    class RecordSuccess {

        /**
         * The caller has just proved they know the password, so the evidence the
         * counter represents is spent. Leaving it would mean two typos this
         * morning still counted against a genuine change this afternoon.
         */
        @Test
        @DisplayName("clears the budget")
        void clearsTheBudget() {
            limiter.recordSuccess(USER);
            verify(redis).delete(KEY);
        }

        @Test
        @DisplayName("survives Redis being unreachable")
        void survivesRedisFailure() {
            when(redis.delete(KEY)).thenThrow(new QueryTimeoutException("redis is down"));
            limiter.recordSuccess(USER);   // must not propagate
        }
    }

    @Nested
    @DisplayName("the numbers")
    class Numbers {

        /**
         * Tighter than {@code LoginRateLimiter.MAX_PER_PAIR}, and that is the
         * point rather than an accident — see the class javadoc. Somebody
         * changing their own password knows it, and none of the reasons login's
         * number had to clear the lockout sequence apply here. Pinned so that
         * raising it to match login's is a decision rather than a tidy-up.
         */
        @Test
        @DisplayName("is tighter than the sign-in throttle")
        void isTighterThanLogin() {
            assertThat(PasswordChangeRateLimiter.MAX_FAILURES)
                    .as("a caller who knows their own password does not need seven attempts")
                    .isLessThan(LoginRateLimiter.MAX_PER_PAIR);
        }

        /**
         * The budget must survive long enough to be a deterrent. A one-minute
         * window would let an attacker take 5 guesses a minute — 7,200 a day —
         * which bounds nothing worth bounding.
         */
        @Test
        @DisplayName("the window is long enough to matter")
        void theWindowIsLongEnough() {
            assertThat(PasswordChangeRateLimiter.WINDOW).isGreaterThanOrEqualTo(Duration.ofMinutes(10));
        }
    }

    /** Kept honest: the key names the user, so two users never share a budget. */
    @Test
    @DisplayName("each user spends their own budget")
    void budgetsArePerUser() {
        when(values.get(PasswordChangeRateLimiter.KEY_PREFIX + 1L)).thenReturn("99");
        when(redis.getExpire(PasswordChangeRateLimiter.KEY_PREFIX + 1L)).thenReturn(60L);
        when(values.get(PasswordChangeRateLimiter.KEY_PREFIX + 2L)).thenReturn(null);

        assertThat(limiter.check(1L)).isPresent();
        assertThat(limiter.check(2L))
                .as("one user exhausting their budget must not refuse another")
                .isEmpty();
    }

    /** Documented behaviour of the pair, end to end, without Redis semantics. */
    @Test
    @DisplayName("a wrong guess then a right one leaves no budget spent")
    void aSuccessfulChangeClearsTheCount() {
        when(values.increment(KEY)).thenReturn(1L);
        when(redis.getExpire(KEY)).thenReturn(-1L);
        limiter.recordFailure(USER);

        limiter.recordSuccess(USER);
        verify(redis).delete(KEY);

        when(values.get(KEY)).thenReturn(null);
        assertThat(limiter.check(USER)).isEqualTo(Optional.empty());
    }
}
