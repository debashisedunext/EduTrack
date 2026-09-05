package com.edunext.edutrack.api.feature.onboarding.notifications;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-112 · OB-13 against real MySQL.
 *
 * <p>What is worth a container here is scoping, counting and the two CHECKs.
 * The tab mapping and the paging arithmetic are cheaper in
 * {@link ObNotificationTabTest} and {@link ObNotificationServiceTest}, which
 * need none.
 *
 * <p>Fixtures use usernames no seed migration will claim, for the reason
 * {@code AuthLoginIT} records.
 */
@SpringBootTest
@Testcontainers
class ObNotificationCentreIT {

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
    ObNotificationService notifications;

    @Autowired
    JdbcTemplate jdbc;

    private long ravi;
    private long meera;

    @BeforeEach
    void seed() {
        // Children first: an entry references the queue row it was delivered
        // from, and both reference the user.
        jdbc.update("DELETE FROM ob_notifications");
        jdbc.update("DELETE FROM ob_notification_outbox");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obnotif_%'");

        ravi = insertUser("it_obnotif_ravi");
        meera = insertUser("it_obnotif_meera");
    }

    // ── scoping ─────────────────────────────────────────────────────────────

    /**
     * The scoping is these five statements and nothing else — there is no
     * {@code ScopeResolver} standing behind them, because an entry is addressed
     * to exactly one person. So it is worth proving rather than assuming.
     */
    @Test
    @DisplayName("an entry is addressed to one person and is invisible to everyone else")
    void theBellIsScopedToItsOwner() {
        raise(ravi, "STEP_ASSIGNED", "ASSIGNMENT", "Ravi's step");
        raise(meera, "STEP_ASSIGNED", "ASSIGNMENT", "Meera's step");

        assertThat(titles(list(ravi, ObNotificationTab.ALL))).containsExactly("Ravi's step");
        assertThat(titles(list(meera, ObNotificationTab.ALL))).containsExactly("Meera's step");
    }

    @Test
    void somebody_elses_entry_cannot_be_marked_read() {
        raise(meera, "STEP_ASSIGNED", "ASSIGNMENT", "Meera's step");
        long id = latestId();

        // 404, not 403 — a 403 would confirm the id exists and belongs to
        // somebody else, which is exactly what CONVENTIONS.md §7 forbids.
        assertThat(notifications.markRead(id, ravi))
                .isEqualTo(ObNotificationService.ReadOutcome.NOT_FOUND);
        assertThat(isRead(id)).isFalse();
    }

    // ── tabs and the badge ──────────────────────────────────────────────────

    @Test
    void a_tab_shows_its_own_category_and_all_shows_everything() {
        raise(ravi, "STEP_ASSIGNED", "ASSIGNMENT", "assigned");
        raise(ravi, "TAT_BREACHED", "ESCALATION", "breached");
        raise(ravi, "TAT_REMINDER", "REMINDER", "due soon");
        raise(ravi, "GO_LIVE", "UPDATE", "live");

        assertThat(titles(list(ravi, ObNotificationTab.ASSIGNMENTS))).containsExactly("assigned");
        assertThat(titles(list(ravi, ObNotificationTab.ESCALATIONS))).containsExactly("breached");
        assertThat(titles(list(ravi, ObNotificationTab.REMINDERS))).containsExactly("due soon");
        assertThat(titles(list(ravi, ObNotificationTab.ALL)))
                .containsExactly("live", "due soon", "breached", "assigned");
    }

    /**
     * {@link com.edunext.edutrack.domain.onboarding.outbox.ObCategory#UPDATE}
     * has no tab, and this is what "appears under All, which is what All is for"
     * means in practice.
     */
    @Test
    void an_update_appears_under_all_and_under_no_tab() {
        raise(ravi, "GO_LIVE", "UPDATE", "live");

        assertThat(titles(list(ravi, ObNotificationTab.ALL))).containsExactly("live");
        assertThat(list(ravi, ObNotificationTab.ASSIGNMENTS).data()).isEmpty();
        assertThat(list(ravi, ObNotificationTab.ESCALATIONS).data()).isEmpty();
        assertThat(list(ravi, ObNotificationTab.REMINDERS).data()).isEmpty();
    }

