package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-024 · the rotation decision table.
 *
 * <p>Every case here is a branch that either revokes a family or refuses without
 * revoking, and getting one wrong is invisible in normal use: a system that
 * never detects reuse and a system that detects it correctly behave identically
 * until the day someone is actually robbed. {@code AuthRefreshIT} proves the
 * same sequence end to end against a real Redis; this proves the decisions.
 */
class RefreshRotationServiceTest {

    private static final String TOKEN = "opaque-token-value";
    private static final String FAMILY = "family-abc";
    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0";

    private static final AuthenticatedUser USER = new AuthenticatedUser(
            42L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
            "DEVELOPER", "Asia/Kolkata", false,
            List.of("ticket.read"), List.of(11L), List.of());

    private static final ResponseCookie SUCCESSOR =
            ResponseCookie.from("refresh_token", "successor-value").build();

    private RefreshTokenStore store;
    private RefreshTokenIssuer issuer;
    private AuthenticationService authentication;
    private AccessTokenIssuer accessTokens;
    private RefreshRotationService rotation;

    @BeforeEach
    void setUp() {
        store = mock(RefreshTokenStore.class);
        issuer = mock(RefreshTokenIssuer.class);
        authentication = mock(AuthenticationService.class);
        accessTokens = mock(AccessTokenIssuer.class);
        rotation = new RefreshRotationService(
                store, issuer, new RefreshTokenProperties(null, null, null, null),
                new SessionProperties(null, null), authentication, accessTokens);
    }

    /** A live token, seven days out, bound to Chrome, well inside both session bounds. */
    private static StoredRefreshToken live() {
        Instant now = Instant.now();
        return new StoredRefreshToken("jti-1", 42L, FAMILY,
                StoredRefreshToken.fingerprintOf(CHROME), now, now.plus(Duration.ofDays(7)),
                now.plus(Duration.ofHours(12)));
    }

    /**
     * A token whose own expiry and family are fine, but whose session has run
     * out one way or the other — the A-025 cases.
     *
     * @param lastUsedAgo     how long since the last refresh (drives the idle window)
     * @param sessionStartAgo how long since login (drives the absolute cap)
     */
    private static StoredRefreshToken session(Duration lastUsedAgo, Duration sessionStartAgo) {
        Instant now = Instant.now();
        return new StoredRefreshToken("jti-1", 42L, FAMILY,
                StoredRefreshToken.fingerprintOf(CHROME),
                now.minus(lastUsedAgo),
                now.plus(Duration.ofDays(7)),
                now.minus(sessionStartAgo).plus(Duration.ofHours(12)));
    }

    /** Wires the whole happy path so each test only has to break one link. */
    private void givenAWorkingRotation(StoredRefreshToken token) {
        when(store.find(TOKEN)).thenReturn(Optional.of(token));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);
        when(store.claim(TOKEN)).thenReturn(true);
        when(authentication.resolveActiveUser(42L)).thenReturn(USER);
        when(accessTokens.issue(USER)).thenReturn(new AccessToken("new.access.token", 900));
        when(issuer.rotate(any(StoredRefreshToken.class))).thenReturn(SUCCESSOR);
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a live token is exchanged for a fresh session and a successor cookie")
    void rotatesALiveToken() {
        StoredRefreshToken token = live();
        givenAWorkingRotation(token);

        RefreshRotationService.Rotation result = rotation.rotate(TOKEN, CHROME);

        assertThat(result.session().accessToken()).isEqualTo("new.access.token");
        assertThat(result.session().user().id()).isEqualTo(42L);
        assertThat(result.cookie()).isSameAs(SUCCESSOR);
        verify(issuer).rotate(token);
    }

    /**
     * The invalidation half of §10.1. A rotation that issues a successor without
     * consuming the predecessor leaves two live tokens for one session and makes
     * every reuse test below unreachable.
     */
    @Test
    @DisplayName("the presented token is consumed, not merely superseded")
    void theOldTokenIsConsumed() {
        StoredRefreshToken token = live();
        givenAWorkingRotation(token);

        rotation.rotate(TOKEN, CHROME);

        verify(store).claim(TOKEN);
        verify(store).markConsumed(TOKEN, FAMILY, token.expiresAt());
    }

