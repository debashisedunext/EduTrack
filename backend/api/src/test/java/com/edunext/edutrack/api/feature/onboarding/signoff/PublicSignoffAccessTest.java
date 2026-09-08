package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-120 · the four properties the contract states once above these routes,
 * enforced once here.
 *
 * <p>The assertions that carry weight are about <b>ordering</b> and about
 * <b>sameness</b>: that the budget is spent before the token is looked at, and
 * that four different failures are indistinguishable. Both are the kind of
 * property a working implementation can lack without any happy-path test
 * noticing.
 */
class PublicSignoffAccessTest {

    private final ObSignoffTokens tokens = mock(ObSignoffTokens.class);
    private final ObSignoffRateLimiter rateLimiter = mock(ObSignoffRateLimiter.class);
    private final PublicSignoffAccess access = new PublicSignoffAccess(tokens, rateLimiter);

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    private void allowRate() {
        when(rateLimiter.checkAndSpend(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a usable token resolves")
    void resolvesAUsableToken() {
        ObSignoff signoff = new ObSignoff();
        allowRate();
        when(tokens.resolve("good")).thenReturn(Optional.of(signoff));

        assertThat(access.require("good", request())).isSameAs(signoff);
    }

    @Test
    @DisplayName("the budget is spent BEFORE the token is looked at")
    void spendsBeforeResolving() {
        // THE ORDERING ASSERTION. Resolving first would let an invalid token be
        // refused without spending budget — and a caller would learn, from
        // never being throttled, that none of their guesses had ever matched.
        // Spending first makes a wrong token cost exactly what a right one does.
        allowRate();
        when(tokens.resolve(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> access.require("nope", request()));

        InOrder order = inOrder(rateLimiter, tokens);
        order.verify(rateLimiter).checkAndSpend(anyString(), anyString());
        order.verify(tokens).resolve(anyString());
    }

    @Test
    @DisplayName("a rate-limited caller never reaches the lookup at all")
    void rateLimitedShortCircuits() {
        // So the 429 cannot become its own oracle: it is raised without the
        // token being examined, and says nothing about whether one exists.
        when(rateLimiter.checkAndSpend(anyString(), anyString()))
                .thenReturn(Optional.of(Duration.ofMinutes(3)));

        assertThatExceptionOfType(SignoffRateLimitedException.class)
                .isThrownBy(() -> access.require("anything", request()))
                .satisfies(e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(3)));

        verify(tokens, never()).resolve(any());
    }

    @Test
    @DisplayName("every kind of bad token throws the same exception")
    void everyFailureIsTheSame() {
        // Unknown, expired, cancelled and already-signed all arrive here as an
        // empty Optional from ObSignoffTokens.resolve, which filters on status
        // and TTL. The contract's rule is that they answer identically;
        // upstream of the handler, that means one exception type with no
        // distinguishing state.
        allowRate();
        when(tokens.resolve(anyString())).thenReturn(Optional.empty());

        for (String candidate : new String[]{"unknown", "expired", "cancelled", "signed"}) {
            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> access.require(candidate, request()))
                    .satisfies(e -> assertThat(e.getMessage()).isEqualTo("Invalid sign-off token"));
        }
    }

    @Test
    @DisplayName("a null or blank token is refused like any other, not with an NPE")
    void blankTokenIsAnOrdinaryRefusal() {
        allowRate();
        when(tokens.resolve(any())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> access.require(null, request()));
        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> access.require("   ", request()));
    }

    @Test
    @DisplayName("resolveQuietly answers empty rather than throwing, for the 202 route")
    void quietlyDoesNotThrowOnAMiss() {
        // requestObSignoffOtp must answer 202 "for an unknown, expired,
        // cancelled or already-signed token too — deliberately
        // indistinguishable from the successful case". A route that must not
        // distinguish cannot be built on a method that throws.
        allowRate();
        when(tokens.resolve(anyString())).thenReturn(Optional.empty());

        assertThat(access.resolveQuietly("nope", request())).isEmpty();
    }

    @Test
    @DisplayName("resolveQuietly still spends the budget, and still refuses when it is gone")
    void quietlyStillRateLimits() {
        // Being told to slow down is not the same as being told whether a token
        // is real, so the quiet path keeps the limit.
        when(rateLimiter.checkAndSpend(anyString(), anyString()))
                .thenReturn(Optional.of(Duration.ofMinutes(1)));

        assertThatExceptionOfType(SignoffRateLimitedException.class)
                .isThrownBy(() -> access.resolveQuietly("anything", request()));
        verify(tokens, never()).resolve(any());
    }

    @Test
    @DisplayName("the source key comes from ClientAddress, not the raw socket")
    void keysOnTheProxyAwareAddress() {
        // Behind a load balancer getRemoteAddr is the balancer, which would key
        // every request in the estate to one bucket and turn the per-source
        // budget into a global one.
        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.setRemoteAddr("10.0.0.1");
        forwarded.addHeader("X-Forwarded-For", "198.51.100.42");
        allowRate();
        when(tokens.resolve(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> access.require("t", forwarded));

        verify(rateLimiter).checkAndSpend("t", "198.51.100.42");
    }
}
