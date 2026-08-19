package com.edunext.edutrack.api.feature.masters.statuses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * B-039 · S-13 tab 1 against a real MySQL.
 *
 * <p>{@link StatusServiceTest} and {@link StatusTransitionServiceTest} prove the
 * decisions against mocks. This proves the four things a mock cannot:
 *
 * <ol>
 *   <li><b>The migration's backfill landed, and landed where it was reasoned to.</b>
 *     The category mapping is a judgement, not a lookup — `REOPENED` being TODO
 *     while `ON_HOLD` is IN_PROGRESS is the whole reason the column exists, and it
 *     is asserted here rather than in a comment.</li>
 *   <li><b>{@code ck_statuses_category} actually refuses.</b> MySQL parsed and
 *     silently ignored `CHECK` before 8.0.16; a constraint that does not
 *     constrain is worse than none, because it reads as protection.</li>
 *   <li><b>The two usage counts read the columns they claim to.</b> Both key on
 *     the status <em>code</em> against `VARCHAR`s in other tables, because
 *     nothing holds `statuses.id`. A join written against the id would compile,
 *     run, and return zero for every status — and every assertion in the mock
 *     suites would still pass.</li>
 *   <li><b>B-003's seed is shaped the way the screen assumes.</b></li>
 * </ol>
 *
 * <p><b>The fixture restores the seed rather than creating its own rows</b>, for
 * {@code PriorityMasterIT}'s reason: the service refuses any code outside the
 * contract's eight, which is this task's own headline refusal and would be a
 * strange thing to work around in its own test.
 */
