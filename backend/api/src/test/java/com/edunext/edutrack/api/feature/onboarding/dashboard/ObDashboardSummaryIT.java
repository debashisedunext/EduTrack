package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardCard;
import com.edunext.edutrack.api.security.CallerIdentity;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-121 · the OB-02 board against real MySQL.
 *
 * <p>What is worth a container is the SQL: whether the product rows add up the
 * way the cards claim, whether {@code MAX(computed_at)} reports the right
 * staleness, whether a day with no row for a product is told apart from a day
 * of zeroes, and — the case this whole class exists for — <b>what happens to a
 * client who bought two products</b>. The scope rules and the delta arithmetic
 * are cheaper in {@link ObDashboardScopeTest} and {@link ObDashboardServiceTest},
 * which need no container.
 *
 * <p>Fixtures use product codes no seed migration will claim, for the reason
 * {@code AuthLoginIT} records.
 */
@SpringBootTest
@Testcontainers
class ObDashboardSummaryIT {

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

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    @Autowired
    ObDashboardService dashboard;

    @Autowired
    ObDashboardSummaryRepository summaries;

    @Autowired
    JdbcTemplate jdbc;

    private long erp;
    private long biometric;
    private long owner;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ob_scope_dashboard_summary");
        jdbc.update("DELETE FROM ob_dashboard_summary");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_OBDASH_%'");

        erp = insertProduct("IT_OBDASH_ERP", "ERP");
        biometric = insertProduct("IT_OBDASH_BIO", "Biometric");

