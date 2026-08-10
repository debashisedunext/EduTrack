package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
 
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-020 · the HTTP contract of {@code POST /api/v1/auth/login}.
 *
 * <p>{@code addFilters = false} removes Spring Security's chain from the slice.
 * The real chain is A-032 and does not exist yet — today
 * {@code ScaffoldSecurityConfig} permits everything, and it is not loaded by a
 * {@code @WebMvcTest}. Leaving the filters on would test Boot's lock-everything
 * default, which is neither what runs in production nor what this class is
 * about.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthenticationService authentication;

    @MockitoBean
    AccessTokenIssuer tokens;

    @MockitoBean
    RefreshTokenIssuer refreshTokens;

    @MockitoBean
    RefreshRotationService rotation;

    @MockitoBean
    LogoutService logout;

    @MockitoBean
    ForgotPasswordService forgotPassword;

    @MockitoBean
    ResetPasswordService resetPassword;

    @MockitoBean
    PasswordResetRateLimiter resetRateLimiter;

    private static final String VALID_BODY = """
            {"username":"asha.rao","password":"Correct-Horse-1!"}
            """;

    private static final ResponseCookie REFRESH_COOKIE = ResponseCookie.from("refresh_token", "opaque-value")
            .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth")
            .maxAge(Duration.ofDays(7)).build();

    /** A-024 · what {@code AuthExceptionHandler} attaches to every refresh refusal. */
    private static final ResponseCookie CLEARING = ResponseCookie.from("refresh_token", "")
            .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth")
            .maxAge(Duration.ZERO).build();

    @BeforeEach
    void issueARefreshCookieByDefault() {
        when(refreshTokens.issue(any(), any())).thenReturn(Optional.of(REFRESH_COOKIE));
        when(refreshTokens.clearing()).thenReturn(CLEARING);
    }

    @Test
    @DisplayName("a valid login returns the session inside the { data } envelope")
    void returnsSessionEnvelope() throws Exception {
        when(authentication.authenticate("asha.rao", "Correct-Horse-1!"))
                .thenReturn(new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                        "DEVELOPER", "Asia/Kolkata", false,
                        List.of("ticket.read"), List.of(11L), List.of()));
        when(tokens.issue(any(AuthenticatedUser.class)))
                .thenReturn(new AccessToken("header.payload.signature", 900));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(7))
                .andExpect(jsonPath("$.data.user.displayName").value("Asha Rao"))
                .andExpect(jsonPath("$.data.user.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.user.permissions[0]").value("ticket.read"))
                .andExpect(jsonPath("$.data.user.projectIds[0]").value(11))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andExpect(jsonPath("$.data.accessToken").value("header.payload.signature"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    @Test
    @DisplayName("the token minted for this login, not a stale one, is what the response carries")
    void carriesExactlyTheMintedToken() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                "DEVELOPER", "Asia/Kolkata", false, List.of(), List.of(), List.of());
        when(authentication.authenticate(anyString(), anyString())).thenReturn(user);
        when(tokens.issue(user)).thenReturn(new AccessToken("this-users-token", 900));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("this-users-token"));
    }

    @Test
    @DisplayName("a refused login is a problem+json with a stable type URI")
    void refusalIsRfc9457() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // CONVENTIONS.md §3: the frontend branches on `type`, so this URI
                // is the part that must not drift. `title` and `detail` may be
                // reworded freely and are deliberately not asserted here.
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-credentials"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("the refusal body names neither the field that failed nor the user")
    void refusalLeaksNothing() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException());

        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("a refusal must not echo the username, the password, or which of them was wrong")
                .doesNotContain("asha.rao")
                .doesNotContain("Correct-Horse-1!")
                .doesNotContainIgnoringCase("no such user")
                .doesNotContainIgnoringCase("not found")
                .doesNotContainIgnoringCase("disabled")
                .doesNotContainIgnoringCase("inactive");
    }

    // ── A-023 · the refresh cookie ───────────────────────────────────────────

    @Test
    @DisplayName("a valid login sets the refresh cookie, and never puts it in the body")
    void setsTheRefreshCookie() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenReturn(new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                        "DEVELOPER", "Asia/Kolkata", false, List.of(), List.of(), List.of()));
        when(tokens.issue(any(AuthenticatedUser.class)))
                .thenReturn(new AccessToken("header.payload.signature", 900));

        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("refresh_token=opaque-value")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("SameSite=Strict")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("a refresh token in the body is a refresh token in localStorage — "
                        + "one XSS away from a seven-day session")
                .doesNotContain("opaque-value")
                .doesNotContain("refresh");
    }

    @Test
    @DisplayName("the User-Agent is passed through for the device binding")
    void forwardsTheUserAgentToTheIssuer() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                "DEVELOPER", "Asia/Kolkata", false, List.of(), List.of(), List.of());
        when(authentication.authenticate(anyString(), anyString())).thenReturn(user);
        when(tokens.issue(any(AuthenticatedUser.class))).thenReturn(new AccessToken("t", 900));

        mvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 Chrome/131.0.0.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        // Dropped here, the fingerprint would be the hash of "" for every user
        // on every device — a binding that exists and binds nothing, which is
        // worse than none because it reads as done.
        verify(refreshTokens).issue(user, "Mozilla/5.0 Chrome/131.0.0.0");
    }

    @Test
    @DisplayName("an unreachable token store shortens the session; the login still returns 200")
    void loginSucceedsWithoutARefreshCookie() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenReturn(new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                        "DEVELOPER", "Asia/Kolkata", false, List.of(), List.of(), List.of()));
        when(tokens.issue(any(AuthenticatedUser.class))).thenReturn(new AccessToken("t", 900));
        when(refreshTokens.issue(any(), any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("t"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    // ── A-021 · account lockout ──────────────────────────────────────────────

    @Test
    @DisplayName("a locked account gets 423 with the lockedUntil timestamp")
    void lockedAccountGets423() throws Exception {
        Instant lockedUntil = Instant.parse("2026-08-07T16:05:00Z");
        when(authentication.authenticate(anyString(), anyString()))
                .thenThrow(new AccountLockedException(lockedUntil));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/account-locked"))
                .andExpect(jsonPath("$.lockedUntil").value(lockedUntil.toString()));
    }

    @Test
    @DisplayName("a blank username is rejected before any authentication work")
    void rejectsBlankUsername() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"  ","password":"Correct-Horse-1!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("a missing password is rejected")
    void rejectsMissingPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"asha.rao"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── A-024 · POST /auth/refresh ───────────────────────────────────────────

    private static final ResponseCookie SUCCESSOR = ResponseCookie.from("refresh_token", "successor-value")
            .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth")
            .maxAge(Duration.ofDays(6)).build();

    private static RefreshRotationService.Rotation aRotation() {
        AuthenticatedUser user = new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                "DEVELOPER", "Asia/Kolkata", false, List.of("ticket.read"), List.of(11L), List.of());
        return new RefreshRotationService.Rotation(
                Session.issue(user, new AccessToken("rotated.access.token", 900)), SUCCESSOR);
    }

    @Test
    @DisplayName("a rotation returns a new session and replaces the cookie")
    void refreshReturnsTheRotatedSession() throws Exception {
        when(rotation.rotate(anyString(), anyString())).thenReturn(aRotation());

        mvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 Chrome/131.0.0.0")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("rotated.access.token"))
                .andExpect(jsonPath("$.data.user.id").value(7))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("refresh_token=successor-value")));
    }

    /**
     * The endpoint's entire input. Reading it from the body or a header instead
     * would mean script could supply it, which is the whole thing {@code
     * HttpOnly} exists to prevent.
     */
    @Test
    @DisplayName("the token comes from the cookie and the User-Agent from the header")
    void refreshReadsTheCookieAndTheUserAgent() throws Exception {
        when(rotation.rotate(anyString(), anyString())).thenReturn(aRotation());

        mvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 Chrome/131.0.0.0")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isOk());

        verify(rotation).rotate("opaque-value", "Mozilla/5.0 Chrome/131.0.0.0");
    }

    @Test
    @DisplayName("the successor never appears in the body, only in the header")
    void theSuccessorIsNeverInTheBody() throws Exception {
        when(rotation.rotate(any(), any())).thenReturn(aRotation());

        String body = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("successor-value").doesNotContain("refresh");
    }

    /**
     * 401 rather than the 400 Spring would produce for a required cookie. The
     * frontend interceptor branches on 401 to send the user to the login screen;
     * a 400 reads as an application bug and gets surfaced as one.
     */
    @Test
    @DisplayName("no cookie at all is a 401, not a 400")
    void refreshWithoutACookieIs401() throws Exception {
        when(rotation.rotate(any(), any())).thenThrow(new InvalidRefreshTokenException());

        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-refresh-token"));
    }

    /**
     * The distinct type is the point: S-01 can tell the user their session was
     * ended because the token was used twice, rather than showing a generic
     * "session expired" that hides a security event from the only person who
     * can act on it.
     */
    @Test
    @DisplayName("reuse is reported with its own type URI, distinct from an ordinary expiry")
    void reuseHasItsOwnTypeUri() throws Exception {
        when(rotation.rotate(any(), any())).thenThrow(new RefreshTokenReuseException());

        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "stolen-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/refresh-token-reuse"))
                .andExpect(jsonPath("$.status").value(401));
    }

    /**
     * Without this the browser replays a dead token on every attempt — against a
     * revoked family that is a 401 loop the user cannot escape without clearing
     * cookies by hand.
     */
    @Test
    @DisplayName("every refusal takes the dead cookie away with it")
    void refusalsClearTheCookie() throws Exception {
        when(rotation.rotate(any(), any())).thenThrow(new RefreshTokenReuseException());

        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "stolen-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("a refusal body names neither the token nor which check failed")
    void refreshRefusalLeaksNothing() throws Exception {
        when(rotation.rotate(any(), any())).thenThrow(new InvalidRefreshTokenException());

        String body = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "some-token-value")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("a caller who can tell 'expired' from 'never issued' has a validity oracle")
                .doesNotContain("some-token-value")
                .doesNotContainIgnoringCase("deactivated")
                .doesNotContainIgnoringCase("device")
                .doesNotContainIgnoringCase("revoked");
    }

    // ── A-025 · POST /auth/logout ────────────────────────────────────────────

    private static final String BEARER = "Bearer header.payload.signature";

    @Test
    @DisplayName("a logout returns 204 with no body")
    void logoutReturnsNoContent() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    /**
     * Both inputs reach the service. Dropping either silently halves the logout:
     * without the header nothing is blacklisted, without the cookie the refresh
     * token survives and mints a replacement on the next tick.
     */
    @Test
    @DisplayName("the Authorization header and the refresh cookie are both passed through")
    void logoutForwardsBothCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isNoContent());

        verify(logout).logout(BEARER, "opaque-value");
    }

    /**
     * A session whose cookie has already expired or rotated must still be able
     * to sign out — otherwise the access token is left live by the very request
     * trying to revoke it.
     */
    @Test
    @DisplayName("logout works with no refresh cookie present")
    void logoutWorksWithoutTheCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNoContent());

        verify(logout).logout(BEARER, null);
    }

    @Test
    @DisplayName("a successful logout clears the refresh cookie")
    void logoutClearsTheCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("no access token is a 401 with the stable type URI")
    void logoutWithoutATokenIs401() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidAccessTokenException())
                .when(logout).logout(any(), any());

        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-access-token"))
                .andExpect(jsonPath("$.status").value(401));
    }

    /**
     * The asymmetry with the refresh refusals above. A logout that could not
     * authenticate has ended nothing, so stripping the cookie would half-end a
     * session the caller was never proven to own.
     */
    @Test
    @DisplayName("a refused logout does NOT clear the cookie")
    void arefusedLogoutLeavesTheCookieAlone() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidAccessTokenException())
                .when(logout).logout(any(), any());

        mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "opaque-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("a refused logout names neither the token nor which check failed")
    void logoutRefusalLeaksNothing() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidAccessTokenException())
                .when(logout).logout(any(), any());

        String body = mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("naming the failed check turns the endpoint into a forging tutor")
                .doesNotContain("forged.token.value")
                .doesNotContainIgnoringCase("signature")
                .doesNotContainIgnoringCase("expired")
                .doesNotContainIgnoringCase("issuer");
    }

    // ── A-027 · POST /auth/forgot-password ───────────────────────────────────

    private static final String FORGOT_BODY = """
            {"email":"asha.rao@edunext.test"}
            """;

    @Test
    @DisplayName("a forgot-password request is accepted with 202 and no body")
    void forgotPasswordReturnsAccepted() throws Exception {
        when(resetRateLimiter.checkAndSpend(anyString(), anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORGOT_BODY))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    /**
     * The address reaches the service lower-cased and trimmed, because the same
     * string is both a database lookup and a rate-limit key — and
     * {@code Digests.sha256Hex} is case-sensitive where MySQL's collation is
     * not. Without normalising, alternating capitalisation buys a fresh budget
     * each time and the per-address cap is trivially bypassed.
     */
    @Test
    @DisplayName("the address is normalised before it reaches the service or the limiter")
    void normalisesTheAddress() throws Exception {
        when(resetRateLimiter.checkAndSpend(anyString(), anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"  ASHA.RAO@edunext.test  "}
                                """))
                .andExpect(status().isAccepted());

        verify(forgotPassword).requestReset("asha.rao@edunext.test");
        verify(resetRateLimiter).checkAndSpend(eq("asha.rao@edunext.test"), anyString());
    }

    /**
     * The single most important test on this endpoint. An unknown address must
     * be indistinguishable from a known one — the service is what stays silent,
     * and the controller must not turn that silence into a different status.
     */
    @Test
    @DisplayName("an unknown address still answers 202, byte-identical to a known one")
    void unknownAddressIsIndistinguishable() throws Exception {
        when(resetRateLimiter.checkAndSpend(anyString(), anyString())).thenReturn(Optional.empty());

        String known = mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORGOT_BODY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@edunext.test"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown)
                .as("a response that differs for an unknown address is a staff directory")
                .isEqualTo(known);
    }

    /**
     * The budget is spent before the lookup, so throttling reveals nothing about
     * whether the account exists — but the limiter must actually be consulted,
     * or the endpoint is an open mail cannon.
     */
    @Test
    @DisplayName("exceeding the limit is 429 with a Retry-After header, and issues nothing")
    void rateLimitedRequestIs429() throws Exception {
        when(resetRateLimiter.checkAndSpend(anyString(), anyString()))
                .thenReturn(Optional.of(Duration.ofSeconds(420)));

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORGOT_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/too-many-reset-requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "420"));

        verify(forgotPassword, org.mockito.Mockito.never()).requestReset(anyString());
    }

    @Test
    @DisplayName("a malformed address is rejected before any work")
    void rejectsAMalformedAddress() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-address"}
                                """))
                .andExpect(status().isBadRequest());

        verify(forgotPassword, org.mockito.Mockito.never()).requestReset(anyString());
    }

    // ── A-027 · POST /auth/reset-password ────────────────────────────────────

    private static final String RESET_BODY = """
            {"token":"a-token-value-of-at-least-32-characters","newPassword":"Chosen-By-Me-9!"}
            """;

    @Test
    @DisplayName("a successful reset returns 204 with no body")
    void resetPasswordReturnsNoContent() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESET_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(resetPassword).reset("a-token-value-of-at-least-32-characters", "Chosen-By-Me-9!");
    }

    /**
     * 410, per the contract — the token was a real resource with a fixed
     * lifetime and that lifetime is over. A 401 would send the frontend's
     * interceptor to the login screen, which is wrong for a flow whose entire
     * premise is that the user cannot log in.
     */
    @Test
    @DisplayName("an expired, spent or unknown token is 410 with a stable type URI")
    void invalidResetTokenIs410() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidResetTokenException())
                .when(resetPassword).reset(anyString(), anyString());

        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESET_BODY))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-reset-token"))
                .andExpect(jsonPath("$.status").value(410));
    }

    @Test
    @DisplayName("a new password under 8 characters is rejected before any work")
    void rejectsAShortNewPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"a-token-value-of-at-least-32-characters","newPassword":"short1!"}
                                """))
                .andExpect(status().isBadRequest());

        verify(resetPassword, org.mockito.Mockito.never()).reset(anyString(), anyString());
    }

    @Test
    @DisplayName("an obviously-too-short token is rejected without a database round trip")
    void rejectsAShortToken() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"tiny","newPassword":"Chosen-By-Me-9!"}
                                """))
                .andExpect(status().isBadRequest());

        verify(resetPassword, org.mockito.Mockito.never()).reset(anyString(), anyString());
    }

    /**
     * Both values on this endpoint are credentials — the token grants the
     * account, and the password becomes it. A refusal that echoes either writes
     * it into every log and error tracker the response passes through.
     */
    @Test
    @DisplayName("no reset response echoes the token or the password")
    void resetNeverEchoesCredentials() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidResetTokenException())
                .when(resetPassword).reset(anyString(), anyString());

        String body = mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESET_BODY))
                .andExpect(status().isGone())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("a-token-value-of-at-least-32-characters")
                .doesNotContain("Chosen-By-Me-9!");
    }
}
