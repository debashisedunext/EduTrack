package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-025 · what logging out must and must not do.
 *
 * <p>Most of these assert an <b>absence</b> — no family revoked, no consumed
 * marker, no refusal when the cookie is missing. That is where the bugs live:
 * a logout that additionally revokes the family still logs the user out, looks
 * completely correct in manual testing, and quietly signs them out of every
 * other device they own.
 */
class LogoutServiceTest {

    private static final String JTI = "8f14e45f-ea8f-4b9a-9c1d-0a2b3c4d5e6f";
    private static final Instant EXPIRES_AT = Instant.now().plus(Duration.ofMinutes(15));
    private static final String REFRESH_VALUE = "opaque-refresh-value";
    private static final String HEADER = "Bearer header.payload.signature";

    private JwtDecoder jwtDecoder;
    private AccessTokenBlacklist blacklist;
    private RefreshTokenStore refreshTokens;
    private LogoutService logout;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        blacklist = mock(AccessTokenBlacklist.class);
        refreshTokens = mock(RefreshTokenStore.class);
        // A-026 moved the bearer verification into AccessTokenVerifier. The real
        // one is used here rather than a mock: it is the thing that decides
        // whether a header is acceptable, and stubbing it out would leave these
        // tests asserting that logout calls a collaborator instead of that a
        // forged header is refused.
        logout = new LogoutService(new AccessTokenVerifier(jwtDecoder), blacklist, refreshTokens);
    }

    private void givenAValidAccessToken() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt(JTI, EXPIRES_AT));
    }

    private static Jwt jwt(String jti, Instant expiresAt) {
        return Jwt.withTokenValue("header.payload.signature")
                .header("alg", "HS256")
                .subject("42")
                .jti(jti)
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15)))
                .expiresAt(expiresAt)
                .claims(c -> c.put("role", "DEVELOPER"))
                .build();
    }

    // ── the two halves, both required ───────────────────────────────────────

    /**
     * Deleting only the refresh token leaves the access token working for up to
     * fifteen more minutes — on a shared machine, fifteen minutes of someone
     * else's session after they pressed Sign out.
     */
    @Test
    @DisplayName("the access token's jti is blacklisted until its own expiry")
    void blacklistsTheAccessToken() {
        givenAValidAccessToken();

        logout.logout(HEADER, REFRESH_VALUE);

        verify(blacklist).revoke(JTI, EXPIRES_AT);
    }

    /**
     * Blacklisting only the access token leaves the refresh cookie able to mint
     * a new one immediately, so the logout undoes itself on the next tick.
     */
    @Test
    @DisplayName("the refresh token is deleted, so the session cannot be renewed")
    void deletesTheRefreshToken() {
        givenAValidAccessToken();

        logout.logout(HEADER, REFRESH_VALUE);

        verify(refreshTokens).discard(REFRESH_VALUE);
    }

    /**
     * Reversed, a request arriving in the gap could rotate the refresh token and
     * walk away with an access token minted after the logout began.
     */
    @Test
    @DisplayName("the usable credential is revoked before the one that could mint another")
    void revokesTheAccessTokenFirst() {
        givenAValidAccessToken();

        logout.logout(HEADER, REFRESH_VALUE);

        InOrder order = inOrder(blacklist, refreshTokens);
        order.verify(blacklist).revoke(anyString(), any(Instant.class));
        order.verify(refreshTokens).discard(anyString());
    }

    // ── what logout must NOT do ─────────────────────────────────────────────

    /**
     * The single most important absence here. Signing out of a laptop must not
     * sign the same user out of their phone — family revocation is A-024's
     * response to <i>theft</i>, and borrowing it for an ordinary logout makes
     * the two indistinguishable in the logs and to the user.
     */
    @Test
    @DisplayName("logout does NOT revoke the token family — other devices stay signed in")
    void doesNotRevokeTheFamily() {
        givenAValidAccessToken();

        logout.logout(HEADER, REFRESH_VALUE);

        verify(refreshTokens, never()).revokeFamily(anyString(), any());
    }

    /**
     * {@code discard}, not {@code claim}+{@code markConsumed}. A consumed marker
     * is what turns a later presentation into detected reuse, so recording one
     * here would mean a stale tab retrying after logout revokes the whole family
     * for something that was never theft.
     */
    @Test
    @DisplayName("logout does NOT mark the refresh token consumed — that would arm reuse detection")
    void doesNotMarkTheTokenConsumed() {
        givenAValidAccessToken();

        logout.logout(HEADER, REFRESH_VALUE);

        verify(refreshTokens, never()).markConsumed(anyString(), anyString(), any());
        verify(refreshTokens, never()).claim(anyString());
    }

    // ── the refresh cookie is optional ──────────────────────────────────────

    /**
     * The people most in need of a working Sign out are those whose session is
     * already half-broken — cookie expired, already rotated, or withheld by
     * {@code SameSite=Strict}. Refusing them leaves the access token live.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("logout succeeds with no refresh cookie, and still kills the access token")
    void succeedsWithoutARefreshCookie(String absent) {
        givenAValidAccessToken();

        assertThatNoException().isThrownBy(() -> logout.logout(HEADER, absent));

        verify(blacklist).revoke(JTI, EXPIRES_AT);
        verify(refreshTokens, never()).discard(anyString());
    }

    /**
     * Every failure mode here leaves someone logged in who asked not to be, so
     * logout is the wrong place to be strict.
     */
    @Test
    @DisplayName("calling logout twice is not an error")
    void isIdempotent() {
        givenAValidAccessToken();

        logout.logout(HEADER, REFRESH_VALUE);
        assertThatNoException().isThrownBy(() -> logout.logout(HEADER, REFRESH_VALUE));

        verify(blacklist, times(2)).revoke(JTI, EXPIRES_AT);
    }

    // ── the access token is mandatory, and really verified ──────────────────

    /**
     * Accepting an unverified token would let anyone forge a {@code jti} and
     * blacklist a stranger's access token — a logout endpoint for logging other
     * people out.
     */
    @Test
    @DisplayName("a token the decoder rejects revokes nothing at all")
    void aForgedTokenRevokesNothing() {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("bad signature"));

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> logout.logout(HEADER, REFRESH_VALUE));

        verifyNoInteractions(blacklist, refreshTokens);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"header.payload.signature", "Basic dXNlcjpwYXNz", "bearer lowercase-scheme"})
    @DisplayName("a missing or non-Bearer Authorization header is refused before any decoding")
    void requiresABearerHeader(String header) {
        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> logout.logout(header, REFRESH_VALUE));

        verifyNoInteractions(jwtDecoder, blacklist, refreshTokens);
    }

    /**
     * Expiry is the decoder's job (its validators enforce {@code exp}), not
     * something this class re-checks — but the refusal still has to arrive as a
     * 401 rather than as a 500, which is what this pins.
     */
    @Test
    @DisplayName("an expired access token is refused, not silently accepted")
    void anExpiredTokenIsRefused() {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("expired"));

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> logout.logout(HEADER, REFRESH_VALUE));
    }

    // ── the blacklist window ────────────────────────────────────────────────

    /**
     * The entry only has to outlive the token it revokes — a second later the
     * token is refused as expired anyway. That is what keeps the blacklist
     * self-limiting instead of unbounded.
     */
    @Test
    @DisplayName("the blacklist entry is bounded by the token's own exp, not a fixed window")
    void blacklistWindowComesFromTheToken() {
        Instant soon = Instant.now().plus(Duration.ofMinutes(2));
        when(jwtDecoder.decode(anyString())).thenReturn(jwt(JTI, soon));

        logout.logout(HEADER, REFRESH_VALUE);

        verify(blacklist).revoke(eq(JTI), eq(soon));
    }

    /**
     * {@code exp} is required by the decoder's validators, so this is defensive —
     * but the fallback must be a conservative window, never "skip", or one class
     * of token would silently survive logout.
     */
    @Test
    @DisplayName("a token with no exp is blacklisted for a horizon rather than skipped")
    void aTokenWithoutExpiryIsStillBlacklisted() {
        Jwt noExpiry = Jwt.withTokenValue("header.payload.signature")
                .header("alg", "HS256")
                .subject("42")
                .jti(JTI)
                .issuedAt(Instant.now())
                .claims(c -> c.put("role", "DEVELOPER"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(noExpiry);

        logout.logout(HEADER, REFRESH_VALUE);

        verify(blacklist).revoke(eq(JTI), any(Instant.class));
    }
}
