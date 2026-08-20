package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-025 · signing out, end to end against a real MySQL and a real Redis.
 *
 * <p>{@code LogoutServiceTest} proves the decisions and
 * {@code AccessTokenBlacklistIT} proves the Redis primitive. What only a full
 * stack can show is that the two halves of §10.1's logout actually compose into
 * a session that is over: the access token poisoned, the refresh token gone,
 * <b>and</b> the user's other devices untouched.
 *
 * <p>The scenario the suite is built around:
 *
 * <ol>
 *   <li>Asha signs in on a laptop and on a phone — two independent families.</li>
 *   <li>She signs out of the laptop.</li>
 *   <li>The laptop's refresh token no longer renews, and its access token is on
 *       the blacklist.</li>
 *   <li>The phone is entirely unaffected — it refreshes normally.</li>
 * </ol>
 *
 * <p><b>One thing this cannot yet assert:</b> that a blacklisted access token is
 * <i>rejected</i> on a protected route. Nothing reads the blacklist until
 * A-032's filter chain lands, so the assertions below check the blacklist
 * directly. That gap is real and deliberate — see {@link AccessTokenBlacklist}.
 *
 * <p>Fixtures use {@code IT_SIGNOUT_*} / {@code ITL*} codes, for the collision
 * reason {@code AuthLoginIT} documents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthLogoutIT {

    private static final String PASSWORD = "Correct-Horse-1!";

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0";

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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RefreshTokenStore refreshTokens;

    @Autowired
    AccessTokenBlacklist blacklist;

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_SIGNOUT_DEV', 'Signout Dev', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_SIGNOUT_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('itl.ticket.read', 'Read', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code = 'itl.ticket.read'
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        for (String[] fixture : new String[][]{
                {"ITL001", "itl.asha", "itl.asha@edunext.test", "Asha Rao"},
                {"ITL002", "itl.devices", "itl.devices@edunext.test", "Devices Probe"},
                {"ITL003", "itl.twice", "itl.twice@edunext.test", "Twice Probe"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, ?, 'Asia/Kolkata', 1, 0)
                    """, fixture[0], fixture[1], fixture[2], hash, fixture[3], roleId);
        }

        seeded = true;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private ResponseEntity<String> login(String username) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, CHROME);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD);
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    /**
     * A-074 · the double submit is sent on every logout, exactly as a browser
     * would send it.
     *
     * <p>It is redundant whenever an access token is present — {@code
     * oauth2ResourceServer} exempts any bearer-carrying request from CSRF — but
     * it is <b>not</b> redundant for the cases that deliberately omit the bearer.
     * There, {@code CsrfFilter} runs first and answers 403, so a test written to
     * assert "logout without an access token is 401" would be measuring CSRF
     * instead of authentication, and would keep passing for the wrong reason if
     * the auth check were ever removed.
     */
    private ResponseEntity<String> logout(String accessToken, String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        String csrfCookie = "XSRF-TOKEN=logout-it-csrf";
        headers.set(HttpHeaders.COOKIE,
                refreshToken != null ? "refresh_token=" + refreshToken + "; " + csrfCookie : csrfCookie);
        headers.set("X-XSRF-TOKEN", "logout-it-csrf");
        return rest.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);
    }

    /**
     * A-074 · {@code /auth/refresh} is CSRF-protected and carries no bearer to
     * be exempted by, so this helper sends the double submit a browser would.
     * The logout helper above needs none: its requests carry a bearer token,
     * which {@code oauth2ResourceServer} exempts from CSRF because a
     * cross-origin page cannot set that header.
     */
    private ResponseEntity<String> refresh(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, CHROME);
        headers.set(HttpHeaders.COOKIE, "refresh_token=" + refreshToken + "; XSRF-TOKEN=logout-it-csrf");
        headers.set("X-XSRF-TOKEN", "logout-it-csrf");
        return rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);
    }

    private static String cookieValue(ResponseEntity<String> response) {
        String header = refreshCookieHeader(response);
        String withoutName = header.substring(header.indexOf('=') + 1);
        return withoutName.substring(0, withoutName.indexOf(';'));
    }

    /**
     * A-074 · the refresh cookie, picked by name rather than by being the only one.
     *
     * <p>This asserted {@code hasSize(1)} until CSRF tokens landed. Responses now
     * carry {@code XSRF-TOKEN} as well, so a count is the wrong shape — but
     * simply dropping the assertion would be a weaker test than the one it
     * replaces. Naming what is permitted keeps the original intent intact: one
     * refresh cookie, and nothing else except the CSRF token. A stray third
     * cookie still fails.
     */
    private static String refreshCookieHeader(ResponseEntity<String> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("the response must set the refresh cookie").isNotNull();
        assertThat(cookies)
                .as("no cookie beyond the refresh token and A-074's CSRF token")
                .allMatch(cookie -> cookie.startsWith("refresh_token=") || cookie.startsWith("XSRF-TOKEN="));
        List<String> refresh = cookies.stream().filter(c -> c.startsWith("refresh_token=")).toList();
        assertThat(refresh).as("exactly one refresh_token cookie").hasSize(1);
        return refresh.getFirst();
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new ObjectMapper().readTree(response.getBody());
    }

    private static String accessToken(ResponseEntity<String> response) throws Exception {
        return json(response).path("data").path("accessToken").asText();
    }

    private static String jtiOf(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1])).path("jti").asText();
    }

    // ── the two halves ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a logout returns 204 and clears the refresh cookie")
    void logoutReturns204AndClearsTheCookie() throws Exception {
        ResponseEntity<String> session = login("itl.asha");

        ResponseEntity<String> response = logout(accessToken(session), cookieValue(session));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        // A-074 · the response also carries XSRF-TOKEN, so the refresh cookie is
        // picked by name rather than by being the only one. The assertion that
        // matters is unchanged: the credential is expired, here and now.
        assertThat(refreshCookieHeader(response))
                .contains("refresh_token=")
                .contains("Max-Age=0");
    }

    /**
     * The half that stops the session being <i>renewed</i>. Without it the
     * refresh cookie mints a new access token on the next tick and the logout
     * undoes itself.
     */
    @Test
    @DisplayName("after logout the refresh token is gone and cannot renew the session")
    void theRefreshTokenIsDead() throws Exception {
        ResponseEntity<String> session = login("itl.asha");
        String refreshToken = cookieValue(session);

        logout(accessToken(session), refreshToken);

        assertThat(refreshTokens.find(refreshToken)).isEmpty();
        assertThat(refresh(refreshToken).getStatusCode())
                .as("a session that can still be renewed was never signed out")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The half that closes the window the user believes they just closed. A JWT
     * is valid because it is signed, not because anything server-side still says
     * so — on a shared machine that is up to fifteen minutes of someone else's
     * session after they pressed Sign out.
     */
    @Test
    @DisplayName("after logout the access token's jti is on the blacklist")
    void theAccessTokenIsBlacklisted() throws Exception {
        ResponseEntity<String> session = login("itl.asha");
        String jti = jtiOf(accessToken(session));

        assertThat(blacklist.isRevoked(jti)).as("not blacklisted before logout").isFalse();

        logout(accessToken(session), cookieValue(session));

        assertThat(blacklist.isRevoked(jti)).isTrue();
    }

    /**
     * The stolen-token replay path must stay distinguishable from an ordinary
     * sign-out: a logged-out token presented again is simply unknown, not theft.
     * If logout marked it consumed, this would revoke the family instead.
     */
    @Test
    @DisplayName("replaying a logged-out refresh token is refused as unknown, NOT as theft")
    void aLoggedOutTokenIsNotReportedAsReuse() throws Exception {
        ResponseEntity<String> session = login("itl.asha");
        String refreshToken = cookieValue(session);

        logout(accessToken(session), refreshToken);
        ResponseEntity<String> replay = refresh(refreshToken);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(replay).path("type").asText())
                .as("logout must not arm reuse detection — a stale tab retrying after "
                        + "sign-out would otherwise revoke the user's whole family")
                .isEqualTo("https://edutrack/errors/invalid-refresh-token");
    }

    // ── blast radius ────────────────────────────────────────────────────────

    /**
     * The most important absence in A-025. Family revocation is A-024's response
     * to theft; borrowing it for an ordinary logout signs the user out of every
     * device they own.
     */
    @Test
    @DisplayName("signing out of one device leaves the user's other devices signed in")
    void logoutDoesNotTouchOtherDevices() throws Exception {
        ResponseEntity<String> laptop = login("itl.devices");
        ResponseEntity<String> phone = login("itl.devices");
        String phoneToken = cookieValue(phone);

        logout(accessToken(laptop), cookieValue(laptop));

        assertThat(refresh(phoneToken).getStatusCode())
                .as("signing out of a laptop must not sign the same user out of their phone")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("logout does not blacklist anyone else's access token")
    void logoutIsScopedToOneToken() throws Exception {
        ResponseEntity<String> mine = login("itl.asha");
        ResponseEntity<String> theirs = login("itl.devices");
        String theirJti = jtiOf(accessToken(theirs));

        logout(accessToken(mine), cookieValue(mine));

        assertThat(blacklist.isRevoked(theirJti)).isFalse();
    }

    // ── tolerance ───────────────────────────────────────────────────────────

    /**
     * The people most in need of a working Sign out are those whose session is
     * already half-broken — cookie expired, already rotated, or withheld by
     * {@code SameSite=Strict} on this navigation.
     */
    @Test
    @DisplayName("logout succeeds with no refresh cookie, and still kills the access token")
    void logoutWorksWithoutARefreshCookie() throws Exception {
        ResponseEntity<String> session = login("itl.asha");
        String jti = jtiOf(accessToken(session));

        ResponseEntity<String> response = logout(accessToken(session), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(blacklist.isRevoked(jti))
                .as("the access token must die even when the cookie is already gone")
                .isTrue();
    }

    @Test
    @DisplayName("signing out twice refuses the second attempt, because the token is now revoked")
    void logoutIsNotIdempotentOnceTheTokenIsRevoked() throws Exception {
        // CHANGED BY A-032, deliberately, and worth reading before changing back.
        //
        // This previously asserted 204 twice, on the reasoning that "a
        // double-click must not surface an error to someone signing out". That
        // held only while nothing read A-025's blacklist. A-032 makes the
        // revocation check part of validating any access token, so the second
        // call presents a token this application has already been told to stop
        // honouring — and the alternative to refusing it is carving out an
        // endpoint where revoked tokens still work.
        //
        // The user-facing concern the original test named is handled where it
        // belongs, on the client: useSignOut issues the request WITHOUT awaiting
        // it and clears local state regardless, precisely so a failed logout
        // cannot surface an error. Nobody sees this 401.
        //
        // What is genuinely lost is idempotence for non-browser callers - a
        // script or a future mobile client gets 401 on a retry. That is the
        // honest cost, and it buys a rule with no exceptions in it: a revoked
        // token acts nowhere.
        ResponseEntity<String> session = login("itl.twice");
        String access = accessToken(session);
        String refreshToken = cookieValue(session);

        assertThat(logout(access, refreshToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout(access, refreshToken).getStatusCode())
                .as("the second attempt carries a token that is now on the blacklist")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── the endpoint really is authenticated ────────────────────────────────

    @Test
    @DisplayName("logout without an access token is 401")
    void logoutRequiresAnAccessToken() throws Exception {
        ResponseEntity<String> session = login("itl.asha");

        ResponseEntity<String> response = logout(null, cookieValue(session));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-access-token");
    }

    /**
     * The check that matters most on this endpoint. Accepting an unverified token
     * would let anyone forge a {@code jti} and blacklist a stranger's access
     * token — a logout endpoint for logging other people out.
     */
    @Test
    @DisplayName("a forged access token is rejected and revokes nothing")
    void aForgedTokenIsRejected() throws Exception {
        ResponseEntity<String> session = login("itl.asha");
        String refreshToken = cookieValue(session);
        String realJti = jtiOf(accessToken(session));

        // Same claims, signed with a key that is not ours.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("{\"sub\":\"1\",\"jti\":\"" + realJti + "\",\"iss\":\"https://edutrack\","
                                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 900) + "}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".not-a-valid-signature";

        ResponseEntity<String> response = logout(forged, refreshToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(blacklist.isRevoked(realJti))
                .as("a forged token must not be able to revoke a real session")
                .isFalse();
        assertThat(refreshTokens.find(refreshToken))
                .as("nor delete a real refresh token")
                .isPresent();
    }

    /**
     * <b>A-074 · the CSRF token here is not incidental.</b> {@code CsrfFilter}
     * runs before the bearer filter, and the exemption for bearer-carrying
     * requests keys on the {@code Bearer} scheme — which is precisely what this
     * request does not use. Without a token the answer is 403 from CSRF, and the
     * test would pass or fail on the wrong mechanism entirely.
     *
     * <p>So the request presents one, the way a browser would, and the assertion
     * goes back to being about the thing it is named for: a non-Bearer scheme is
     * refused with 401.
     */
    @Test
    @DisplayName("a non-Bearer Authorization header is rejected")
    void requiresTheBearerScheme() {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==");
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=logout-scheme-probe");
        headers.add("X-XSRF-TOKEN", "logout-scheme-probe");

        ResponseEntity<String> response = rest.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A logout that could not authenticate has ended nothing, so stripping the
     * cookie would half-end a session the caller was never proven to own.
     */
    @Test
    @DisplayName("a refused logout does not clear the cookie")
    void aRefusedLogoutLeavesTheCookieAlone() throws Exception {
        ResponseEntity<String> session = login("itl.asha");

        ResponseEntity<String> response = logout(null, cookieValue(session));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }
}
