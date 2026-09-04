package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-112 · {@link OnboardingScopeResolver} against a real database.
 *
 * <p>The same shape as {@code TicketScopeIT} and for the same reason it gives:
 * a {@link org.springframework.data.jpa.domain.Specification} is a lambda, and
 * asserting on the criteria object it builds proves the code was written, not
 * that the right rows come back. So every case here runs a real query and
 * counts real rows.
 *
 * <p>Fixtures use {@code IT_OBS_*} codes, for the collision reason
 * {@code AuthLoginIT} documents.
 */
@SpringBootTest
@Testcontainers
class OnboardingScopeIT {

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
    ScopedJourneys journeys;

    @Autowired
    JdbcTemplate jdbc;

    private static boolean seeded;

    /*
     * The corpus. Two clients boarded by two different Sales users, and a
     * third whose created_by is NULL — chosen so that no two roles can produce
     * the same answer by accident, and so the nullable created_by that
     * ObClient's javadoc warns about is actually exercised.
     *
     *   journey   client boarded by   steps
     *   J1        Sonia               Kavya owns
     *   J2        Rahul               Nikhil owns, Kavya backs up
     *   J3        (nobody)            Nikhil owns
     */
    private long sonia;
    private long rahul;
    private long kavya;
    private long nikhil;
    private long j1;
    private long j2;
    private long j3;

    @BeforeEach
    void seed() {
        if (!seeded) {
            // INSERT IGNORE rather than a plain INSERT: if any later statement
            // in this block fails, the rows committed before it survive, and a
            // plain INSERT would then report a duplicate key on every
            // subsequent test — burying the real error under nine copies of a
            // symptom. Learned the hard way on this very fixture.
            jdbc.update("INSERT IGNORE INTO roles (code, name, is_system) "
                    + "VALUES ('IT_OBS_ROLE', 'OB Scope Fixture', 0)");
            insertUser("OBS001", "its.sonia", "Sonia Deshpande");
            insertUser("OBS002", "its.rahul", "Rahul Menon");
            insertUser("OBS003", "its.kavya", "Kavya Sharma");
            insertUser("OBS004", "its.nikhil", "Nikhil Joshi");
            jdbc.update("INSERT IGNORE INTO ob_products (code, name) "
                    + "VALUES ('IT_OBS_P', 'Scope Fixture Product')");

            long product = scalar("SELECT id FROM ob_products WHERE code = 'IT_OBS_P'");
            jdbc.update("INSERT IGNORE INTO ob_journey_templates (product_id, name) VALUES (?, 'IT_OBS_T')", product);
            long template = scalar("SELECT id FROM ob_journey_templates WHERE name = 'IT_OBS_T'");

            insertClient("IT_OBS_C1", userId("its.sonia"));
            insertClient("IT_OBS_C2", userId("its.rahul"));
            insertClient("IT_OBS_C3", null);

            // A journey may only exist for a product the client actually
            // bought — fk_ob_journeys_application is a composite FK onto
            // (ob_client_id, product_id) in ob_client_applications. Not a
            // fixture detail: it is the rule that stops a journey being
            // instantiated for something nobody purchased.
            insertApplication(clientId("IT_OBS_C1"), product);
            insertApplication(clientId("IT_OBS_C2"), product);
            insertApplication(clientId("IT_OBS_C3"), product);

            insertJourney(clientId("IT_OBS_C1"), product, template);
            insertJourney(clientId("IT_OBS_C2"), product, template);
            insertJourney(clientId("IT_OBS_C3"), product, template);

            // J1: Kavya owns the step outright. J2: Kavya is only the backup —
            // the case the resolver deliberately widens to include. J3: Nikhil.
            insertStep(journeyId("IT_OBS_C1"), "Step A", userId("its.kavya"), null);
            insertStep(journeyId("IT_OBS_C2"), "Step B", userId("its.nikhil"), userId("its.kavya"));
            insertStep(journeyId("IT_OBS_C3"), "Step C", userId("its.nikhil"), null);
            seeded = true;
        }
        sonia = userId("its.sonia");
        rahul = userId("its.rahul");
        kavya = userId("its.kavya");
        nikhil = userId("its.nikhil");
        j1 = journeyId("IT_OBS_C1");
        j2 = journeyId("IT_OBS_C2");
        j3 = journeyId("IT_OBS_C3");
    }

    @Test
    @DisplayName("OB_MANAGER sees every journey, including the one nobody is recorded as boarding")
    void managerSeesEverything() {
        assertThat(visibleTo(caller(sonia, "OB_MANAGER"))).contains(j1, j2, j3);
    }

    @Test
    @DisplayName("OB_ADMIN and OB_VIEWER see what the Manager sees — they differ on writes, not rows")
    void adminAndViewerSeeEverything() {
        assertThat(visibleTo(caller(sonia, "OB_ADMIN"))).contains(j1, j2, j3);
        assertThat(visibleTo(caller(sonia, "OB_VIEWER"))).contains(j1, j2, j3);
    }

    @Test
    @DisplayName("OB_SALES sees only journeys for clients they created")
    void salesSeesOnlyTheirOwnClients() {
        assertThat(visibleTo(caller(sonia, "OB_SALES"))).contains(j1).doesNotContain(j2, j3);
        assertThat(visibleTo(caller(rahul, "OB_SALES"))).contains(j2).doesNotContain(j1, j3);
    }

