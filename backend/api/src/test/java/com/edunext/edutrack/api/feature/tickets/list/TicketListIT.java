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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    /** Dashboard Rework Dev 1, PR 5 · the id-returning form the new tests need to link a cycle row. */
    private long insertTicketReturningId(long project, long assignee, String level, String status) {
        insertTicket(project, assignee, level, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** cycle_no 1, matching every fixture ticket's default current_cycle_no. */
    private void insertCycle(long ticketId, Instant startedAt, Instant finishedAt) {
        jdbc.update("""
                INSERT INTO ticket_cycles (ticket_id, cycle_no, start_date, started_at, finished_at)
                VALUES (?, 1, NOW(6), ?, ?)
                """, ticketId,
                startedAt == null ? null : Timestamp.from(startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt));
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
        // A-060 added reportedFrom/reportedTo; PR 5 added the seven after that.
        // Twenty-nine positional nulls is a call site that breaks on every new
        // filter and gives no clue which slot moved; a builder or a
        // `Filters.none()` would fix that for good. Left alone here
        // deliberately: this is Stream C's file and each addition's business is
        // its own parameters, not a refactor of it.
        return new TicketListSpecs.Filters(null, projectId, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
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
                    .allSatisfy(t -> assertThat(t.assignee()).isNotNull()
                            .extracting(TicketListDtos.UserRef::id).isEqualTo(me));
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
            // Identity is the ticket code now that D-061 has dropped the
            // surrogate from the payload. It is unique per row and it is what
            // the contract actually exposes, so the traversal is asserted on
            // the same identifier a consumer would deduplicate by.
            List<String> seen = new ArrayList<>();
            String cursor = null;

            for (int guard = 0; guard < 20; guard++) {
                CursorPage<TicketListDtos.TicketSummary> page =
                        service.list(admin, mine(), null, cursor, 5);
                page.data().forEach(t -> seen.add(t.ticketId()));
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

    // ── the shape the contract declares ──────────────────────────────────────

    @Nested
    @DisplayName("the row carries the contract's own field names")
    class Shape {

        /**
         * D-061 · the defect this task exists to close.
         *
         * <p>The list served {@code ticketCode}, {@code assignedTo},
         * {@code projectId} and {@code clientId}; the contract declares
         * {@code ticketId}, {@code assignee}, {@code project} and {@code client}.
         * The frontend generates from the contract, so all four read
         * {@code undefined} and S-17 rendered four blank columns against a green
         * build.
         *
         * <p>{@code ContractConformanceTest} is what stops the *names* drifting
         * again. This asserts the other half — that the nested references are
         * actually populated, since a correctly named field carrying a null
         * would render just as blank and satisfy a name-only check.
         */
        @Test
        @DisplayName("ticketId is the code, and the refs are resolved rather than left null")
        void theRowIsRenderable() {
            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), unfiltered(), null, null, 50);

            assertThat(page.data()).isNotEmpty();

            // Not asserted against C-011's `{PROJECT_CODE}-{YY}-{NNNNN}` pattern:
            // this IT mints its own codes, so the pattern would be testing the
            // fixture rather than the payload. That the generator's output is
            // well-formed is C-011's own test; what matters here is that the
            // field is populated and is the code rather than a surrogate.
            assertThat(page.data())
                    .as("the ID column reads this, and blank is exactly the bug D-061 closes")
                    .allSatisfy(t -> assertThat(t.ticketId()).isNotBlank());

            assertThat(page.data())
                    .as("every ticket has a project, so an unresolved one is a bug rather than a gap")
                    .allSatisfy(t -> assertThat(t.project()).isNotNull()
                            .satisfies(p -> {
                                assertThat(p.projectCode()).isNotBlank();
                                assertThat(p.name()).isNotBlank();
                            }));

            assertThat(page.data())
                    .filteredOn(t -> t.assignee() != null)
                    .as("an assignee that resolves to an id with no name renders as blank too")
                    .isNotEmpty()
                    .allSatisfy(t -> assertThat(t.assignee().displayName()).isNotBlank());
        }

        /**
         * The nesting is only affordable because it is batched.
         *
         * <p>Not a query-count assertion — those are brittle and hibernate's
         * statistics are off in this context — but the property that makes the
         * count constant: one page resolves the same reference once, so fifty
         * rows sharing a project share the object rather than each fetching it.
         */
        @Test
        @DisplayName("rows sharing a project share one resolved reference")
        void referencesAreResolvedOncePerPage() {
            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), unfiltered(), null, null, 50);

            java.util.Map<Long, java.util.Set<TicketListDtos.Project>> byId = new java.util.HashMap<>();
            for (TicketListDtos.TicketSummary t : page.data()) {
                if (t.project() != null) {
                    byId.computeIfAbsent(t.project().id(), k -> new java.util.HashSet<>()).add(t.project());
                }
            }

            assertThat(byId).isNotEmpty();
            assertThat(byId.values())
                    .as("two different objects for one project id means a per-row lookup crept back")
                    .allSatisfy(refs -> assertThat(refs).hasSize(1));
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
                    null, null, null, null, null, null, null, null, null, null, null,
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
                    null, null, null, true, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), unassigned, null, null, 200);

            assertThat(page.data()).isNotEmpty()
                    .allSatisfy(t -> assertThat(t.assignee()).isNull());
        }

        // ── C-070 · §7.5's module filter ─────────────────────────────────────

        @Test
        @DisplayName("module filters within scope, and the row carries the id it filtered on")
        void moduleFilters() {
            Long fees = jdbc.queryForObject("SELECT id FROM product_modules WHERE code = 'FEES'", Long.class);
            Long library = jdbc.queryForObject("SELECT id FROM product_modules WHERE code = 'LIBRARY'", Long.class);
            jdbc.update("UPDATE tickets SET module_id = ? WHERE project_id = ? LIMIT 4", fees, mineProject);
            jdbc.update("UPDATE tickets SET module_id = ? WHERE project_id = ? AND module_id IS NULL LIMIT 2",
                    library, mineProject);

            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), withModule(fees), null, null, 200);

            assertThat(page.data()).hasSize(4)
                    // The second half is the one that was broken rather than
                    // missing: `toSummary` returned an unconditional null here,
                    // so a grid could filter correctly and still render every
                    // Module cell empty.
                    .allSatisfy(t -> assertThat(t.moduleId()).isEqualTo(fees));
        }

        @Test
        @DisplayName("a module id too large for the column matches nothing rather than truncating")
        void moduleIdOutOfIntRange() {
            // 4294967299 truncates to 3 through `intValue()`, and 3 is a real
            // module — so the naive narrowing returns somebody else's tickets to
            // a caller who asked for a module that cannot exist. Zero rows is
            // the only honest answer.
            Long fees = jdbc.queryForObject("SELECT id FROM product_modules WHERE code = 'FEES'", Long.class);
            jdbc.update("UPDATE tickets SET module_id = ? WHERE project_id = ?", fees, mineProject);

            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "ADMIN", List.of()), withModule(4_294_967_299L), null, null, 200);

            assertThat(page.data()).isEmpty();
        }

        @Test
        @DisplayName("no module filter still returns tickets that have no module")
        void moduleFilterIsOptional() {
            // Every column is nullable (§7.5) and tickets raised before the
            // fields existed have no honest value. An unfiltered list that
            // dropped them would hide most of the table.
            CursorPage<TicketListDtos.TicketSummary> page =
                    service.list(caller(me, "ADMIN", List.of()), mine(), null, null, 200);

            assertThat(page.data()).hasSize(12)
                    .anySatisfy(t -> assertThat(t.moduleId()).isNull());
        }

        private TicketListSpecs.Filters withModule(Long moduleId) {
            return new TicketListSpecs.Filters(null, mineProject, null, null, moduleId, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }

        // ── Dashboard Rework Dev 1, PR 5 · the seven Today/Weekly params ────

        /**
         * TODO is "not started" — NEW or REOPENED. Two different codes, one
         * category: the case `status` alone cannot express, which is the whole
         * reason `statusCategory` exists rather than the caller filtering by
         * `status=NEW` and missing every reopened ticket.
         */
        @Test
        @DisplayName("statusCategory matches every code in the category, not one")
        void statusCategoryFilters() {
            insertTicket(mineProject, me, "HIGH", "NEW");
            insertTicket(mineProject, me, "HIGH", "REOPENED");
            insertTicket(mineProject, me, "HIGH", "IN_PROGRESS");

            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "ADMIN", List.of()), withStatusCategory("TODO"), null, null, 200);

            assertThat(page.data())
                    .as("NEW and REOPENED are both TODO; IN_PROGRESS is not")
                    .extracting(TicketListDtos.TicketSummary::status)
                    .containsExactlyInAnyOrder("NEW", "REOPENED");
        }

        @Test
        @DisplayName("statuses matches an explicit set of codes, comma-separated")
        void statusesFilters() {
            insertTicket(mineProject, me, "HIGH", "ON_HOLD");
            insertTicket(mineProject, me, "HIGH", "AWAITING_INFO");
            insertTicket(mineProject, me, "HIGH", "IN_PROGRESS");

            CursorPage<TicketListDtos.TicketSummary> page = service.list(caller(me, "ADMIN", List.of()),
                    withStatuses(null, List.of("ON_HOLD", "AWAITING_INFO")), null, null, 200);

            assertThat(page.data())
                    .as("the Blocked card's set — ON_HOLD and AWAITING_INFO, nothing else")
                    .extracting(TicketListDtos.TicketSummary::status)
                    .containsExactlyInAnyOrder("ON_HOLD", "AWAITING_INFO");
        }

        /** The contract's own rule: statuses is ignored the moment status names exactly one. */
        @Test
        @DisplayName("statuses is ignored once status is also sent")
        void statusWinsOverStatuses() {
            insertTicket(mineProject, me, "HIGH", "NEW");
            insertTicket(mineProject, me, "HIGH", "ON_HOLD");

            CursorPage<TicketListDtos.TicketSummary> page = service.list(caller(me, "ADMIN", List.of()),
                    withStatuses("NEW", List.of("ON_HOLD", "AWAITING_INFO")), null, null, 200);

            assertThat(page.data())
                    .as("status=NEW alone, not ANDed and not ORed with statuses")
                    .extracting(TicketListDtos.TicketSummary::status)
                    .containsExactly("NEW");
        }

        @Test
        @DisplayName("updatedFrom/updatedTo filters on updatedAt")
        void updatedWindowFilters() {
            long recent = insertTicketReturningId(mineProject, me, "HIGH", "NEW");
            long stale = insertTicketReturningId(mineProject, me, "HIGH", "NEW");
            jdbc.update("UPDATE tickets SET updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), stale);

            CursorPage<TicketListDtos.TicketSummary> page = service.list(caller(me, "ADMIN", List.of()),
                    withUpdatedWindow(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1), null),
                    null, null, 200);

            assertThat(page.data()).extracting(t -> t.ticketId())
                    .as("the backdated ticket falls outside the window; the fresh one is inside it")
                    .doesNotContain(codeOf(stale)).contains(codeOf(recent));
        }

        /**
         * Reads the *current cycle's* stamp, not a ticket-level column — PR 3's
         * whole reason for a per-cycle pair rather than one on `tickets`.
         */
        @Test
        @DisplayName("startedFrom/startedTo filters on the current cycle's startedAt")
        void startedWindowFilters() {
            long started = insertTicketReturningId(mineProject, me, "HIGH", "IN_PROGRESS");
            long notStarted = insertTicketReturningId(mineProject, me, "HIGH", "NEW");
            insertCycle(started, Instant.parse("2026-08-20T09:00:00Z"), null);
            // notStarted carries no ticket_cycles row at all — the common case
            // for a ticket nobody has picked up — and must not match either.

            CursorPage<TicketListDtos.TicketSummary> page = service.list(caller(me, "ADMIN", List.of()),
                    withStartedWindow(LocalDate.parse("2026-08-19"), LocalDate.parse("2026-08-21")),
                    null, null, 200);

            assertThat(page.data()).extracting(t -> t.ticketId())
                    .as("only the ticket with a matching cycle stamp; the cycle-less one cannot match")
                    .containsExactly(codeOf(started))
                    .doesNotContain(codeOf(notStarted));
        }

        @Test
        @DisplayName("finishedFrom/finishedTo filters on the current cycle's finishedAt")
        void finishedWindowFilters() {
            long finished = insertTicketReturningId(mineProject, me, "HIGH", "RESOLVED");
            long stillOpen = insertTicketReturningId(mineProject, me, "HIGH", "IN_PROGRESS");
            insertCycle(finished, Instant.parse("2026-08-20T09:00:00Z"), Instant.parse("2026-08-20T17:00:00Z"));
            insertCycle(stillOpen, Instant.parse("2026-08-20T09:00:00Z"), null);

            CursorPage<TicketListDtos.TicketSummary> page = service.list(caller(me, "ADMIN", List.of()),
                    withFinishedWindow(LocalDate.parse("2026-08-19"), LocalDate.parse("2026-08-21")),
                    null, null, 200);

            assertThat(page.data()).extracting(t -> t.ticketId())
                    .as("finished has a finishedAt in range; stillOpen's is null and cannot match a window")
                    .containsExactly(codeOf(finished));
        }

        /**
         * Resolved from the stage master (`is_review_stage`), not a hardcoded
         * VERIFY/SIGNOFF list — this fixture proves the join actually reaches
         * the flag, which a stub returning true for every ticket would also
         * pass if the assertion only checked "not empty".
         */
        @Test
        @DisplayName("pendingReview matches RESOLVED tickets and tickets in a review stage")
        void pendingReviewFilters() {
            long resolved = insertTicketReturningId(mineProject, me, "HIGH", "RESOLVED");
            long inSignoff = insertTicketReturningId(mineProject, me, "HIGH", "IN_PROGRESS");
            long inQa = insertTicketReturningId(mineProject, me, "HIGH", "IN_PROGRESS");
            jdbc.update("UPDATE tickets SET current_stage = 'SIGNOFF' WHERE id = ?", inSignoff);
            jdbc.update("UPDATE tickets SET current_stage = 'QA' WHERE id = ?", inQa);

            CursorPage<TicketListDtos.TicketSummary> page = service.list(
                    caller(me, "ADMIN", List.of()), withPendingReview(), null, null, 200);

            assertThat(page.data()).extracting(t -> t.ticketId())
                    .as("RESOLVED and SIGNOFF (a review stage) match; QA — testing, not review — does not")
                    .containsExactlyInAnyOrder(codeOf(resolved), codeOf(inSignoff));
        }

        private String codeOf(long ticketId) {
            return jdbc.queryForObject("SELECT ticket_code FROM tickets WHERE id = ?", String.class, ticketId);
        }

        private TicketListSpecs.Filters withStatusCategory(String category) {
            return new TicketListSpecs.Filters(null, mineProject, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    category, null, null, null, null, null, null, null, null);
        }

        private TicketListSpecs.Filters withStatuses(String status, List<String> statuses) {
            return new TicketListSpecs.Filters(null, mineProject, null, null, null, null, status, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, statuses, null, null, null, null, null, null, null);
        }

        private TicketListSpecs.Filters withUpdatedWindow(LocalDate from, LocalDate to) {
            return new TicketListSpecs.Filters(null, mineProject, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, from, to, null, null, null, null, null);
        }

        private TicketListSpecs.Filters withStartedWindow(LocalDate from, LocalDate to) {
            return new TicketListSpecs.Filters(null, mineProject, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, from, to, null, null, null);
        }

        private TicketListSpecs.Filters withFinishedWindow(LocalDate from, LocalDate to) {
            return new TicketListSpecs.Filters(null, mineProject, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, from, to, null);
        }

        private TicketListSpecs.Filters withPendingReview() {
            return new TicketListSpecs.Filters(null, mineProject, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, true);
        }
    }
}
