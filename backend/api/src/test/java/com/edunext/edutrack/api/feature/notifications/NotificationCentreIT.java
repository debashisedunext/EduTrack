package com.edunext.edutrack.api.feature.notifications;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-041 · the notification centre against real MySQL.
 *
 * <p>What is worth proving here is mostly about <em>scoping and counting</em>:
 * that a notification addressed to one person is invisible to everyone else,
 * that the badge counts the same thing whichever tab is open, and that a code
 * this build has never seen still renders. The tab mapping itself is cheaper to
 * assert in {@link NotificationTabTest}, which needs no container.
 *
 * <p>Fixtures use codes no seed migration will claim, for the reason recorded in
 * {@code AuthLoginIT}.
 */
@SpringBootTest
@Testcontainers
class NotificationCentreIT {

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
    NotificationService notifications;

    @Autowired
    JdbcTemplate jdbc;

    private long ravi;
    private long meera;
    private long projectId;
    private long ticketId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_notif_%'");
        jdbc.update("DELETE FROM projects WHERE project_code = 'ITN'");

        projectId = insertProject();
        ravi = insertUser("it_notif_ravi");
        meera = insertUser("it_notif_meera");
        ticketId = insertTicket("ITN-26-00001");
    }

    // ------------------------------------------------------------- scoping

    @Test
    @DisplayName("a notification is addressed to one person and invisible to everyone else")
    void theBellIsScopedToItsOwner() {
        raise(ravi, "MENTIONED", "Ravi was mentioned");
        raise(meera, "MENTIONED", "Meera was mentioned");

        assertThat(list(ravi, NotificationTab.ALL).data())
                .singleElement()
                .extracting(NotificationDtos.Notification::title)
                .isEqualTo("Ravi was mentioned");
    }

    @Test
    @DisplayName("marking somebody else's notification read does nothing and says so")
    void youCannotMarkAnotherUsersNotification() {
        raise(meera, "MENTIONED", "not yours");
        long id = latestId();

        assertThat(notifications.markRead(id, ravi))
                .isEqualTo(NotificationService.ReadOutcome.NOT_FOUND);
        assertThat(isRead(id)).isFalse();
    }

    // ---------------------------------------------------------------- tabs

    @Test
    void aTabShowsOnlyItsOwnCategory() {
        raise(ravi, "MENTIONED", "mention");
        raise(ravi, "SLA_BREACHED", "breach");
        raise(ravi, "TICKET_ASSIGNED", "assignment");

        assertThat(titles(list(ravi, NotificationTab.MENTIONS))).containsExactly("mention");
        assertThat(titles(list(ravi, NotificationTab.ESCALATIONS))).containsExactly("breach");
        assertThat(titles(list(ravi, NotificationTab.ASSIGNMENTS))).containsExactly("assignment");
        assertThat(titles(list(ravi, NotificationTab.ALL))).hasSize(3);
    }

    @Test
    @DisplayName("an event with no tab still appears under All")
    void uncategorisedEventsAreNotLost() {
        raise(ravi, "MAIL_DELIVERY_FAILED", "a mail bounced");

        // COMMENT_ADDED, the daily digest and this are worth a bell entry and
        // not worth a tab. All is where they live.
        assertThat(titles(list(ravi, NotificationTab.ALL))).containsExactly("a mail bounced");
        assertThat(list(ravi, NotificationTab.MENTIONS).data()).isEmpty();
    }

    @Test
    @DisplayName("a code this build has never heard of still renders")
    void unknownEventCodesStillList() {
        // Written by a newer deploy, or by a code since retired. Losing the
        // notification would be far worse than losing its tab.
        raise(ravi, "SOMETHING_FROM_THE_FUTURE", "still readable");

        assertThat(list(ravi, NotificationTab.ALL).data())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.title()).isEqualTo("still readable");
                    assertThat(row.eventKey()).isEqualTo("SOMETHING_FROM_THE_FUTURE");
                });
    }

    // -------------------------------------------------------------- badges

    @Test
    @DisplayName("the badge counts every unread, not the unread in the open tab")
    void unreadCountIsGlobalRatherThanTabScoped() {
        raise(ravi, "MENTIONED", "mention");
        raise(ravi, "SLA_BREACHED", "breach");

        // A badge that changed as you clicked between tabs would be reporting
        // something nobody asked about.
        assertThat(list(ravi, NotificationTab.MENTIONS).meta().unreadCount()).isEqualTo(2);
        assertThat(list(ravi, NotificationTab.ESCALATIONS).meta().unreadCount()).isEqualTo(2);
        assertThat(list(ravi, NotificationTab.ALL).meta().unreadCount()).isEqualTo(2);
    }

    @Test
    void unreadOnlyHidesWhatHasBeenRead() {
        raise(ravi, "MENTIONED", "old");
        long first = latestId();
        raise(ravi, "MENTIONED", "new");
        notifications.markRead(first, ravi);

        assertThat(titles(notifications.list(ravi, NotificationTab.ALL, true, null, 25)))
                .containsExactly("new");
        assertThat(notifications.list(ravi, NotificationTab.ALL, true, null, 25).meta().unreadCount())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ marking

    @Test
    void markingReadIsIdempotentAndDoesNotRestampTheTime() {
        raise(ravi, "MENTIONED", "look at this");
        long id = latestId();

        assertThat(notifications.markRead(id, ravi)).isEqualTo(NotificationService.ReadOutcome.MARKED);
        String first = readAt(id);

        assertThat(notifications.markRead(id, ravi))
                .isEqualTo(NotificationService.ReadOutcome.ALREADY_READ);
        assertThat(readAt(id))
                .as("'when did you see this' must not become 'when did you last open the list'")
                .isEqualTo(first);
    }

    @Test
    void markAllReadClearsOnlyYourOwn() {
        raise(ravi, "MENTIONED", "mine one");
        raise(ravi, "SLA_BREACHED", "mine two");
        raise(meera, "MENTIONED", "hers");

        assertThat(notifications.markAllRead(ravi)).isEqualTo(2);

        assertThat(list(ravi, NotificationTab.ALL).meta().unreadCount()).isZero();
        assertThat(list(meera, NotificationTab.ALL).meta().unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("mark-all-read is not scoped to the tab that happens to be open")
    void markAllReadIgnoresTabs() {
        raise(ravi, "MENTIONED", "mention");
        raise(ravi, "SLA_BREACHED", "breach");

        notifications.markAllRead(ravi);

        // "Mark all read" that left some unread would be a lie the badge
        // contradicts a second later.
        assertThat(list(ravi, NotificationTab.ALL).meta().unreadCount()).isZero();
    }

    // ------------------------------------------------------------- paging

    @Test
    void pagingWalksBackwardsWithoutRepeatingOrSkipping() {
        for (int i = 1; i <= 5; i++) {
            raise(ravi, "MENTIONED", "n" + i);
        }

        NotificationService.Page first = notifications.list(ravi, NotificationTab.ALL, false, null, 2);
        assertThat(titles(first)).containsExactly("n5", "n4");
        assertThat(first.meta().hasMore()).isTrue();

        NotificationService.Page second = notifications.list(
                ravi, NotificationTab.ALL, false, Long.valueOf(first.meta().nextCursor()), 2);
        assertThat(titles(second)).containsExactly("n3", "n2");

        NotificationService.Page third = notifications.list(
                ravi, NotificationTab.ALL, false, Long.valueOf(second.meta().nextCursor()), 2);
        assertThat(titles(third)).containsExactly("n1");
        assertThat(third.meta().hasMore()).isFalse();
        assertThat(third.meta().nextCursor())
                .as("a cursor on the last page invites a request that returns nothing")
                .isNull();
    }

    // -------------------------------------------------------------- shape

    @Test
    @DisplayName("the ticket surfaces as its code, not its row id")
    void ticketIsSentAsTheHumanCode() {
        raise(ravi, "MENTIONED", "on a ticket", ticketId, "/chat/threads/4471");

        assertThat(list(ravi, NotificationTab.ALL).data()).singleElement().satisfies(row -> {
            // Sending the numeric id renders a link nobody can follow.
            assertThat(row.ticketId()).isEqualTo("ITN-26-00001");
            assertThat(row.deepLink()).isEqualTo("/chat/threads/4471");
            assertThat(row.isRead()).isFalse();
        });
    }

    @Test
    void aNotificationBelongingToNoTicketHasNoTicketId() {
        raise(ravi, "DAILY_DIGEST", "your open tickets");

        assertThat(list(ravi, NotificationTab.ALL).data())
                .singleElement()
                .extracting(NotificationDtos.Notification::ticketId)
                .isNull();
    }

    // ------------------------------------------------------------- helpers

    private NotificationService.Page list(long userId, NotificationTab tab) {
        return notifications.list(userId, tab, false, null, 25);
    }

    private static java.util.List<String> titles(NotificationService.Page page) {
        return page.data().stream().map(NotificationDtos.Notification::title).toList();
    }

    private void raise(long userId, String eventCode, String title) {
        raise(userId, eventCode, title, null, null);
    }

    private void raise(long userId, String eventCode, String title, Long ticket, String link) {
        // Inserted directly rather than through NotificationWriter: half of
        // these codes have no producer yet, and one of them deliberately does
        // not exist in NotificationEvent at all.
        jdbc.update("""
                INSERT INTO notifications (user_id, ticket_id, event_code, title, body, link_url)
                VALUES (?, ?, ?, ?, 'body', ?)
                """, userId, ticket, eventCode, title, link);
    }

    private long latestId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM notifications", Long.class);
        return id == null ? 0L : id;
    }

    private boolean isRead(long id) {
        Boolean read = jdbc.queryForObject(
                "SELECT is_read FROM notifications WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(read);
    }

    private String readAt(long id) {
        return jdbc.queryForObject(
                "SELECT CAST(read_at AS CHAR) FROM notifications WHERE id = ?", String.class, id);
    }

    private long insertProject() {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITN', 'Notification fixture')");
        return lastId();
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastId();
    }

    private long insertTicket(String code) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level)
                VALUES (?, ?, 'notification fixture', 'MEDIUM', 'MEDIUM')
                """, code, projectId);
        return lastId();
    }

    private long lastId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