    @Test
    @DisplayName("the badge counts every unread, not the unread in the open tab")
    void theBadgeIsTheTotal() {
        raise(ravi, "STEP_ASSIGNED", "ASSIGNMENT", "assigned");
        raise(ravi, "TAT_BREACHED", "ESCALATION", "breached");
        raise(ravi, "GO_LIVE", "UPDATE", "live");

        // One row in the tab, three unread on the badge — and it must read the
        // same on every tab, because it is read on every page load.
        var escalations = list(ravi, ObNotificationTab.ESCALATIONS);
        assertThat(escalations.data()).hasSize(1);
        assertThat(escalations.meta().unreadCount()).isEqualTo(3);
        assertThat(list(ravi, ObNotificationTab.ALL).meta().unreadCount()).isEqualTo(3);
    }

    /**
     * Losing the tab is better than losing the notification. A category stamped
     * by a newer deploy must not make an entry disappear from every tab at once,
     * which is what an All that filtered on "every category we know" would do.
     */
    @Test
    void a_category_this_build_has_never_seen_still_shows_under_all() {
        // Straight past the CHECK is impossible, so this uses a value the CHECK
        // admits and the API's tab enum does not have to.
        raise(ravi, "SOMETHING_NEWER", "UPDATE", "from a newer deploy");

        assertThat(titles(list(ravi, ObNotificationTab.ALL))).containsExactly("from a newer deploy");
    }

    // ── read state ──────────────────────────────────────────────────────────

    @Test
    void re_reading_does_not_restamp_read_at() {
        raise(ravi, "STEP_ASSIGNED", "ASSIGNMENT", "assigned");
        long id = latestId();

        assertThat(notifications.markRead(id, ravi))
                .isEqualTo(ObNotificationService.ReadOutcome.MARKED);
        String first = readAt(id);

        // "When did you see this" must not become "when did you last open the
        // list" — `is_read = 0` is in the UPDATE's WHERE for exactly this.
        assertThat(notifications.markRead(id, ravi))
                .isEqualTo(ObNotificationService.ReadOutcome.ALREADY_READ);
        assertThat(readAt(id)).isEqualTo(first);
    }

    @Test
    @DisplayName("mark-all-read is not scoped to the tab that happens to be open")
    void markAllReadIgnoresTabs() {
        raise(ravi, "STEP_ASSIGNED", "ASSIGNMENT", "assigned");
        raise(ravi, "TAT_BREACHED", "ESCALATION", "breached");
        raise(meera, "GO_LIVE", "UPDATE", "somebody else's");

        assertThat(notifications.markAllRead(ravi)).isEqualTo(2);

        assertThat(list(ravi, ObNotificationTab.ALL).meta().unreadCount()).isZero();
        // And it stopped at the boundary.
        assertThat(list(meera, ObNotificationTab.ALL).meta().unreadCount()).isEqualTo(1);
    }

    @Test
    void unread_only_filters_within_the_tab() {
        raise(ravi, "TAT_BREACHED", "ESCALATION", "old breach");
        long read = latestId();
        raise(ravi, "TAT_BREACHED", "ESCALATION", "new breach");
        notifications.markRead(read, ravi);

        assertThat(titles(notifications.list(ravi, ObNotificationTab.ESCALATIONS, true, null, 25)))
                .containsExactly("new breach");
    }

    // ── paging ──────────────────────────────────────────────────────────────

    @Test
    void paging_walks_backwards_without_repeating_or_skipping() {
        for (int i = 1; i <= 5; i++) {
            raise(ravi, "GO_LIVE", "UPDATE", "n" + i);
        }

        var first = notifications.list(ravi, ObNotificationTab.ALL, false, null, 2);
        assertThat(titles(first)).containsExactly("n5", "n4");
        assertThat(first.meta().page().hasMore()).isTrue();

        var second = notifications.list(
                ravi, ObNotificationTab.ALL, false, Long.valueOf(first.meta().page().nextCursor()), 2);
        assertThat(titles(second)).containsExactly("n3", "n2");

        var third = notifications.list(
                ravi, ObNotificationTab.ALL, false, Long.valueOf(second.meta().page().nextCursor()), 2);
        assertThat(titles(third)).containsExactly("n1");
        assertThat(third.meta().page().hasMore()).isFalse();
        assertThat(third.meta().page().nextCursor())
                .as("a cursor on the last page invites a request that returns nothing")
                .isNull();
    }