    /**
     * Order is the guarantee. If the successor were minted first and the marker
     * write then failed, the predecessor would be live-looking-absent forever:
     * a stolen copy of it would work and raise no alarm, which is precisely the
     * hole A-024 exists to close.
     */
    @Test
    @DisplayName("the token is claimed and recorded as spent BEFORE a successor exists")
    void consumptionIsRecordedBeforeTheSuccessorIsMinted() {
        givenAWorkingRotation(live());

        rotation.rotate(TOKEN, CHROME);

        InOrder order = inOrder(store, issuer);
        order.verify(store).claim(TOKEN);
        order.verify(store).markConsumed(eq(TOKEN), eq(FAMILY), any(Instant.class));
        order.verify(issuer).rotate(any(StoredRefreshToken.class));
    }

    /**
     * The reason the refresh path re-queries at all instead of replaying the
     * claims already sitting in the stored record.
     */
    @Test
    @DisplayName("scope is re-read from the database, so a role change lands at the next refresh")
    void scopeIsReResolvedRatherThanReplayed() {
        givenAWorkingRotation(live());
        AuthenticatedUser promoted = new AuthenticatedUser(42L, "asha.rao", "asha.rao@edunext.test",
                "Asha Rao", "PM", "Asia/Kolkata", false, List.of("ticket.assign"), List.of(11L, 12L), List.of());
        when(authentication.resolveActiveUser(42L)).thenReturn(promoted);
        when(accessTokens.issue(promoted)).thenReturn(new AccessToken("pm.token", 900));

        RefreshRotationService.Rotation result = rotation.rotate(TOKEN, CHROME);

        assertThat(result.session().user().role()).isEqualTo("PM");
        verify(accessTokens).issue(promoted);
    }

    // ── reuse: the one refusal that revokes ─────────────────────────────────

    /**
     * The headline case. The token is gone from the live keyspace but the
     * consumed marker remembers it — without that marker this is
     * indistinguishable from a typo and §10.1 cannot be enforced at all.
     */
    @Test
    @DisplayName("a consumed token presented again revokes the whole family")
    void reuseRevokesTheFamily() {
        when(store.find(TOKEN)).thenReturn(Optional.empty());
        when(store.findConsumedFamily(TOKEN)).thenReturn(Optional.of(FAMILY));

        assertThatExceptionOfType(RefreshTokenReuseException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store).revokeFamily(eq(FAMILY), any(Duration.class));
        verify(issuer, never()).rotate(any());
    }

    /**
     * Two tabs waking at once, or a client retrying a refresh whose response was
     * lost. Server-side this is genuinely indistinguishable from a replay, and
     * §10.1 does not carve out an exception — so it is treated as reuse. The
     * cost is a rare re-login; the alternative is a race window in which one
     * session forks into two, which is the same shape as the attack.
     */
    @Test
    @DisplayName("losing the race to claim a token is treated as reuse, not as a retry")
    void aLostClaimRaceIsReuse() {
        when(store.find(TOKEN)).thenReturn(Optional.of(live()));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);
        when(authentication.resolveActiveUser(42L)).thenReturn(USER);
        // Another request got there first: DEL removed nothing.
        when(store.claim(TOKEN)).thenReturn(false);

