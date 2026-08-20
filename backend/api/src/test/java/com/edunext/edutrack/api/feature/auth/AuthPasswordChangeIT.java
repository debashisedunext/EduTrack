package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-026 · the forced password change, end to end against a real MySQL and a real
 * Redis. Blueprint §10.1, screen S-03.
 *
 * <p>{@code PasswordChangeServiceTest} proves the decisions against mocks. What
 * only a full stack can show is that the change is <b>real</b>: that the new
 * password actually opens the account, that the old one stops doing so, and that
 * {@code must_change_password} is genuinely 0 in the row rather than merely
 * reported as cleared. Those three are the difference between a working forced
 * change and one that flips a flag while leaving the emailed temporary password
 * live.
 *
 * <p><b>One thing this cannot yet assert:</b> that a flagged token is refused on
 * an ordinary route. Nothing consults {@link PasswordChangeGate} until A-032's
 * chain lands — {@code PasswordChangeGateTest} covers the decision directly, and
 * the claim it reads is asserted here on a token minted by a real login.
 *
 * <p>Each destructive test takes its own fixture user: changing a password is
 * not repeatable, and sharing one row would make the suite order-dependent.
 * {@code IT_PWD_*} / {@code ITP*} codes, for the collision reason
 * {@code AuthLoginIT} documents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthPasswordChangeIT {

    private static final String TEMP_PASSWORD = "Temp-Password-1!";
    private static final String NEW_PASSWORD = "Chosen-By-The-User-9!";

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
    AccessTokenBlacklist blacklist;

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    /**
     * <b>Without this the whole suite fails on the verb, not the behaviour.</b>
     * With no Apache HttpClient on the test classpath, {@link TestRestTemplate}
     * falls back to a {@code HttpURLConnection}-backed factory whose method list
     * predates RFC 5789 — every PATCH dies as {@code Invalid HTTP method} before
     * a request is sent. The JDK's own {@code HttpClient} has no such list.
     *
     * <p>Swapped here rather than by adding {@code httpclient5} to the module's
     * dependencies: the endpoint under test is a PATCH because the contract says
     * so, and pulling a transitive HTTP stack into every other stream's build to
     * satisfy one test class is a poor trade. Only the request factory changes —
     * the root URI handler that makes the relative paths above work belongs to
     * the {@code RestTemplate}, not the factory, so it survives.
     */
    @BeforeEach
    void useAClientThatKnowsAboutPatch() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /**
     * Every fixture starts flagged, which is the state A-003's schema puts a
     * newly created user in — {@code must_change_password TINYINT(1) NOT NULL
     * DEFAULT 1}. {@code itp.settled} is the exception, standing in for an
     * account that has already rotated.
     */
    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_PWD_DEV', 'Password Dev', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_PWD_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('itp.ticket.read', 'Read', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code = 'itp.ticket.read'
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(TEMP_PASSWORD);
        for (String[] fixture : new String[][]{
                {"ITP001", "itp.newjoiner", "itp.newjoiner@edunext.test", "New Joiner", "1", "1"},
                {"ITP002", "itp.wrongcurrent", "itp.wrongcurrent@edunext.test", "Wrong Current", "1", "1"},
                {"ITP003", "itp.resubmits", "itp.resubmits@edunext.test", "Resubmits", "1", "1"},
                {"ITP004", "itp.forged", "itp.forged@edunext.test", "Forged Probe", "1", "1"},
                {"ITP005", "itp.tooshort", "itp.tooshort@edunext.test", "Too Short", "1", "1"},
                {"ITP006", "itp.revoked", "itp.revoked@edunext.test", "Revoked Probe", "1", "1"},
                {"ITP007", "itp.neighbour", "itp.neighbour@edunext.test", "Neighbour", "1", "1"},
                {"ITP008", "itp.settled", "itp.settled@edunext.test", "Settled User", "1", "0"},
                // Read-only fixture. It must keep its temporary password, so it
                // cannot be shared with a test that changes one — JUnit's method
                // order is deterministic but unspecified, and a shared row would
                // make this suite pass or fail depending on it.
                // A-074 · two rows split off from itp.wrongcurrent, for the reason
                // ITP009 above already gives. wrongCurrentPasswordsDoNotLockTheAccount
                // spends six wrong guesses deliberately, which is more than the
                // PasswordChangeRateLimiter budget of five — so every later test
                // sharing that row was answered 429 before it reached the check it
                // was written to make. The throttle did not create the
                // order-dependency; it revealed one this fixture table had already
                // decided it did not want.
                {"ITP010", "itp.wrongonce", "itp.wrongonce@edunext.test", "Wrong Once", "1", "1"},
                {"ITP011", "itp.echoprobe", "itp.echoprobe@edunext.test", "Echo Probe", "1", "1"},
                {"ITP009", "itp.firstlogin", "itp.firstlogin@edunext.test", "First Login", "1", "1"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, ?, 'Asia/Kolkata', ?, ?)
                    """, fixture[0], fixture[1], fixture[2], hash, fixture[3], roleId,
                    Integer.valueOf(fixture[4]), Integer.valueOf(fixture[5]));
        }

        seeded = true;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private ResponseEntity<String> login(String username, String password) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, CHROME);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, password);
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> changePassword(String accessToken, String current, String replacement) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        String body = """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, replacement);
        return rest.exchange("/api/v1/me/password", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new ObjectMapper().readTree(response.getBody());
    }

    private static String accessToken(ResponseEntity<String> response) throws Exception {
        return json(response).path("data").path("accessToken").asText();
    }

    private static JsonNode claimsOf(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private boolean mustChangePasswordOf(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT must_change_password FROM users WHERE username = ?", Boolean.class, username));
    }

    private String storedHashOf(String username) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    // ── the flag reaches the client and the token ───────────────────────────

    /**
     * The body half is what S-03 routes on; the claim half is what the server
     * will refuse requests on. Reporting only in the body leaves the flag a
     * suggestion, and the token issued beside it fully privileged.
     */
    @Test
    @DisplayName("a first login reports the flag in the body AND stamps it into the token")
    void aFirstLoginCarriesTheFlagBothWays() throws Exception {
        ResponseEntity<String> session = login("itp.firstlogin", TEMP_PASSWORD);

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(session).path("data").path("mustChangePassword").asBoolean()).isTrue();
        assertThat(claimsOf(accessToken(session))
                .path(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM).asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an account that has already rotated carries no claim at all")
    void aSettledAccountCarriesNoClaim() throws Exception {
        ResponseEntity<String> session = login("itp.settled", TEMP_PASSWORD);

        assertThat(json(session).path("data").path("mustChangePassword").asBoolean()).isFalse();
        assertThat(claimsOf(accessToken(session))
                .has(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM)).isFalse();
    }

    // ── the change itself ───────────────────────────────────────────────────

    /**
     * The whole task in one test: the flag clears, the new password works, and —
     * the assertion that separates a real change from a flipped flag — the old
     * one stops working.
     */
    @Test
    @DisplayName("changing the password clears the flag, and the old password stops working")
    void theChangeIsReal() throws Exception {
        ResponseEntity<String> session = login("itp.newjoiner", TEMP_PASSWORD);
        String hashBefore = storedHashOf("itp.newjoiner");

        ResponseEntity<String> changed = changePassword(accessToken(session), TEMP_PASSWORD, NEW_PASSWORD);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(changed.getBody()).isNull();
        assertThat(mustChangePasswordOf("itp.newjoiner"))
                .as("the flag must actually be 0 in the row, not merely reported as cleared")
                .isFalse();
        assertThat(storedHashOf("itp.newjoiner"))
                .as("a new Argon2id hash, not the temporary one")
                .isNotEqualTo(hashBefore);

        assertThat(login("itp.newjoiner", NEW_PASSWORD).getStatusCode())
                .as("the password the user chose must open their account")
                .isEqualTo(HttpStatus.OK);
        assertThat(login("itp.newjoiner", TEMP_PASSWORD).getStatusCode())
                .as("the administrator-generated password was emailed in plain text — "
                        + "if it still works, nothing was rotated")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The claim is minted once and never mutates, so the observable proof that
     * the server agrees the flag is gone is the <i>next</i> token.
     */
    @Test
    @DisplayName("the next login's token carries no must-change claim")
    void theNextTokenIsClean() throws Exception {
        ResponseEntity<String> session = login("itp.neighbour", TEMP_PASSWORD);
        changePassword(accessToken(session), TEMP_PASSWORD, NEW_PASSWORD);

        ResponseEntity<String> after = login("itp.neighbour", NEW_PASSWORD);

        assertThat(json(after).path("data").path("mustChangePassword").asBoolean()).isFalse();
        assertThat(claimsOf(accessToken(after))
                .has(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM)).isFalse();
    }

    /**
     * Without this the caller is locked out by the flag they just cleared: their
     * live token still says {@code mustChangePassword}, and under A-032 the gate
     * would refuse it for the rest of its fifteen minutes.
     */
    @Test
    @DisplayName("the access token that made the change is revoked")
    void theTokenThatDidItIsRevoked() throws Exception {
        ResponseEntity<String> session = login("itp.revoked", TEMP_PASSWORD);
        String token = accessToken(session);
        String jti = claimsOf(token).path("jti").asText();

        assertThat(blacklist.isRevoked(jti)).as("not revoked before the change").isFalse();

        assertThat(changePassword(token, TEMP_PASSWORD, NEW_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(blacklist.isRevoked(jti))
                .as("the stale must-change claim has to die with the change that cleared it")
                .isTrue();
    }

    // ── refusals, and what they leave behind ────────────────────────────────

    @Test
    @DisplayName("a wrong current password is refused and changes nothing")
    void aWrongCurrentPasswordChangesNothing() throws Exception {
        ResponseEntity<String> session = login("itp.wrongonce", TEMP_PASSWORD);
        String hashBefore = storedHashOf("itp.wrongonce");

        ResponseEntity<String> refused =
                changePassword(accessToken(session), "not-my-password", NEW_PASSWORD);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(refused).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-credentials");
        assertThat(storedHashOf("itp.wrongonce")).isEqualTo(hashBefore);
        assertThat(mustChangePasswordOf("itp.wrongonce")).isTrue();
    }

    /**
     * A-021's lockout guards the login form. Counting failures here would let a
     * stolen token lock the real user out of signing in — a denial of service
     * delivered by the control meant to protect them.
     *
     * <p><b>A-074 · this is now also where the two budgets are seen to be
     * separate.</b> Six wrong guesses exceeds
     * {@code PasswordChangeRateLimiter.MAX_FAILURES}, so the later attempts are
     * refused 429 by the change throttle — and the account is still able to log
     * in, which is the whole point. That is the property A-021's note asked for
     * stated as an assertion rather than as prose: <i>refusing the change never
     * refuses the login</i>.
     *
     * <p>The loop is left at six rather than trimmed to five. It is what makes
     * the throttle fire, and a test that stopped short of it would no longer
     * exercise the interaction it now documents.
     */
    @Test
    @DisplayName("wrong current passwords throttle the change and still never lock the login")
    void wrongCurrentPasswordsDoNotLockTheAccount() throws Exception {
        ResponseEntity<String> session = login("itp.wrongcurrent", TEMP_PASSWORD);
        String token = accessToken(session);

        HttpStatusCode last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            last = changePassword(token, "guess-" + attempt, NEW_PASSWORD).getStatusCode();
        }

        assertThat(last)
                .as("A-074 · the sixth guess is past the five-failure budget and must be refused")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject(
                "SELECT failed_attempts FROM users WHERE username = 'itp.wrongcurrent'", Integer.class))
                .isZero();
        assertThat(login("itp.wrongcurrent", TEMP_PASSWORD).getStatusCode())
                .as("six failed change attempts must not shut the user out of the login form")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The one that makes the forced change mean something. Submitting the
     * temporary password back would otherwise clear the flag and leave it live.
     */
    @Test
    @DisplayName("resubmitting the temporary password is refused and leaves the flag set")
    void resubmittingTheSamePasswordIsRefused() throws Exception {
        ResponseEntity<String> session = login("itp.resubmits", TEMP_PASSWORD);

        ResponseEntity<String> refused =
                changePassword(accessToken(session), TEMP_PASSWORD, TEMP_PASSWORD);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(refused).path("type").asText())
                .isEqualTo("https://edutrack/errors/password-unchanged");
        assertThat(mustChangePasswordOf("itp.resubmits"))
                .as("a flag cleared by a non-change is worse than no flag — it manufactures "
                        + "evidence that the credential was rotated")
                .isTrue();
    }

    @Test
    @DisplayName("a new password under the contract's minimum is refused before any write")
    void aTooShortPasswordIsRefused() throws Exception {
        ResponseEntity<String> session = login("itp.tooshort", TEMP_PASSWORD);
        String hashBefore = storedHashOf("itp.tooshort");

        ResponseEntity<String> refused = changePassword(accessToken(session), TEMP_PASSWORD, "Sh0rt!");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storedHashOf("itp.tooshort")).isEqualTo(hashBefore);
        assertThat(mustChangePasswordOf("itp.tooshort")).isTrue();
    }

    // ── the endpoint really is authenticated ────────────────────────────────

    @Test
    @DisplayName("no access token is 401")
    void requiresAnAccessToken() throws Exception {
        seedOnce();

        ResponseEntity<String> refused = changePassword(null, TEMP_PASSWORD, NEW_PASSWORD);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(refused).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-access-token");
    }

    /**
     * The check that matters most on this endpoint. Accepting an unverified token
     * would let anyone forge a {@code sub} and set a stranger's password —
     * account takeover by HTTP request.
     */
    @Test
    @DisplayName("a forged token cannot set anybody's password")
    void aForgedTokenIsRejected() throws Exception {
        ResponseEntity<String> victim = login("itp.forged", TEMP_PASSWORD);
        long victimId = json(victim).path("data").path("user").path("id").asLong();
        String hashBefore = storedHashOf("itp.forged");

        // The victim's own subject, signed with a key that is not ours.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("{\"sub\":\"" + victimId + "\",\"iss\":\"https://edutrack\","
                                + "\"jti\":\"forged-jti\","
                                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 900) + "}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".not-a-valid-signature";

        ResponseEntity<String> refused = changePassword(forged, TEMP_PASSWORD, NEW_PASSWORD);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(storedHashOf("itp.forged"))
                .as("a forged token must not be able to re-password a real account")
                .isEqualTo(hashBefore);
        assertThat(login("itp.forged", TEMP_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a non-Bearer Authorization header is rejected")
    void requiresTheBearerScheme() {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==");

        ResponseEntity<String> refused = rest.exchange("/api/v1/me/password", HttpMethod.PATCH,
                new HttpEntity<>("""
                        {"currentPassword":"%s","newPassword":"%s"}"""
                        .formatted(TEMP_PASSWORD, NEW_PASSWORD), headers), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The token can be up to fifteen minutes old. An account deactivated inside
     * that window must not be able to set a new password and walk back in.
     */
    @Test
    @DisplayName("a deactivated account cannot set a new password with its live token")
    void aDeactivatedAccountIsRefused() throws Exception {
        ResponseEntity<String> session = login("itp.settled", TEMP_PASSWORD);
        String token = accessToken(session);
        jdbc.update("UPDATE users SET is_active = 0 WHERE username = 'itp.settled'");
        try {
            ResponseEntity<String> refused = changePassword(token, TEMP_PASSWORD, NEW_PASSWORD);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(json(refused).path("type").asText())
                    .isEqualTo("https://edutrack/errors/invalid-access-token");
        } finally {
            jdbc.update("UPDATE users SET is_active = 1 WHERE username = 'itp.settled'");
        }
    }

    /**
     * Passwords are the one thing that must never come back out. A refusal that
     * echoes the rejected value writes it into every log the response passes.
     */
    @Test
    @DisplayName("no refusal echoes either password")
    void refusalsEchoNothing() throws Exception {
        ResponseEntity<String> session = login("itp.echoprobe", TEMP_PASSWORD);

        String body = changePassword(accessToken(session), "wrong-guess-value", "Rejected-Value-9!")
                .getBody();

        assertThat(body)
                .doesNotContain("wrong-guess-value")
                .doesNotContain("Rejected-Value-9!");
    }
}
