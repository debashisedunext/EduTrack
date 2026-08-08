package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
        issuer = new RefreshTokenIssuer(store, properties());
    }

    private static RefreshTokenProperties properties() {
        return new RefreshTokenProperties(null, null, null, null);
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
        assertThat(value)
                .as("an opaque token is a lookup key; anything readable in it is a claim "
                        + "the server would be trusting the client to hold")
                .doesNotContain("42")
                .doesNotContainIgnoringCase("asha")
                .doesNotContainIgnoringCase("developer")
                .doesNotContain(".");
    }

    @Test
    @DisplayName("a thousand tokens are a thousand distinct values")
    void everyIssueMintsAFreshValue() {
        Set<String> values = new HashSet<>();
        List<String> families = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            RefreshTokenStore freshStore = mock(RefreshTokenStore.class);
            RefreshTokenIssuer freshIssuer = new RefreshTokenIssuer(freshStore, properties());

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

    @Test
    @DisplayName("no token is minted before there is somewhere to record it")
    void nothingIsIssuedWithoutAStore() {
        RefreshTokenStore unused = mock(RefreshTokenStore.class);
        new RefreshTokenIssuer(unused, properties());

        // Construction must not touch the store — a bean that dials Redis while
        // the context is refreshing is how the api came to die on startup once
        // already (RelayBootsWithoutRedisTest).
        verifyNoInteractions(unused);
    }
}
