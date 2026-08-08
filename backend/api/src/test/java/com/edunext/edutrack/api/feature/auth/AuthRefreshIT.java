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
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-024 · refresh rotation end to end, against a real MySQL and a real Redis.
 *
 * <p>{@code RefreshRotationServiceTest} proves the decision table with mocks and
 * {@code RefreshTokenStoreIT} proves the Redis primitives. Neither can prove the
 * thing that actually matters, which is that <b>the theft scenario in §10.1 plays
 * out correctly through the whole stack</b> — cookie in, cookie out, Redis state
 * between calls, and a stolen token that stops working along with every session
 * descended from the login it came from.
 *
 * <p>The scenario the suite is built around:
 *
 * <ol>
 *   <li>Asha logs in. Token A.</li>
 *   <li>An attacker copies A.</li>
 *   <li>Asha's browser refreshes normally: A is consumed, B is issued.</li>
 *   <li>The attacker presents A. It is spent, so this is theft — the family
 *       dies.</li>
 *   <li>Asha's B, which was perfectly valid a moment ago, is now refused too.
 *       She logs in again; the attacker cannot.</li>
 * </ol>
 *
 * <p>Fixtures use {@code IT_ROTATE_*} / {@code ITR*} codes for the reason
 * {@code AuthLoginIT} documents: Stream B's B-001 seeds the six system roles,
 * and a fixture claiming {@code DEVELOPER} collides on {@code uq_roles_code} the
 * moment that lands. None of them contains the string "refresh" — several tests
 * assert that word appears nowhere in a response body, which is a real check
 * (it catches a {@code refreshToken} field escaping into the JSON) and would be
 * defeated by a fixture that happened to spell it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthRefreshIT {

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

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_ROTATE_DEV', 'Rotation Dev', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_ROTATE_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('itr.ticket.read', 'Read', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code = 'itr.ticket.read'
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        // One account per scenario. These tests deactivate users and revoke
        // families, so a shared fixture would make them depend on each other's
        // execution order.
        for (String[] fixture : new String[][]{
                {"ITR001", "itr.asha", "itr.asha@edunext.test", "Asha Rao"},
                {"ITR002", "itr.chain", "itr.chain@edunext.test", "Chain Probe"},
                {"ITR003", "itr.leaver", "itr.leaver@edunext.test", "Leaver Probe"},
                {"ITR004", "itr.device", "itr.device@edunext.test", "Device Probe"},
                {"ITR005", "itr.promoted", "itr.promoted@edunext.test", "Promoted Probe"}}) {
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

    private ResponseEntity<String> refresh(String token) {
        return refresh(token, CHROME);
    }

    private ResponseEntity<String> refresh(String token, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        if (userAgent != null) headers.set(HttpHeaders.USER_AGENT, userAgent);
        if (token != null) headers.set(HttpHeaders.COOKIE, "refresh_token=" + token);
        return rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);
    }

    /** The opaque value out of a {@code Set-Cookie}, without normalising anything away. */
    private static String cookieValue(ResponseEntity<String> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("expected exactly one Set-Cookie").isNotNull().hasSize(1);
        String header = cookies.getFirst();
        String withoutName = header.substring(header.indexOf('=') + 1);
        return withoutName.substring(0, withoutName.indexOf(';'));
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new ObjectMapper().readTree(response.getBody());
    }

    private static JsonNode claimsOf(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static String accessToken(ResponseEntity<String> response) throws Exception {
        return json(response).path("data").path("accessToken").asText();
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a refresh returns a new access token and a replacement cookie")
    void refreshRotates() throws Exception {
        ResponseEntity<String> loggedIn = login("itr.asha");
        String tokenA = cookieValue(loggedIn);

        ResponseEntity<String> refreshed = refresh(tokenA);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenB = cookieValue(refreshed);
        assertThat(tokenB)
                .as("a refresh that returns the same token is not rotation")
                .isNotEqualTo(tokenA);
        assertThat(accessToken(refreshed)).isNotBlank().isNotEqualTo(accessToken(loggedIn));
        assertThat(json(refreshed).path("data").path("user").path("displayName").asText())
                .isEqualTo("Asha Rao");
    }

    @Test
    @DisplayName("the successor cookie carries the same protective attributes as the original")
    void theSuccessorCookieIsJustAsProtected() {
        String tokenA = cookieValue(login("itr.asha"));

        List<String> cookies = refresh(tokenA).getHeaders().get(HttpHeaders.SET_COOKIE);

        assertThat(cookies).isNotNull().hasSize(1);
        assertThat(cookies.getFirst())
                .startsWith("refresh_token=")
                .contains("Path=/api/v1/auth")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");
    }

    @Test
    @DisplayName("the rotated token is never in the body")
    void theSuccessorIsNeverInTheBody() {
        String tokenA = cookieValue(login("itr.asha"));
        ResponseEntity<String> refreshed = refresh(tokenA);

        assertThat(refreshed.getBody())
                .doesNotContain(cookieValue(refreshed))
                .doesNotContainIgnoringCase("refresh");
    }

    @Test
    @DisplayName("rotation stays inside the login's family and keeps its deadline")
    void rotationInheritsFamilyAndDeadline() {
        String tokenA = cookieValue(login("itr.chain"));
        StoredRefreshToken a = refreshTokens.find(tokenA).orElseThrow();

        String tokenB = cookieValue(refresh(tokenA));
        StoredRefreshToken b = refreshTokens.find(tokenB).orElseThrow();
        String tokenC = cookieValue(refresh(tokenB));
        StoredRefreshToken c = refreshTokens.find(tokenC).orElseThrow();

        assertThat(b.familyId()).isEqualTo(a.familyId());
        assertThat(c.familyId())
                .as("a family that restarts on each rotation means revocation only ever "
                        + "reaches back to the last refresh")
                .isEqualTo(a.familyId());
        assertThat(c.expiresAt())
                .as("a sliding deadline makes the session unbounded — the only thing that "
                        + "would ever end it is a logout, which an attacker will not perform")
                .isEqualTo(a.expiresAt());
        assertThat(c.jti()).isNotEqualTo(b.jti()).isNotEqualTo(a.jti());
    }

    /**
     * The reason the refresh path re-queries instead of replaying the claims in
     * the stored record. Without it a promotion — or a demotion — would not take
     * effect for seven days.
     */
    @Test
    @DisplayName("scope is re-read on every refresh, so a role change lands within 15 minutes")
    void aRoleChangeLandsAtTheNextRefresh() throws Exception {
        String tokenA = cookieValue(login("itr.promoted"));
        assertThat(claimsOf(accessToken(login("itr.promoted"))).path("projects")).isEmpty();

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITRP', 'Refresh Project')");
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITRP'", Long.class);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'itr.promoted'", Long.class);
        jdbc.update("INSERT INTO project_members (project_id, user_id) VALUES (?, ?)", projectId, userId);

        JsonNode claims = claimsOf(accessToken(refresh(tokenA)));

        assertThat(claims.path("projects").size())
                .as("the new membership must appear without waiting for the refresh token to expire")
                .isEqualTo(1);
    }

    // ── the theft scenario ──────────────────────────────────────────────────

    @Test
    @DisplayName("a consumed token cannot be spent twice")
    void aConsumedTokenIsDead() throws Exception {
        String tokenA = cookieValue(login("itr.asha"));
        refresh(tokenA);

        ResponseEntity<String> replay = refresh(tokenA);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(replay).path("type").asText())
                .isEqualTo("https://edutrack/errors/refresh-token-reuse");
    }

    /**
     * <b>The test A-024 exists for.</b> Everything else can pass while this
     * fails, and the system would look completely healthy right up to the day
     * someone is robbed.
     */
    @Test
    @DisplayName("replaying a stolen token kills the honest user's live session too")
    void reuseRevokesTheWholeFamily() throws Exception {
        // 1. Asha logs in. 2. An attacker copies the cookie.
        String stolen = cookieValue(login("itr.asha"));
        String familyId = refreshTokens.find(stolen).orElseThrow().familyId();

        // 3. Asha's browser refreshes normally. She now holds B; the attacker
        //    still holds the spent A and has no idea.
        String ashasLiveToken = cookieValue(refresh(stolen));
        assertThat(refreshTokens.find(ashasLiveToken)).as("B is live and valid").isPresent();

        // 4. The attacker plays A.
        ResponseEntity<String> theft = refresh(stolen);
        assertThat(theft.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshTokens.isFamilyRevoked(familyId)).isTrue();

        // 5. Asha's B — untouched, unexpired, never presented twice — is refused.
        //    This is the point: a silent re-issue here would let the attacker keep
        //    a parallel session alive indefinitely, so the honest session has to
        //    go down with it.
        ResponseEntity<String> ashaRefreshes = refresh(ashasLiveToken);
        assertThat(ashaRefreshes.getStatusCode())
                .as("the successor of a compromised chain must not survive the detection")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // And she can log in again — revocation ends a session, not an account.
        assertThat(login("itr.asha").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("revoking one login does not touch the same user's other devices")
    void revocationIsScopedToOneLogin() {
        String phone = cookieValue(login("itr.asha"));
        String laptop = cookieValue(login("itr.asha"));

        refresh(phone);
        refresh(phone); // reuse — the phone's family dies

        assertThat(refresh(laptop).getStatusCode())
                .as("one compromised session must not sign the user out everywhere")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a refusal takes the dead cookie away so the browser stops replaying it")
    void refusalsClearTheCookie() {
        String tokenA = cookieValue(login("itr.asha"));
        refresh(tokenA);

        List<String> cookies = refresh(tokenA).getHeaders().get(HttpHeaders.SET_COOKIE);

        assertThat(cookies).isNotNull().hasSize(1);
        assertThat(cookies.getFirst()).contains("refresh_token=").contains("Max-Age=0");
    }

    // ── the ordinary refusals ───────────────────────────────────────────────

    @Test
    @DisplayName("no cookie is a 401, not a 400")
    void noCookieIsUnauthorized() throws Exception {
        seedOnce();
        ResponseEntity<String> response = refresh(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-refresh-token");
    }

    @Test
    @DisplayName("a token that was never issued is refused as an ordinary expiry, not as theft")
    void anUnknownTokenIsNotTheft() throws Exception {
        seedOnce();
        ResponseEntity<String> response = refresh("a-value-that-was-never-issued");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .as("a random value must not be reportable as a security incident, or the "
                        + "alert becomes noise anyone can generate")
                .isEqualTo("https://edutrack/errors/invalid-refresh-token");
    }

    /**
     * The deliberate asymmetry. A Chrome auto-update rewrites the User-Agent, and
     * inside a seven-day window that is close to routine — revoking on it would
     * sign people out after a browser update and file it as theft.
     */
    @Test
    @DisplayName("a different client is refused, but the family survives")
    void aDeviceMismatchDoesNotRevokeTheFamily() throws Exception {
        String tokenA = cookieValue(login("itr.device"));
        String familyId = refreshTokens.find(tokenA).orElseThrow().familyId();

        ResponseEntity<String> fromCurl = refresh(tokenA, "curl/8.4.0");

        assertThat(fromCurl.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(fromCurl).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-refresh-token");
        assertThat(refreshTokens.isFamilyRevoked(familyId))
                .as("a browser update must not be recorded as theft")
                .isFalse();
    }

    /**
     * The other half of re-reading identity on every refresh: an account disabled
     * on Monday must not keep minting access tokens until Sunday.
     */
    @Test
    @DisplayName("a deactivated account cannot renew, and is not called theft either")
    void aDeactivatedAccountCannotRefresh() throws Exception {
        String tokenA = cookieValue(login("itr.leaver"));

        jdbc.update("UPDATE users SET is_active = 0 WHERE username = 'itr.leaver'");

        ResponseEntity<String> response = refresh(tokenA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-refresh-token");
    }

    /**
     * A lock is applied by someone else guessing a password. If it ended live
     * sessions, any outsider could sign any employee out at will — the protection
     * would become the attack.
     */
    @Test
    @DisplayName("A-021's lockout does not end an existing session")
    void aLockedAccountCanStillRefresh() {
        String tokenA = cookieValue(login("itr.asha"));

        jdbc.update("""
                UPDATE users SET locked_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE)
                 WHERE username = 'itr.asha'
                """);

        assertThat(refresh(tokenA).getStatusCode())
                .as("otherwise five wrong guesses by a stranger sign the real user out")
                .isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE users SET locked_until = NULL WHERE username = 'itr.asha'");
    }

    @Test
    @DisplayName("no refusal says which check failed")
    void refusalsAreUniformlyOpaque() {
        seedOnce();
        String unknown = refresh("a-value-that-was-never-issued").getBody();
        String missing = refresh(null).getBody();

        assertThat(unknown)
                .as("distinguishing 'expired' from 'never issued' is a validity oracle over "
                        + "the token space")
                .isEqualTo(missing);
        assertThat(unknown)
                .doesNotContainIgnoringCase("device")
                .doesNotContainIgnoringCase("deactivated")
                .doesNotContainIgnoringCase("revoked");
    }

    /**
     * A refusal that happens to be slow or fast is a signal too, but the property
     * asserted here is the cheap one: a rejected refresh must not run the Argon2id
     * verification that makes login the expensive endpoint. It is a lookup.
     */
    @Test
    @DisplayName("a rejected refresh is cheap — no password hashing on this path")
    void aRejectedRefreshDoesNoKdfWork() {
        seedOnce();
        long start = System.nanoTime();
        refresh("a-value-that-was-never-issued");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed)
                .as("login costs ~175 ms per attempt by design; refresh must not inherit that, "
                        + "or it becomes the cheaper DoS target A-074 is already tracking")
                .isLessThan(Duration.ofMillis(150));
    }
}