    /**
     * The NULL {@code created_by} case. It must be invisible to every Sales
     * user rather than visible to all of them — see {@code ObClient.createdBy}.
     */
    @Test
    @DisplayName("a client with no recorded author is visible to no Sales user")
    void salesNeverSeesAnUnownedClient() {
        assertThat(visibleTo(caller(sonia, "OB_SALES"))).doesNotContain(j3);
        assertThat(visibleTo(caller(rahul, "OB_SALES"))).doesNotContain(j3);
        assertThat(visibleTo(caller(kavya, "OB_SALES"))).doesNotContain(j3);
    }

    @Test
    @DisplayName("OB_STEP_OWNER sees journeys containing their steps, as owner or as backup")
    void stepOwnerSeesOwnedAndBackedUpSteps() {
        assertThat(visibleTo(caller(kavya, "OB_STEP_OWNER"))).contains(j1, j2).doesNotContain(j3);
        assertThat(visibleTo(caller(nikhil, "OB_STEP_OWNER"))).contains(j2, j3).doesNotContain(j1);
    }

    @Test
    @DisplayName("TICKETING_MEMBER has no standing in onboarding and sees nothing")
    void ticketingMemberSeesNothing() {
        assertThat(visibleTo(caller(sonia, "TICKETING_MEMBER"))).isEmpty();
    }

    @Test
    @DisplayName("a caller with the module but no role inside it sees nothing")
    void noModuleRoleSeesNothing() {
        Authentication caller = new UsernamePasswordAuthenticationToken(
                new DevPrincipal(sonia, "its.sonia", "Sonia Deshpande", "ADMIN",
                        List.of(), List.of(), List.of("ONBOARDING"), Map.of()),
                "n/a", List.of());
        assertThat(visibleTo(caller)).isEmpty();
    }

    /**
     * The one that matters most: a ticketing ADMIN is not an onboarding Admin.
     * If this ever passes with rows, the resolver has been switched back onto
     * {@code roleCode} and every journey is readable by every system admin.
     */
    @Test
    @DisplayName("a ticketing ADMIN with no onboarding role sees nothing")
    void ticketingAdminIsNotAnOnboardingAdmin() {
        assertThat(visibleTo(caller(sonia, "ADMIN", "ADMIN"))).isEmpty();
    }

    @Test
    @DisplayName("an out-of-scope id is 404, not 403 — indistinguishable from one never issued")
    void outOfScopeIsNotFound() {
        Authentication rahulSales = caller(rahul, "OB_SALES");
        assertThatThrownBy(() -> journeys.require(rahulSales, j1))
                .isInstanceOf(JourneyNotFoundException.class);
        assertThatThrownBy(() -> journeys.require(rahulSales, 987654321L))
                .isInstanceOf(JourneyNotFoundException.class);
        assertThat(journeys.byId(rahulSales, j1)).isEmpty();
        assertThat(journeys.canSee(rahulSales, j1)).isFalse();
        assertThat(journeys.canSee(rahulSales, j2)).isTrue();
    }

    @Test
    @DisplayName("count agrees with the rows the same caller can list")
    void countMatchesTheVisibleRows() {
        Authentication soniaSales = caller(sonia, "OB_SALES");
        assertThat(journeys.count(soniaSales, null)).isEqualTo(visibleTo(soniaSales).size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<Long> visibleTo(Authentication caller) {
        return journeys.list(caller, null, Sort.by("id")).stream().map(ObJourney::getId).toList();
    }

    /** A caller whose ticketing role is deliberately unrelated to their module role. */
    private Authentication caller(long userId, String moduleRole) {
        return caller(userId, "SUPPORT", moduleRole);
    }

    private Authentication caller(long userId, String roleCode, String moduleRole) {
        DevPrincipal principal = new DevPrincipal(userId, "fixture", "Fixture", roleCode,
                List.of(), List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
        return new UsernamePasswordAuthenticationToken(principal, "n/a", List.of());
    }

    private void insertApplication(long clientId, long productId) {
        jdbc.update("INSERT IGNORE INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
    }

    private void insertUser(String empCode, String username, String fullName) {
        jdbc.update("""
                INSERT IGNORE INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES (?, ?, ?, 'not-a-real-hash', ?,
                        (SELECT id FROM roles WHERE code = 'IT_OBS_ROLE'), 'Asia/Kolkata', 1, 0)
                """, empCode, username, username + "@edunext.test", fullName);
    }

    private void insertClient(String name, Long createdBy) {
        jdbc.update("INSERT IGNORE INTO ob_clients (name, onboarding_date, created_by) VALUES (?, '2026-01-01', ?)",
                name, createdBy);
    }

    private void insertJourney(long clientId, long productId, long templateId) {
        jdbc.update("INSERT IGNORE INTO ob_journeys (ob_client_id, product_id, template_id) VALUES (?, ?, ?)",
                clientId, productId, templateId);
    }

    private void insertStep(long journeyId, String name, Long owner, Long backup) {
        jdbc.update("INSERT IGNORE INTO ob_journey_steps (journey_id, sequence, name, owner_user_id, backup_owner_user_id) "
                + "VALUES (?, 1, ?, ?, ?)", journeyId, name, owner, backup);
    }

    private long userId(String username) {
        return scalar("SELECT id FROM users WHERE username = '" + username + "'");
    }

    private long clientId(String name) {
        return scalar("SELECT id FROM ob_clients WHERE name = '" + name + "'");
    }

    private long journeyId(String clientName) {
        return scalar("SELECT j.id FROM ob_journeys j JOIN ob_clients c ON c.id = j.ob_client_id "
                + "WHERE c.name = '" + clientName + "'");
    }

    private long scalar(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
