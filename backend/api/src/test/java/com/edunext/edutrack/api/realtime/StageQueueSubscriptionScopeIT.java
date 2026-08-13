package com.edunext.edutrack.api.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-059 · who actually gets into a team's queue, against real MySQL.
 *
 * <p>{@link SubscriptionAuthorisationTest} proves the interceptor asks the
 * question and honours the answer, with the scope stubbed. This proves the
 * answer — and the interesting cases are all the ways somebody stops being on a
 * project without the row disappearing, because those are what a session
 * outliving its token would otherwise keep open.
 */
@SpringBootTest
@Testcontainers
class StageQueueSubscriptionScopeIT {

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

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    StageQueueSubscriptionScope scope;

    @Autowired
    JdbcTemplate jdbc;

    private long theirProject;
    private long anotherProject;
    private long anil;
    private long stranger;
    private long admin;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM project_members WHERE user_id IN "
                + "(SELECT id FROM users WHERE username LIKE 'it_sq_%')");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_sq_%'");
        jdbc.update("DELETE FROM projects WHERE project_code IN ('ISQ1', 'ISQ2')");

        theirProject = insertProject("ISQ1");
        anotherProject = insertProject("ISQ2");

        // A QA resource, deliberately not an Admin — the role that has silently
        // answered two other features' authorisation tests in this codebase.
        anil = insertUser("it_sq_anil", "QA");
        stranger = insertUser("it_sq_stranger", "QA");
        admin = insertUser("it_sq_admin", "ADMIN");

        join(theirProject, anil);
        join(anotherProject, stranger);
    }

    @Test
    @DisplayName("somebody on the project may watch any of its teams")
    void aMemberMayWatchTheirOwnProjectsQueues() {
        // Any stage, because the queue is per project. A Developer watching the
        // QA queue on their own project is how they see their handoff land.
        assertThat(scope.mayObserveStage(anil, "QA", theirProject)).isTrue();
        assertThat(scope.mayObserveStage(anil, "DEPLOYMENT", theirProject)).isTrue();
    }

    @Test
    @DisplayName("a project you are not on is refused")
    void anotherProjectIsRefused() {
        // §16's walkthrough: "Neither of them ever sees the ticket list of a
        // project they aren't on."
        assertThat(scope.mayObserveStage(anil, "QA", anotherProject)).isFalse();
        assertThat(scope.mayObserveStage(stranger, "QA", theirProject)).isFalse();
    }

    @Test
    @DisplayName("an Admin may watch any team's queue")
    void adminSeesEverything() {
        assertThat(scope.mayObserveStage(admin, "QA", theirProject)).isTrue();
        assertThat(scope.mayObserveStage(admin, "QA", anotherProject)).isTrue();
    }

    @Test
    @DisplayName("a revoked membership closes the room, not just the next login")
    void aRevokedMembershipIsRefused() {
        jdbc.update("UPDATE project_members SET is_active = 0 WHERE project_id = ? AND user_id = ?",
                theirProject, anil);

        // The reason this is read from the database rather than from
        // CallerIdentity.projectIds(): a subscription outlives the request that
        // opened it, and a token minted before the change would keep the room
        // open for the life of the session.
        assertThat(scope.mayObserveStage(anil, "QA", theirProject)).isFalse();
    }

    @Test
    @DisplayName("a deactivated user is refused even while their membership stands")
    void aDeactivatedUserIsRefused() {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", anil);

        // S-24 deactivates a resource and reassigns their work; the membership
        // row is left alone. Reading only project_members would leave a departed
        // employee's socket receiving their old team's activity.
        assertThat(scope.mayObserveStage(anil, "QA", theirProject)).isFalse();
    }

    @Test
    @DisplayName("a deactivated Admin is refused too")
    void aDeactivatedAdminIsRefused() {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", admin);

        assertThat(scope.mayObserveStage(admin, "QA", theirProject)).isFalse();
    }

    @Test
    @DisplayName("a project that does not exist grants nothing")
    void anUnknownProjectIsRefused() {
        assertThat(scope.mayObserveStage(anil, "QA", 9_999_999L)).isFalse();
    }

    private long insertProject(String code) {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, 'Stage queue fixture')", code);
        return lastId();
    }

    /** Explicit role, for the reason recorded on {@code ChatEngineIT.insertUser}. */
    private long insertUser(String username, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        assertThat(roleId).as("seeded role %s", roleCode).isNotNull();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastId();
    }

    private void join(long projectId, long userId) {
        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                VALUES (?, ?, 'QA', 1)
                """, projectId, userId);
    }

    private long lastId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
