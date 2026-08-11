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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-029 · two-factor authentication end to end against a real MySQL and Redis.
 * Blueprint §10.1 and screen S-04.
 *
 * <p>{@code TotpGeneratorTest} proves the algorithm against the RFC's own
 * vectors and {@code TotpServiceTest} proves the decisions. What only a full
 * stack can show is that the three properties the feature actually promises
 * hold through HTTP, JSON, encryption and the database:
 *
 * <ol>
 *   <li><b>Setup does not enable.</b> An account mid-enrolment still logs in
 *       with a password alone — otherwise a QR that failed to scan locks
 *       somebody out of the account they were protecting.</li>
 *   <li><b>Once confirmed, a password alone is refused</b>, and the right code
 *       lets it through. That is the entire point of the feature.</li>
 *   <li><b>A code cannot be replayed</b>, and a recovery code works exactly
 *       once — the two things that stop an observed login being reusable.</li>
 * </ol>
 *
 * <p>The secret is read out of the database and decrypted here to generate valid
 * codes, which is what an authenticator app would hold. That also proves the
 * round trip: a secret that did not survive encryption and storage would produce
 * codes the server rejects.
 *
 * <p>Fixtures use {@code IT_2FA_*} / {@code ITT*} codes, for the collision reason
 * {@code AuthLoginIT} documents, and each destructive test takes its own user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TwoFactorIT {

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

    /** The real cipher, so the test decrypts exactly what the application wrote. */
    @Autowired
    TotpSecretCipher cipher;

    @Autowired
    TotpGenerator generator;

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_2FA_DEV', '2FA Dev', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_2FA_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('itt.ticket.read', 'Read', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code = 'itt.ticket.read'
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        // STRICTLY ONE FIXTURE PER TEST.
        //
        // Enrolling is not repeatable and it changes how the account logs in:
        // once 2FA is on, the password-only login that `enrol()` starts from
        // returns 401. Two tests sharing a user therefore pass or fail on
        // JUnit's method order, which is deterministic but unspecified — a first
        // draft of this class shared six users and failed exactly that way.
        for (String[] fixture : new String[][]{
                {"ITT001", "itt.setupshape", "itt.setupshape@edunext.test", "Setup Shape"},
                {"ITT002", "itt.setuponly", "itt.setuponly@edunext.test", "Setup Not Confirmed"},
                {"ITT003", "itt.confirms", "itt.confirms@edunext.test", "Confirms Enrolment"},
                {"ITT004", "itt.wrongcode", "itt.wrongcode@edunext.test", "Wrong Confirm Code"},
                {"ITT005", "itt.pwalone", "itt.pwalone@edunext.test", "Password Alone"},
                {"ITT006", "itt.codelogin", "itt.codelogin@edunext.test", "Code Completes Login"},
                {"ITT007", "itt.wrongpw", "itt.wrongpw@edunext.test", "Wrong Password"},
                {"ITT008", "itt.replay", "itt.replay@edunext.test", "Replay Probe"},
                {"ITT009", "itt.recuse", "itt.recuse@edunext.test", "Recovery Login"},
                {"ITT010", "itt.reconce", "itt.reconce@edunext.test", "Recovery Single Use"},
                {"ITT011", "itt.rechash", "itt.rechash@edunext.test", "Recovery Hashing"},
                {"ITT012", "itt.encrypted", "itt.encrypted@edunext.test", "Encryption Probe"},
                {"ITT013", "itt.disablepw", "itt.disablepw@edunext.test", "Disable Needs Password"},
                {"ITT014", "itt.disables", "itt.disables@edunext.test", "Disables Fully"},
                {"ITT015", "itt.reenrol", "itt.reenrol@edunext.test", "Cannot Re-enrol"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, ?, 'Asia/Kolkata', 1, 0)
                    """, fixture[0], fixture[1], fixture[2], hash, fixture[3], roleId);
        }

        seeded = true;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private ResponseEntity<String> login(String username, String password, String totpCode) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, CHROME);
        // Six digits is a TOTP code; anything else can only be a recovery code,
        // and LoginRequest keeps them in separate fields because the contract
        // constrains totpCode to exactly six digits.
        String field = totpCode != null && totpCode.matches("[0-9]{6}") ? "totpCode" : "recoveryCode";
        String body = totpCode == null
                ? """
                  {"username":"%s","password":"%s"}""".formatted(username, password)
                : """
                  {"username":"%s","password":"%s","%s":"%s"}"""
                        .formatted(username, password, field, totpCode);
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    private String accessTokenFor(String username) throws Exception {
        ResponseEntity<String> session = login(username, PASSWORD, null);
        assertThat(session.getStatusCode()).as("fixture login must succeed").isEqualTo(HttpStatus.OK);
        return json(session).path("data").path("accessToken").asText();
    }

    private ResponseEntity<String> post(String path, String accessToken, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return rest.postForEntity(path, new HttpEntity<>(body == null ? "" : body, headers), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new ObjectMapper().readTree(response.getBody());
    }

    /** The secret as an authenticator app would hold it — decrypted from storage. */
    private String liveSecretFor(String username) {
        String stored = jdbc.queryForObject(
                "SELECT totp_secret FROM users WHERE username = ?", String.class, username);
        assertThat(stored).as("an enrolment must have written a secret").isNotNull();
        return cipher.decrypt(stored);
    }

    private String currentCodeFor(String username) {
        String secret = liveSecretFor(username);
        return generator.codeFor(secret, generator.timeStepAt(Instant.now()));
    }

    private boolean isEnabled(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT totp_enabled FROM users WHERE username = ?", Boolean.class, username));
    }

    /** Enrols fully and returns the recovery codes. */
    private List<String> enrol(String username) throws Exception {
        String token = accessTokenFor(username);
        assertThat(post("/api/v1/me/2fa/setup", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> confirmed = post("/api/v1/me/2fa/confirm", token,
                """
                {"code":"%s"}""".formatted(currentCodeFor(username)));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(confirmed).has("data"))
                .as("CONVENTIONS.md §2 — every 2xx JSON body is wrapped in { data }")
                .isTrue();

        JsonNode codes = json(confirmed).path("data").path("recoveryCodes");
        return new ObjectMapper().convertValue(codes, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
    }

    // ── enrolment is two steps ──────────────────────────────────────────────

    @Test
    @DisplayName("setup returns a secret and an otpauth URI an authenticator can read")
    void setupReturnsWhatAnAuthenticatorNeeds() throws Exception {
        String token = accessTokenFor("itt.setupshape");

        ResponseEntity<String> response = post("/api/v1/me/2fa/setup", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // CONVENTIONS.md §2 — asserted explicitly, because the first version of
        // these endpoints returned the body bare and every test here still
        // passed: they read the fields they cared about and nothing looked at
        // the envelope. check-conventions.py caught it in CI instead, which is
        // the right backstop but the wrong first line of defence.
        assertThat(json(response).has("data"))
                .as("every 2xx JSON body is wrapped in { data }")
                .isTrue();

        JsonNode body = json(response).path("data");
        assertThat(body.path("secret").asText()).matches("[A-Z2-7]{32}");
        assertThat(body.path("otpauthUri").asText())
                .startsWith("otpauth://totp/EduTrack:itt.setupshape?")
                .contains("secret=" + body.path("secret").asText())
                .contains("issuer=EduTrack");
    }

    /**
     * <b>The property the two-step design exists for.</b> If setup enabled 2FA,
     * a QR that never scanned would leave this account demanding codes nobody
     * can generate — and it is the account the user was securing.
     */
    @Test
    @DisplayName("setup alone does not enable 2FA — the password still logs in")
    void setupDoesNotLockTheUserOut() throws Exception {
        String token = accessTokenFor("itt.setuponly");
        post("/api/v1/me/2fa/setup", token, null);

        assertThat(isEnabled("itt.setuponly")).isFalse();
        assertThat(login("itt.setuponly", PASSWORD, null).getStatusCode())
                .as("an unconfirmed enrolment must not gate the login")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("confirming with a valid code enables 2FA and returns recovery codes once")
    void confirmEnablesAndIssuesRecoveryCodes() throws Exception {
        List<String> recoveryCodes = enrol("itt.confirms");

        assertThat(isEnabled("itt.confirms")).isTrue();
        assertThat(recoveryCodes).hasSize(10).doesNotHaveDuplicates();
        assertThat(recoveryCodes).allMatch(code -> code.matches("[0-9A-Z]{5}-[0-9A-Z]{5}"));
    }

    @Test
    @DisplayName("confirming with a wrong code leaves 2FA off")
    void aWrongConfirmationCodeLeavesItOff() throws Exception {
        String token = accessTokenFor("itt.wrongcode");
        post("/api/v1/me/2fa/setup", token, null);

        ResponseEntity<String> response = post("/api/v1/me/2fa/confirm", token, """
                {"code":"000000"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-totp-code");
        assertThat(isEnabled("itt.wrongcode")).isFalse();
    }

    // ── the login challenge ─────────────────────────────────────────────────

    /**
     * The entire point of the feature: once 2FA is on, the password alone is not
     * enough.
     */
    @Test
    @DisplayName("once enabled, a password alone is refused with two-factor-required")
    void thePasswordAloneIsNoLongerEnough() throws Exception {
        enrol("itt.pwalone");

        ResponseEntity<String> response = login("itt.pwalone", PASSWORD, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/two-factor-required");
    }

    @Test
    @DisplayName("the password plus the current code logs in")
    void theCodeCompletesTheLogin() throws Exception {
        enrol("itt.codelogin");

        ResponseEntity<String> response =
                login("itt.codelogin", PASSWORD, currentCodeFor("itt.codelogin"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("data").path("accessToken").asText()).isNotBlank();
    }

    /**
     * A wrong password must still answer {@code invalid-credentials} rather than
     * {@code two-factor-required} — otherwise the response tells an attacker
     * which accounts exist and are protected, which is a list of whom to phish.
     */
    @Test
    @DisplayName("a wrong password on a 2FA account never reveals that 2FA is on")
    void aWrongPasswordDoesNotRevealTwoFactor() throws Exception {
        enrol("itt.wrongpw");

        ResponseEntity<String> response = login("itt.wrongpw", "not-the-password", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("type").asText())
                .as("must be indistinguishable from any other bad-password refusal")
                .isEqualTo("https://edutrack/errors/invalid-credentials");
    }

    /**
     * RFC 6238 §5.2. Without this, a code seen in a phishing proxy or over a
     * shoulder stays usable for the rest of its window — which is exactly the
     * attack a second factor is supposed to defeat.
     */
    @Test
    @DisplayName("a code cannot be used twice")
    void aCodeCannotBeReplayed() throws Exception {
        enrol("itt.replay");
        String code = currentCodeFor("itt.replay");

        assertThat(login("itt.replay", PASSWORD, code).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = login("itt.replay", PASSWORD, code);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(replay).path("type").asText())
                .as("a replay is refused with the same body as a wrong code — saying "
                        + "'correct but already used' would confirm the clock and the code")
                .isEqualTo("https://edutrack/errors/invalid-totp-code");
    }

    // ── recovery codes ──────────────────────────────────────────────────────

    /**
     * The reason recovery codes exist at all: without them a lost phone is a
     * permanently locked account whose only exit is an administrator.
     */
    @Test
    @DisplayName("a recovery code substitutes for the authenticator")
    void aRecoveryCodeLogsIn() throws Exception {
        List<String> codes = enrol("itt.recuse");

        ResponseEntity<String> response = login("itt.recuse", PASSWORD, codes.getFirst());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a recovery code works exactly once")
    void aRecoveryCodeIsSingleUse() throws Exception {
        List<String> codes = enrol("itt.reconce");
        String code = codes.getFirst();

        assertThat(login("itt.reconce", PASSWORD, code).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(login("itt.reconce", PASSWORD, code).getStatusCode())
                .as("a spent recovery code must not open the account again")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("recovery codes are stored hashed, never in plaintext")
    void recoveryCodesAreHashed() throws Exception {
        List<String> codes = enrol("itt.rechash");

        List<String> stored = jdbc.queryForList("""
                SELECT rc.code_hash FROM totp_recovery_codes rc
                  JOIN users u ON u.id = rc.user_id
                 WHERE u.username = 'itt.rechash'
                """, String.class);

        assertThat(stored).hasSize(10);
        assertThat(stored).allSatisfy(hash -> assertThat(hash).startsWith("$argon2id$"));
        assertThat(stored).doesNotContainAnyElementsOf(codes);
    }

    // ── the secret at rest ──────────────────────────────────────────────────

    /**
     * A database dump on its own must be worthless. The stored value is
     * ciphertext; only the key — which lives in configuration — turns it back
     * into something that generates codes.
     */
    @Test
    @DisplayName("the stored secret is ciphertext, not the Base32 an authenticator reads")
    void theSecretIsEncryptedAtRest() throws Exception {
        String token = accessTokenFor("itt.encrypted");
        ResponseEntity<String> setup = post("/api/v1/me/2fa/setup", token, null);
        String plaintextSecret = json(setup).path("data").path("secret").asText();

        String stored = jdbc.queryForObject(
                "SELECT totp_secret FROM users WHERE username = 'itt.encrypted'", String.class);

        assertThat(stored).isNotNull().isNotEqualTo(plaintextSecret);
        assertThat(stored).doesNotContain(plaintextSecret);
        // And it round-trips — a secret that did not survive storage would
        // produce codes the server rejects, which would present as "your
        // authenticator is broken".
        assertThat(cipher.decrypt(stored)).isEqualTo(plaintextSecret);
    }

    // ── disable ─────────────────────────────────────────────────────────────

    /**
     * <b>The password requirement is the security of this endpoint.</b> Removing
     * the second factor is the first thing somebody holding a stolen token would
     * do, and a fifteen-minute token must not be enough.
     */
    @Test
    @DisplayName("disabling without the password is refused")
    void disableRequiresThePassword() throws Exception {
        enrol("itt.disablepw");
        String token = accessTokenFor2fa("itt.disablepw");

        ResponseEntity<String> response = post("/api/v1/me/2fa/disable", token, """
                {"password":"not-the-password"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(isEnabled("itt.disablepw")).as("2FA must still be on").isTrue();
    }

    @Test
    @DisplayName("disabling with the password clears the secret and the recovery codes")
    void disableClearsEverything() throws Exception {
        enrol("itt.disables");
        String token = accessTokenFor2fa("itt.disables");

        ResponseEntity<String> response = post("/api/v1/me/2fa/disable", token, """
                {"password":"%s"}""".formatted(PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(isEnabled("itt.disables")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT totp_secret FROM users WHERE username = 'itt.disables'", String.class))
                .as("re-enabling must mean scanning a new QR, not resurrecting the old secret")
                .isNull();

        Integer remaining = jdbc.queryForObject("""
                SELECT COUNT(*) FROM totp_recovery_codes rc
                  JOIN users u ON u.id = rc.user_id
                 WHERE u.username = 'itt.disables'
                """, Integer.class);
        assertThat(remaining).isZero();

        assertThat(login("itt.disables", PASSWORD, null).getStatusCode())
                .as("the password alone works again")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Silently re-enrolling would let a stolen fifteen-minute token swap the
     * second factor for one of the attacker's own — a permanent foothold gained
     * through the feature meant to prevent it.
     */
    @Test
    @DisplayName("setup is refused while 2FA is already enabled")
    void cannotReEnrolWithoutDisablingFirst() throws Exception {
        enrol("itt.reenrol");
        String token = accessTokenFor2fa("itt.reenrol");

        ResponseEntity<String> response = post("/api/v1/me/2fa/setup", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/two-factor-already-enabled");
    }

    /** A token for an account that already has 2FA on — the login needs a code. */
    private String accessTokenFor2fa(String username) throws Exception {
        ResponseEntity<String> session = login(username, PASSWORD, currentCodeFor(username));
        assertThat(session.getStatusCode()).as("2FA login must succeed").isEqualTo(HttpStatus.OK);
        return json(session).path("data").path("accessToken").asText();
    }
}
