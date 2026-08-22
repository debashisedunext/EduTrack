package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-052 · the aggregated detail read.
 *
 * <p>The assertions that matter here are not "does it return data" but the
 * things that are easy to get wrong and silent when wrong: that the scope
 * guard is applied <em>before</em> anything is read by ticket id, that
 * {@code ?cycle=} filters the journals and nothing else, that {@code ribbon}
 * stays null rather than quietly invented (still blocked on Stream B's
 * workflow-stage sequence — C-051), and that {@code availableActions}
 * reflects C-043's golden rule against a real {@code Ticket} entity, not
 * just the mocked one {@code TicketDetailServiceTest} exercises.
 */
@SpringBootTest
@Testcontainers
class TicketDetailIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ticket_detail_it")
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
    TicketDetailService service;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long otherProjectId;
    private long userId;
    private long ticketId;

    @BeforeEach
    void seed() {
        // ticket_history and ticket_effort_logs are append-only — A-008's
        // triggers refuse DELETE — so fixtures are never truncated here. Each
        // run makes its own project, user and ticket, and every assertion is
        // scoped to them.
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, 'Detail IT', 'ACTIVE')",
                "DTL" + suffix());
        projectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, 'Other IT', 'ACTIVE')",
                "OTH" + suffix());
        otherProjectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Detail IT', ?, 1)
                """, "DTL" + suffix(), "dtl." + suffix(),
                "dtl" + suffix() + "@example.test", roleId);
        userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        ticketId = insertTicket(projectId, (short) 2);

        // Two cycles' worth of journal, so the cycle filter has something to cut.
        insertHistory(ticketId, (short) 1, "CREATED");
        insertHistory(ticketId, (short) 2, "REOPENED");
        insertEffort(ticketId, (short) 1, "2.00");
        insertEffort(ticketId, (short) 2, "3.50");
        insertComment(ticketId, (short) 1, "first cycle note");
        insertCycle(ticketId, (short) 1, true);
        insertCycle(ticketId, (short) 2, false);
    }

    /**
     * The service resolves by ticket CODE, not by id — the contract's
     * {@code TicketId} is the code, so that is what every real caller sends.
     * These fixtures are created by id, so this reads back the code the
     * insert generated rather than restating its format in a second place.
     */
    private String codeOf(long id) {
        return jdbc.queryForObject("SELECT ticket_code FROM tickets WHERE id = ?", String.class, id);
    }

    private long insertTicket(long project, short currentCycle) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level,
                                     status, assigned_to, current_cycle_no)
                VALUES (?, ?, 'detail probe', 'HIGH', 'HIGH', 'IN_PROGRESS', ?, ?)
                """, "DT-26-" + suffix(), project, userId, currentCycle);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * A unique tail for every fixture identifier, seven characters wide.
     *
     * <p>Replaces {@code System.nanoTime() % 100000}, which <b>failed a full gate
     * run on 16 Aug 2026</b>: {@code Duplicate entry 'OTH48000' for key
     * 'projects.uq_projects_code'}. Five decimal digits is a 100 000-value space,
     * {@code @BeforeEach} claims six of them per test method, and — as the comment
     * above says — <em>fixtures are never truncated here</em>, because the
     * append-only triggers refuse DELETE. So every run adds rows to a space that
     * never empties, and a collision stops being unlikely and becomes a matter of
     * time. It is also the worst kind of failure to debug: it lands on a random
     * test, once, and passes on re-run.
     *
     * <p>Base 36 rather than more digits, because {@code projects.project_code} is
     * {@code VARCHAR(10)} and a three-letter prefix leaves exactly seven. That
     * buys 36^7 ≈ 78 billion values in the same width — the constraint that
     * forced {@code % 100000} in the first place.
     *
     * <p><b>Not part of C-030.</b> Fixed here because it is Stream C's own file
     * and it cost this branch a gate run; nothing about the mention fan-out
     * touches it.
     */
    private static String suffix() {
        // abs() and the length guard because nanoTime's origin is arbitrary and
        // the spec permits a negative or small value; a '-' in a project code
        // would fail for a reason nobody would look for here.
        String base36 = Long.toString(Math.abs(System.nanoTime()), 36);
        return base36.length() <= 7 ? base36 : base36.substring(base36.length() - 7);
    }

    private void insertHistory(long ticket, short cycle, String eventType) {
        jdbc.update("""
                INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type)
                VALUES (?, ?, ?, ?, 'USER')
                """, ticket, cycle, eventType, userId);
    }

    private void insertEffort(long ticket, short cycle, String hours) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no,
                                                user_id, work_date, hours)
                VALUES (?, ?, 'DEV', 1, ?, '2026-08-10', ?)
                """, ticket, cycle, userId, new java.math.BigDecimal(hours));
    }

    private void insertComment(long ticket, short cycle, String body) {
        jdbc.update("""
                INSERT INTO ticket_comments (ticket_id, cycle_no, author_id, body_html, body_text, is_internal)
                VALUES (?, ?, ?, ?, ?, 1)
                """, ticket, cycle, userId, "<p>" + body + "</p>", body);
    }

    private void insertCycle(long ticket, short cycle, boolean sealed) {
        jdbc.update("""
                INSERT INTO ticket_cycles (ticket_id, cycle_no, start_date, is_sealed)
                VALUES (?, ?, '2026-08-01 09:00:00', ?)
                """, ticket, cycle, sealed ? 1 : 0);
    }

    /** An Admin sees everything, which keeps the non-scope tests about aggregation. */
    private Authentication admin() {
        return caller("ADMIN", List.of());
    }

    private Authentication caller(String role, List<Long> projectIds) {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(userId, "dtl.fixture", "Fixture", role, projectIds, List.of()),
                null, List.of());
    }

    // ── the aggregation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("one call instead of six")
    class Aggregation {

        @Test
        @DisplayName("returns every section the detail page needs")
        void returnsEverySection() {
            TicketDetailDtos.Detail d = service.detail(admin(), codeOf(ticketId), null);

            assertThat(d.ticket().id()).isEqualTo(ticketId);
            assertThat(d.ticket().ticketCode()).isNotBlank();
            assertThat(d.cycles()).as("both cycles").hasSize(2);
            assertThat(d.history()).isNotEmpty();
            assertThat(d.effortLogs()).isNotEmpty();
            assertThat(d.comments()).isNotEmpty();
            assertThat(d.attachments()).isNotNull();
            assertThat(d.watchers()).isNotNull();
        }

        /**
         * {@code ribbon}'s rule is still unbuilt. Asserted explicitly so that
         * filling it in is a deliberate act that breaks a test, rather than
         * something that quietly starts returning invented data.
         */
        @Test
        @DisplayName("ribbon is null until C-051's stage sequence exists")
        void ribbonIsNullNotInvented() {
            TicketDetailDtos.Detail d = service.detail(admin(), codeOf(ticketId), null);

            assertThat(d.ribbon())
                    .as("a ribbon needs C-042's transitions and B's workflow stages; the second half is still missing")
                    .isNull();
        }

        /**
         * C-043 · the fixture ticket defaults to {@code current_stage = 'INTAKE'}
         * and status {@code IN_PROGRESS} — a live stage — and {@code admin()} is
         * both an Admin <em>and</em> (same {@code userId}) the ticket's own
         * assignee, so either half of the golden rule alone would pass this.
         */
        @Test
        @DisplayName("availableActions offers handoff/rework to the current owner")
        void availableActionsOffersAdvanceToTheOwner() {
            TicketDetailDtos.Detail d = service.detail(admin(), codeOf(ticketId), null);

            assertThat(d.availableActions()).containsExactlyInAnyOrder("handoff", "rework");
        }
    }

    // ── the scope guard ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the scope guard runs before anything is read")
    class Scope {

        /**
         * The failure this guards against is not a leaked ticket row — it is a
         * leaked <em>history</em>. Every query after the guard reads by ticket
         * id and would happily return another project's journal.
         */
        @Test
        @DisplayName("an out-of-scope ticket is not found, not forbidden")
        void outOfScopeIsNotFound() {
            long theirs = insertTicket(otherProjectId, (short) 1);

            assertThatThrownBy(() -> service.detail(caller("PM", List.of(projectId)), codeOf(theirs), null))
                    .as("A-035: indistinguishable from a ticket that never existed")
                    .isInstanceOf(TicketNotFoundException.class);
        }

        @Test
        @DisplayName("a ticket that never existed fails identically")
        void missingTicketFailsTheSameWay() {
            assertThatThrownBy(() -> service.detail(admin(), "ZZ-26-99999", null))
                    .isInstanceOf(TicketNotFoundException.class);
        }

        /**
         * C-043 · in scope but not the owner. Support holds {@code
         * ticket.view_all} — {@code project_id IN (their projects)}, per
         * {@code ScopeResolver} — so this caller sees the ticket at all, unlike
         * a Developer who would be scoped out before the golden rule is ever
         * asked. What stops them advancing it is the golden rule specifically,
         * not the row scope, and this is the one caller shape that isolates
         * that difference against a real {@code Ticket} entity.
         */
        @Test
        @DisplayName("in scope but not the owner sees the ticket with no advance actions")
        void inScopeButNotOwnerGetsNoAdvanceActions() {
            Authentication otherSupport = new UsernamePasswordAuthenticationToken(
                    new DevPrincipal(userId + 1, "other.support", "Other Support",
                            "SUPPORT", List.of(projectId), List.of()),
                    null, List.of());

            TicketDetailDtos.Detail d = service.detail(otherSupport, codeOf(ticketId), null);

            assertThat(d.ticket().id()).isEqualTo(ticketId);
            assertThat(d.availableActions()).isEmpty();
        }
    }

    // ── the cycle selector ───────────────────────────────────────────────────

    @Nested
    @DisplayName("?cycle= selects the journals and nothing else")
    class CycleSelection {

        @Test
        @DisplayName("defaults to the ticket's current cycle")
        void defaultsToCurrentCycle() {
            TicketDetailDtos.Detail d = service.detail(admin(), codeOf(ticketId), null);

            assertThat(d.history()).allSatisfy(h ->
                    assertThat(h.cycleNo()).as("current cycle is 2").isEqualTo((short) 2));
            assertThat(d.effortLogs()).allSatisfy(e ->
                    assertThat(e.cycleNo()).isEqualTo((short) 2));
        }

        @Test
        @DisplayName("an earlier cycle returns that cycle's journals")
        void earlierCycleIsReadable() {
            TicketDetailDtos.Detail d = service.detail(admin(), codeOf(ticketId), 1);

            assertThat(d.history()).isNotEmpty().allSatisfy(h ->
                    assertThat(h.cycleNo()).isEqualTo((short) 1));
            assertThat(d.effortLogs()).isNotEmpty().allSatisfy(e ->
                    assertThat(e.cycleNo()).isEqualTo((short) 1));
        }

        /**
         * Deliberate asymmetry, and the reason it is asserted: cycle_no is
         * nullable on comments and attachments, so filtering them would make a
         * file uploaded outside any cycle invisible on every cycle.
         */
        @Test
        @DisplayName("cycles list and comments are not filtered by the selected cycle")
        void ticketScopedSectionsAreNotFiltered() {
            TicketDetailDtos.Detail current = service.detail(admin(), codeOf(ticketId), null);
            TicketDetailDtos.Detail earlier = service.detail(admin(), codeOf(ticketId), 1);

            assertThat(earlier.cycles()).as("the selector needs every cycle to offer")
                    .hasSameSizeAs(current.cycles());
            assertThat(earlier.comments()).hasSameSizeAs(current.comments());
        }
    }
}
