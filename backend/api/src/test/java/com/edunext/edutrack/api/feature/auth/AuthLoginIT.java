package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-020 · the login endpoint against a real MySQL, a real Flyway run and a real
 * Argon2id hash. Definition of done: "works against the real backend, not only
 * mocks".
 *
 * <p>{@link AuthenticationServiceTest} proves the logic in isolation; this
 * proves the parts that only fail when they meet each other — the SQL against
 * the actual A-003 columns, the join to {@code roles}, the collation behaviour
 * of the username lookup, and a hash written by {@code common} being verified
 * by {@code api}.
 *
 * <p><b>Fixtures use codes no seed migration will ever claim.</b> Stream B's
 * B-001 seeds the six system roles of blueprint §2, and a fixture inserting its
 * own {@code DEVELOPER} row collides on {@code uq_roles_code} the moment that
 * lands — the failure Debashis fixed in {@code SchemaIntegrationIT} at 9de2ed1.
 * {@code IT_AUTH_*} keeps this suite independent of seed data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthLoginIT {

    private static final String PASSWORD = "Correct-Horse-1!";

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

    /**
     * A-023. The login response now sets a refresh cookie backed by Redis, and
     * {@code RefreshTokenIssuer} degrades to <em>no</em> cookie when the store
     * is unreachable — so without a real broker here the A-023 assertions below
     * would fail against a login that is otherwise working perfectly.
     */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Flyway is redirected here as well as the datasource. application.yml runs
     * migrations as {@code edutrack_migrate} (A-010), which exists only in the
     * docker-compose stack — the container's own user holds the DDL rights this
     * throwaway schema needs.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
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
    org.springframework.data.redis.core.StringRedisTemplate redis;

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    /**
     * A-076 · every test in this class signs in from the same client, so without
     * this they share one rate-limit budget and start depending on execution
     * order — the same coupling the per-scenario A-021 fixtures above exist to
     * avoid, arriving through Redis instead of through the users table.
     *
     * <p>Clearing rather than raising the limits for tests: a suite that runs
     * against different bounds than production is a suite that cannot catch the
     * bounds being wrong.
     */
    @BeforeEach
    void clearRateLimitBudgets() {
        Set<String> keys = redis.keys(LoginRateLimiter.PAIR_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        keys = redis.keys(LoginRateLimiter.SPRAY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /**
     * Seeded once against the migrated schema. Hashed with the production
     * encoder rather than a fixed literal, so the row is exactly what A-026 or
     * Stream B's Resource Master would write.
     */
    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_AUTH_DEV', 'IT Auth Developer', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_AUTH_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('it.ticket.read', 'Read tickets', 'TICKET')");
        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('it.ticket.update', 'Update tickets', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code IN ('it.ticket.read', 'it.ticket.update')
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA001', 'it.asha', 'it.asha@edunext.test', ?, 'Asha Rao',
                        ?, 'Asia/Kolkata', 1, 0)
                """, hash, roleId);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'it.asha'", Long.class);

        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA002', 'it.dormant', 'it.dormant@edunext.test', ?, 'Dormant Devi',
                        ?, 'Asia/Kolkata', 0, 0)
                """, hash, roleId);

        // A-021 fixtures. One per scenario rather than one shared account,
        // because these tests mutate failed_attempts and locked_until and would
        // otherwise depend on each other's execution order.
        for (String[] fixture : new String[][]{
                {"ITA003", "it.counter", "it.counter@edunext.test", "Counter Probe"},
                {"ITA004", "it.locker", "it.locker@edunext.test", "Locker Probe"},
                {"ITA005", "it.lockedout", "it.lockedout@edunext.test", "Lockedout Probe"},
                {"ITA006", "it.lapsed", "it.lapsed@edunext.test", "Lapsed Probe"},
                // A-076. One per scenario for the reason above, and separate from
                // the A-021 four because these spend far more than five failures
                // and would lock accounts those tests depend on being unlocked.
                {"ITA010", "it.rate.pair", "it.rate.pair@edunext.test", "Rate Pair Probe"},
                {"ITA011", "it.rate.order", "it.rate.order@edunext.test", "Rate Order Probe"},
                {"ITA012", "it.rate.success", "it.rate.success@edunext.test", "Rate Success Probe"},
                {"ITA013", "it.rate.case", "it.rate.case@edunext.test", "Rate Case Probe"},
                {"ITA014", "it.rate.honest", "it.rate.honest@edunext.test", "Rate Honest Probe"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, ?, 'Asia/Kolkata', 1, 0)
                    """, fixture[0], fixture[1], fixture[2], hash, fixture[3], roleId);
        }

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITAP', 'IT Auth Project')");
        Long projectId = jdbc.queryForObject("SELECT id FROM projects WHERE project_code = 'ITAP'", Long.class);
        jdbc.update("INSERT INTO project_members (project_id, user_id) VALUES (?, ?)", projectId, userId);

        seeded = true;
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0";

    private ResponseEntity<String> login(String username, String password) {
        return login(username, password, USER_AGENT);
    }

    private ResponseEntity<String> login(String username, String password, String userAgent) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, password);
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
    }

    // ── the success path ────────────────────────────────────────────────────

    @Test
    @DisplayName("correct credentials return the caller's identity and resolved scope")
    void validLoginReturnsScopedIdentity() throws Exception {
        ResponseEntity<String> response = login("it.asha", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode user = json(response).path("data").path("user");
        assertThat(user.path("displayName").asText()).isEqualTo("Asha Rao");
        assertThat(user.path("role").asText()).isEqualTo("IT_AUTH_DEV");
        assertThat(user.path("email").asText()).isEqualTo("it.asha@edunext.test");
        assertThat(user.path("timezone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(user.path("permissions").isArray()).isTrue();
        assertThat(user.path("permissions").size()).isEqualTo(2);
        assertThat(user.path("projectIds").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the username lookup is case-insensitive, as the column collation is")
    void usernameMatchIsCaseInsensitive() {
        // utf8mb4_0900_ai_ci. Asserted rather than assumed, because switching the
        // column to a _bin collation later would silently start rejecting people
        // who capitalise their own name.
        assertThat(login("IT.Asha", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("S-01's single field accepts an email address as well as a username")
    void emailIsAcceptedAsTheLoginIdentifier() throws Exception {
        // Blueprint §7.1 specifies one "Username / Email" field. This shipped
        // resolving usernames only, and because A-020 makes every refusal
        // byte-identical, an address typed into that box was indistinguishable
        // from a wrong password — invisible from the outside, and it cost real
        // attempts towards a lockout.
        ResponseEntity<String> response = login("it.asha@edunext.test", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("data").path("user").path("username").asText())
                .isEqualTo("it.asha");
    }

    @Test
    @DisplayName("the email lookup is case-insensitive too")
    void emailMatchIsCaseInsensitive() {
        assertThat(login("IT.Asha@Edunext.Test", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a username cannot shadow another account's email address")
    void aUsernameShapedLikeAnEmailCannotInterceptTheRealOwner() {
        // The reason the resolver branches on '@' rather than running
        // `username = ? OR email = ?`. Both columns are independently unique, so
        // nothing stops this pair existing — and under an OR, which row comes
        // back first is not something the schema promises.
        //
        // A dedicated victim rather than it.asha, following this file's own
        // convention above: the impostor arm below is a failed login, and
        // spending it.asha's attempts here would couple this test to A-021's
        // lockout tests through failed_attempts.
        seedOnce();
        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA007', 'it.victim', 'it.victim@edunext.test', ?, 'Victim Verma',
                        (SELECT id FROM roles WHERE code = 'IT_AUTH_DEV'), 'Asia/Kolkata', 1, 0)
                """, hash);
        // The impostor holds the victim's ADDRESS as its USERNAME.
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA008', 'it.victim@edunext.test', 'it.impostor@edunext.test', ?, 'Impostor',
                        (SELECT id FROM roles WHERE code = 'IT_AUTH_DEV'), 'Asia/Kolkata', 1, 0)
                """, PasswordHashing.argon2id().encode("Impostor-Pass-1!"));

        // The impostor's own password must not open the address it squats on.
        assertThat(login("it.victim@edunext.test", "Impostor-Pass-1!").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // The real owner's still does.
        assertThat(login("it.victim@edunext.test", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ── A-031 · the role-based landing route ────────────────────────────────

    @Test
    @DisplayName("the landing route reaches the wire, and differs by role")
    void landingRouteIsResolvedPerRole() throws Exception {
        seedOnce();
        // Real role codes, seeded by V20260806_0900 and already in the migrated
        // schema — not this class's synthetic IT_AUTH_DEV. The point of the test
        // is the mapping the application actually ships.
        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        for (String[] spec : new String[][]{
                {"ITA020", "it.land.admin", "ADMIN", "/dashboard"},
                {"ITA021", "it.land.pm", "PM", "/dashboard"},
                {"ITA022", "it.land.dev", "DEVELOPER", "/my-tasks"},
                {"ITA023", "it.land.support", "SUPPORT", "/tickets"},
                {"ITA024", "it.land.qa", "QA", "/stages/queue"},
                {"ITA025", "it.land.deploy", "DEPLOYMENT", "/stages/queue"}}) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES (?, ?, ?, ?, ?, (SELECT id FROM roles WHERE code = ?), 'Asia/Kolkata', 1, 0)
                    """, spec[0], spec[1], spec[1] + "@edunext.test", hash, spec[1], spec[2]);

            ResponseEntity<String> response = login(spec[1], PASSWORD);
            assertThat(response.getStatusCode()).as("role %s", spec[2]).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("data").path("landingRoute").asText())
                    .as("role %s", spec[2])
                    .isEqualTo(spec[3]);
        }
    }

    @Test
    @DisplayName("a role with no mapping still returns a route, rather than omitting the field")
    void anUnmappedRoleStillLands() throws Exception {
        // it.asha holds this class's synthetic IT_AUTH_DEV, which LandingRoutes
        // has never heard of — the same position a role added by B-011 and never
        // mapped would be in. Session omits null fields, so returning null here
        // would drop landingRoute off the wire entirely and leave the frontend
        // unable to tell "not mapped" from "server too old to send it".
        JsonNode data = json(login("it.asha", PASSWORD)).path("data");

        assertThat(data.has("landingRoute")).isTrue();
        assertThat(data.path("landingRoute").asText()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("a must-change-password session still carries its landing route")
    void landingRouteSurvivesTheForcedChangeGate() throws Exception {
        // S-03 reads this field to know where to send the user once they have set
        // a new password. A first sign-in is exactly the journey where the
        // role-based destination matters most, and the one place a missing value
        // is hardest to spot — everyone simply ends up on the dashboard.
        seedOnce();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA026', 'it.land.fresh', 'it.land.fresh@edunext.test', ?, 'Fresh Starter',
                        (SELECT id FROM roles WHERE code = 'DEVELOPER'), 'Asia/Kolkata', 1, 1)
                """, PasswordHashing.argon2id().encode(PASSWORD));

        JsonNode data = json(login("it.land.fresh", PASSWORD)).path("data");

        assertThat(data.path("mustChangePassword").asBoolean()).isTrue();
        assertThat(data.path("landingRoute").asText()).isEqualTo("/my-tasks");
    }

    // ── A-076 · the login throttle ──────────────────────────────────────────

    /**
     * A-021 locks at five failures, and these tests spend ten or more. Clearing
     * the lock keeps each test about the one bound it names — a 423 arriving
     * mid-run would fail the assertion for a reason that has nothing to do with
     * the throttle. The ordering of the two bounds gets its own test below.
     */
    private void clearAccountLock(String username) {
        jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE username = ?", username);
    }

    @Test
    @DisplayName("attempts against one identifier are capped, and the refusal carries Retry-After")
    void thePairBudgetIsBounded() {
        for (int i = 0; i < LoginRateLimiter.MAX_PER_PAIR; i++) {
            assertThat(login("it.rate.pair", "Wrong-Horse-9!").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> throttled = login("it.rate.pair", "Wrong-Horse-9!");
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Without Retry-After a well-behaved client cannot tell how long to back
        // off, retries immediately, and stays refused.
        assertThat(throttled.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(throttled.getBody()).contains("too-many-login-attempts");
    }

    @Test
    @DisplayName("the cap sits above A-021's lockout, so an account can still lock")
    void theThrottleDoesNotPreEmptTheAccountLockout() {
        // If this limit bit first, five failures would never be reached, no
        // account would ever lock, and no admin would ever be told. The ordering
        // of the two bounds is the assertion.
        assertThat(LoginRateLimiter.MAX_PER_PAIR).isGreaterThan(5);

        for (int i = 0; i < 5; i++) {
            login("it.rate.order", "Wrong-Horse-9!");
        }
        assertThat(login("it.rate.order", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    @DisplayName("the 429 is keyed on what was submitted, so it says nothing about existence")
    void theThrottleIsNotAnEnumerationOracle() {
        // The reason this check can run before the KDF at all. A name that has
        // never existed must be throttled on exactly the same terms as a real
        // one — otherwise "which identifier gets 429'd" answers "which identifier
        // exists", and running early would have traded a DoS gap for an oracle.
        for (int i = 0; i < LoginRateLimiter.MAX_PER_PAIR; i++) {
            login("it.no.such.person", "Wrong-Horse-9!");
        }

        assertThat(login("it.no.such.person", "Wrong-Horse-9!").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a successful sign-in clears the pair budget")
    void successReleasesTheBudget() {
        // Otherwise someone signing in and out through the day is eventually
        // refused for succeeding. Safe because a caller who can authenticate
        // already holds the password there is nothing left to protect.
        for (int i = 0; i < LoginRateLimiter.MAX_PER_PAIR - 1; i++) {
            login("it.rate.success", "Wrong-Horse-9!");
        }
        clearAccountLock("it.rate.success");
        assertThat(login("it.rate.success", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Budget reset — a fresh run of the same size is still allowed, where
        // without the clear the very next attempt would be the eleventh.
        for (int i = 0; i < LoginRateLimiter.MAX_PER_PAIR - 1; i++) {
            assertThat(login("it.rate.success", "Wrong-Horse-9!").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("failing against many distinct identifiers trips spray detection")
    void sprayDetectionBoundsDistinctFailedIdentifiers() {
        // The gap failed_attempts cannot close: one password against many names
        // never reaches five failures on any single account.
        for (int i = 0; i <= LoginRateLimiter.MAX_DISTINCT_FAILED_USERNAMES; i++) {
            login("it.sprayed." + i, "One-Password-For-All-1!");
        }

        // A name never tried before, from the same source, is now refused —
        // the budget is the source's, not the account's.
        assertThat(login("it.entirely.fresh", "One-Password-For-All-1!").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("successful sign-ins never enter the spray set")
    void honestTrafficDoesNotFeedSprayDetection() {
        // This is what keeps the NAT problem from returning: two hundred people
        // behind one gateway are two hundred distinct identifiers, and counting
        // attempts rather than failures would throttle the whole office before
        // anything had gone wrong.
        for (int i = 0; i <= LoginRateLimiter.MAX_DISTINCT_FAILED_USERNAMES; i++) {
            assertThat(login("it.rate.honest", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // Still 401 rather than 429: the successes contributed nothing, so this
        // source's spray budget is untouched.
        assertThat(login("it.dormant", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("capitalisation does not buy a fresh budget")
    void theBudgetIsCaseInsensitiveLikeTheColumn() {
        // Both identifier columns are utf8mb4_0900_ai_ci, so IT.Asha and it.asha
        // are one account. Keying on the raw string would hand out a new budget
        // per capitalisation and make the limit free to evade.
        for (int i = 0; i < LoginRateLimiter.MAX_PER_PAIR; i++) {
            login(i % 2 == 0 ? "it.rate.case" : "IT.Rate.Case", "Wrong-Horse-9!");
        }

        assertThat(login("It.RaTe.CaSe", "Wrong-Horse-9!").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ── the failure paths, which must be indistinguishable ──────────────────

    @Test
    @DisplayName("an unknown user and a wrong password produce byte-identical refusals")
    void failuresAreIndistinguishable() {
        ResponseEntity<String> unknownUser = login("it.nobody", PASSWORD);
        ResponseEntity<String> wrongPassword = login("it.asha", "Wrong-Horse-9!");

        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    @DisplayName("a deactivated account is refused, and refused identically")
    void deactivatedAccountIsRefusedIdentically() {
        ResponseEntity<String> dormant = login("it.dormant", PASSWORD);
        ResponseEntity<String> unknown = login("it.nobody", PASSWORD);

        assertThat(dormant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(dormant.getBody()).isEqualTo(unknown.getBody());
    }

    @Test
    @DisplayName("the refusal is problem+json with the stable type URI")
    void refusalIsRfc9457() throws Exception {
        ResponseEntity<String> response = login("it.nobody", PASSWORD);

        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-credentials");
    }

    @Test
    @DisplayName("no refusal leaks the username, the password or the reason")
    void refusalLeaksNothing() {
        assertThat(login("it.asha", "Wrong-Horse-9!").getBody())
                .doesNotContain("it.asha")
                .doesNotContain("Wrong-Horse-9!")
                .doesNotContainIgnoringCase("not found")
                .doesNotContainIgnoringCase("inactive");
    }

    // ── A-021 · the counter, against a real database ────────────────────────

    /**
     * The test that catches the two mistakes {@code LoginAttemptRecorder}'s
     * javadoc warns about. Both — rolling the increment back with the thrown
     * exception, and losing {@code @Transactional} to self-invocation — leave
     * every unit test green and the counter permanently at zero. Only a real
     * transaction manager and a real database show it.
     */
    @Test
    @DisplayName("a failed attempt is persisted, not rolled back with the exception")
    void failedAttemptSurvivesTheRollback() {
        seedOnce();
        jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE username = 'it.counter'");

        login("it.counter", "Wrong-Horse-9!");

        Integer attempts = jdbc.queryForObject(
                "SELECT failed_attempts FROM users WHERE username = 'it.counter'", Integer.class);
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("the fifth failure locks the account and zeroes the counter")
    void fifthFailureLocks() {
        seedOnce();
        jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE username = 'it.locker'");

        for (int i = 0; i < 5; i++) {
            assertThat(login("it.locker", "Wrong-Horse-9!").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        Integer attempts = jdbc.queryForObject(
                "SELECT failed_attempts FROM users WHERE username = 'it.locker'", Integer.class);
        Object lockedUntil = jdbc.queryForObject(
                "SELECT locked_until FROM users WHERE username = 'it.locker'", Object.class);

        assertThat(lockedUntil).as("locked_until is stamped on the fifth failure").isNotNull();
        assertThat(attempts).as("the counter resets so the next window is a fresh five").isZero();
    }

    @Test
    @DisplayName("a locked account returns 423 to the CORRECT password, and 401 to a wrong one")
    void lockedAccountReportsOnlyToTheRightPassword() throws Exception {
        seedOnce();
        jdbc.update("""
                UPDATE users SET locked_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE)
                 WHERE username = 'it.lockedout'
                """);

        ResponseEntity<String> wrongPassword = login("it.lockedout", "Wrong-Horse-9!");
        assertThat(wrongPassword.getStatusCode())
                .as("a wrong password must not reveal the lock")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> correctPassword = login("it.lockedout", PASSWORD);
        assertThat(correctPassword.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        JsonNode problem = json(correctPassword);
        assertThat(problem.path("type").asText()).isEqualTo("https://edutrack/errors/account-locked");
        assertThat(problem.path("lockedUntil").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a lapsed lock lets the user back in with no cleanup job")
    void lapsedLockSelfHeals() {
        seedOnce();
        jdbc.update("""
                UPDATE users SET locked_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE)
                 WHERE username = 'it.lapsed'
                """);

        assertThat(login("it.lapsed", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a successful login clears a partial counter")
    void successResetsTheCounter() {
        seedOnce();
        jdbc.update("UPDATE users SET failed_attempts = 3, locked_until = NULL WHERE username = 'it.asha'");

        assertThat(login("it.asha", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer attempts = jdbc.queryForObject(
                "SELECT failed_attempts FROM users WHERE username = 'it.asha'", Integer.class);
        assertThat(attempts).isZero();
    }

    // ── A-022 · the access token ─────────────────────────────────────────────

    /**
     * Decodes the JWT payload without verifying the signature — signature
     * correctness against the configured secret is {@code AccessTokenIssuerTest}'s
     * job. What only a real login can prove is that the claims embedded in the
     * token match the scope this same request just resolved from the database.
     */
    private static JsonNode decodePayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).as("a compact JWS has three segments").hasSize(3);
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
    }

    @Test
    @DisplayName("a successful login returns a JWT whose claims match the resolved scope")
    void issuesAccessTokenWithResolvedScope() throws Exception {
        JsonNode session = json(login("it.asha", PASSWORD)).path("data");

        assertThat(session.path("expiresIn").asInt()).isEqualTo(900);
        String token = session.path("accessToken").asText();
        assertThat(token).isNotBlank();

        JsonNode claims = decodePayload(token);
        assertThat(claims.path("role").asText()).isEqualTo("IT_AUTH_DEV");
        assertThat(claims.path("permissions").size()).isEqualTo(2);
        assertThat(claims.path("projects").size()).isEqualTo(1);
        assertThat(claims.path("jti").asText()).isNotBlank();
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(900);
    }

    @Test
    @DisplayName("two logins mint two different token ids")
    void eachLoginMintsAFreshJti() throws Exception {
        String first = decodePayload(json(login("it.asha", PASSWORD)).path("data").path("accessToken").asText())
                .path("jti").asText();
        String second = decodePayload(json(login("it.asha", PASSWORD)).path("data").path("accessToken").asText())
                .path("jti").asText();

        assertThat(first).isNotEqualTo(second);
    }

    // ── A-023 · the refresh cookie ───────────────────────────────────────────

    /**
     * The {@code Set-Cookie} header as the server actually wrote it.
     *
     * <p>Read raw rather than through {@code HttpCookie.parse}, because the
     * attributes are the substance of this task — a parser that normalises
     * {@code SameSite} away would let the assertion pass on a cookie that no
     * browser would treat as strict.
     */
    private static String setCookieHeader(ResponseEntity<String> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("the login response must set exactly one cookie").hasSize(1);
        return cookies.getFirst();
    }

    private static String cookieValue(ResponseEntity<String> response) {
        String header = setCookieHeader(response);
        String withoutName = header.substring(header.indexOf('=') + 1);
        return withoutName.substring(0, withoutName.indexOf(';'));
    }

    @Test
    @DisplayName("a real login sets the refresh cookie with every documented attribute")
    void loginSetsTheRefreshCookie() {
        String header = setCookieHeader(login("it.asha", PASSWORD));

        assertThat(header)
                .startsWith("refresh_token=")
                .contains("Max-Age=604800")
                .contains("Path=/api/v1/auth")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");
    }

    /**
     * The guarantee the cookie exists for. A refresh token in the JSON body
     * would be stored by the frontend somewhere script can reach, which turns
     * a fifteen-minute XSS exposure into a seven-day one.
     */
    @Test
    @DisplayName("the refresh token appears in the header and nowhere in the body")
    void theRefreshTokenIsNeverInTheBody() {
        ResponseEntity<String> response = login("it.asha", PASSWORD);

        assertThat(response.getBody())
                .doesNotContain(cookieValue(response))
                .doesNotContainIgnoringCase("refresh");
    }

    @Test
    @DisplayName("the token in the cookie resolves to a Redis record for the user who logged in")
    void theCookieIsBackedByAStoredRecord() {
        ResponseEntity<String> response = login("it.asha", PASSWORD);
        Long expectedUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'it.asha'", Long.class);

        StoredRefreshToken stored = refreshTokens.find(cookieValue(response)).orElseThrow();

        assertThat(stored.userId()).isEqualTo(expectedUserId);
        assertThat(stored.jti()).isNotBlank();
        assertThat(stored.familyId()).isNotBlank();
        assertThat(stored.matchesDevice(USER_AGENT))
                .as("the fingerprint must be of the User-Agent that actually logged in — "
                        + "this is the check A-024 runs on every refresh")
                .isTrue();
    }

    @Test
    @DisplayName("the same account logging in from two browsers gets two independent families")
    void twoLoginsAreTwoIndependentSessions() {
        String firstToken = cookieValue(login("it.asha", PASSWORD, USER_AGENT));
        String secondToken = cookieValue(login("it.asha", PASSWORD, "curl/8.4.0"));

        StoredRefreshToken first = refreshTokens.find(firstToken).orElseThrow();
        StoredRefreshToken second = refreshTokens.find(secondToken).orElseThrow();

        assertThat(firstToken).isNotEqualTo(secondToken);
        assertThat(first.familyId())
                .as("A-024 revokes a family on reuse; sharing one across logins would log "
                        + "the user out of every device instead of the compromised one")
                .isNotEqualTo(second.familyId());
        assertThat(second.matchesDevice("curl/8.4.0")).isTrue();
        assertThat(second.matchesDevice(USER_AGENT)).isFalse();
    }

    @Test
    @DisplayName("a refused login sets no cookie at all")
    void aRefusedLoginIssuesNothing() {
        assertThat(login("it.asha", "Wrong-Horse-9!").getHeaders().get(HttpHeaders.SET_COOKIE))
                .as("a refresh token handed out on a failed login is a session handed to a stranger")
                .isNull();
        assertThat(login("it.nobody", PASSWORD).getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }
}
