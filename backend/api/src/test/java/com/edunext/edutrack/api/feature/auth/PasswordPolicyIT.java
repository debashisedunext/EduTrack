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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-028 · blueprint §10.3's password policy, end to end against a real MySQL.
 *
 * <p>{@code PasswordPolicyTest} and {@code PasswordComplexityValidatorTest} prove
 * the decisions against mocks. What only a full stack can show is that the two
 * rules actually bite on <b>both</b> ways of setting a password, and that
 * history is really written and really consulted:
 *
 * <ol>
 *   <li>Composition is enforced on {@code PATCH /me/password} <i>and</i> on
 *       {@code POST /auth/reset-password} — the weaker path would otherwise be
 *       the way in.</li>
 *   <li>A password used two changes ago is genuinely refused, against real
 *       Argon2id hashes rather than a stubbed {@code matches}.</li>
 *   <li>A password pushed out of the window by three later changes becomes
 *       usable again, which is what "last 3" means and is the half a
 *       too-eager implementation gets wrong.</li>
 * </ol>
 *
 * <p>Fixtures use {@code IT_POLICY_*} / {@code ITQ*} codes, for the collision
 * reason {@code AuthLoginIT} documents. Each destructive test takes its own
 * user: password changes are not repeatable, and a shared row would make the
 * suite depend on JUnit's method order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PasswordPolicyIT {

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

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    /** {@code PATCH} needs a client that knows the verb — see {@code AuthPasswordChangeIT}. */
    @BeforeEach
    void useAClientThatKnowsAboutPatch() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_POLICY_DEV', 'Policy Dev', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_POLICY_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('itq.ticket.read', 'Read', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code = 'itq.ticket.read'
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        for (String[] fixture : new String[][]{
                {"ITQ001", "itq.weak", "itq.weak@edunext.test", "Weak Password"},
                {"ITQ002", "itq.reuse", "itq.reuse@edunext.test", "Reuses Password"},
                {"ITQ003", "itq.window", "itq.window@edunext.test", "Window Walker"},
                {"ITQ004", "itq.reset", "itq.reset@edunext.test", "Resets Weakly"},
                {"ITQ005", "itq.history", "itq.history@edunext.test", "History Writer"},
                // One fixture per destructive test. A password change is not
                // repeatable, so two tests sharing a row would chain — and
                // JUnit's method order is deterministic but unspecified, which
                // makes such a suite pass or fail on an ordering nobody chose.
                // A first draft of this class had three tests sharing itq.stamp
                // and failed exactly that way.
                {"ITQ006", "itq.stamp", "itq.stamp@edunext.test", "Stamp Checker"},
                {"ITQ007", "itq.outgoing", "itq.outgoing@edunext.test", "Outgoing Hash"},
                {"ITQ008", "itq.clock", "itq.clock@edunext.test", "Clock Checker"},
                {"ITQ009", "itq.edge", "itq.edge@edunext.test", "Window Edge"},
                {"ITQ010", "itq.ancient", "itq.ancient@edunext.test", "Ancient Password"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, ?, 'Asia/Kolkata', 1, 0)
                    """, fixture[0], fixture[1], fixture[2], hash, fixture[3], roleId);
        }

        seeded = true;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

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

    private String accessTokenFor(String username, String password) throws Exception {
        ResponseEntity<String> session = login(username, password);
        assertThat(session.getStatusCode()).as("fixture login must succeed").isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(session.getBody()).path("data").path("accessToken").asText();
    }

    private ResponseEntity<String> changePassword(String accessToken, String current, String replacement) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return rest.exchange("/api/v1/me/password", HttpMethod.PATCH,
                new HttpEntity<>("""
                        {"currentPassword":"%s","newPassword":"%s"}"""
                        .formatted(current, replacement), headers), String.class);
    }

    private ResponseEntity<String> resetPassword(String token, String newPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/v1/auth/reset-password",
                new HttpEntity<>("""
                        {"token":"%s","newPassword":"%s"}""".formatted(token, newPassword), headers),
                String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new ObjectMapper().readTree(response.getBody());
    }

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        assertThat(id).isNotNull();
        return id;
    }

    private String issueResetTokenFor(String username) {
        seedOnce();
        String token = "it-policy-token-" + java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, userIdOf(username), Digests.sha256Hex(token),
                LocalDateTime.ofInstant(Instant.now().plus(Duration.ofMinutes(20)), ZoneOffset.UTC));
        return token;
    }

    private int historyCountFor(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_history WHERE user_id = ?", Integer.class, userIdOf(username));
        return count == null ? 0 : count;
    }

    // ── composition, on the change path ─────────────────────────────────────

    /**
     * The exact hole A-028 exists to close. Before this task both endpoints
     * enforced only length, so this password was accepted.
     */
    @Test
    @DisplayName("a long all-lower-case password is refused on PATCH /me/password")
    void changeRefusesALengthOnlyPassword() throws Exception {
        String token = accessTokenFor("itq.weak", PASSWORD);

        ResponseEntity<String> response = changePassword(token, PASSWORD, "aaaaaaaaaaaaaaaa");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login("itq.weak", "aaaaaaaaaaaaaaaa").getStatusCode())
                .as("the refused password must not have been set")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a password missing only a symbol is refused")
    void changeRefusesAMissingSymbol() throws Exception {
        String token = accessTokenFor("itq.weak", PASSWORD);

        assertThat(changePassword(token, PASSWORD, "ChosenByMe99").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a password with all four classes is accepted")
    void changeAcceptsAFullyCompliantPassword() throws Exception {
        String token = accessTokenFor("itq.weak", PASSWORD);

        assertThat(changePassword(token, PASSWORD, "Chosen-By-Me-9!").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login("itq.weak", "Chosen-By-Me-9!").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── composition, on the reset path ──────────────────────────────────────

    /**
     * <b>Both paths or neither.</b> Recovering an account and changing a password
     * from inside a session must not differ in what they accept, or the weaker
     * one becomes the way in — and the reset path is the unauthenticated one.
     */
    @Test
    @DisplayName("the same composition rule is enforced on POST /auth/reset-password")
    void resetRefusesALengthOnlyPassword() {
        String token = issueResetTokenFor("itq.reset");

        ResponseEntity<String> response = resetPassword(token, "aaaaaaaaaaaaaaaa");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login("itq.reset", "aaaaaaaaaaaaaaaa").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A policy refusal must not spend the link. Otherwise reaching for an old
     * favourite password leaves the user refused, out of a valid link, and
     * needing a fresh mail to try again.
     */
    @Test
    @DisplayName("a policy refusal leaves the reset token usable")
    void aPolicyRefusalDoesNotBurnTheToken() {
        String token = issueResetTokenFor("itq.reset");

        assertThat(resetPassword(token, "tooweak").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(resetPassword(token, "Second-Attempt-9!").getStatusCode())
                .as("the same link must still work on a compliant second attempt")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── no reuse of the last three ──────────────────────────────────────────

    /**
     * Against real Argon2id hashes, not a stubbed {@code matches}. Each stored
     * hash carries its own salt, so a candidate that "looks the same" is only
     * detected by actually running the KDF against every entry.
     */
    @Test
    @DisplayName("a password used one change ago is refused")
    void refusesTheImmediatelyPreviousPassword() throws Exception {
        String first = "First-Password-9!";
        String second = "Second-Password-9!";

        String token = accessTokenFor("itq.reuse", PASSWORD);
        assertThat(changePassword(token, PASSWORD, first).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        token = accessTokenFor("itq.reuse", first);
        assertThat(changePassword(token, first, second).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Now try to go back to `first`, which is one change ago.
        token = accessTokenFor("itq.reuse", second);
        ResponseEntity<String> response = changePassword(token, second, first);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/password-reused");
    }

    /**
     * "Last 3" has to mean three — a rule that never forgets is a different rule,
     * and it is the half a too-eager implementation gets wrong. Walking four
     * changes past a password must make it usable again.
     */
    @Test
    @DisplayName("a password pushed out of the window becomes usable again")
    void aPasswordFallsOutOfTheWindow() throws Exception {
        String original = "Original-Pass-9!";

        String token = accessTokenFor("itq.window", PASSWORD);
        assertThat(changePassword(token, PASSWORD, original).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // FOUR fillers, not three, and the arithmetic is worth spelling out
        // because getting it wrong is what a first draft of this test did.
        // History records the password being RETIRED, so `original` does not
        // enter the window when it is set — it enters when it is replaced:
        //
        //   set original  → files PASSWORD    window: [PASSWORD]
        //   → Filler-One  → files original    window: [original, PASSWORD]
        //   → Filler-Two  → files Filler-One  window: [F1, original, PASSWORD]
        //   → Filler-Three→ files Filler-Two  window: [F2, F1, original]
        //   → Filler-Four → files Filler-Three window: [F3, F2, F1]  ← out
        //
        // So `original` is still refused after three fillers, correctly, and
        // only the fourth pushes it clear.
        String current = original;
        for (String next : List.of("Filler-One-9!", "Filler-Two-9!", "Filler-Three-9!", "Filler-Four-9!")) {
            token = accessTokenFor("itq.window", current);
            assertThat(changePassword(token, current, next).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            current = next;
        }

        token = accessTokenFor("itq.window", current);
        assertThat(changePassword(token, current, original).getStatusCode())
                .as("once four later passwords have been filed, the original is outside the last three")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * The other half of the same rule, and the one that proves the window is not
     * simply always-permissive: three fillers is one short, so the original is
     * still inside the last three and must still be refused.
     */
    @Test
    @DisplayName("a password still inside the window is refused")
    void aPasswordJustInsideTheWindowIsRefused() throws Exception {
        String original = "Edge-Original-9!";

        String token = accessTokenFor("itq.edge", PASSWORD);
        assertThat(changePassword(token, PASSWORD, original).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String current = original;
        for (String next : List.of("Edge-One-9!", "Edge-Two-9!", "Edge-Three-9!")) {
            token = accessTokenFor("itq.edge", current);
            assertThat(changePassword(token, current, next).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            current = next;
        }

        token = accessTokenFor("itq.edge", current);
        assertThat(changePassword(token, current, original).getStatusCode())
                .as("three fillers leaves the original as the oldest of the last three")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── what the history table actually holds ───────────────────────────────

    /**
     * The window is pruned, so history cannot grow without bound — every row is
     * a real password hash, so needless rows are needless exposure as well as
     * storage.
     */
    @Test
    @DisplayName("history never exceeds the configured depth")
    void historyIsPrunedToTheWindow() throws Exception {
        String current = PASSWORD;
        for (String next : List.of("Hist-One-9!", "Hist-Two-9!", "Hist-Three-9!", "Hist-Four-9!", "Hist-Five-9!")) {
            String token = accessTokenFor("itq.history", current);
            assertThat(changePassword(token, current, next).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            current = next;
        }

        assertThat(historyCountFor("itq.history"))
                .as("five changes, depth 3 — the older rows must have been pruned")
                .isEqualTo(3);
    }

    /**
     * History holds hashes, never plaintext. A leak of this table must be a leak
     * of retired hashes and nothing more.
     */
    @Test
    @DisplayName("history stores Argon2id hashes, never a plaintext")
    void historyStoresOnlyHashes() throws Exception {
        String replacement = "Never-In-Plaintext-9!";
        String token = accessTokenFor("itq.stamp", PASSWORD);

        assertThat(changePassword(token, PASSWORD, replacement).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<String> stored = jdbc.queryForList(
                "SELECT password_hash FROM password_history WHERE user_id = ?",
                String.class, userIdOf("itq.stamp"));

        assertThat(stored).isNotEmpty();
        assertThat(stored).allSatisfy(hash -> {
            assertThat(hash).startsWith("$argon2id$");
            assertThat(hash).doesNotContain(PASSWORD).doesNotContain(replacement);
        });
    }

    /**
     * The retired hash is the one being <i>replaced</i>. Filing the incoming hash
     * would put the live password in the window and quietly reduce the effective
     * depth by one.
     */
    @Test
    @DisplayName("the hash filed is the outgoing one, not the newly set one")
    void historyHoldsTheOutgoingHash() throws Exception {
        long userId = userIdOf("itq.outgoing");
        String liveHashBefore = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId);

        String token = accessTokenFor("itq.outgoing", PASSWORD);
        assertThat(changePassword(token, PASSWORD, "Rotated-Again-9!").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<String> stored = jdbc.queryForList(
                "SELECT password_hash FROM password_history WHERE user_id = ? ORDER BY id DESC",
                String.class, userId);
        String liveHashAfter = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId);

        assertThat(stored.getFirst())
                .as("the most recently filed row is the hash that was just replaced")
                .isEqualTo(liveHashBefore);
        assertThat(stored)
                .as("the live password must never appear in history")
                .doesNotContain(liveHashAfter);
    }

    /**
     * A-028 · {@code password_changed_at} is what §10.3's optional expiry reads.
     * It has to actually move when a password is set, or the clock is decorative.
     */
    @Test
    @DisplayName("password_changed_at is stamped on every password write")
    void theExpiryClockIsStamped() throws Exception {
        long userId = userIdOf("itq.clock");
        LocalDateTime before = jdbc.queryForObject(
                "SELECT password_changed_at FROM users WHERE id = ?", LocalDateTime.class, userId);

        String token = accessTokenFor("itq.clock", PASSWORD);
        assertThat(changePassword(token, PASSWORD, "Clock-Moves-9!").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        LocalDateTime after = jdbc.queryForObject(
                "SELECT password_changed_at FROM users WHERE id = ?", LocalDateTime.class, userId);

        assertThat(after).isNotNull();
        assertThat(before).isNotNull();
        assertThat(after).isAfter(before);
    }

    /**
     * The default, and a deliberate recommendation rather than an oversight —
     * see {@code PasswordPolicyProperties}. Nothing should acquire forced
     * rotation by accident.
     */
    @Test
    @DisplayName("expiry is off by default, so an ancient password still logs in")
    void expiryIsOffByDefault() {
        seedOnce();
        jdbc.update("""
                UPDATE users SET password_changed_at = ? WHERE username = 'itq.ancient'
                """, LocalDateTime.now(ZoneOffset.UTC).minusDays(1000));

        ResponseEntity<String> session = login("itq.ancient", PASSWORD);

        assertThat(session.getStatusCode())
                .as("a 1000-day-old password must still work while expiry is disabled")
                .isEqualTo(HttpStatus.OK);
        assertThat(session.getBody())
                .as("and must not be reported as needing a change")
                .contains("\"mustChangePassword\":false");
    }
}