@SpringBootTest
@Testcontainers
class StatusMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_status_master_it")
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
    StatusService service;

    @Autowired
    StatusTransitionService matrix;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Restores the eight statuses and the transition matrix to their seeded
     * state. Both are org-wide master data with no tenant to scope a fixture to,
     * so a test that retires a status has to put it back or the next one runs
     * against a different world.
     */
    @AfterEach
    void restoreSeed() {
        jdbc.update("UPDATE statuses SET is_active = 1, is_open = CASE WHEN code = 'CLOSED' "
                + "THEN 0 ELSE 1 END, is_terminal = CASE WHEN code = 'CLOSED' THEN 1 ELSE 0 END");
        jdbc.update("UPDATE workflow_transitions SET is_active = 1");
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITSTAT-%'");
    }

    // ------------------------------------------------------------------
    // the migration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every seeded status carries a category, and it is one of the three")
    void backfillIsComplete() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM statuses WHERE category IS NULL", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT category FROM statuses", String.class))
                .isSubsetOf("TODO", "IN_PROGRESS", "DONE");
    }

    /**
     * The mapping the migration reasoned out, asserted rather than described.
     * These five rows are why the column exists at all — every one of them
     * carries {@code is_open = 1, is_terminal = 0}, so nothing already in the
     * schema separates the two categories they fall into.
     */
    @Test
    @DisplayName("the backfill put REOPENED in TODO and ON_HOLD in IN_PROGRESS")
    void backfillMapping() {
        assertThat(categories())
                .containsEntry("NEW", "TODO")
                .containsEntry("REOPENED", "TODO")
                .containsEntry("IN_PROGRESS", "IN_PROGRESS")
                .containsEntry("ON_HOLD", "IN_PROGRESS")
                .containsEntry("AWAITING_INFO", "IN_PROGRESS")
                .containsEntry("REWORK", "IN_PROGRESS")
                .containsEntry("RESOLVED", "DONE")
                .containsEntry("CLOSED", "DONE");
    }

    /**
     * The counter-example that keeps `category` from being `is_open` renamed.
     * If a later refactor "simplifies" one into the other, this fails.
     */
    @Test
    @DisplayName("RESOLVED is DONE while still open — category and isOpen are different facts")
    void resolvedIsDoneAndOpen() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, is_open FROM statuses WHERE code = 'RESOLVED'");

        assertThat(row.get("category")).isEqualTo("DONE");
        // The connector hands a TINYINT(1) back as a Boolean, not a Number —
        // `tinyInt1isBit` defaults on. Asserted as one rather than cast, because
        // the cast compiles and fails at runtime, which is a worse test.
        assertThat(row.get("is_open")).isEqualTo(Boolean.TRUE);
    }

    /**
     * <b>Asserted on {@link DataAccessException} and the vendor code, not on
     * {@link DataIntegrityViolationException} — and the difference is a finding
     * rather than a convenience.</b>
     *
     * <p>MySQL answers a violated {@code CHECK} with error 3819 and a missing
     * {@code NOT NULL} default with 1364. Spring's MySQL error-code map contains
     * neither, so both arrive as {@code UncategorizedSQLException}, which is
     * <em>not</em> a {@code DataIntegrityViolationException}. Any service in this
     * codebase that catches the latter to turn a constraint breach into a 409
     * will not catch these two — worth knowing before somebody writes that catch
     * and watches a 500 go out instead.
     *
     * <p>Recorded here rather than fixed: adding vendor codes to the translator
     * is an application-wide change and Stream A's, not a decision this screen
     * should make on its own. Nothing in B-039 depends on it, because both rules
     * are enforced in the service before the database is reached — the database
     * is the second line, and this test is what proves the second line is real.
     */
    @Test
    @DisplayName("ck_statuses_category actually refuses — MySQL 8.4 enforces CHECK")
    void categoryCheckIsEnforced() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE statuses SET category = 'DOING' WHERE code = 'NEW'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_statuses_category");
    }

    @Test
    @DisplayName("category is NOT NULL with no default — a row cannot arrive uncategorised")
    void categoryIsMandatory() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO statuses (code, name, colour, seq) VALUES ('ZZTEMP','Temp','#000000',900)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("doesn't have a default value");
    }

    // ------------------------------------------------------------------
    // the seed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B-003 seeded exactly the contract's eight statuses")
    void seedMatchesTheContract() {
        assertThat(service.list(true)).extracting(StatusDtos.StatusView::code)
                .containsExactlyInAnyOrderElementsOf(StatusService.CONTRACT_CODES);
    }

    @Test
    @DisplayName("only CLOSED is terminal, and it is the only one that is not open")
    void terminalAndOpenAgreeInTheSeed() {
        assertThat(service.list(true))
                .filteredOn(StatusDtos.StatusView::isTerminal)
                .extracting(StatusDtos.StatusView::code)
                .containsExactly("CLOSED");
        assertThat(service.list(true))
                .filteredOn(s -> !s.isOpen())
                .extracting(StatusDtos.StatusView::code)
                .containsExactly("CLOSED");
    }

    @Test
    @DisplayName("the list comes back in seq order, which is not category order")
    void listIsInSeqOrder() {
        assertThat(service.list(true)).extracting(StatusDtos.StatusView::code)
                .containsExactly("NEW", "IN_PROGRESS", "ON_HOLD", "AWAITING_INFO",
                        "REWORK", "RESOLVED", "CLOSED", "REOPENED");
    }

    // ------------------------------------------------------------------
    // the counts — the part worth the container
    // ------------------------------------------------------------------

    /**
     * The count keys on {@code tickets.status}, a {@code VARCHAR} holding the
     * code. Written as a join on {@code statuses.id} it would return zero here
     * and the mock suite would not notice.
     */
    @Test
    @DisplayName("ticketCount reads tickets.status by code, not by a join on the id")
    void ticketCountReadsTheCodeColumn() {
        long before = statusNamed("NEW").ticketCount();

        insertTicket("ITSTAT-1", "NEW");

        assertThat(statusNamed("NEW").ticketCount()).isEqualTo(before + 1);
    }

    /**
     * Both ends, and the UNION ALL is why. A count of {@code to_status} alone
     * would quote the retire dialog a smaller number than the retire then acts on.
     */
    @Test
    @DisplayName("transitionCount counts both ends of the matrix, not just incoming moves")
    void transitionCountCountsBothEnds() {
        int incoming = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_transitions WHERE is_active = 1 AND to_status = 'IN_PROGRESS'",
                Integer.class);
        int outgoing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_transitions WHERE is_active = 1 AND from_status = 'IN_PROGRESS'",
                Integer.class);

        assertThat(statusNamed("IN_PROGRESS").transitionCount()).isEqualTo(incoming + outgoing);
    }

    @Test
    @DisplayName("the grouped read and the single read agree")
    void groupedAndSingleAgree() {
        StatusDtos.StatusView fromList = statusNamed("RESOLVED");
        StatusDtos.StatusView fromDetail = service.list(true).stream()
                .filter(s -> s.code().equals("RESOLVED")).findFirst().orElseThrow();

        assertThat(service.find(fromDetail.id())).get()
                .extracting(StatusDtos.StatusView::ticketCount,
                        StatusDtos.StatusView::transitionCount)
                .containsExactly(fromList.ticketCount(), fromList.transitionCount());
    }

    // ------------------------------------------------------------------
    // the refusals, against the real schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uq_statuses_code agrees with the service's own duplicate check")
    void uniqueCodeIsEnforcedByTheSchemaToo() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO statuses (code, name, category, colour, seq) "
                        + "VALUES ('NEW','Duplicate','TODO','#000000',900)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("retiring a status with a live ticket is refused")
    void retireBlockedByRealTicket() {
        insertTicket("ITSTAT-2", "ON_HOLD");
        int id = statusNamed("ON_HOLD").id();

        assertThatThrownBy(() -> service.update(id, retire()))
                .isInstanceOf(StatusService.StatusInUseException.class);

        assertThat(statusNamed("ON_HOLD").isActive()).isTrue();
    }

    /**
     * The cascade, against the real matrix. This is what keeps the master and
     * Stream C's whitelist gate from disagreeing.
     */
    @Test
    @DisplayName("retiring an empty status deactivates every transition touching it")
    void retireCascadesInTheDatabase() {
        StatusDtos.StatusView onHold = statusNamed("ON_HOLD");
        int expected = onHold.transitionCount();
        assertThat(expected).isPositive();

        assertThat(service.update(onHold.id(), retire())).get()
                .extracting(StatusDtos.StatusView::deactivatedTransitions)
                .isEqualTo(expected);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_transitions WHERE is_active = 1 "
                        + "AND (from_status = 'ON_HOLD' OR to_status = 'ON_HOLD')", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("a ninth status is refused before anything reaches the database")
    void ninthCodeNeverReachesTheTable() {
        assertThatThrownBy(() -> service.create(new StatusDtos.StatusWrite(
                "TRIAGED", "Triaged", "IN_PROGRESS", "#4F46E5", null, null, null, null)))
                .isInstanceOf(StatusService.StatusValidationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM statuses WHERE code = 'TRIAGED'", Integer.class))
                .isZero();
    }

    // ------------------------------------------------------------------
    // the matrix
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B-008's correction held — every seeded role_code resolves to a real role")
    void everyRoleCodeResolves() {
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT t.role_code FROM workflow_transitions t "
                        + "LEFT JOIN roles r ON r.code = t.role_code WHERE r.id IS NULL",
                String.class))
                .isEmpty();
    }

    @Test
    @DisplayName("the seed has on-create rows, so the invariant is satisfiable")
    void seedHasOnCreateRows() {
        assertThat(matrix.list(null))
                .filteredOn(t -> t.fromStatus() == null && t.isActive())
                .extracting(StatusDtos.TransitionView::toStatus)
                .containsOnly("NEW");
    }

    @Test
    @DisplayName("a replace keeps the ids of rows it did not change")
    void replaceIsAnUpsertInTheDatabase() {
        List<StatusDtos.TransitionView> before = matrix.list(null).stream()
                .filter(StatusDtos.TransitionView::isActive).toList();

        matrix.replace(new StatusDtos.TransitionMatrixWrite(before.stream()
                .map(t -> new StatusDtos.TransitionWrite(t.fromStatus(), t.toStatus(),
                        t.roleCode(), t.requiresReason(), t.requiresEffort()))
                .toList()));

        assertThat(matrix.list(null))
                .filteredOn(StatusDtos.TransitionView::isActive)
                .extracting(StatusDtos.TransitionView::id)
                .containsExactlyInAnyOrderElementsOf(
                        before.stream().map(StatusDtos.TransitionView::id).toList());
    }

    @Test
    @DisplayName("a matrix with no on-create row is refused and nothing is written")
    void invariantHoldsAgainstTheRealTable() {
        long activeBefore = matrix.list(null).stream()
                .filter(StatusDtos.TransitionView::isActive).count();

        assertThatThrownBy(() -> matrix.replace(new StatusDtos.TransitionMatrixWrite(
                List.of(new StatusDtos.TransitionWrite("NEW", "IN_PROGRESS", "PM", null, null)))))
                .isInstanceOf(StatusTransitionService.NoCreateTransitionException.class);

        assertThat(matrix.list(null).stream()
                .filter(StatusDtos.TransitionView::isActive).count())
                .isEqualTo(activeBefore);
    }

    @Test
    @DisplayName("G-3 is data: the seed has no RESOLVED -> CLOSED row for a Developer")
    void governanceIsExpressedAsAbsence() {
        assertThat(matrix.list("DEVELOPER"))
                .filteredOn(StatusDtos.TransitionView::isActive)
                .extracting(StatusDtos.TransitionView::fromStatus,
                        StatusDtos.TransitionView::toStatus)
                .doesNotContain(tuple("RESOLVED", "CLOSED"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Map<String, String> categories() {
        return jdbc.query("SELECT code, category FROM statuses", rs -> {
            Map<String, String> out = new java.util.HashMap<>();
            while (rs.next()) {
                out.put(rs.getString("code"), rs.getString("category"));
            }
            return out;
        });
    }

    private StatusDtos.StatusView statusNamed(String code) {
        return service.list(true).stream()
                .filter(s -> s.code().equals(code))
                .findFirst().orElseThrow();
    }

    private static StatusDtos.StatusPatch retire() {
        return new StatusDtos.StatusPatch(null, null, null, null, null, null, null, false);
    }

    /**
     * The smallest ticket the schema will accept, plus the project and user it
     * hangs off.
     *
     * <p><b>Nothing seeds `projects` or `users`</b> — B-007's fixture corpus is
     * dev data and deliberately not a migration (SEED-MANIFEST §5), so an IT that
     * needs a ticket has to build the whole chain. Written inline rather than
     * shared with another suite: the columns a ticket needs are Stream C's and
     * change under them, and a shared helper is a shared reason to fail.
     */
    private void insertTicket(String code, String status) {
        Long projectId = ensureProject();
        Long userId = ensureUser();

        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, description, "
                        + "level, original_level, status, reported_by) "
                        + "VALUES (?, ?, 'IT fixture', 'B-039 status master IT', "
                        + "'LOW', 'LOW', ?, ?)",
                code, projectId, status, userId);
    }

    private Long ensureProject() {
        Long existing = jdbc.query("SELECT id FROM projects WHERE project_code = 'ITSTAT' LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITSTAT','B-039 IT project')");
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITSTAT'", Long.class);
    }

    private Long ensureUser() {
        Long existing = jdbc.query("SELECT id FROM users WHERE emp_code = 'ITSTAT1' LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        Integer roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE code = 'ADMIN'", Integer.class);
        jdbc.update("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                + "VALUES ('ITSTAT1','itstat','itstat@example.test','x','B-039 IT user', ?)", roleId);
        return jdbc.queryForObject("SELECT id FROM users WHERE emp_code = 'ITSTAT1'", Long.class);
    }
}
