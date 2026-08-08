package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A-023 · the cookie's attributes, the token's opacity and the record written
 * behind it — none of which need Redis, HTTP or a database to be wrong.
 *
 * <p>{@code RefreshTokenStoreIT} proves the write survives a real Redis;
 * {@code AuthLoginIT} proves a real login produces the cookie. This is the
 * layer where the security-relevant details live, and every one of them is the
 * kind that fails silently: a cookie that quietly lost {@code HttpOnly} still
 * works perfectly.
 */
class RefreshTokenIssuerTest {

    private static final AuthenticatedUser USER = new AuthenticatedUser(
            42L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
            "DEVELOPER", "Asia/Kolkata", false,
            List.of("ticket.read"), List.of(11L), List.of());

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0";

    private RefreshTokenStore store;
    private RefreshTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        store = mock(RefreshTokenStore.class);
        issuer = new RefreshTokenIssuer(store, properties(), session());
    }

    private static RefreshTokenProperties properties() {
        return new RefreshTokenProperties(null, null, null, null);
    }

    /** A-025 · production defaults — 30-minute idle, 12-hour absolute. */
    private static SessionProperties session() {
        return new SessionProperties(null, null);
    }

    private ResponseCookie issueCookie() {
        return issuer.issue(USER, CHROME).orElseThrow();
    }

    /** The value handed to {@code store.save}, which is the raw token. */
    private String capturedTokenValue() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(store).save(value.capture(), any(StoredRefreshToken.class));
        return value.getValue();
    }

    private StoredRefreshToken capturedRecord() {
        ArgumentCaptor<StoredRefreshToken> record = ArgumentCaptor.forClass(StoredRefreshToken.class);
        verify(store).save(anyString(), record.capture());
        return record.getValue();
    }

    // ── the cookie ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the cookie is HttpOnly, Secure, SameSite=Strict and lives seven days")
    void cookieCarriesTheDocumentedAttributes() {
        ResponseCookie cookie = issueCookie();

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.isHttpOnly())
                .as("without HttpOnly a single XSS lifts a seven-day credential")
                .isTrue();
        assertThat(cookie.isSecure())
                .as("without Secure the credential goes over plaintext")
                .isTrue();
        assertThat(cookie.getSameSite())
                .as("Strict is what makes CSRF against /auth/refresh structurally impossible")
                .isEqualTo("Strict");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("the cookie is scoped to /auth, not to the whole API")
    void cookieIsScopedToTheAuthRoutesOnly() {
        // Only /auth/refresh (A-024) and /auth/logout (A-025) ever read it.
        // Widening this to "/" attaches a seven-day credential to every ticket
        // list, every attachment download and every proxy log along the way.
        assertThat(issueCookie().getPath()).isEqualTo("/api/v1/auth");
    }

    @Test
    @DisplayName("the Max-Age and the stored expiry come from one setting and cannot drift")
    void cookieLifetimeMatchesTheStoredExpiry() {
        ResponseCookie cookie = issueCookie();
        StoredRefreshToken record = capturedRecord();

        assertThat(Duration.between(record.issuedAt(), record.expiresAt()))
                .isEqualTo(cookie.getMaxAge());
    }

    // ── the token value ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the value is opaque — it carries no user id, no role and no expiry")
    void tokenValueIsOpaque() {
        String value = issueCookie().getValue();

        // 32 bytes, base64url without padding.
        assertThat(value).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(value))
                .as("exactly 32 bytes of SecureRandom — a value with spare room for a claim "
                        + "is a value that will eventually be given one")
                .hasSize(32);
        assertThat(value)
                .as("an opaque token is a lookup key; anything readable in it is a claim "
                        + "the server would be trusting the client to hold")
                .doesNotContainIgnoringCase("asha")
                .doesNotContainIgnoringCase("developer")
                .doesNotContain(".");

        // The user id is deliberately NOT probed as a substring. "42" turns up by
        // chance in about one random 43-character base64url string in a hundred —
        // the assertion tested the random number generator, not the code, and
        // reddened CI roughly every hundredth run (it did, on 2026-08-08, during
        // A-024). What it was reaching for is that the value does not encode the
        // user, which the 32-random-bytes check above and the thousand distinct
        // values below establish without depending on luck.
    }

    @Test
    @DisplayName("a thousand tokens are a thousand distinct values")
    void everyIssueMintsAFreshValue() {
        Set<String> values = new HashSet<>();
        List<String> families = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            RefreshTokenStore freshStore = mock(RefreshTokenStore.class);
            RefreshTokenIssuer freshIssuer = new RefreshTokenIssuer(freshStore, properties(), session());

            values.add(freshIssuer.issue(USER, CHROME).orElseThrow().getValue());

            ArgumentCaptor<StoredRefreshToken> record = ArgumentCaptor.forClass(StoredRefreshToken.class);
            verify(freshStore).save(anyString(), record.capture());
            families.add(record.getValue().familyId());
        }

        assertThat(values).hasSize(1000);
        assertThat(families).doesNotHaveDuplicates();
    }

    // ── the stored record ───────────────────────────────────────────────────

    @Test
    @DisplayName("the record identifies the user, the family and the device — never the token")
    void recordCarriesWhatA024Needs() {
        issueCookie();
        StoredRefreshToken record = capturedRecord();
        String rawToken = capturedTokenValue();

        assertThat(record.userId()).isEqualTo(42L);
        assertThat(record.jti()).isNotBlank();
        assertThat(record.familyId()).isNotBlank();
        assertThat(record.deviceFingerprint()).isEqualTo(StoredRefreshToken.fingerprintOf(CHROME));

        assertThat(record.toString())
                .as("the raw token must never be persisted — only its SHA-256, as the key")
                .doesNotContain(rawToken);
    }

    @Test
    @DisplayName("each login starts its own family, so revoking one does not log the user out everywhere")
    void eachLoginStartsANewFamily() {
        issuer.issue(USER, CHROME);
        issuer.issue(USER, CHROME);

        ArgumentCaptor<StoredRefreshToken> records = ArgumentCaptor.forClass(StoredRefreshToken.class);
        verify(store, org.mockito.Mockito.times(2)).save(anyString(), records.capture());

        assertThat(records.getAllValues().get(0).familyId())
                .isNotEqualTo(records.getAllValues().get(1).familyId());
    }

    // ── the device binding (A-024's hook) ───────────────────────────────────

    @Test
    @DisplayName("the fingerprint identifies the browser without storing what it is")
    void fingerprintIsAHashNotTheHeader() {
        String fingerprint = StoredRefreshToken.fingerprintOf(CHROME);

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]+");
        assertThat(fingerprint)
                .as("a plaintext User-Agent is a fingerprinting datum sitting in a cache for a week")
                .doesNotContain("Mozilla")
                .doesNotContain("Chrome");
        assertThat(fingerprint).isEqualTo(StoredRefreshToken.fingerprintOf(CHROME));
    }

    @Test
    @DisplayName("a token replayed from a different browser does not match")
    void matchesDeviceRejectsADifferentUserAgent() {
        issueCookie();
        StoredRefreshToken record = capturedRecord();

        assertThat(record.matchesDevice(CHROME)).isTrue();
        assertThat(record.matchesDevice("curl/8.4.0"))
                .as("this is the check A-024 runs on refresh")
                .isFalse();
    }

    @Test
    @DisplayName("a missing User-Agent binds weakly rather than failing")
    void absentUserAgentIsTolerated() {
        Optional<ResponseCookie> cookie = issuer.issue(USER, null);

        assertThat(cookie).isPresent();
        assertThat(capturedRecord().matchesDevice(null))
                .as("null and an absent header must fingerprint identically, or every "
                        + "refresh from such a client looks like theft")
                .isTrue();
    }

    // ── Redis unreachable ───────────────────────────────────────────────────

    @Test
    @DisplayName("an unreachable token store shortens the session; it does not fail the login")
    void storeFailureDegradesRatherThanFailing() {
        doThrow(new QueryTimeoutException("redis is down"))
                .when(store).save(anyString(), any(StoredRefreshToken.class));

        // The alternative is a 500 on every login while Redis is down, taking
        // the whole product out over a degradation confined to session length.
        // Degrading fails towards LESS access: a 15-minute session, then
        // re-login. Nothing here is weakened, only shortened.
        assertThat(issuer.issue(USER, CHROME))
                .as("no cookie, but the caller still gets its access token")
                .isEmpty();
    }

    // ── A-024 · rotation ────────────────────────────────────────────────────

    private static StoredRefreshToken consumed(Duration remaining) {
        Instant now = Instant.now();
        return new StoredRefreshToken("jti-1", 42L, "family-abc",
                StoredRefreshToken.fingerprintOf(CHROME),
                now.minus(Duration.ofDays(7).minus(remaining)), now.plus(remaining),
                SESSION_DEADLINE);
    }

    /**
     * A-025 · a fixed absolute deadline, so a test can assert the successor
     * inherited <i>this exact instant</i> rather than something recomputed that
     * merely looks close.
     */
    private static final Instant SESSION_DEADLINE = Instant.parse("2026-12-31T00:00:00Z");

    /**
     * The family is the unit revocation operates on. A successor in a fresh
     * family would make "revoke the family" reach only as far back as the last
     * refresh — an attacker who rotates once has escaped it entirely.
     */
    @Test
    @DisplayName("the successor stays in the predecessor's family")
    void rotationInheritsTheFamily() {
        StoredRefreshToken predecessor = consumed(Duration.ofDays(5));

        issuer.rotate(predecessor);

        assertThat(capturedRecord().familyId()).isEqualTo("family-abc");
        assertThat(capturedRecord().userId()).isEqualTo(42L);
    }

    /**
     * The alternative — a fresh seven days on every rotation — makes the session
     * unbounded: a token used once a week never expires, and neither does a
     * stolen chain being kept warm. A-025's absolute 12 h tightens this further.
     */
    @Test
    @DisplayName("the deadline is inherited, so expiry does not slide on every refresh")
    void rotationDoesNotExtendTheDeadline() {
        StoredRefreshToken predecessor = consumed(Duration.ofDays(2));

        ResponseCookie successor = issuer.rotate(predecessor);

        assertThat(capturedRecord().expiresAt())
                .as("a sliding deadline means the only thing that ever ends a session is a "
                        + "logout, which an attacker has no reason to perform")
                .isEqualTo(predecessor.expiresAt());
        assertThat(successor.getMaxAge())
                .as("the cookie must expire with the family, not seven days after it")
                .isLessThanOrEqualTo(Duration.ofDays(2))
                .isGreaterThan(Duration.ofDays(2).minusMinutes(1));
    }

    /**
     * Re-reading the fingerprint from the current request would let a token that
     * had drifted to another browser silently re-bind itself there — a binding
     * that only ever matches is the same as no binding.
     */
    /**
     * A-025 · the absolute cap must survive rotation untouched. Recomputing it
     * from "now" on each refresh would push the 12-hour deadline forward every
     * fifteen minutes, so an active session would never reach it — the bound
     * would exist in the code and never once fire.
     */
    @Test
    @DisplayName("the 12-hour absolute deadline is inherited, never recomputed")
    void rotationInheritsTheAbsoluteSessionDeadline() {
        issuer.rotate(consumed(Duration.ofDays(5)));

        assertThat(capturedRecord().sessionExpiresAt()).isEqualTo(SESSION_DEADLINE);
    }

    /**
     * A-025 · the counterpart — {@code issuedAt} <b>is</b> re-stamped, because it
     * doubles as "last activity" for the idle window. Inheriting it would freeze
     * the idle clock at login and every session would trip the 30-minute timeout
     * half an hour in, no matter how actively it was being used.
     */
    @Test
    @DisplayName("issuedAt IS re-stamped on rotation — it is the idle clock")
    void rotationRestampsTheActivityClock() {
        StoredRefreshToken predecessor = consumed(Duration.ofDays(5));

        issuer.rotate(predecessor);

        assertThat(capturedRecord().issuedAt())
                .as("the successor's issuedAt is 'last used', and must move with each refresh")
                .isAfter(predecessor.issuedAt());
    }

    @Test
    @DisplayName("a login stamps the absolute deadline 12 hours out")
    void loginStampsTheAbsoluteDeadline() {
        issueCookie();
        StoredRefreshToken record = capturedRecord();

        assertThat(Duration.between(record.issuedAt(), record.sessionExpiresAt()))
                .isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("the device binding is inherited, never re-stamped from the new request")
    void rotationInheritsTheDeviceBinding() {
        issuer.rotate(consumed(Duration.ofDays(5)));

        assertThat(capturedRecord().matchesDevice(CHROME)).isTrue();
    }

    @Test
    @DisplayName("the successor is a new value with a new jti — not the old one re-dated")
    void rotationMintsAGenuinelyNewToken() {
        StoredRefreshToken predecessor = consumed(Duration.ofDays(5));

        ResponseCookie successor = issuer.rotate(predecessor);

        assertThat(successor.getValue()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(capturedRecord().jti())
                .as("two links in one chain must be distinguishable in a log and to A-025's logout")
                .isNotEqualTo(predecessor.jti());
        assertThat(capturedTokenValue()).isEqualTo(successor.getValue());
    }

    /**
     * Unlike {@link RefreshTokenIssuer#issue}, rotation must not degrade. The
     * predecessor is already destroyed by the time this runs, so there is no
     * session left to preserve — failing ends it, which is the safe direction.
     */
    @Test
    @DisplayName("rotation does NOT degrade when the store is unreachable")
    void rotationFailsRatherThanDegrading() {
        doThrow(new QueryTimeoutException("redis is down"))
                .when(store).save(anyString(), any(StoredRefreshToken.class));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> issuer.rotate(consumed(Duration.ofDays(5))));
    }

    // ── A-024 · the clearing cookie ─────────────────────────────────────────

    /**
     * A clearing cookie that differs in name or Path is a <i>second</i> cookie
     * rather than a replacement, and clears nothing — the browser keeps replaying
     * the dead one.
     */
    @Test
    @DisplayName("the clearing cookie matches the one it replaces in name and path")
    void clearingCookieReplacesRatherThanAdds() {
        ResponseCookie clearing = issuer.clearing();
        ResponseCookie live = issueCookie();

        assertThat(clearing.getName()).isEqualTo(live.getName());
        assertThat(clearing.getPath()).isEqualTo(live.getPath());
        assertThat(clearing.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(clearing.getValue()).isEmpty();
        assertThat(clearing.isHttpOnly()).isTrue();
        assertThat(clearing.getSameSite()).isEqualTo("Strict");
    }

    @Test
    @DisplayName("no token is minted before there is somewhere to record it")
    void nothingIsIssuedWithoutAStore() {
        RefreshTokenStore unused = mock(RefreshTokenStore.class);
        new RefreshTokenIssuer(unused, properties(), session());

        // Construction must not touch the store — a bean that dials Redis while
        // the context is refreshing is how the api came to die on startup once
        // already (RelayBootsWithoutRedisTest).
        verifyNoInteractions(unused);
    }
}
