package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-065 · §7.8's scheduled report emails against real MySQL.
 *
 * <p>Three groups of assertion, and only the middle one is unusual:
 *
 * <ul>
 *   <li>the endpoints — create, list, cancel, download — including the 404s
 *       that must not be 403s;</li>
 *   <li>🔴 <b>the scope re-resolution</b>, which is the security design of the
 *       whole feature. A schedule stores no role and no projects, so a demotion
 *       has to narrow the next email and a deactivation has to stop it. Both
 *       are asserted by <em>changing the user between two runs</em>, because
 *       that is the only way to tell a re-resolution from a frozen copy — a
 *       single run passes identically either way;</li>
 *   <li>the run mechanics — the period comes from the cadence, the file is
 *       stored before anybody is told about it, and a failure still advances
 *       the clock so one broken schedule cannot spin.</li>
 * </ul>
 *
 * <p>The object store is substituted in memory. What is under test is which
 * bytes get produced and who may fetch them, and needing MinIO to answer that
 * would make this suite untestable on a laptop for no gain — the S3 mapping
 * itself is four lines with no branch.
 */
@SpringBootTest(properties = {
        // The bean has to exist so these tests can drive runDue() themselves;
        // what must not happen is the api context sweeping while the suite is
        // still seeding. StatsRefreshWorker's arrangement, one module over.
        "edutrack.reports.scheduler-enabled=false",
        // Fixed so "next run" assertions are about the cadence maths rather
        // than about what time the suite happens to run.
        "edutrack.reports.zone=Asia/Kolkata",
        "edutrack.reports.send-hour=6",
})
@Testcontainers
class ReportScheduleIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_report_schedule_it")
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

    /** An in-memory object store — see the class note. */
    @TestConfiguration
    static class InMemoryStore {

        static final Map<String, byte[]> OBJECTS = new HashMap<>();

        @Bean
        @Primary
        ReportFileStore inMemoryReportFileStore() {
            return new ReportFileStore() {
                @Override
                public String put(long scheduleId, long runId, String fileName,
                                  String contentType, byte[] file) {
                    String key = "reports/schedules/%d/runs/%d/%s".formatted(scheduleId, runId, fileName);
                    OBJECTS.put(key, file);
                    return key;
                }

                @Override
                public Optional<byte[]> read(String key) {
                    return Optional.ofNullable(OBJECTS.get(key));
                }
            };
        }
    }

    @Autowired
    ReportScheduleService service;

    @Autowired
    ReportScheduleRepository repository;

    @Autowired
    ReportService reports;

    @Autowired
    ReportExportService exports;

    @Autowired
    ReportFileStore files;

    @Autowired
    com.edunext.edutrack.domain.outbox.OutboxEnqueuer outbox;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** A Wednesday, so "last week" is unambiguous in the weekly assertions. */
    private static final Instant NOW = Instant.parse("2026-08-19T04:00:00Z");

    private long mine;
    private long theirs;
    private long owner;
    private long stranger;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM report_schedule_runs");
        jdbc.update("DELETE FROM report_schedules");
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM project_members");
        InMemoryStore.OBJECTS.clear();

        mine = project("SCHA");
        theirs = project("SCHB");
        owner = user("sch.owner", "PM");
        stranger = user("sch.other", "PM");
        member(mine, owner);

        // date-wise reads daily_ticket_stats. Both projects carry rows, so a
        // scope failure shows up as a bigger number rather than as no number.
        for (LocalDate d : List.of(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11))) {
            projectStat(d, mine, 3, 1, 0, 9);
            projectStat(d, theirs, 30, 10, 0, 90);
        }
    }

    // ── endpoints ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("creating a schedule")
    class Creating {

        @Test
        @DisplayName("comes back with the id and when it will actually arrive")
        void createReturnsTheSchedule() {
            ReportScheduleDtos.Schedule created = service.create(pm(owner, mine), request());

            assertThat(created.id()).isPositive();
            assertThat(created.reportKey()).isEqualTo("date-wise");
            assertThat(created.reportTitle()).isNotBlank();
            assertThat(created.active()).isTrue();
            // D-001 declared a bare 201. Without nextRunAt the dialog cannot
            // answer the only question anybody has after pressing Schedule.
            assertThat(created.nextRunAt()).isNotNull();
        }

        /**
         * The mail links to an authenticated download, so an address with no
         * account is a standing invitation to a sign-in page it cannot pass.
         * Refused where the person choosing can fix it.
         */
        @Test
        @DisplayName("refuses a recipient with no account, and names it")
        void unknownRecipientIsRefused() {
            ReportScheduleDtos.ScheduleRequest request = new ReportScheduleDtos.ScheduleRequest(
                    "date-wise", "WEEKLY", "xlsx",
                    List.of(emailOf(owner), "nobody@external.example"), Map.of());

            assertThatThrownBy(() -> service.create(pm(owner, mine), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("nobody@external.example");
        }

        @Test
        @DisplayName("refuses a report the caller's role cannot run")
        void unavailableReportIsRefused() {
            ReportScheduleDtos.ScheduleRequest request = new ReportScheduleDtos.ScheduleRequest(
                    "no-such-report", "WEEKLY", "xlsx", List.of(emailOf(owner)), Map.of());

            assertThatThrownBy(() -> service.create(pm(owner, mine), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no-such-report");
        }

        /**
         * 🔴 The stored window that would make every run identical.
         *
         * <p>The viewer posts back the filter bar it rendered, dates included.
         * If they were kept they would win over the cadence and the schedule
         * would email one frozen fortnight for ever — which looks exactly like
         * a working schedule until two files are compared.
         */
        @Test
        @DisplayName("drops any date range from the stored filters")
        void datesAreNotStored() {
            Map<String, Object> filters = new HashMap<>();
            filters.put("from", "2026-08-01");
            filters.put("to", "2026-08-07");
            filters.put("projectId", String.valueOf(mine));

            ReportScheduleDtos.Schedule created = service.create(pm(owner, mine),
                    new ReportScheduleDtos.ScheduleRequest(
                            "date-wise", "WEEKLY", "xlsx", List.of(emailOf(owner)), filters));

            assertThat(created.parameters()).doesNotContainKeys("from", "to");
            assertThat(created.parameters()).containsKey("projectId");
        }

        @Test
        @DisplayName("two spellings of one address are one recipient")
        void recipientsAreDeduplicated() {
            String email = emailOf(owner);
            ReportScheduleDtos.Schedule created = service.create(pm(owner, mine),
                    new ReportScheduleDtos.ScheduleRequest("date-wise", "DAILY", "csv",
                            List.of(email, email.toUpperCase(java.util.Locale.ROOT)), Map.of()));

            assertThat(created.recipients()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("listing and cancelling")
    class Managing {

        @Test
        @DisplayName("a cancelled schedule stays listed, inactive")
        void cancelledSchedulesStayVisible() {
            long id = service.create(pm(owner, mine), request()).id();

            service.cancel(pm(owner, mine), id);

            List<ReportScheduleDtos.Schedule> mineNow = service.mine(pm(owner, mine));
            assertThat(mineNow).singleElement().satisfies(s -> {
                assertThat(s.id()).isEqualTo(id);
                // Not removed: "why did this stop arriving" is a question the
                // screen has to be able to answer.
                assertThat(s.active()).isFalse();
            });
        }

        @Test
        @DisplayName("cancelling twice is not an error")
        void cancelIsIdempotent() {
            long id = service.create(pm(owner, mine), request()).id();

            service.cancel(pm(owner, mine), id);
            service.cancel(pm(owner, mine), id);

            assertThat(service.mine(pm(owner, mine))).singleElement()
                    .satisfies(s -> assertThat(s.active()).isFalse());
        }

        /**
         * §2's rule for an out-of-scope id. A 403 would confirm the schedule
         * exists and belongs to somebody, which is worth nothing to its owner
         * and something to anybody enumerating.
         */
        @Test
        @DisplayName("somebody else's schedule is a 404, never a 403")
        void anotherUsersScheduleIsNotFound() {
            long id = service.create(pm(owner, mine), request()).id();

            assertThatThrownBy(() -> service.cancel(pm(stranger, mine), id))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .isEqualTo(404));
        }

        @Test
        @DisplayName("a schedule is not in the list of somebody unconnected to it")
        void listExcludesUnrelatedUsers() {
            service.create(pm(owner, mine), request());

            assertThat(service.mine(pm(stranger, mine))).isEmpty();
        }

        /**
         * 🔴 The defect that made the whole feature single-player.
         *
         * <p>The list and the download both filtered on {@code created_by}, so
         * a colleague who was emailed a report followed the link to a page that
         * showed them nothing. Scheduling a report <em>to your team</em> — the
         * reason the feature exists — produced three people staring at a dead
         * link and one person who could actually open it.
         */
        @Test
        @DisplayName("a recipient sees the schedule they are on")
        void recipientsSeeTheSchedule() {
            service.create(pm(owner, mine), new ReportScheduleDtos.ScheduleRequest(
                    "date-wise", "WEEKLY", "xlsx", List.of(emailOf(stranger)), Map.of()));

            assertThat(service.mine(pm(stranger, mine))).singleElement().satisfies(s -> {
                assertThat(s.reportKey()).isEqualTo("date-wise");
                // Read-only: stopping somebody else's standing instruction is
                // not the recipient's decision, and the owner's name is on the
                // row so they know who to ask.
                assertThat(s.ownedByMe()).isFalse();
            });
            assertThat(service.mine(pm(owner, mine))).singleElement()
                    .satisfies(s -> assertThat(s.ownedByMe()).isTrue());
        }

        /**
         * The read-only half. A recipient may open the files and may not stop
         * the schedule — and the refusal is a 404 rather than a 403, because
         * "not yours to cancel" and "no such schedule" must look the same to
         * anybody probing ids.
         */
        @Test
        @DisplayName("a recipient cannot cancel somebody else's schedule")
        void recipientsCannotCancel() {
            long id = service.create(pm(owner, mine), new ReportScheduleDtos.ScheduleRequest(
                    "date-wise", "WEEKLY", "xlsx", List.of(emailOf(stranger)), Map.of())).id();

            assertThatThrownBy(() -> service.cancel(pm(stranger, mine), id))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .isEqualTo(404));
            assertThat(repository.findById(id)).get()
                    .satisfies(s -> assertThat(s.active()).isTrue());
        }

        /**
         * Addresses are typed by hand, so the one stored has to be the one the
         * lookup will later compare against — otherwise a recipient who typed
         * their own address in capitals silently stops being a recipient.
         */
        @Test
        @DisplayName("a recipient typed in a different case still sees it")
        void recipientMatchingIsCaseInsensitive() {
            service.create(pm(owner, mine), new ReportScheduleDtos.ScheduleRequest(
                    "date-wise", "WEEKLY", "xlsx",
                    List.of(emailOf(stranger).toUpperCase(java.util.Locale.ROOT)), Map.of()));

            assertThat(service.mine(pm(stranger, mine))).hasSize(1);
        }
    }

    // ── the run ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("running a due schedule")
    class Running {

        @Test
        @DisplayName("produces a file, records the run, and enqueues one mail per recipient")
        void aDueScheduleRuns() {
            long id = dueSchedule("WEEKLY");

            assertThat(runner(NOW).runDue()).isEqualTo(1);

            List<ReportScheduleRepository.RunRow> runs = repository.recentRuns(id, 5);
            assertThat(runs).singleElement().satisfies(run -> {
                assertThat(run.status()).isEqualTo("SUCCEEDED");
                assertThat(run.storageKey()).isNotNull();
                assertThat(run.appliedScope()).isNotBlank();
                // The completed week before a Wednesday run: Mon 10 – Sun 16.
                assertThat(run.periodFrom()).isEqualTo(LocalDate.of(2026, 8, 10));
                assertThat(run.periodTo()).isEqualTo(LocalDate.of(2026, 8, 16));
            });

            assertThat(InMemoryStore.OBJECTS).hasSize(1);
            assertThat(queuedMail()).isEqualTo(1);
        }

        /**
         * Stored first, mailed second. A mail announcing a file that was never
         * stored sends people to a 404 they can do nothing about.
         */
        @Test
        @DisplayName("the file exists before anybody is told about it")
        void nothingIsMailedWithoutAFile() {
            long id = dueSchedule("WEEKLY");
            runner(NOW).runDue();

            ReportScheduleRepository.RunRow run = repository.recentRuns(id, 1).getFirst();
            assertThat(files.read(run.storageKey())).isPresent();
        }

        @Test
        @DisplayName("the clock advances, so one sweep does not fire it twice")
        void theClockAdvances() {
            long id = dueSchedule("DAILY");

            assertThat(runner(NOW).runDue()).isEqualTo(1);
            // Same instant, second sweep: nothing is due any more.
            assertThat(runner(NOW).runDue()).isZero();

            assertThat(repository.findById(id)).get()
                    .satisfies(s -> assertThat(s.nextRunAt()).isAfter(NOW));
        }

        /**
         * A schedule whose report cannot be produced must still move its clock,
         * or it stays due and is retried on every sweep — several times a
         * minute, writing a FAILED row each time.
         */
        @Test
        @DisplayName("a failed run still advances the clock")
        void failureDoesNotSpin() {
            long id = dueSchedule("DAILY");
            // A key no runner serves. Written straight to the column, because
            // create() refuses it — which is the point: this is the state a
            // report withdrawn from the catalogue leaves behind.
            jdbc.update("UPDATE report_schedules SET report_key = 'withdrawn-report' WHERE id = ?", id);

            runner(NOW).runDue();

            assertThat(repository.recentRuns(id, 1)).singleElement().satisfies(run -> {
                assertThat(run.status()).isEqualTo("FAILED");
                assertThat(run.errorText()).contains("withdrawn-report");
            });
            assertThat(repository.findById(id)).get()
                    .satisfies(s -> assertThat(s.nextRunAt()).isAfter(NOW));
            assertThat(queuedMail()).as("nothing is mailed about a run that failed").isZero();
        }

        @Test
        @DisplayName("a cancelled schedule does not run")
        void cancelledSchedulesAreSkipped() {
            long id = dueSchedule("DAILY");
            service.cancel(pm(owner, mine), id);

            assertThat(runner(NOW).runDue()).isZero();
        }
    }

    // ── the property this whole feature rests on ─────────────────────────────

    @Nested
    @DisplayName("🔴 scope is re-resolved on every run, never frozen")
    class ScopeIsLive {

        /**
         * The assertion that distinguishes a re-resolution from a stored copy.
         *
         * <p>A single run passes identically either way, so the user is
         * <em>changed between two runs</em>: the schedule is created by a PM on
         * one project, and the second run happens after they have been moved to
         * Developer. A frozen scope would keep answering with the project's
         * figures; a live one answers with their own work.
         *
         * <p>Asserted on {@code appliedScope}, which is the server's own
         * sentence about what it narrowed to — and is written into the
         * spreadsheet itself, so it is what a recipient would read.
         */
        @Test
        @DisplayName("a demotion narrows the next email without touching the schedule")
        void demotionNarrowsTheNextRun() {
            long id = dueSchedule("DAILY");

            runner(NOW).runDue();
            String asPm = repository.recentRuns(id, 1).getFirst().appliedScope();

            demoteToDeveloper(owner);
            makeDue(id);
            runner(NOW.plusSeconds(3600)).runDue();

            String asDeveloper = repository.recentRuns(id, 1).getFirst().appliedScope();
            assertThat(asDeveloper)
                    .as("the same schedule, the same row, a narrower answer")
                    .isNotEqualTo(asPm);
        }

        /**
         * The other half: deactivating a leaver stops their schedules, with
         * nothing anywhere having to remember to.
         */
        @Test
        @DisplayName("a deactivated owner cancels the schedule rather than emailing on")
        void deactivatedOwnerStopsTheSchedule() {
            long id = dueSchedule("DAILY");
            jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", owner);

            runner(NOW).runDue();

            assertThat(repository.findById(id)).get()
                    .satisfies(s -> assertThat(s.active()).isFalse());
            assertThat(repository.recentRuns(id, 1)).singleElement().satisfies(run -> {
                assertThat(run.status()).isEqualTo("FAILED");
                assertThat(run.errorText()).contains("no longer an active user");
            });
            assertThat(queuedMail()).isZero();
        }

        /**
         * Losing a project narrows the rows even though role and schedule are
         * untouched — the membership is read fresh, not taken from a token
         * minted when the schedule was made.
         */
        @Test
        @DisplayName("losing a project membership narrows the rows")
        void membershipIsReadFresh() {
            assertThat(repository.callerFor(owner)).get()
                    .satisfies(c -> assertThat(c.projectIds()).containsExactly(mine));

            jdbc.update("UPDATE project_members SET is_active = 0 WHERE user_id = ?", owner);

            assertThat(repository.callerFor(owner)).get()
                    .satisfies(c -> assertThat(c.projectIds()).isEmpty());
        }

        @Test
        @DisplayName("an inactive user resolves to nobody, not to an empty scope")
        void inactiveUserIsAbsent() {
            jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", owner);

            // Empty rather than a CallerIdentity with no projects: the second
            // would be an Admin-shaped "unrestricted" for anyone whose role
            // happened to be ADMIN, which is exactly backwards.
            assertThat(repository.callerFor(owner)).isEmpty();
        }
    }

    // ── download ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("downloading a run's file")
    class Downloading {

        @Test
        @DisplayName("the owner gets the bytes that were stored")
        void ownerCanDownload() {
            long id = dueSchedule("WEEKLY");
            runner(NOW).runDue();
            long runId = repository.recentRuns(id, 1).getFirst().id();

            assertThat(service.fileFor(pm(owner, mine), id, runId, files)).get()
                    .satisfies(file -> {
                        assertThat(file.bytes()).isNotEmpty();
                        assertThat(file.fileName()).endsWith(".xlsx");
                        // Named for the period, not for the day it was
                        // generated — these accumulate in a Downloads folder
                        // weeks apart.
                        assertThat(file.fileName()).contains("2026-08-10");
                    });
        }

        /**
         * The link travels by email and email gets forwarded, so this check
         * cannot assume the person clicking is the person it was sent to.
         */
        @Test
        @DisplayName("somebody who is not on it gets nothing, whatever the run id")
        void unrelatedUsersGetNothing() {
            long id = dueSchedule("WEEKLY");
            runner(NOW).runDue();
            long runId = repository.recentRuns(id, 1).getFirst().id();

            assertThat(service.fileFor(pm(stranger, mine), id, runId, files)).isEmpty();
        }

        /**
         * 🔴 The other half of the single-player defect: a recipient could not
         * open the file the email told them was ready.
         *
         * <p>The rows are the owner's view, and that is the point rather than
         * an oversight — a schedule is a sharing act, the same one the owner
         * performs by exporting and forwarding, bounded by their own scope at
         * the moment it ran. What must still be refused is somebody never named
         * on it, which the test above holds.
         */
        @Test
        @DisplayName("a recipient can download the file they were emailed about")
        void recipientsCanDownload() {
            long id = service.create(pm(owner, mine), new ReportScheduleDtos.ScheduleRequest(
                    "date-wise", "DAILY", "xlsx", List.of(emailOf(stranger)), Map.of())).id();
            makeDue(id);
            runner(NOW).runDue();
            long runId = repository.recentRuns(id, 1).getFirst().id();

            assertThat(service.fileFor(pm(stranger, mine), id, runId, files)).get()
                    .satisfies(file -> assertThat(file.bytes()).isNotEmpty());
        }

        @Test
        @DisplayName("a file that has aged out of the store is empty, not an error")
        void missingObjectIsEmpty() {
            long id = dueSchedule("WEEKLY");
            runner(NOW).runDue();
            long runId = repository.recentRuns(id, 1).getFirst().id();
            InMemoryStore.OBJECTS.clear();

            assertThat(service.fileFor(pm(owner, mine), id, runId, files)).isEmpty();
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private ScheduledReportRunner runner(Instant now) {
        return new ScheduledReportRunner(repository, reports, exports, files, outbox, json,
                "Asia/Kolkata", 6, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ReportScheduleDtos.ScheduleRequest request() {
        return new ReportScheduleDtos.ScheduleRequest(
                "date-wise", "WEEKLY", "xlsx", List.of(emailOf(owner)), Map.of());
    }

    /** A schedule that is due right now, whatever cadence it carries. */
    private long dueSchedule(String cadence) {
        long id = service.create(pm(owner, mine), new ReportScheduleDtos.ScheduleRequest(
                "date-wise", cadence, "xlsx", List.of(emailOf(owner)), Map.of())).id();
        makeDue(id);
        return id;
    }

    private void makeDue(long id) {
        jdbc.update("UPDATE report_schedules SET next_run_at = ?, is_active = 1 WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(60)), id);
    }

    private int queuedMail() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_log WHERE event_code = 'SCHEDULED_REPORT'", Integer.class);
        return count == null ? 0 : count;
    }

    private static CallerIdentity pm(long userId, long projectId) {
        return new CallerIdentity(userId, "PM", List.of(projectId));
    }

    private void demoteToDeveloper(long userId) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        jdbc.update("UPDATE users SET role_id = ? WHERE id = ?", roleId, userId);
    }

    private String emailOf(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Schedule IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Schedule IT', ?, 1)
                """, u, u, u + "@example.test", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long projectId, long userId) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, is_active) VALUES (?, ?, 1)",
                projectId, userId);
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
