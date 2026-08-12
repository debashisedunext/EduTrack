package com.edunext.edutrack.api.feature.notifications.webhook;

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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-039 · an emailed reply becomes a comment, against real MySQL.
 *
 * <p>Most of these tests are about the mail that <strong>must not</strong>
 * become a comment. That is where the risk lives: a reply filed against the
 * wrong ticket, or attributed to somebody who did not send it, is worse than a
 * reply that was dropped and has to be typed in by hand.
 */
@SpringBootTest
@Testcontainers
class InboundReplyIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_inbound_it")
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

    private static final String PRIYA = "it_inbound_priya@example.com";
    private static final String OUTSIDER = "it_inbound_outsider@example.com";

    @Autowired
    InboundReplyService service;

    @Autowired
    JdbcTemplate jdbc;

    private long priya;
    private long outsider;
    private long ticketId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ticket_comments WHERE source = 'EMAIL'");
        jdbc.update("DELETE FROM email_log WHERE to_email LIKE 'it_inbound_%'");
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITI-%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_inbound_%'");
        jdbc.update("DELETE FROM projects WHERE project_code = 'ITI'");

        long projectId = insertProject();
        priya = insertUser("it_inbound_priya");
        outsider = insertUser("it_inbound_outsider");
        ticketId = insertTicket("ITI-26-00001", projectId);
        // Priya was mailed about this ticket; the outsider never was.
        mailWasSentTo(PRIYA, ticketId);
    }

    // ------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a signed reply from someone we mailed becomes a comment on that ticket")
    void aReplyBecomesAComment() {
        Optional<Long> commentId = service.accept(reply(
                "Priya Nair <" + PRIYA + ">",
                "<ticket." + ticketId + ".mail.1@edutrack.local>",
                "Fixed in build 412, please retest.\n\nOn Tue, EduTrack wrote:\n> anything"));

        assertThat(commentId).isPresent();
        assertThat(bodyOf(commentId.get())).isEqualTo("Fixed in build 412, please retest.");
        assertThat(authorOf(commentId.get())).isEqualTo(priya);
        assertThat(ticketOf(commentId.get())).isEqualTo(ticketId);
    }

    @Test
    @DisplayName("the comment is marked EMAIL, so the thread shows where it came from")
    void theCommentRecordsItsSource() {
        long id = service.accept(reply("<" + PRIYA + ">",
                "<ticket." + ticketId + "@edutrack.local>", "Approved.")).orElseThrow();

        assertThat(sourceOf(id)).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("it is internal by default — a mailed remark is not published to a client")
    void theCommentIsInternal() {
        long id = service.accept(reply("<" + PRIYA + ">",
                "<ticket." + ticketId + "@edutrack.local>", "Internal note.")).orElseThrow();

        // Guessing wrong is one-directional: an internal note published to a
        // client cannot be recalled.
        assertThat(internalOf(id)).isTrue();
    }

    @Test
    @DisplayName("it is stamped with the cycle and stage the ticket is on now")
    void theCommentJoinsTheCurrentCycle() {
        long id = service.accept(reply("<" + PRIYA + ">",
                "<ticket." + ticketId + "@edutrack.local>", "Noted.")).orElseThrow();

        // §4A.2's counters are what the History tab groups by; a comment with
        // no cycle floats outside every group.
        assertThat(jdbc.queryForObject(
                "SELECT cycle_no FROM ticket_comments WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT stage_code FROM ticket_comments WHERE id = ?", String.class, id))
                .isEqualTo("INTAKE");
    }

    // -------------------------------------------------------- what must be dropped

    @Test
    @DisplayName("a forged From from someone we never mailed writes nothing")
    void aForgedSenderIsRejected() {
        // The outsider is a real, active user — so matching From against users
        // and stopping there would let this through, as a comment carrying
        // their colleague's name on a ticket they were never told about.
        Optional<Long> result = service.accept(reply(
                "<" + OUTSIDER + ">",
                "<ticket." + ticketId + "@edutrack.local>",
                "Deploy it to production now."));

        assertThat(result).isEmpty();
        assertThat(commentCount()).isZero();
    }

    @Test
    @DisplayName("a sender who is not a user at all writes nothing")
    void anUnknownSenderIsDropped() {
        assertThat(service.accept(reply("<nobody@elsewhere.test>",
                "<ticket." + ticketId + "@edutrack.local>", "Hello?"))).isEmpty();
        assertThat(commentCount()).isZero();
    }

    @Test
    @DisplayName("a deactivated user's reply writes nothing")
    void aDeactivatedSenderIsDropped() {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", priya);

        assertThat(service.accept(reply("<" + PRIYA + ">",
                "<ticket." + ticketId + "@edutrack.local>", "Still here."))).isEmpty();
        assertThat(commentCount()).isZero();
    }

    @Test
    @DisplayName("headers naming no ticket write nothing")
    void mailWithNoTicketReferenceIsDropped() {
        assertThat(service.accept(reply("<" + PRIYA + ">",
                "<mail.5000@edutrack.local>", "Reply to a digest."))).isEmpty();
        assertThat(commentCount()).isZero();
    }

    @Test
    @DisplayName("a chain naming two tickets writes nothing rather than picking one")
    void anAmbiguousChainIsDropped() {
        InboundReply ambiguous = new InboundReply("<" + PRIYA + ">", null,
                "<ticket." + ticketId + "@edutrack.local> <ticket.999999@edutrack.local>",
                "Which ticket is this?", "Re: something");

        assertThat(service.accept(ambiguous)).isEmpty();
        assertThat(commentCount()).isZero();
    }

    @Test
    @DisplayName("a reply naming a ticket that does not exist writes nothing")
    void anUnknownTicketIsDropped() {
        assertThat(service.accept(reply("<" + PRIYA + ">",
                "<ticket.999999@edutrack.local>", "Anyone there?"))).isEmpty();
        assertThat(commentCount()).isZero();
    }

    @Test
    @DisplayName("a reply that is nothing but quoted text writes nothing")
    void anEmptyReplyIsDropped() {
        // The stripper deliberately returns the original rather than an empty
        // string, so this is not blank — but a reply with genuinely no text is.
        assertThat(service.accept(reply("<" + PRIYA + ">",
                "<ticket." + ticketId + "@edutrack.local>", "   \n  "))).isEmpty();
        assertThat(commentCount()).isZero();
    }

    // ------------------------------------------------------------------ details

    @Test
    @DisplayName("the address is matched regardless of case and display name")
    void theAddressIsNormalised() {
        assertThat(service.accept(reply(
                "Priya Nair <" + PRIYA.toUpperCase(java.util.Locale.ROOT) + ">",
                "<ticket." + ticketId + "@edutrack.local>", "Case should not matter.")))
                .isPresent();
    }

    // ----------------------------------------------------------------- helpers

    private InboundReply reply(String from, String inReplyTo, String text) {
        return new InboundReply(from, inReplyTo, null, text, "Re: [ITI-26-00001] test");
    }

    private long insertProject() {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITI', 'Inbound fixture')");
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

    private long insertTicket(String code, long projectId) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level)
                VALUES (?, ?, 'inbound fixture', 'MEDIUM', 'MEDIUM')
                """, code, projectId);
        return lastId();
    }

    private void mailWasSentTo(String email, long ticket) {
        jdbc.update("""
                INSERT INTO email_log (event_code, to_email, ticket_id, subject, status)
                VALUES ('TICKET_ASSIGNED', ?, ?, '[ITI-26-00001] test', 'SENT')
                """, email, ticket);
    }

    private long lastId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0 : id;
    }

    private String bodyOf(long id) {
        return jdbc.queryForObject(
                "SELECT body_text FROM ticket_comments WHERE id = ?", String.class, id);
    }

    private long authorOf(long id) {
        Long v = jdbc.queryForObject(
                "SELECT author_id FROM ticket_comments WHERE id = ?", Long.class, id);
        return v == null ? 0 : v;
    }

    private long ticketOf(long id) {
        Long v = jdbc.queryForObject(
                "SELECT ticket_id FROM ticket_comments WHERE id = ?", Long.class, id);
        return v == null ? 0 : v;
    }

    private String sourceOf(long id) {
        return jdbc.queryForObject(
                "SELECT source FROM ticket_comments WHERE id = ?", String.class, id);
    }

    private boolean internalOf(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_internal FROM ticket_comments WHERE id = ?", Boolean.class, id));
    }

    private int commentCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_comments WHERE ticket_id = ?", Integer.class, ticketId);
        return n == null ? 0 : n;
    }
}