        assertThatExceptionOfType(RefreshTokenReuseException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store).revokeFamily(eq(FAMILY), any(Duration.class));
        verify(issuer, never()).rotate(any());
    }

    /**
     * Revocation is the response, not the reporting of it. A handler that throws
     * first and revokes later — or forgets — leaves the attacker's own successor
     * working.
     */
    @Test
    @DisplayName("the family is revoked before the refusal is raised, never after")
    void revocationHappensEvenThoughNobodyCatchesTheException() {
        when(store.find(TOKEN)).thenReturn(Optional.empty());
        when(store.findConsumedFamily(TOKEN)).thenReturn(Optional.of(FAMILY));

        try {
            rotation.rotate(TOKEN, CHROME);
        } catch (RefreshTokenReuseException expected) {
            // The assertion is that this already happened by the time we arrive.
        }

        verify(store).revokeFamily(eq(FAMILY), any(Duration.class));
    }

    // ── the refusals that must NOT revoke ───────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("no cookie is a plain refusal — there is no family to revoke")
    void aMissingCookieRevokesNothing(String absent) {
        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(absent, CHROME));

        // Not even a lookup: an empty string hashes to a perfectly valid key.
        verifyNoInteractions(store, issuer, authentication, accessTokens);
    }

    /**
     * A value that was never issued must not be able to end anybody's session.
     * If an unknown token could revoke, the family id would have to be guessed
     * from somewhere — and any such guess hands an unauthenticated caller a
     * logout weapon.
     */
    @Test
    @DisplayName("an unknown or expired token is refused without revoking anything")
    void anUnknownTokenRevokesNothing() {
        when(store.find(TOKEN)).thenReturn(Optional.empty());
        when(store.findConsumedFamily(TOKEN)).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store, never()).revokeFamily(anyString(), any());
        verify(store, never()).claim(anyString());
    }

    /**
     * The successors an attacker or the victim minted before detection. They are
     * live and look perfect; the tombstone is the only thing that stops them,
     * and it has to be consulted before the claim or a revoked family rotates on.
     */
    @Test
    @DisplayName("a live token from a revoked family is refused and never rotates")
    void aRevokedFamilyIsRefused() {
        when(store.find(TOKEN)).thenReturn(Optional.of(live()));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(true);

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store).discard(TOKEN);
        verify(store, never()).claim(anyString());
        verify(issuer, never()).rotate(any());
    }

    /**
     * <b>The deliberate asymmetry.</b> A User-Agent changes on every Chrome
     * auto-update — inside a seven-day window that is close to routine — so
     * revoking the family here would sign people out after a browser update and
     * record it as theft. Refusing the one token costs a single re-login, which
     * is the right price for a binding that is documented as weak. A real thief
     * copies the header anyway.
     */
    @Test
    @DisplayName("a token presented by a different client is refused WITHOUT revoking the family")
    void aDeviceMismatchDoesNotRevokeTheFamily() {
        when(store.find(TOKEN)).thenReturn(Optional.of(live()));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, "curl/8.4.0"));

        verify(store).discard(TOKEN);
        verify(store, never()).revokeFamily(anyString(), any());
        verify(store, never()).claim(anyString());
    }

    // ── A-025 · the two session bounds ──────────────────────────────────────

    /**
     * §10.1's absolute cap. Without it rotation is an unbounded chain — a token
     * used inside every idle window lives forever, and so does a stolen chain
     * being kept warm, leaving explicit logout as the only thing that ever ends
     * a session.
     */
    @Test
    @DisplayName("a session past 12 hours is refused however recently it was used")
    void theAbsoluteCapEndsEvenAnActiveSession() {
        // Used one minute ago — nowhere near idle — but logged in 13 hours ago.
        when(store.find(TOKEN)).thenReturn(Optional.of(
                session(Duration.ofMinutes(1), Duration.ofHours(13))));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store).discard(TOKEN);
        verify(store, never()).claim(anyString());
        verify(issuer, never()).rotate(any());
    }

    /** §10.1's idle window, measured from the last successful refresh. */
    @Test
    @DisplayName("a session idle longer than 30 minutes is refused")
    void theIdleWindowEndsAnAbandonedSession() {
        // Logged in an hour ago (well inside 12h) but untouched for 45 minutes.
        when(store.find(TOKEN)).thenReturn(Optional.of(
                session(Duration.ofMinutes(45), Duration.ofHours(1))));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store).discard(TOKEN);
        verify(issuer, never()).rotate(any());
    }

    /**
     * The ordinary case, and the one a too-eager idle check would break: a client
     * renewing on the natural 15-minute access-token cadence must never trip a
     * 30-minute window.
     */
    @Test
    @DisplayName("the normal 15-minute refresh cadence never trips the idle window")
    void aNormallyActiveSessionIsUnaffected() {
        StoredRefreshToken token = session(Duration.ofMinutes(15), Duration.ofHours(3));
        when(store.find(TOKEN)).thenReturn(Optional.of(token));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);
        when(store.claim(TOKEN)).thenReturn(true);
        when(authentication.resolveActiveUser(42L)).thenReturn(USER);
        when(accessTokens.issue(USER)).thenReturn(new AccessToken("t", 900));
        when(issuer.rotate(any(StoredRefreshToken.class))).thenReturn(SUCCESSOR);

        assertThatNoException().isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(issuer).rotate(token);
    }

    /**
     * Both are the most ordinary things that happen to a session. Treating
     * either as theft would fire the alert constantly and teach everyone to
     * ignore the one signal that matters.
     */
    @Test
    @DisplayName("neither timeout revokes the family — an idle session is not a stolen one")
    void sessionTimeoutsAreNotTreatedAsTheft() {
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);

        when(store.find(TOKEN)).thenReturn(Optional.of(
                session(Duration.ofMinutes(45), Duration.ofHours(1))));
        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        when(store.find(TOKEN)).thenReturn(Optional.of(
                session(Duration.ofMinutes(1), Duration.ofHours(13))));
        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store, never()).revokeFamily(anyString(), any());
    }

    @Test
    @DisplayName("an expired record is refused rather than rotated into a negative TTL")
    void anExpiredRecordIsRefused() {
        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        when(store.find(TOKEN)).thenReturn(Optional.of(new StoredRefreshToken(
                "jti-1", 42L, FAMILY, StoredRefreshToken.fingerprintOf(CHROME),
                past.minus(Duration.ofDays(7)), past, past.plus(Duration.ofHours(12)))));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store, never()).revokeFamily(anyString(), any());
        verify(issuer, never()).rotate(any());
    }

    /**
     * Nothing was stolen — the session is simply over. Revoking would also mean
     * a deactivation logs a security alert, which is how alerts stop being read.
     */
    @Test
    @DisplayName("a deactivated account ends the session without calling it theft")
    void aDeactivatedAccountIsRefusedWithoutRevoking() {
        when(store.find(TOKEN)).thenReturn(Optional.of(live()));
        when(store.isFamilyRevoked(FAMILY)).thenReturn(false);
        when(authentication.resolveActiveUser(42L)).thenThrow(new InvalidRefreshTokenException());

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store, never()).revokeFamily(anyString(), any());
        verify(store, never()).claim(anyString());
    }

    /**
     * A lock is set by someone else typing a wrong password five times. If it
     * ended live sessions, any outsider could sign any employee out at will —
     * the protection would become the attack.
     */
    @Test
    @DisplayName("A-021's lockout is not consulted; refreshing is not a login")
    void refreshDoesNotConsultTheLoginLockout() {
        givenAWorkingRotation(live());

        assertThatNoException().isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(authentication).resolveActiveUser(42L);
        verify(authentication, never()).authenticate(anyString(), anyString());
    }

    // ── failure direction ───────────────────────────────────────────────────

    /**
     * A 401 is a statement about the credential. Saying it because Redis blinked
     * signs out the whole fleet and tells every one of them their session was
     * invalid — false, and unrecoverable-looking. A server error is retryable
     * and the browser keeps its cookie.
     */
    @Test
    @DisplayName("an unreachable store is a server error, never a 401")
    void storeFailuresAreNotConvertedIntoRefusals() {
        when(store.find(TOKEN)).thenThrow(new QueryTimeoutException("redis is down"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));
    }

    /**
     * The predecessor is already destroyed by this point, so there is no session
     * left to preserve. Failing outright ends the session; the tempting
     * alternative — swallowing the error and re-issuing — turns a storage outage
     * into a session that never rotates.
     */
    @Test
    @DisplayName("if consumption cannot be recorded, nothing is issued")
    void aFailedConsumptionMarkerIssuesNothing() {
        givenAWorkingRotation(live());
        doThrow(new QueryTimeoutException("redis is down"))
                .when(store).markConsumed(anyString(), anyString(), any(Instant.class));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(issuer, never()).rotate(any());
        verify(accessTokens, never()).issue(any());
    }

    @Test
    @DisplayName("a refusal before the claim leaves the caller's token untouched")
    void refusalsBeforeTheClaimDoNotDestroyEvidence() {
        when(store.find(TOKEN)).thenReturn(Optional.empty());
        when(store.findConsumedFamily(TOKEN)).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidRefreshTokenException.class)
                .isThrownBy(() -> rotation.rotate(TOKEN, CHROME));

        verify(store, never()).claim(anyString());
        verify(store, never()).markConsumed(anyString(), anyString(), any());
        verify(authentication, never()).resolveActiveUser(anyLong());
    }
}
