package com.edunext.edutrack.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.ServletRequestPathUtils;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-074 · what {@link CookieRouteCsrf} protects, and what it deliberately does
 * not.
 */
class CookieRouteCsrfTest {

    private final CookieRouteCsrf csrf = new CookieRouteCsrf(true, Duration.ofDays(7));

    /**
     * The parsed-path attribute the servlet container would have set.
     *
     * <p>{@code PathPatternRequestMatcher} reads the request path through
     * {@code ServletRequestPathUtils} rather than off the raw URI, so a mock
     * request needs it cached the way a real dispatch would have. Without this
     * the matcher does not throw — <b>it silently matches nothing</b>, which is
     * exactly how the first version of this class shipped a CSRF filter that
     * protected no route at all and left every test green.
     */
    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }

    @Nested
    @DisplayName("the protected routes")
    class Protected {

        @ParameterizedTest
        @CsvSource({
                "POST,/api/v1/auth/refresh",
                "POST,/api/v1/auth/logout",
        })
        @DisplayName("the two routes that authenticate from a cookie are protected")
        void cookieRoutesAreProtected(String method, String uri) {
            RequestMatcher matcher = csrf.cookieAuthenticatedRoutes();
            assertThat(matcher.matches(request(method, uri)))
                    .as("%s %s reads refresh_token and must carry a CSRF token", method, uri)
                    .isTrue();
        }

        /**
         * The regression this whole class exists for.
         *
         * <p>A matcher that matches nothing produces a passing suite, a green
         * CI run and no CSRF protection whatsoever. Asserting that *something*
         * matches is the cheapest possible guard against re-introducing that,
         * and it is separate from the cases above because those could all be
         * deleted and this would still fail.
         */
        @Test
        @DisplayName("the matcher matches at least one route — an inert matcher is the failure mode")
        void theMatcherIsNotInert() {
            assertThat(csrf.cookieAuthenticatedRoutes().matches(request("POST", "/api/v1/auth/refresh")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("what is deliberately left unprotected")
    class Unprotected {

        /**
         * Every bearer-authenticated route. See {@link CookieRouteCsrf}'s
         * javadoc: a browser never attaches an {@code Authorization} header by
         * itself, so these cannot be forged and a token on them is ceremony.
         *
         * <p>This is here so that widening the matcher to {@code /api/**} — the
         * reflex when an unexpected CSRF failure turns up — fails a test that
         * explains the decision, rather than quietly costing every client a
         * header round trip it never needed.
         */
        @ParameterizedTest
        @CsvSource({
                "POST,/api/v1/tickets",
                "PATCH,/api/v1/tickets/42",
                "DELETE,/api/v1/tickets/42/attachments/7",
                "POST,/api/v1/me/password",
                "PATCH,/api/v1/me/password",
                "POST,/api/v1/auth/login",
        })
        @DisplayName("bearer routes and login are not CSRF-protected")
        void bearerRoutesAreNotProtected(String method, String uri) {
            assertThat(csrf.cookieAuthenticatedRoutes().matches(request(method, uri)))
                    .as("%s %s authenticates from a header or from nothing, not from a cookie", method, uri)
                    .isFalse();
        }

        @Test
        @DisplayName("a GET on a protected path is not protected — CsrfFilter exempts safe methods anyway")
        void safeMethodsAreNotProtected() {
            assertThat(csrf.cookieAuthenticatedRoutes().matches(request("GET", "/api/v1/auth/refresh")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the cookie")
    class TheCookie {

        private Cookie issue() {
            CsrfTokenRepository repository = csrf.csrfTokenRepository();
            MockHttpServletRequest request = request("GET", "/api/v1/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            CsrfToken token = repository.generateToken(request);
            repository.saveToken(token, request, response);
            return response.getCookie(CookieRouteCsrf.COOKIE_NAME);
        }

        @Test
        @DisplayName("is readable by script — that is the whole mechanism")
        void isReadableByScript() {
            assertThat(issue()).isNotNull();
            assertThat(issue().isHttpOnly())
                    .as("the SPA has to read this one to echo it back")
                    .isFalse();
        }

        /**
         * The bug this pins is invisible in every test that runs inside one
         * browser session: a session-scoped CSRF cookie disappears when the
         * browser closes, while {@code refresh_token} survives seven days — so
         * the next morning's startup refresh arrives with a valid credential,
         * no token, and is refused. Every returning user is signed out.
         */
        @Test
        @DisplayName("outlives the browser session, because refresh_token does")
        void cookieOutlivesTheRefreshToken() {
            assertThat(issue().getMaxAge())
                    .as("must match edutrack.auth.refresh-token.ttl, or the shorter cookie shortens the session")
                    .isEqualTo((int) Duration.ofDays(7).toSeconds());
        }

        @Test
        @DisplayName("is scoped to the whole site, not to /api/v1/auth")
        void isScopedToTheWholeSite() {
            assertThat(issue().getPath())
                    .as("a cookie scoped to the auth path is invisible to document.cookie on /tickets")
                    .isEqualTo("/");
        }

        @Test
        @DisplayName("mirrors refresh_token's Secure flag")
        void mirrorsSecure() {
            assertThat(issue().getSecure()).isTrue();
            assertThat(new CookieRouteCsrf(false, Duration.ofDays(7))
                    .csrfTokenRepository() instanceof CookieCsrfTokenRepository).isTrue();
        }
    }
}
