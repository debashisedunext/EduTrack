package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.common.pagination.CursorPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /tickets} — the list S-17 has been reading from a mock.
 *
 * <p>Three properties are worth pinning and the rest is filter plumbing: that
 * scope is applied server-side and a caller's own filters cannot widen it, that
 * paging the whole list visits every row exactly once, and that an unknown sort
 * degrades to the default rather than failing a saved view.
 */
@SpringBootTest
@Testcontainers
class TicketListIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ticket_list_it")
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
    TicketListService service;

    @Autowired
    JdbcTemplate jdbc;

    private long mineProject;
    private long theirProject;
    private long me;
    private long someoneElse;
    private String tag;

    @BeforeEach
    void seed() {
        tag = "L" + (System.nanoTime() % 1_000_000);

        mineProject = insertProject(tag + "A");
        theirProject = insertProject(tag + "B");
        me = insertUser(tag + "me");
        someoneElse = insertUser(tag + "them");

        // 12 in my project assigned to me, 5 in theirs assigned to somebody else.
        for (int i = 0; i < 12; i++) {
            insertTicket(mineProject, me, "HIGH", "OPEN");
        }
        for (int i = 0; i < 5; i++) {
            insertTicket(theirProject, someoneElse, "LOW", "OPEN");
        }
    }

    private long insertProject(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code, "List IT " + code);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertUser(String code) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'List IT', ?, 1)
                """, code, code, code + "@example.test", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertTicket(long project, long assignee, String level, String status) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level,
                                     status, assigned_to)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tag + "-" + System.nanoTime() % 10_000_000, project,
                "list probe " + tag, level, level, status, assignee);
    }

    private Authentication caller(long userId, String role, List<Long> projectIds) {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(userId, "list.fixture", "Fixture", role, projectIds, List.of()),
                null, List.of());
    }

    /** Restricted to this run's rows, so a fixture from another test cannot flip a result. */
    private TicketListSpecs.Filters mine() {
        return filters(mineProject);
    }

    private TicketListSpecs.Filters filters(Long projectId) {
        return new TicketListSpecs.Filters(null, projectId, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private TicketListSpecs.Filters unfiltered() {
        return filters(null);
    }

    // ── scope ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("scope is decided server-side")
    class Scope {

        @Test
        @DisplayName("a developer sees only what is assigned to them")
        void developerSeesOnlyTheirOwn() {
            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "DEVELOPER", List.of()), unfiltered(), null, null, 200);

            assertThat(page.data())
                    .isNotEmpty()
                    .allSatisfy(t -> assertThat(t.assignedTo()).isEqualTo(me));
        }

        /**
         * The rule the whole guard exists for: a filter is a conjunct, never a
         * replacement. Asking for somebody else's project must narrow within
         * what you can see rather than reach outside it.
         */
        @Test
        @DisplayName("a caller's own filter cannot widen their scope")
        void filtersCannotWidenScope() {
            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "DEVELOPER", List.of()), filters(theirProject), null, null, 200);

            assertThat(page.data())
                    .as("asking for a project they cannot see returns nothing, not that project")
                    .isEmpty();
        }

        @Test
        @DisplayName("an admin is unrestricted")
        void adminSeesEverything() {
            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "ADMIN", List.of()), filters(theirProject), null, null, 200);

            assertThat(page.data()).as("admin can see another project").isNotEmpty();
        }
    }

    // ── paging ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cursor paging")
    class Paging {

        /**
         * The property offset paging cannot give: page all the way through and
         * every row appears exactly once. A limit that does not divide the total
         * is deliberate — an exact multiple hides the off-by-one at the end.
         */
        @Test
        @DisplayName("paging to the end visits every row exactly once")
        void fullTraversalLosesNothing() {
            Authentication admin = caller(me, "ADMIN", List.of());
            List<Long> seen = new ArrayList<>();
            String cursor = null;

            for (int guard = 0; guard < 20; guard++) {
                CursorPage<TicketListDtos.TicketSummary> page =
                        service.list(admin, mine(), null, cursor, 5);
                page.data().forEach(t -> seen.add(t.id()));
                if (!page.meta().hasMore()) {
                    break;
                }
                cursor = page.meta().nextCursor();
            }

            assertThat(seen).as("12 seeded, none skipped").hasSize(12);
            assertThat(seen).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the last page says so rather than promising another")
        void lastPageIsHonest() {
            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), mine(), null, null, 200);

            assertThat(page.data()).hasSize(12);
            assertThat(page.meta().hasMore()).isFalse();
            assertThat(page.meta().nextCursor()).isNull();
        }

        @Test
        @DisplayName("a cursor we never issued starts from the beginning")
        void forgedCursorIsTheFirstPage() {
            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "ADMIN", List.of()), mine(), null, "not-a-real-cursor", 5);

            assertThat(page.data()).as("first page, not an error").hasSize(5);
        }

        @Test
        @DisplayName("limit is clamped, never rejected")
        void limitIsClamped() {
            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), mine(), null, null, 100_000);

            assertThat(page.data()).hasSize(12);
        }
    }

    // ── filters and sort ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("filters and sort")
    class Filtering {

        @Test
        @DisplayName("an unknown sort falls back to the default instead of failing")
        void unknownSortDegrades() {
            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "ADMIN", List.of()), mine(), "-somethingNobodyHas", null, 5);

            assertThat(page.data())
                    .as("a saved view naming a renamed column should still open")
                    .hasSize(5);
        }

        @Test
        @DisplayName("level filters within scope")
        void levelFilters() {
            TicketListSpecs.Filters high = new TicketListSpecs.Filters(
                    null, mineProject, null, null, null, "HIGH", null, null, null,
                    null, null, null, null, null, null, null, null, null);

            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), high, null, null, 200);

            assertThat(page.data()).hasSize(12)
                    .allSatisfy(t -> assertThat(t.level()).isEqualTo("HIGH"));
        }

        @Test
        @DisplayName("unassigned returns only tickets with no assignee")
        void unassignedFilter() {
            // Inserted with a real assignee and then cleared. assigned_to is a
            // foreign key to users, so there is no sentinel to insert directly —
            // the first draft used 0 and the constraint refused it, correctly.
            insertTicket(mineProject, me, "LOW", "OPEN");
            jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE project_id = ? AND level = 'LOW'",
                    mineProject);

            TicketListSpecs.Filters unassigned = new TicketListSpecs.Filters(
                    null, mineProject, null, null, null, null, null, null, null,
                    null, null, null, true, null, null, null, null, null);

            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), unassigned, null, null, 200);

            assertThat(page.data()).isNotEmpty()
                    .allSatisfy(t -> assertThat(t.assignedTo()).isNull());
        }
    }
}
