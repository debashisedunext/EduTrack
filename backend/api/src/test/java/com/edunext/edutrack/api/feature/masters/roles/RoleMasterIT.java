package com.edunext.edutrack.api.feature.masters.roles;

import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-015 · S-09 against a real MySQL.
 *
 * <p>{@code RoleServiceTest} proves the decisions against mocks. This proves the
 * half a mock cannot: that B-001's seed is actually shaped the way the screen
 * assumes, that the replace-all save survives the composite primary key on
 * {@code role_permissions}, and that {@code users.role_id} — a foreign key
 * <em>without</em> a cascade — really is what makes the in-use refusal
 * necessary rather than merely polite.
 *
 * <p>Fixture rows are prefixed {@code ITROLE} so nothing collides with the seed
 * or with the resource suites, and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class RoleMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_role_master_it")
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
    RoleService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearFixtureRows() {
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITROLE%'");
        jdbc.update("DELETE FROM role_permissions WHERE role_id IN "
                + "(SELECT id FROM roles WHERE code LIKE 'ITROLE%')");
        jdbc.update("DELETE FROM roles WHERE code LIKE 'ITROLE%'");
    }

    // ------------------------------------------------------------------
    // what B-001 actually seeded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the six seeded roles are all flagged is_system")
    void seededRolesAreSystemRoles() {
        // If one were not, S-09 would offer a delete that orphans every user
        // holding it — and nothing else in the stack would stop it.
        List<RoleDtos.Role> seeded = service.list(null).stream()
                .filter(r -> !r.code().startsWith("ITROLE"))
                .toList();

        assertThat(seeded).extracting(RoleDtos.Role::code)
                .contains("ADMIN", "PM", "SUPPORT", "DEVELOPER", "QA", "DEPLOYMENT");
        assertThat(seeded).allMatch(RoleDtos.Role::isSystem);
    }

    @Test
    @DisplayName("the catalogue is the 18 seeded capabilities, grouped and ordered")
    void catalogueIsSeededAndOrdered() {
        List<RoleDtos.Permission> catalogue = service.permissions();

        assertThat(catalogue).hasSize(18);
        assertThat(catalogue).extracting(RoleDtos.Permission::category).containsOnly(
                "admin", "ticket", "history", "reports", "master", "audit");
        // (category, code) — the order the screen renders its sections in, so
        // no client sorts it a seventh way.
        assertThat(catalogue).isSortedAccordingTo(
                java.util.Comparator.comparing(RoleDtos.Permission::category)
                        .thenComparing(RoleDtos.Permission::code));
    }

    @Test
    @DisplayName("history.edit_delete is seeded, held by nobody, and marked ungrantable")
    void historyEditDeleteIsSeededWithZeroGrants() {
        assertThat(service.permissions())
                .filteredOn(p -> p.code().equals("history.edit_delete"))
                .singleElement()
                .satisfies(p -> assertThat(p.isGrantable()).isFalse());

        Integer holders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE p.code = 'history.edit_delete'", Integer.class);
        assertThat(holders).isZero();
    }

    @Test
    @DisplayName("ADMIN's grants come back on the detail read")
    void adminHasItsSeededGrants() {
        int adminId = roleId("ADMIN");

        Optional<RoleDtos.RoleDetail> admin = service.find(adminId);

        assertThat(admin).isPresent();
        assertThat(admin.get().permissionCodes())
                .contains("resource.manage", "ticket.force_move", "audit.view")
                .doesNotContain("history.edit_delete");
        assertThat(admin.get().permissionCount())
                .isEqualTo(admin.get().permissionCodes().size());
    }

    // ------------------------------------------------------------------
    // the matrix, against the composite primary key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the replace-all save survives the composite primary key")
    void replaceAllRoundTrips() {
        // The delete and the inserts hit the same rows inside one transaction.
        // Without the flush between them, Hibernate can order an insert ahead
        // of the delete and collide with the key it is about to remove.
        int id = service.create(new RoleDtos.RoleWrite(
                "ITROLE_AUD", "IT Auditor", null, null)).id();

        service.replacePermissions(id, List.of("reports.view", "audit.view", "ticket.view_all"));
        assertThat(service.find(id).orElseThrow().permissionCodes())
                .containsExactlyInAnyOrder("reports.view", "audit.view", "ticket.view_all");

        // The same set again, plus one, minus one — the shape a second save
        // from the screen actually takes.
        service.replacePermissions(id, List.of("reports.view", "audit.view", "history.view_team"));
        assertThat(service.find(id).orElseThrow().permissionCodes())
                .containsExactlyInAnyOrder("reports.view", "audit.view", "history.view_team");

        service.replacePermissions(id, List.of());
        assertThat(service.find(id).orElseThrow().permissionCodes()).isEmpty();
    }

    @Test
    @DisplayName("granting history.edit_delete is refused against the real catalogue")
    void ungrantableIsRefusedForReal() {
        int id = service.create(new RoleDtos.RoleWrite(
                "ITROLE_HIST", "IT History", null, null)).id();

        assertThatThrownBy(() -> service.replacePermissions(id,
                List.of("reports.view", "history.edit_delete")))
                .isInstanceOf(RoleService.UngrantablePermissionException.class);

        assertThat(service.find(id).orElseThrow().permissionCodes()).isEmpty();
    }

    // ------------------------------------------------------------------
    // the two refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a system role is refused before the database is asked")
    void systemRoleIsUndeletable() {
        assertThatThrownBy(() -> service.delete(roleId("DEVELOPER")))
                .isInstanceOf(RoleService.SystemRoleUndeletableException.class);

        assertThat(service.find(roleId("DEVELOPER"))).isPresent();
    }

    @Test
    @DisplayName("users.role_id has no cascade, so the in-use check is what makes the refusal usable")
    void roleInUseIsRefusedRatherThanFailingAtTheForeignKey() {
        int id = service.create(new RoleDtos.RoleWrite(
                "ITROLE_USED", "IT Used", null, null)).id();
        insertUser("ITROLE001", "itrole.one", id);
        insertUser("ITROLE002", "itrole.two", id);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(RoleService.RoleInUseException.class)
                .satisfies(e -> assertThat(((RoleService.RoleInUseException) e).userCount())
                        .isEqualTo(2L));

        // Reassign, and the same delete now succeeds.
        jdbc.update("UPDATE users SET role_id = ? WHERE emp_code LIKE 'ITROLE%'", roleId("QA"));
        assertThat(service.delete(id)).isTrue();
        assertThat(service.find(id)).isEmpty();
    }

    @Test
    @DisplayName("an inactive resource still blocks the delete")
    void inactiveUsersCountToo() {
        // They still point at the row, so the foreign key would still refuse.
        int id = service.create(new RoleDtos.RoleWrite(
                "ITROLE_INAC", "IT Inactive", null, null)).id();
        insertUser("ITROLE003", "itrole.three", id);
        jdbc.update("UPDATE users SET is_active = 0 WHERE emp_code = 'ITROLE003'");

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(RoleService.RoleInUseException.class);
    }

    @Test
    @DisplayName("the code is unique across case, not merely across the exact string")
    void codeUniquenessIsCaseInsensitive() {
        service.create(new RoleDtos.RoleWrite("ITROLE_DUP", "IT Duplicate", null, null));

        assertThatThrownBy(() -> service.create(
                new RoleDtos.RoleWrite("itrole_dup", "IT Duplicate Again", null, null)))
                .isInstanceOf(RoleService.DuplicateRoleCodeException.class);
    }

    @Test
    @DisplayName("userCount on the grid counts holders, not grants")
    void userCountIsPopulatedOnTheList() {
        int id = service.create(new RoleDtos.RoleWrite(
                "ITROLE_CNT", "IT Counted", null, null)).id();
        insertUser("ITROLE004", "itrole.four", id);
        service.replacePermissions(id, List.of("reports.view", "audit.view"));

        assertThat(service.list(null))
                .filteredOn(r -> r.code().equals("ITROLE_CNT"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.userCount()).isEqualTo(1L);
                    assertThat(r.permissionCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("isActive=true hides a deactivated role from the picker but not from the grid")
    void inactiveRolesAreFilteredOnlyWhenAsked() {
        int id = service.create(new RoleDtos.RoleWrite(
                "ITROLE_OFF", "IT Retired", null, false)).id();

        assertThat(service.list(true)).extracting(RoleDtos.Role::code)
                .doesNotContain("ITROLE_OFF");
        assertThat(service.list(null)).extracting(RoleDtos.Role::code)
                .contains("ITROLE_OFF");
        assertThat(service.find(id)).isPresent();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private int roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Integer.class, code);
    }

    private void insertUser(String empCode, String username, int roleId) {
        jdbc.update("INSERT INTO users (emp_code, username, email, full_name, password_hash, "
                        + "role_id, is_active) VALUES (?, ?, ?, ?, '$argon2id$fixture', ?, 1)",
                empCode, username, username + "@edunext.test", "Fixture " + empCode, roleId);
    }
}