    // ── the schema's own guarantees ─────────────────────────────────────────

    /**
     * The idempotency the IN_APP adapter relies on. A lapsed lease means B-110's
     * dispatcher re-delivers, and without this key that is a duplicate entry the
     * reader cannot tell from two real events.
     */
    @Test
    void one_queue_row_can_only_produce_one_entry() {
        long outboxId = enqueueInApp(ravi);
        writeEntryFor(outboxId, "first");

        assertThatThrownBy(() -> writeEntryFor(outboxId, "again after a lapsed lease"))
                .hasMessageContaining("uq_ob_notifications_outbox");

        assertThat(titles(list(ravi, ObNotificationTab.ALL))).containsExactly("first");
    }

    /**
     * The other half of the same key. B-114's digest writes entries with no
     * queue row behind them, and many of them — MySQL does not compare NULLs, so
     * the exactly-once rule costs those nothing.
     */
    @Test
    void entries_with_no_queue_row_behind_them_do_not_collide() {
        raise(ravi, "GO_LIVE", "UPDATE", "first");
        raise(ravi, "GO_LIVE", "UPDATE", "second");

        assertThat(list(ravi, ObNotificationTab.ALL).data()).hasSize(2);
    }

    /**
     * A read row with no timestamp answers "when did you see this" with silence,
     * which is the one question the pair exists to answer.
     */
    @Test
    void the_database_refuses_a_read_row_with_no_timestamp() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_notifications
                       (recipient_user_id, event_key, category, title, is_read, read_at)
                VALUES (?, 'GO_LIVE', 'UPDATE', 'inconsistent', 1, NULL)
                """, ravi))
                .hasMessageContaining("ck_ob_notifications_read");
    }

    @Test
    void the_database_refuses_a_category_outside_the_vocabulary() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_notifications
                       (recipient_user_id, event_key, category, title)
                VALUES (?, 'GO_LIVE', 'MENTION', 'from the other centre')
                """, ravi))
                .hasMessageContaining("ck_ob_notifications_category");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ObNotificationDtos.ObNotificationListResponse list(long userId, ObNotificationTab tab) {
        return notifications.list(userId, tab, false, null, 25);
    }

    private static List<String> titles(ObNotificationDtos.ObNotificationListResponse page) {
        return page.data().stream().map(ObNotificationDtos.ObNotification::title).toList();
    }

    /**
     * Inserted directly rather than through the worker's writer: that class is
     * in {@code worker} and this module does not depend on it, and half of what
     * is asserted here is about rows an enqueuer could not produce anyway.
     */
    private void raise(long userId, String eventKey, String category, String title) {
        jdbc.update("""
                INSERT INTO ob_notifications
                       (recipient_user_id, event_key, category, title, body, link_url)
                VALUES (?, ?, ?, ?, 'body', '/onboarding/clients/1')
                """, userId, eventKey, category, title);
    }

    /** One IN_APP queue row, so the exactly-once key has something to point at. */
    private long enqueueInApp(long userId) {
        jdbc.update("""
                INSERT INTO ob_notification_outbox
                       (event_key, channel, recipient_type, recipient_user_id, dedupe_key)
                VALUES ('GO_LIVE', 'IN_APP', 'STAFF', ?, ?)
                """, userId, "GO_LIVE:IN_APP:user:" + userId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private void writeEntryFor(long outboxId, String title) {
        jdbc.update("""
                INSERT INTO ob_notifications
                       (recipient_user_id, event_key, category, title, outbox_id)
                VALUES (?, 'GO_LIVE', 'UPDATE', ?, ?)
                """, ravi, title, outboxId);
    }

    private long latestId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM ob_notifications", Long.class);
        return id == null ? 0L : id;
    }

    private boolean isRead(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_read FROM ob_notifications WHERE id = ?", Boolean.class, id));
    }

    private String readAt(long id) {
        return jdbc.queryForObject(
                "SELECT read_at FROM ob_notifications WHERE id = ?", String.class, id);
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
