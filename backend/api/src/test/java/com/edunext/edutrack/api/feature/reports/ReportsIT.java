package com.edunext.edutrack.api.feature.reports;

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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-063 · S-27 against real MySQL.
 *
 * <p>The assertions that matter here are the ones a unit test cannot make: that
 * scope reaches the SQL rather than merely being computed, and that a report is
 * genuinely served from {@code daily_ticket_stats} — the table CLAUDE.md's
 * "never a live {@code COUNT(*)}" rule exists to send it to.
 *
 * <p>Summary rows are inserted directly rather than produced by A-051's worker,
 * for {@code DashboardScopeIT}'s reason: what is under test is the read path,
 * and computing the rows first would make a failure ambiguous between the two.
 */
@SpringBootTest
@Testcontainers
class ReportsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_reports_it")
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
    ReportService service;

    @Autowired
    JdbcTemplate jdbc;

    private static final LocalDate D1 = LocalDate.of(2026, 8, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 11);
    private static final LocalDate D3 = LocalDate.of(2026, 8, 12);

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long mine;
    private long theirs;
    private long me;
    private long colleague;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");

        mine = project("RPTA");
        theirs = project("RPTB");

        // The developer's own row, and a colleague's an order of magnitude
        // larger. If resource scoping ever broke, "4" would read as "64" —
        // a number wrong enough to be noticed, which "5" would not be.
        me = user("rpt.me");
        colleague = user("rpt.them");
        resourceStat(D2, me, 2, 4, 1);
        resourceStat(D2, colleague, 30, 60, 25);

        // My project: small and legible. Theirs: an order of magnitude bigger,
        // so a scope failure shows up as a wrong number rather than as a
        // plausible one.
        projectStat(D1, mine, 2, 1, 0, 5);
        projectStat(D2, mine, 3, 2, 1, 6);
        projectStat(D3, mine, 1, 4, 0, 3);

        projectStat(D1, theirs, 40, 20, 5, 100);
        projectStat(D2, theirs, 50, 25, 6, 120);
        projectStat(D3, theirs, 60, 30, 7, 140);
    }

    private CallerIdentity admin() {
        return new CallerIdentity(1L, "ADMIN", List.of());
    }

    private CallerIdentity pm() {
        return new CallerIdentity(2L, "PM", List.of(mine));
    }

    private CallerIdentity developer() {
        // The seeded user, not an arbitrary id — the whole point of the
        // resource-keyed assertions is that the rows returned are this
        // person's.
        return new CallerIdentity(me, "DEVELOPER", List.of(mine));
    }

    @Nested
    @DisplayName("the catalogue")
    class Catalogue {

        @Test
        @DisplayName("lists every report for every role, unbuilt ones included")
        void sameCardsForEverybody() {
            // The hub does not hide cards by role. Hiding them would collapse
            // "not built", "not permitted" and "does not exist" into one state.
            // Availability differs per caller; the card is always present.
            assertThat(service.catalogue(admin()).reports()).hasSize(18);
            assertThat(service.catalogue(pm()).reports()).hasSize(18);
            assertThat(service.catalogue(developer()).reports()).hasSize(18);
        }

        /**
         * The note and the rows must agree.
         *
         * <p>This is the assertion whose absence let a Developer see
         * organisation-wide figures under "these reports cover your own work
         * only" — the note and the data were each asserted, never their
         * consistency.
         */
        @Test
        @DisplayName("nothing a delivery role can run contradicts the scope note above it")
        void everyRunnableReportAgreesWithTheScopeNote() {
            ReportDtos.Catalogue mine = service.catalogue(developer());

            assertThat(mine.scopeNote()).isEqualTo("These reports cover your own work only.");

            // A-066 turned six more on, so this can no longer be a fixed list —
            // and pinning one would only have to be rewritten by A-067. What
            // must hold is the rule: nothing offered to a delivery role may read
            // a project-keyed table, because the note above the grid says the
            // rows are their own.
            assertThat(mine.reports())
                    .filteredOn(ReportDtos.Descriptor::available)
                    .extracting(ReportDtos.Descriptor::key)
                    .doesNotContain("project-health", "aging", "stage-funnel",
                            "client-report", "email-delivery-log")
                    .contains(DateWiseReportRunner.KEY);
        }

        @Test
        @DisplayName("tells a delivery role whose rows they are about to see")
        void scopeNoteForDeveloper() {
            assertThat(service.catalogue(developer()).scopeNote())
                    .isEqualTo("These reports cover your own work only.");
        }

        @Test
        @DisplayName("says nothing to an Admin, because 'everything' is not information")
        void noNoteForAdmin() {
            assertThat(service.catalogue(admin()).scopeNote()).isNull();
        }
    }

    @Nested
    @DisplayName("running the date-wise report")
    class DateWise {

        @Test
        @DisplayName("returns one row per summarised day, with the columns the viewer draws")
        void rowsAndColumns() {
            ReportService.Rendered rendered = run(admin(), D1, D3, null);

            assertThat(rendered.report().columns())
                    .extracting(ReportDtos.Column::key)
                    .containsExactly("date", "created", "closed", "reopened", "openTotal");

            assertThat(rendered.report().rows()).hasSize(3);
        }

        @Test
        @DisplayName("sums flow across projects for an Admin, day by day")
        void adminSeesEverything() {
            List<Map<String, Object>> rows = run(admin(), D1, D3, null).report().rows();

            // D1: my 2 created + their 40.
            assertThat(rows.get(0)).containsEntry("created", 42L).containsEntry("closed", 21L);
            assertThat(rows.get(2)).containsEntry("created", 61L).containsEntry("closed", 34L);
        }

        /**
         * The assertion the whole scope apparatus exists for.
         */
        @Test
        @DisplayName("a PM sees only their own project, whatever the other one did")
        void pmIsScopedToTheirProjects() {
            List<Map<String, Object>> rows = run(pm(), D1, D3, null).report().rows();

            assertThat(rows).hasSize(3);
            assertThat(rows.get(0)).containsEntry("created", 2L);
            assertThat(rows.get(1)).containsEntry("created", 3L);
            assertThat(rows.get(2)).containsEntry("created", 1L);
        }

        @Test
        @DisplayName("a PM asking for somebody else's project gets their own, not a 403 and not that project")
        void outOfScopeProjectIsNarrowedNotRefused() {
            // §7 of the contract conventions: an out-of-scope row is a 404, never
            // a 403 — and here there is not even a row to deny. A report over a
            // project you cannot see simply has nothing of yours in it.
            List<Map<String, Object>> rows = run(pm(), D1, D3, theirs).report().rows();

            assertThat(rows.get(0)).containsEntry("created", 2L);
        }

        @Test
        @DisplayName("net backlog is the recorded stock, never a running total")
        void backlogIsStockNotCumulative() {
            // A cumulative created-minus-closed would read 1, 2, -1 here and
            // would start from zero whatever the window, implying an empty
            // backlog that was never empty. The recorded value is what was
            // actually true at the end of each day.
            List<Map<String, Object>> rows = run(pm(), D1, D3, null).report().rows();

            assertThat(rows.get(0)).containsEntry("openTotal", 5L);
            assertThat(rows.get(1)).containsEntry("openTotal", 6L);
            assertThat(rows.get(2)).containsEntry("openTotal", 3L);
        }

        @Test
        @DisplayName("days with no summarised row are absent, not zero-filled")
        void gapsAreNotInvented() {
            // A project created last month has no rows before it existed.
            // Drawing zeros there asserts a backlog of nothing rather than an
            // absence of data.
            List<Map<String, Object>> rows =
                    run(admin(), D1.minusDays(10), D3, null).report().rows();

            assertThat(rows).hasSize(3);
        }

        @Test
        @DisplayName("states the scope it applied, so an ignored filter is not mistaken for an empty result")
        void appliedScopeIsReported() {
            assertThat(run(admin(), D1, D3, null).meta().appliedScope())
                    .isEqualTo("the whole organisation");
            assertThat(run(pm(), D1, D3, null).meta().appliedScope())
                    .isEqualTo("your projects");
        }

        /**
         * The URL half of the same fix.
         *
         * <p>Withholding the card on the hub and still answering the endpoint
         * would be the half-fix that matters least: the URL is what a bookmark,
         * a shared link and A-065's stored schedule all use.
         */
        @Test
        @DisplayName("a delivery role cannot reach a project-keyed report by URL either")
        void deliveryRoleCannotRunItDirectly() {
            assertThat(service.run(developer(), "project-health", D1, D3, null, null)).isEmpty();
        }

        /**
         * The assertion that would have caught the original defect: a delivery
         * role's numbers must be their own, not their projects'.
         */
        @Test
        @DisplayName("a delivery role gets their own figures, from the resource-keyed table")
        void deliveryRoleSeesOwnWork() {
            ReportService.Rendered rendered = service
                    .run(developer(), DateWiseReportRunner.KEY, D1, D3, null, null)
                    .orElseThrow();

            // Different columns, because created and reopened do not exist per
            // person — a ticket is raised by a reporter and reopened by a manager.
            assertThat(rendered.report().columns())
                    .extracting(ReportDtos.Column::key)
                    .containsExactly("date", "closed", "effortHours", "assignedOpen", "assignedDelayed");

            // The developer's own row: 2 closed and 4 open, not the project's 40.
            assertThat(rendered.report().rows()).hasSize(1);
            assertThat(rendered.report().rows().get(0))
                    .containsEntry("closed", 2L)
                    .containsEntry("assignedOpen", 4L);

            assertThat(rendered.meta().appliedScope()).isEqualTo("your own work");
        }

        @Test
        @DisplayName("one developer's report never contains another's rows")
        void resourceRowsAreNotShared() {
            ReportService.Rendered rendered = service
                    .run(developer(), DateWiseReportRunner.KEY, D1, D3, null, null)
                    .orElseThrow();

            // The colleague holds 60. If scoping were wrong this would be 64.
            assertThat(rendered.report().rows().get(0)).containsEntry("assignedOpen", 4L);
        }
    }

    @Nested
    @DisplayName("the ETag")
    class Validators {

        @Test
        @DisplayName("is stable while the summary tables have not been recomputed")
        void stableAcrossIdenticalRuns() {
            // The contract's own note: reports are re-run every time somebody
            // changes a filter and changes it back. This is what pays for that.
            assertThat(run(admin(), D1, D3, null).etag())
                    .isEqualTo(run(admin(), D1, D3, null).etag());
        }

        @Test
        @DisplayName("differs between two callers with different scope on the same URL")
        void scopeIsInTheValidator() {
            // Omitting scope from the hash is how a cache hands one person
            // another's report after a role change.
            assertThat(run(admin(), D1, D3, null).etag())
                    .isNotEqualTo(run(pm(), D1, D3, null).etag());
        }

        @Test
        @DisplayName("is absent when nothing has been computed for the window")
        void noValidatorForAnEmptyWindow() {
            // A window with no rows is a state that can change with no
            // computed_at to prove it, so a stable validator would pin an empty
            // report in place until the range was changed.
            assertThat(run(admin(), LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 5), null).etag())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("keys that do not resolve")
    class NotFound {

        @Test
        @DisplayName("an unknown key is empty, which the controller turns into 404")
        void unknownKey() {
            assertThat(service.run(admin(), "no-such-report", D1, D3, null, null)).isEmpty();
        }

        @Test
        @DisplayName("a declared but unbuilt report is also 404, not an empty 200")
        void unbuiltKey() {
            // The catalogue is where "exists, not built yet" is said in words a
            // person reads. A runner has no columns to name and no rows to
            // return, so a 200 would have to invent an empty report — which
            // asserts the query ran and found nothing.
            // resource-contribution, not resource-scorecard — A-066 built that
            // one, and a test naming a report that keeps changing state is a
            // test that fails on somebody else's task.
            assertThat(service.run(admin(), "resource-contribution", D1, D3, null, null)).isEmpty();
        }
    }

    private ReportService.Rendered run(CallerIdentity caller, LocalDate from, LocalDate to, Long projectId) {
        Optional<ReportService.Rendered> rendered =
                service.run(caller, DateWiseReportRunner.KEY, from, to, projectId, null);
        assertThat(rendered).as("the date-wise report should be runnable").isPresent();
        return rendered.get();
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Reports IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Reports IT', ?, 1)
                """, u, u, u + "@example.test", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void resourceStat(LocalDate day, long userId, int closed, int assignedOpen, int assignedDelayed) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (stat_date, user_id, closed, effort_hours,
                                                  assigned_open, assigned_critical, assigned_delayed,
                                                  computed_at)
                VALUES (?, ?, ?, 6.50, ?, 0, ?, '2026-08-12 06:00:00')
                """, day, userId, closed, assignedOpen, assignedDelayed);
    }

    private void projectStat(LocalDate day, long projectId, int created, int closed, int reopened, int openTotal) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, created, closed, reopened,
                                                open_total, open_critical, open_delayed, open_reopened,
                                                computed_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, '2026-08-12 06:00:00')
                """, day, projectId, created, closed, reopened, openTotal);
    }
}
