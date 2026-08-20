package com.edunext.edutrack.api.feature.search;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-072 · global search against real MySQL.
 *
 * <p>The assertions that need a database are the ones that matter: the FULLTEXT
 * index exists and matches, and — above all — <b>the scope predicate is really
 * in the SQL</b>. {@code SearchUnitTest} proves the predicate is built
 * correctly; only a database proves it was bound and applied.
 *
 * <p>The fixture is deliberately asymmetric, following {@code ReportRunnersIT}:
 * the other project holds far more matching tickets than mine, so a scope
 * failure reads as an obviously wrong count rather than a plausible one.
 */
@SpringBootTest(properties = "edutrack.reports.scheduler-enabled=false")
@Testcontainers
class SearchIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_search_it")
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
    SearchService service;

    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long mine;
    private long theirs;
    private long me;
    private long colleague;
    private String myCode;
    private String theirCode;
    /** Unique per test — nothing is deleted between them, so a shared name finds every earlier run. */
    private String myName;

    @BeforeEach
    void seed() {
        mine = project("SRA");
        theirs = project("SRB");
        String tag = "Meera" + SEQ.incrementAndGet();
        me = user("srch.me", tag + " Searcher");
        myName = tag;
        colleague = user("srch.them", "Colleague Search");

        myCode = ticket(mine, me, "Login fails on Safari", "The login button does nothing");
        theirCode = ticket(theirs, colleague, "Login fails on Firefox", "Another login problem");
        // Weight the other project, so a scope failure is a wrong number rather
        // than a plausible one.
        for (int i = 0; i < 5; i++) {
            ticket(theirs, colleague, "Login timeout " + i, "login again");
        }
    }

    @Nested
    @DisplayName("the deep link")
    class DeepLink {

        @Test
        @DisplayName("a pasted code resolves to exactly that ticket")
        void exactMatch() {
            var results = service.search(pm(mine), myCode);

            assertThat(results.exactTicket()).isNotNull();
            assertThat(results.exactTicket().ticketId()).isEqualTo(myCode);
        }

        @Test
        @DisplayName("brackets and a whole URL resolve the same way")
        void pastedForms() {
            assertThat(service.search(pm(mine), "[" + myCode + "]").exactTicket()).isNotNull();
            assertThat(service.search(pm(mine), "http://localhost:5173/tickets/" + myCode)
                    .exactTicket()).isNotNull();
        }

        /**
         * 🔴 The property this whole feature could have leaked.
         *
         * <p>A code for somebody else's project must return <b>nothing</b> — not
         * a refusal, not an empty-but-different answer. A search box that said
         * "that exists but is not yours" would let anybody confirm a ticket's
         * existence by pasting codes, which is the same existence leak
         * {@code /tickets/{id}} answers 404 rather than 403 to avoid.
         */
        @Test
        @DisplayName("a code outside the caller's scope finds nothing at all")
        void outOfScopeCodeIsInvisible() {
            var results = service.search(pm(mine), theirCode);

            assertThat(results.exactTicket())
                    .as("their ticket exists, and this caller must not learn that")
                    .isNull();
            assertThat(results.tickets()).noneSatisfy(t ->
                    assertThat(t.ticketId()).isEqualTo(theirCode));
        }

        @Test
        @DisplayName("the exact hit is not repeated in the keyword list")
        void exactIsNotDuplicated() {
            var results = service.search(pm(mine), myCode);

            assertThat(results.tickets()).noneSatisfy(t ->
                    assertThat(t.ticketId()).isEqualTo(myCode));
        }
    }

    @Nested
    @DisplayName("the keyword")
    class Keyword {

        /**
         * The native query {@code TicketListSpecs} could not write. It kept
         * {@code LIKE} rather than lose the scope guard and left the A-009 index
         * for "when this becomes a native query that keeps scope" — this is it.
         */
        @Test
        @DisplayName("full-text matches title and description")
        void fullTextMatches() {
            // Scoped to this test's project: nothing is deleted between tests, so
            // an unscoped search for a shared word finds every earlier run's
            // tickets and LIMIT drops this one. ReportRunnersIT names the same trap.
            var results = service.search(pm(mine), "Safari");

            assertThat(results.tickets()).extracting(t -> t.ticketId()).contains(myCode);
        }

        @Test
        @DisplayName("a half-typed word still matches, because terms carry a wildcard")
        void prefixMatches() {
            assertThat(service.search(pm(mine), "Saf").tickets())
                    .extracting(t -> t.ticketId()).contains(myCode);
        }

        /**
         * 🔴 The scope predicate, proved in SQL rather than in a record.
         *
         * <p>Admin sees every "login" ticket; the PM sees only their project's.
         * Asserted on the counts, because one row is what both the correct and
         * the leaking answer would return if the fixture were symmetric.
         */
        @Test
        @DisplayName("a PM's keyword search stops at their own projects")
        void keywordIsScoped() {
            var asAdmin = service.search(admin(), "login");
            var asPm = service.search(pm(mine), "login");

            assertThat(asAdmin.tickets()).hasSizeGreaterThan(asPm.tickets().size());
            assertThat(asPm.tickets()).allSatisfy(t ->
                    assertThat(t.ticketId()).isEqualTo(myCode));
        }

        @Test
        @DisplayName("a delivery role sees only what is assigned to them")
        void deliveryRoleSeesOwnWork() {
            assertThat(service.search(developer(colleague), "login").tickets())
                    .as("the colleague's six, none of them mine")
                    .noneSatisfy(t -> assertThat(t.ticketId()).isEqualTo(myCode));
        }

        /**
         * 🔴 {@code ScopeResolver}'s central warning, proved end to end: an
         * empty project list must deny rather than widen.
         */
        @Test
        @DisplayName("a PM with no projects finds nothing, rather than everything")
        void emptyScopeFindsNothing() {
            var results = service.search(new CallerIdentity(2L, "PM", List.of()), "login");

            assertThat(results.tickets()).isEmpty();
            assertThat(results.exactTicket()).isNull();
        }

        @Test
        @DisplayName("a two-letter query matches no ticket, rather than every ticket")
        void shortQueryMatchesNothing() {
            // Below innodb_ft_min_token_size. The failure guarded against is an
            // empty boolean query, which matches every row in the table.
            assertThat(service.search(admin(), "QA").tickets()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the person")
    class People {

        @Test
        @DisplayName("finds a colleague by the start of their name")
        void byName() {
            assertThat(service.search(admin(), myName).people())
                    .extracting(p -> p.displayName()).contains(myName + " Searcher");
        }

        /**
         * The directory is already open to every role — {@code GET /users} is
         * {@code everyRole} — so narrowing it here would be a new access policy
         * invented inside a search box, contradicting the screen next door.
         */
        @Test
        @DisplayName("a delivery role can find people too")
        void deliveryRolesSeePeople() {
            assertThat(service.search(developer(me), "Colleague").people()).isNotEmpty();
        }

        @Test
        @DisplayName("carries identity and nothing about performance")
        void identityOnly() {
            var person = service.search(admin(), myName).people().getFirst();

            assertThat(person.displayName()).isNotBlank();
            assertThat(person.role()).isNotBlank();
            // The record has no counts, workload or last-login to assert the
            // absence of — that is the point, and SearchDtos.GlobalSearchPersonHit says so.
        }

        @Test
        @DisplayName("a deactivated user is not somewhere anybody is trying to go")
        void inactiveUsersAreExcluded() {
            jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", me);

            assertThat(service.search(admin(), myName).people()).isEmpty();
        }
    }

    @Test
    @DisplayName("an empty query asks for nothing and gets nothing")
    void emptyQuery() {
        var results = service.search(admin(), "   ");

        assertThat(results.exactTicket()).isNull();
        assertThat(results.tickets()).isEmpty();
        assertThat(results.people()).isEmpty();
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private CallerIdentity admin() {
        return new CallerIdentity(1L, "ADMIN", List.of());
    }

    private CallerIdentity pm(long projectId) {
        return new CallerIdentity(2L, "PM", List.of(projectId));
    }

    private CallerIdentity developer(long userId) {
        return new CallerIdentity(userId, "DEVELOPER", List.of());
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Search IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String fullName) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', ?, ?, 1)
                """, u, u, u + "@example.test", fullName, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** @return the ticket code, which is what search deals in on the wire. */
    private String ticket(long projectId, long assignee, String title, String description) {
        String code = "SR%d-26-%05d".formatted(projectId % 10, SEQ.incrementAndGet());
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, description, level, original_level,
                                     status, date_reported, reported_by, assigned_to, current_cycle_no)
                VALUES (?, ?, ?, ?, 'MEDIUM', 'MEDIUM', 'IN_PROGRESS', '2026-08-10 09:00:00', ?, ?, 1)
                """, code, projectId, title, description, assignee, assignee);
        return code;
    }
}
