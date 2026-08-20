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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-027 · the forgot/reset-password flow, end to end against a real MySQL and a
 * real Redis. Blueprint §10.3, screen S-02.
 *
 * <p>{@code ForgotPasswordServiceTest} and {@code ResetPasswordServiceTest}
 * prove the decisions against mocks. What only a full stack can show is that the
 * flow's two headline promises are real rather than asserted:
 *
 * <ol>
 *   <li><b>Single use.</b> The same emailed link cannot open the account twice —
 *       proved against the actual conditional UPDATE, not a stubbed boolean.</li>
 *   <li><b>"All sessions revoked."</b> A session established on <i>another
 *       device</i> before the reset can no longer refresh afterwards. This is the
 *       assertion the contract's 204 description is making, and mocks cannot
 *       make it.</li>
 * </ol>
 *
 * <p><b>The raw token is read from the database fixture, not from a mail.</b>
 * Only its SHA-256 is stored, so the test mints the token itself and inserts the
 * digest — which is exactly what {@code ForgotPasswordService} does, and lets
 * the redemption half be exercised without depending on D-029/D-030's template
 * rendering, which does not exist yet. The request half is covered separately by
 * asserting the row and the queued mail it produces.
 *
 * <p>Fixtures use {@code IT_RESET_*} / {@code ITR*} codes, for the collision
 * reason {@code AuthLoginIT} documents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthPasswordResetIT {

    private static final String PASSWORD = "Correct-Horse-1!";
    private static final String NEW_PASSWORD = "Reset-By-Mail-9!";

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

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_RESET_DEV', 'Reset Dev', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_RESET_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('itr.ticket.read', 'Read', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code = 'itr.ticket.read'
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        for (String[] fixture : new String[][]{
                {"ITR001", "itr.forgets", "itr.forgets@edunext.test", "Forgets Password", "1"},
                {"ITR002", "itr.redeems", "itr.redeems@edunext.test", "Redeems Once", "1"},
                {"ITR003", "itr.twice", "itr.twice@edunext.test", "Clicks Twice", "1"},
                {"ITR004", "itr.expired", "itr.expired@edunext.test", "Expired Link", "1"},
                {"ITR005", "itr.sessions", "itr.sessions@edunext.test", "Many Devices", "1"},
                {"ITR006", "itr.outstanding", "itr.outstanding@edunext.test", "Many Links", "1"},
                {"ITR007", "itr.disabled", "itr.disabled@edunext.test", "Disabled User", "0"},
                // Owned solely by aRequestIssuesAHashedTokenAndMail, which counts
                // the rows one request produces. Sharing a fixture with a test
                // that also issues tokens makes that count depend on JUnit's
                // method order — deterministic, but unspecified, so the suite
                // would pass or fail on an ordering nobody chose.
                {"ITR008", "itr.requests", "itr.requests@edunext.test", "Requests A Link", "1"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, ?, 'Asia/Kolkata', ?, 0)
                    """, fixture[0], fixture[1], fixture[2], hash, fixture[3], roleId,
                    Integer.valueOf(fixture[4]));
        }

        seeded = true;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private ResponseEntity<String> forgot(String email) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // A distinct forwarded address per call keeps the per-client budget from
        // being the thing that fails an unrelated assertion — the per-address
        // budget is what these tests are about.
        headers.set("X-Forwarded-For", "10.0." + Math.abs(email.hashCode() % 250) + ".1");
        return rest.postForEntity("/api/v1/auth/forgot-password",
                new HttpEntity<>("""
                        {"email":"%s"}""".formatted(email), headers), String.class);
    }

    private ResponseEntity<String> reset(String token, String newPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/v1/auth/reset-password",
                new HttpEntity<>("""
                        {"token":"%s","newPassword":"%s"}""".formatted(token, newPassword), headers),
                String.class);
    }

    private ResponseEntity<String> login(String username, String password) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, CHROME);
        return rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>("""
                        {"username":"%s","password":"%s"}""".formatted(username, password), headers),
                String.class);
    }

    /**
     * A-074 · {@code /auth/refresh} is CSRF-protected and carries no bearer to
     * be exempted by, so this sends the double submit a browser would. Without
     * it these cases would be refused 403 before reaching the revocation logic
     * they are about.
     */
    private ResponseEntity<String> refresh(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, CHROME);
        headers.set(HttpHeaders.COOKIE, "refresh_token=" + refreshToken + "; XSRF-TOKEN=reset-it-csrf");
        headers.set("X-XSRF-TOKEN", "reset-it-csrf");
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
     * dropping the assertion would be weaker than the test it replaces. Naming
     * what is permitted keeps the intent: one refresh cookie, and nothing else
     * except the CSRF token.
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

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        assertThat(id).isNotNull();
        return id;
    }

    /**
     * Mints a token the way {@code ForgotPasswordService} does and inserts its
     * digest, returning the raw value. Stands in for reading the mail, which
     * cannot carry the link until D-029/D-030 render bodies from templates.
     */
    private String issueTokenFor(String username, Instant expiresAt) {
        seedOnce();
        String token = "it-reset-token-" + java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, userIdOf(username), Digests.sha256Hex(token),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return token;
    }

    private String storedHashOf(String username) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    // ── requesting a link ───────────────────────────────────────────────────

    @Test
    @DisplayName("a request for a real address stores a hashed token and queues a mail")
    void aRequestIssuesAHashedTokenAndMail() {
        ResponseEntity<String> response = forgot("itr.requests@edunext.test");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        long userId = userIdOf("itr.requests");
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM password_reset_tokens WHERE user_id = ?", String.class, userId);
        assertThat(storedHash)
                .as("SHA-256 hex — the raw token must never reach this table")
                .isNotNull()
                .hasSize(64)
                .matches("[0-9a-f]{64}");

        Integer queued = jdbc.queryForObject("""
                SELECT COUNT(*) FROM email_log WHERE to_user_id = ? AND event_code = 'PASSWORD_RESET'
                """, Integer.class, userId);
        assertThat(queued).isEqualTo(1);
    }

    /**
     * The property this endpoint exists to protect. An unknown address must be
     * answered identically to a known one — anything else is a staff directory
     * anyone on the internet can read one address at a time.
     */
    @Test
    @DisplayName("an unknown address is answered exactly like a known one")
    void anUnknownAddressIsIndistinguishable() {
        ResponseEntity<String> known = forgot("itr.redeems@edunext.test");
        ResponseEntity<String> unknown = forgot("no-such-person@edunext.test");

        assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getBody()).isEqualTo(known.getBody());

        Integer rows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM email_log WHERE to_email = 'no-such-person@edunext.test'
                """, Integer.class);
        assertThat(rows).as("nothing may be queued for an address with no account").isZero();
    }

    @Test
    @DisplayName("a deactivated account is accepted silently and issues nothing")
    void aDeactivatedAccountIssuesNothing() {
        ResponseEntity<String> response = forgot("itr.disabled@edunext.test");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Integer tokens = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?",
                Integer.class, userIdOf("itr.disabled"));
        assertThat(tokens)
                .as("an account switched off deliberately must not be recoverable")
                .isZero();
    }

    /**
     * Three per address per fifteen minutes. The budget is spent before the
     * lookup, so this fires for unknown addresses too — which is what keeps the
     * 429 from becoming the oracle the 202 exists to prevent.
     */
    @Test
    @DisplayName("a fourth request inside the window is 429 with a Retry-After header")
    void theFourthRequestIsRateLimited() {
        String email = "itr.ratelimit@edunext.test";

        for (int i = 0; i < 3; i++) {
            assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<String> fourth = forgot(email);

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("a 429 with no Retry-After tells a client to back off without saying how far")
                .isNotNull();
    }

    // ── redeeming a link ────────────────────────────────────────────────────

    @Test
    @DisplayName("a valid token sets the new password, and the old one stops working")
    void theResetIsReal() {
        String token = issueTokenFor("itr.redeems", Instant.now().plus(Duration.ofMinutes(20)));
        String hashBefore = storedHashOf("itr.redeems");

        ResponseEntity<String> response = reset(token, NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storedHashOf("itr.redeems")).isNotEqualTo(hashBefore);

        assertThat(login("itr.redeems", NEW_PASSWORD).getStatusCode())
                .as("the password chosen through the reset must open the account")
                .isEqualTo(HttpStatus.OK);
        assertThat(login("itr.redeems", PASSWORD).getStatusCode())
                .as("if the old password still works, nothing was reset")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Single use. A link sitting in a mailbox — possibly the mailbox that was
     * compromised in the first place — must not open the account a second time.
     */
    @Test
    @DisplayName("the same link cannot be redeemed twice")
    void aTokenIsSingleUse() throws Exception {
        String token = issueTokenFor("itr.twice", Instant.now().plus(Duration.ofMinutes(20)));

        assertThat(reset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> second = reset(token, "A-Third-Password-9!");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(json(second).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-reset-token");
        assertThat(login("itr.twice", "A-Third-Password-9!").getStatusCode())
                .as("the second redemption must not have changed anything")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired token is refused and changes nothing")
    void anExpiredTokenIsRefused() throws Exception {
        String token = issueTokenFor("itr.expired", Instant.now().minus(Duration.ofMinutes(1)));
        String hashBefore = storedHashOf("itr.expired");

        ResponseEntity<String> response = reset(token, NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-reset-token");
        assertThat(storedHashOf("itr.expired")).isEqualTo(hashBefore);
    }

    /**
     * An unknown token answers exactly like an expired or spent one. Telling
     * them apart would let anyone holding a token learn whether it was ever
     * real, from an endpoint that requires no authentication.
     */
    @Test
    @DisplayName("an unknown token is refused with the same 410 as an expired one")
    void anUnknownTokenIsRefusedIdentically() throws Exception {
        ResponseEntity<String> response =
                reset("this-token-was-never-issued-by-anyone-at-all", NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-reset-token");
    }

    /**
     * Someone who clicked "forgot password" three times has three live links.
     * Redeeming one must retire the rest — a link that still works after the
     * password has changed is a second, unmonitored way in.
     */
    @Test
    @DisplayName("redeeming one link retires every other outstanding link for that user")
    void redemptionRetiresTheOtherLinks() throws Exception {
        String first = issueTokenFor("itr.outstanding", Instant.now().plus(Duration.ofMinutes(20)));
        String second = issueTokenFor("itr.outstanding", Instant.now().plus(Duration.ofMinutes(20)));
        String third = issueTokenFor("itr.outstanding", Instant.now().plus(Duration.ofMinutes(20)));

        assertThat(reset(second, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(reset(first, "Another-Password-9!").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(reset(third, "Another-Password-9!").getStatusCode()).isEqualTo(HttpStatus.GONE);

        Integer live = jdbc.queryForObject("""
                SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND used_at IS NULL
                """, Integer.class, userIdOf("itr.outstanding"));
        assertThat(live).isZero();
    }

    // ── "all sessions revoked" ──────────────────────────────────────────────

    /**
     * <b>The assertion the contract's 204 is making.</b> Someone resetting a
     * forgotten password is often doing it precisely because they believe
     * somebody else is in the account; leaving that somebody's seven-day refresh
     * token alive would make the whole flow cosmetic.
     */
    @Test
    @DisplayName("a reset kills a session established earlier on another device")
    void aResetRevokesEveryExistingSession() {
        ResponseEntity<String> laptop = login("itr.sessions", PASSWORD);
        ResponseEntity<String> phone = login("itr.sessions", PASSWORD);
        String laptopToken = cookieValue(laptop);
        String phoneToken = cookieValue(phone);

        assertThat(refresh(phoneToken).getStatusCode())
                .as("both sessions renew normally before the reset")
                .isEqualTo(HttpStatus.OK);

        String token = issueTokenFor("itr.sessions", Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(reset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refresh(laptopToken).getStatusCode())
                .as("a refresh token issued before the reset must no longer renew")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The other half: revocation must not outlive itself. A user who resets and
     * then signs in with the new password gets a session that works — otherwise
     * the cutoff would lock them out of the account they just recovered.
     */
    @Test
    @DisplayName("a session created after the reset is unaffected by the revocation")
    void aFreshSessionAfterTheResetWorks() {
        String token = issueTokenFor("itr.forgets", Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(reset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = login("itr.forgets", NEW_PASSWORD);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(refresh(cookieValue(after)).getStatusCode())
                .as("the cutoff must not reach forward past the reset it recorded")
                .isEqualTo(HttpStatus.OK);
    }

    // ── the reset clears the account's other blocked states ─────────────────

    /**
     * Someone resetting a forgotten password very often got here by failing five
     * times. Leaving them locked out of the account they just recovered — while
     * holding a password we know is correct — makes the recovery useless for the
     * next fifteen minutes.
     */
    @Test
    @DisplayName("a reset clears the lockout counter, so the new password works immediately")
    void aResetClearsTheLockout() {
        seedOnce();
        jdbc.update("""
                UPDATE users SET failed_attempts = 5, locked_until = ?
                 WHERE username = 'itr.expired'
                """, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));

        String token = issueTokenFor("itr.expired", Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(reset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(login("itr.expired", NEW_PASSWORD).getStatusCode())
                .as("a recovered account that is still locked has not been recovered")
                .isEqualTo(HttpStatus.OK);
    }
}