        // ob_scope_dashboard_summary.scope_user_id carries a foreign key to
        // users, so the narrowed cases need a real row to point at rather than
        // the invented id the mocked unit tests can get away with.
        jdbc.update("INSERT IGNORE INTO roles (code, name, is_system) "
                + "VALUES ('IT_OBDASH_ROLE', 'OB Dashboard Fixture', 0)");
        jdbc.update("""
                INSERT IGNORE INTO users (emp_code, username, email, password_hash, full_name,
                                          role_id, timezone, is_active, must_change_password)
                VALUES ('OBDASH01', 'its.obdash.owner', 'its.obdash.owner@edunext.test',
                        'not-a-real-hash', 'Dashboard Fixture Owner',
                        (SELECT id FROM roles WHERE code = 'IT_OBDASH_ROLE'), 'Asia/Kolkata', 1, 0)
                """);
        owner = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'its.obdash.owner'", Long.class);
    }

    // ── the arithmetic that is exact ────────────────────────────────────────

    /**
     * Journeys and steps belong to exactly one product, so the product rows
     * partition and summing them is the real number. This is the half of the
     * table that behaves.
     */
    @Test
    @DisplayName("journey- and step-counted cards add up exactly across products")
    void theProductRowsPartition() {
        // ERP: 2 locked + 1 held + 5 running = 8 open. Biometric: 0 + 0 + 3 = 3.
        insertRow(WEDNESDAY, erp, row -> {
            row.put("journeys_locked", 2);
            row.put("journeys_held", 1);
            row.put("journeys_open_running", 5);
            row.put("steps_due_today", 4);
            row.put("steps_due_this_week", 9);
            row.put("rag_amber", 3);
            row.put("rag_red", 1);
        });
        insertRow(WEDNESDAY, biometric, row -> {
            row.put("journeys_open_running", 3);
            row.put("steps_due_today", 1);
            row.put("steps_due_this_week", 2);
            row.put("rag_amber", 1);
            row.put("rag_red", 0);
        });

        var cards = dashboard.summary(manager(), null).summary().cards();

        assertThat(count(cards, ObDashboardCardKey.ONGOING_PROJECTS)).isEqualTo(11);
        assertThat(count(cards, ObDashboardCardKey.TODAYS_DELIVERY)).isEqualTo(5);
        assertThat(count(cards, ObDashboardCardKey.THIS_WEEKS_DEADLINES)).isEqualTo(11);
        // amber + red across both products. Locked is deliberately not here.
        assertThat(count(cards, ObDashboardCardKey.AT_RISK)).isEqualTo(5);
        assertThat(cards).filteredOn(one -> !one.key().isClientCounted())
                .noneMatch(ObDashboardCard::countIsUpperBound);
    }

    /**
     * A journey whose gate has not cleared has no colour at all (A-108), so it
     * must not reach the At Risk card. Folding it in would put a whole fresh
     * intake on the one card that is supposed to mean "somebody must act".
     */
    @Test
    @DisplayName("a locked journey is ongoing and is not at risk")
    void lockedIsNotAColour() {
        insertRow(WEDNESDAY, erp, row -> row.put("journeys_locked", 6));

        var cards = dashboard.summary(manager(), null).summary().cards();

        assertThat(count(cards, ObDashboardCardKey.ONGOING_PROJECTS)).isEqualTo(6);
        assertThat(count(cards, ObDashboardCardKey.AT_RISK)).isZero();
    }

    @Test
    @DisplayName("a completed journey has left the board")
    void completedIsNotOngoing() {
        insertRow(WEDNESDAY, erp, row -> {
            row.put("journeys_open_running", 2);
            row.put("journeys_completed", 40);
        });

        assertThat(count(dashboard.summary(manager(), null).summary().cards(),
                ObDashboardCardKey.ONGOING_PROJECTS)).isEqualTo(2);
    }

    // ── the arithmetic that is not, and says so ─────────────────────────────

    /**
     * 🔴 The defect {@code countIsUpperBound} exists for, proved against the
     * storage rather than argued in a comment.
     *
     * <p>One client bought ERP and Biometric and is late on both. B-120 writes
     * {@code clients_overdue = 1} on each product row, which is right for each
     * product — and the all-products sum says 2 for one client.
     */
    @Test
    @DisplayName("a client who bought two products is counted twice, and the card admits it")
    void theAllProductsBoardOverstatesClientCountedCards() {
        insertRow(WEDNESDAY, erp, row -> {
            row.put("clients_overdue", 1);
            row.put("clients_live", 1);
            row.put("clients_escalated", 1);
        });
        insertRow(WEDNESDAY, biometric, row -> {
            row.put("clients_overdue", 1);
            row.put("clients_live", 1);
            row.put("clients_escalated", 1);
        });

        var cards = dashboard.summary(manager(), null).summary().cards();

        // The honest upper bound: one client, reported as at most two.
        assertThat(count(cards, ObDashboardCardKey.OVERDUE_CLIENTS)).isEqualTo(2);
        assertThat(count(cards, ObDashboardCardKey.LIVE)).isEqualTo(2);
        assertThat(count(cards, ObDashboardCardKey.CLIENT_ESCALATIONS)).isEqualTo(2);
        assertThat(cards).filteredOn(ObDashboardCard::countIsUpperBound)
                .extracting(one -> one.key().wireName())
                .containsExactlyInAnyOrder("overdue-clients", "live", "client-escalations");
    }

    /**
     * The same fixture with a product selected. One row is read, nothing is
     * summed, and the figure is the real one — which is why the picker is worth
     * building and why the flag is false here.
     */
    @Test
    @DisplayName("selecting a product reads one row, so the client counts are exact")
    void oneProductIsExact() {
        insertRow(WEDNESDAY, erp, row -> row.put("clients_live", 1));
        insertRow(WEDNESDAY, biometric, row -> row.put("clients_live", 1));

        var cards = dashboard.summary(manager(), erp).summary().cards();

        assertThat(count(cards, ObDashboardCardKey.LIVE)).isEqualTo(1);
        assertThat(cards).noneMatch(ObDashboardCard::countIsUpperBound);
    }

    // ── staleness and the delta ─────────────────────────────────────────────

    /**
     * B-120 writes stock and flow in separate statements inside one
     * transaction, so a day's rows can carry different {@code computed_at}
     * values. The board reports the newest — a dashboard that understates its
     * own staleness is the one that invites a bug report about the difference
     * between it and a list.
     */
    @Test
    @DisplayName("computedAt is the newest refresh folded into the board, not the oldest")
    void stalenessIsTheNewestRow() {
        insertRow(WEDNESDAY, erp, row -> row.put("computed_at", "2026-09-02 06:00:00.000000"));
        insertRow(WEDNESDAY, biometric, row -> row.put("computed_at", "2026-09-02 06:05:00.000000"));

        assertThat(dashboard.summary(manager(), null).summary().computedAt())
                .isEqualTo("2026-09-02T06:05:00Z");
    }

    @Test
    @DisplayName("the delta compares the two most recent stored days, gap or no gap")
    void theDeltaSpansAMissingDay() {
        // Tuesday is missing — the worker was down. Wednesday compares against
        // Monday rather than reporting no history at all.
        insertRow(MONDAY, erp, row -> row.put("journeys_open_running", 7));
        insertRow(WEDNESDAY, erp, row -> row.put("journeys_open_running", 10));

        var cards = dashboard.summary(manager(), null).summary().cards();

        assertThat(cards).filteredOn(one -> one.key() == ObDashboardCardKey.ONGOING_PROJECTS)
                .singleElement()
                .extracting(ObDashboardCard::deltaFromYesterday)
                .isEqualTo(3L);
    }

    /**
     * A product whose first row landed today has one day of history even where
     * the table has two, so its delta is null rather than a comparison against
     * a day it was not in.
     */
    @Test
    @DisplayName("the delta is filtered by product, so a new product has no history to compare")
    void theDeltaIsScopedToTheProduct() {
        insertRow(MONDAY, erp, row -> row.put("journeys_open_running", 7));
        insertRow(WEDNESDAY, erp, row -> row.put("journeys_open_running", 10));
        insertRow(WEDNESDAY, biometric, row -> row.put("journeys_open_running", 2));

        var cards = dashboard.summary(manager(), biometric).summary().cards();

        assertThat(count(cards, ObDashboardCardKey.ONGOING_PROJECTS)).isEqualTo(2);
        assertThat(cards).allSatisfy(one ->
                assertThat(one.deltaFromYesterday()).isNull());
    }

    // ── silence against zero ────────────────────────────────────────────────

    /**
     * A bare aggregate over no rows still returns one row with every column
     * NULL. That is "no data for this product", not a zeroed board — a zero is
     * a claim where an absent row is silence, which is A-108's own reason for
     * clamping the refresh window rather than back-filling.
     */
    @Test
    @DisplayName("a product with no row is silence, not a board of zeroes")
    void anAbsentRowIsNotAZero() {
        insertRow(WEDNESDAY, erp, row -> row.put("journeys_open_running", 5));

        assertThat(summaries.rollup(WEDNESDAY, biometric)).isEmpty();
    }

    @Test
    @DisplayName("before B-120's first pass the board says so and carries no validator")
    void anEmptyTableReportsNeverComputed() {
        var rendered = dashboard.summary(manager(), null);

        assertThat(rendered.summary().computedAt()).isNull();
        assertThat(rendered.etag()).isNull();
        assertThat(rendered.summary().cards())
                .hasSize(ObDashboardCardKey.values().length)
                .allSatisfy(one ->
                        assertThat(one.unavailableReason()).contains("No summary has been computed"));
    }

    // ── the narrowed scopes, from their own table ───────────────────────────

    /**
     * <b>The assertion this whole class exists to make now.</b> Both tables
     * hold figures for the same day and the same product, and they disagree on
     * purpose: 40 org-wide against 9 for this caller. A Step Owner must read 9.
     *
     * <p>The failure it guards is a narrowed caller being handed the org-wide
     * board, which would look entirely correct on screen and would disclose the
     * size of the book to somebody scoped out of most of it. Worth a container
     * rather than a mock precisely because the mocked version cannot tell "read
     * the right table" from "read the right rows".
     */
    @Test
    @DisplayName("a Step Owner reads their own figures, never the org-wide ones")
    void aNarrowedRoleReadsTheScopedTable() {
        insertRow(WEDNESDAY, erp, row -> row.put("journeys_open_running", 40));
        insertScopedRow(WEDNESDAY, owner, erp, row -> {
            row.put("journeys_open_running", 9);
            row.put("rag_amber", 2);
            row.put("rag_red", 1);
        });

        var summary = dashboard.summary(stepOwner(), null).summary();

        assertThat(count(summary.cards(), ObDashboardCardKey.ONGOING_PROJECTS)).isEqualTo(9);
        assertThat(count(summary.cards(), ObDashboardCardKey.AT_RISK)).isEqualTo(3);
        assertThat(summary.cards()).allSatisfy(one ->
                assertThat(one.unavailableReason()).isNull());
        assertThat(summary.appliedScope()).isEqualTo("journeys containing your services");
    }

    /**
     * One Step Owner's row is not another's. The table is keyed by caller, so
     * this is the one way the feature can leak — and an id passed wrongly would
     * still return a plausible board.
     */
    @Test
    @DisplayName("one narrowed caller cannot read another's row")
    void scopedRowsAreKeyedByCaller() {
        insertScopedRow(WEDNESDAY, owner, erp, row -> row.put("journeys_open_running", 9));

        var summary = dashboard.summary(caller("OB_STEP_OWNER", owner + 1_000), null).summary();

        assertThat(summary.cards()).allSatisfy(one -> {
            assertThat(one.count()).isZero();
            assertThat(one.unavailableReason()).contains("Nothing is in your scope yet");
        });
    }

    /**
     * The refresh writes no row for a caller with nothing visible, so that
     * "nothing is yours yet" stays separable from "the job has never run" — and
     * neither is a board of zeroes, which would claim nothing of theirs is
     * overdue. Asserted with the org-wide board deliberately full, so a
     * regression that fell back to it would fail here rather than look right.
     */
    @Test
    @DisplayName("a narrowed caller with no row is told so, not shown zeroes or everyone else's")
    void aNarrowedCallerWithNothingInScopeIsToldSo() {
        insertRow(WEDNESDAY, erp, row -> row.put("journeys_open_running", 40));
        insertScopedRow(WEDNESDAY, owner, erp, row -> row.put("journeys_open_running", 9));

        var rendered = dashboard.summary(caller("OB_STEP_OWNER", owner + 1_000), null);

        assertThat(rendered.summary().computedAt()).isNull();
        assertThat(rendered.summary().cards()).allSatisfy(one -> {
            assertThat(one.count()).isZero();
            assertThat(one.unavailableReason()).contains("Nothing is in your scope yet");
            assertThat(one.unavailableReason()).doesNotContain("No summary has been computed");
        });
    }

    /**
     * Both tables carry the same three client-counted columns as
     * {@code COUNT(DISTINCT client)} within a product, so summing the product
     * rows over-counts a multi-product client on either. One rule, so
     * {@code countIsUpperBound} keeps one meaning on the wire.
     */
    @Test
    @DisplayName("the client-counted cards are upper bounds on the scoped board too")
    void theScopedBoardOverstatesTheSameThreeCards() {
        insertScopedRow(WEDNESDAY, owner, erp, row -> row.put("clients_overdue", 1));
        insertScopedRow(WEDNESDAY, owner, biometric, row -> row.put("clients_overdue", 1));

        var cards = dashboard.summary(stepOwner(), null).summary().cards();

        assertThat(cards).filteredOn(one -> one.key().isClientCounted())
                .allMatch(ObDashboardCard::countIsUpperBound);
        assertThat(cards).filteredOn(one -> !one.key().isClientCounted())
                .noneMatch(ObDashboardCard::countIsUpperBound);
    }

    // ── every card the enum declares is projected by the SQL ────────────────

    /**
     * The map and the enum are matched by key, not by position, so a card added
     * to one and not the other would otherwise read zero for ever. This is the
     * assertion that turns that into a failure.
     */
    @Test
    void every_card_has_an_expression_behind_it() {
        assertThat(ObDashboardSummaryRepository.expressions())
                .containsOnlyKeys(ObDashboardCardKey.values());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name) VALUES (?, ?)", code, name);
        return jdbc.queryForObject("SELECT id FROM ob_products WHERE code = ?", Long.class, code);
    }

    /**
     * One summary row. Every column defaults to 0 and {@code computed_at} to a
     * fixed instant, so a case names only the figures it is about — which is
     * what lets the assertions above be read against the fixture rather than
     * against twenty zeroes.
     */
    private void insertRow(LocalDate day, long productId, java.util.function.Consumer<Map<String, Object>> overrides) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("computed_at", "2026-09-02 06:00:00.000000");
        overrides.accept(row);

        String columns = String.join(", ", row.keySet());
        String placeholders = String.join(", ", java.util.Collections.nCopies(row.size(), "?"));
        jdbc.update("INSERT INTO ob_dashboard_summary (stat_date, product_id, " + columns + ") "
                        + "VALUES (?, ?, " + placeholders + ")",
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(day, productId),
                                row.values().stream())
                        .toArray());
    }

    /** {@link #insertRow}, one key column wider. Same defaults, same reason. */
    private void insertScopedRow(LocalDate day, long scopeUserId, long productId,
                                 java.util.function.Consumer<Map<String, Object>> overrides) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("computed_at", "2026-09-02 06:00:00.000000");
        overrides.accept(row);

        String columns = String.join(", ", row.keySet());
        String placeholders = String.join(", ", java.util.Collections.nCopies(row.size(), "?"));
        jdbc.update("INSERT INTO ob_scope_dashboard_summary "
                        + "(stat_date, scope_user_id, product_id, " + columns + ") "
                        + "VALUES (?, ?, ?, " + placeholders + ")",
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(day, scopeUserId, productId),
                                row.values().stream())
                        .toArray());
    }

    private static long count(List<ObDashboardCard> cards, ObDashboardCardKey key) {
        return cards.stream().filter(one -> one.key() == key).findFirst().orElseThrow().count();
    }

    private static CallerIdentity manager() {
        return caller("OB_MANAGER");
    }

    private CallerIdentity stepOwner() {
        return caller("OB_STEP_OWNER", owner);
    }

    private static CallerIdentity caller(String moduleRole) {
        return caller(moduleRole, 42);
    }

    private static CallerIdentity caller(String moduleRole, long userId) {
        return new CallerIdentity(
                userId, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }
}
