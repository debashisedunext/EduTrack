package com.edunext.edutrack.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-074 · the headers every response carries, and the CSRF token the two
 * cookie-authenticated routes now demand.
 *
 * <p>Driven over a real port rather than through {@code MockMvc}: every subject
 * here is produced by a servlet filter, and {@code @AutoConfigureMockMvc} is
 * exactly where filters get switched off. A header test that runs with
 * {@code addFilters = false} asserts nothing at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityHardeningIT {

    /**
     * A-074 · its own infrastructure, matching {@code SecurityChainIT} next door.
     *
     * <p><b>Not optional, and its absence does not look like its cause.</b>
     * Without these the context falls back to whatever {@code spring.datasource}
     * resolves to, Flyway finds a schema missing whichever migration landed most
     * recently, and every case in this class errors identically on
     * {@code Validate failed: Migrations have failed validation} — a message
     * about migrations, in a class that has nothing to do with them, naming no
     * header and no token.
     */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--sql-mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    // ── the headers ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("security headers")
    class Headers {

        /**
         * Asserted on an unauthenticated 401 rather than on a happy path, and
         * that is the point: headers written by a filter that only runs after
         * authentication succeeds would be absent from every refusal, which is
         * the response an attacker sees most often.
         */
        @Test
        @DisplayName("are present on a refusal, not only on a success")
        void headersSurviveARefusal() {
            HttpHeaders headers = get("/api/v1/tickets").getHeaders();
            assertThat(headers.getFirst("Content-Security-Policy")).isNotBlank();
            assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        }

        @Test
        @DisplayName("are present on the SPA shell, which is the document that renders")
        void headersAreOnTheDocument() {
            HttpHeaders headers = get("/").getHeaders();
            assertThat(headers.getFirst("Content-Security-Policy")).isNotBlank();
            assertThat(headers.getFirst("Referrer-Policy"))
                    .isEqualTo("strict-origin-when-cross-origin");
            assertThat(headers.getFirst("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
            assertThat(headers.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
            assertThat(headers.getFirst("Permissions-Policy")).contains("camera=()", "microphone=()", "geolocation=()");
        }

        @Test
        @DisplayName("the CSP closes the directives an injected document would use")
        void cspClosesTheDangerousDirectives() {
            String csp = get("/").getHeaders().getFirst("Content-Security-Policy");
            assertThat(csp)
                    .contains("default-src 'self'")
                    .contains("object-src 'none'")
                    .contains("base-uri 'self'")
                    .contains("form-action 'self'")
                    .contains("frame-ancestors 'none'");
        }

        /**
         * The directive whose absence would be invisible: {@code script-src}
         * without {@code 'unsafe-inline'} is the whole reason a CSP is worth
         * having, and it is also the easiest thing to relax when something
         * breaks. A test that only asserted the header exists would not notice.
         */
        @Test
        @DisplayName("script-src never allows inline or eval")
        void scriptSrcIsNotRelaxed() {
            String csp = get("/").getHeaders().getFirst("Content-Security-Policy");
            String scriptSrc = directive(csp, "script-src");
            assertThat(scriptSrc)
                    .as("an inline script must be allowed by hash, never by 'unsafe-inline'")
                    .doesNotContain("'unsafe-inline'")
                    .doesNotContain("'unsafe-eval'");
        }

        /**
         * HSTS is emitted by Spring only over HTTPS, and this suite speaks
         * plain HTTP — so asserting the header is present here would assert the
         * opposite of the intended behaviour. Asserting its <i>absence</i> is
         * the honest test available at this port, and it also pins that we have
         * not configured the header onto insecure responses, which would teach
         * a browser to refuse http://localhost for two years.
         */
        @Test
        @DisplayName("HSTS is not emitted over plain HTTP")
        void hstsIsHttpsOnly() {
            assertThat(get("/").getHeaders().getFirst("Strict-Transport-Security"))
                    .as("an HSTS header on http://localhost would pin a developer's browser to https for 2 years")
                    .isNull();
        }

        private String directive(String csp, String name) {
            return List.of(csp.split(";")).stream()
                    .map(String::trim)
                    .filter(part -> part.startsWith(name + " "))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + name + " directive in: " + csp));
        }
    }

    // ── CSRF ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CSRF on the cookie-authenticated routes")
    class Csrf {

        /**
         * A browser's double submit, reproduced: the same value in the cookie
         * and in the header. A cross-origin page can produce neither, which is
         * what makes the comparison mean something — and a test can produce
         * both trivially, which is why this is not a weakened assertion.
         */
        private HttpHeaders doubleSubmit(String token) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, CookieRouteCsrf.COOKIE_NAME + "=" + token);
            headers.add(CookieRouteCsrf.HEADER_NAME, token);
            return headers;
        }

        private ResponseEntity<String> post(String path, HttpHeaders headers) {
            return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/auth/refresh", "/api/v1/auth/logout"})
        @DisplayName("a request with no CSRF token is refused")
        void noTokenIsRefused(String path) {
            assertThat(post(path, new HttpHeaders()).getStatusCode())
                    .as("%s reads refresh_token, which a browser attaches by itself", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/auth/refresh", "/api/v1/auth/logout"})
        @DisplayName("a header that disagrees with the cookie is refused")
        void mismatchedTokenIsRefused(String path) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, CookieRouteCsrf.COOKIE_NAME + "=the-real-token");
            headers.add(CookieRouteCsrf.HEADER_NAME, "a-token-an-attacker-guessed");
            assertThat(post(path, headers).getStatusCode())
                    .as("the whole mechanism is that these two must agree")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * The token gets the caller past the CSRF filter — it does not
         * authenticate them. A 403 here would mean the filter is still
         * rejecting; anything else means it let the request through to the
         * handler, which then refuses it on its own terms (no refresh cookie).
         */
        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/auth/refresh", "/api/v1/auth/logout"})
        @DisplayName("a matching token passes the filter and reaches the handler")
        void matchingTokenReachesTheHandler(String path) {
            assertThat(post(path, doubleSubmit("a-matching-token")).getStatusCode())
                    .as("past CSRF; the handler then answers on its own terms")
                    .isNotEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * The cookie alone is not a token. It is half of a double submit, and a
         * request carrying only the half a browser sends automatically is
         * exactly the request the mechanism exists to refuse — so this is the
         * assertion that separates a working double submit from one that merely
         * looks for a cookie.
         */
        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/auth/refresh", "/api/v1/auth/logout"})
        @DisplayName("the cookie without the matching header is refused")
        void cookieWithoutHeaderIsRefused(String path) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, CookieRouteCsrf.COOKIE_NAME + "=a-token");
            assertThat(post(path, headers).getStatusCode())
                    .as("a browser sends the cookie by itself; only script can send the header")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * <b>The exemption that looks like a hole and is not.</b>
         *
         * <p>Spring Security's {@code oauth2ResourceServer} registers a CSRF
         * override that ignores any request carrying an {@code Authorization:
         * Bearer} header, and it applies to these two routes like any other. So
         * {@code POST /auth/logout} with a bearer token succeeds with no CSRF
         * token at all — which is alarming until the question is asked the
         * right way round.
         *
         * <p>The question is not "is CSRF enforced on every request?" but "is it
         * enforced on every request an attacker can forge?". A cross-origin page
         * cannot set an {@code Authorization} header — that is the same property
         * the whole bearer-routes-need-no-CSRF argument rests on — so a forged
         * logout arrives <i>without</i> a bearer and is refused, which
         * {@code noTokenIsRefused} pins directly above. The exemption only ever
         * applies to a request that already proved it was not ambient.
         *
         * <p>Asserted rather than left implicit because this is what keeps
         * {@code SecurityChainIT}'s logout tests passing unchanged, and the next
         * person to notice a 204 here needs to find this paragraph rather than
         * "fix" it by widening the matcher.
         */
        @Test
        @DisplayName("a bearer-authenticated call skips CSRF — nothing a browser can forge carries a bearer")
        void bearerAuthenticatedCallsAreExempt() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth("not-a-real-token");
            assertThat(post("/api/v1/auth/logout", headers).getStatusCode())
                    .as("the bearer is refused on its own terms (401), not held up by CSRF (403)")
                    .isNotEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("the token cookie is issued without being asked for, or a client can never obtain one")
        void theCookieIsIssuedUnprompted() {
            List<String> cookies = get("/api/v1/tickets").getHeaders().get(HttpHeaders.SET_COOKIE);
            assertThat(cookies)
                    .as("deferred token generation would only ever set this on a request that already had one")
                    .isNotNull();
            assertThat(String.join("; ", cookies)).contains(CookieRouteCsrf.COOKIE_NAME + "=");
        }

        /**
         * The other half of {@code CookieRouteCsrfTest}'s unprotected list,
         * asserted through the real filter chain rather than against the
         * matcher in isolation — because "the matcher says no" and "the filter
         * lets it through" are different claims.
         */
        @Test
        @DisplayName("a bearer route takes no CSRF token")
        void bearerRoutesAreUnaffected() {
            assertThat(post("/api/v1/auth/login", new HttpHeaders()).getStatusCode())
                    .as("login carries no cookie credential and must not demand a token")
                    .isNotEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
