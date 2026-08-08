package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.realtime.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * D-050 · the chat engine against real MySQL.
 *
 * <p>What is worth testing here is precisely what "one engine, three surfaces"
 * claims: that a ticket thread, a direct message and a project channel travel
 * the same code path and differ only in the §9.3 room they broadcast to. A test
 * per surface asserting the same behaviours is what stops the three quietly
 * diverging later.
 *
 * <p>{@link RealtimePublisher} is mocked. The real Redis fan-out already has
 * its own proof in {@code RealtimeRelayIT}; what has never been checked is
 * whether <em>chat</em> addresses its messages correctly, and a mock makes the
 * destination a direct assertion rather than something inferred from a
 * subscriber that may simply have been slow.
 *
 * <p>Fixtures use codes no seed migration will claim, for the reason recorded
 * in {@code AuthLoginIT}: a fixture that invents its own DEVELOPER row collides
 * with B-001 the moment it lands.
 */
@SpringBootTest
@Testcontainers
class ChatEngineIT {

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
    ChatService chat;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    RealtimePublisher realtime;

    private long ravi;
    private long meera;
    private long outsider;
    private long projectId;
    private long ticketId;
    private long ticketThread;
    private long directThread;
    private long projectThread;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_participants");
        jdbc.update("DELETE FROM chat_threads");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_chat_%'");
        jdbc.update("DELETE FROM projects WHERE project_code = 'ITC'");

        projectId = insertProject();
        ravi = insertUser("it_chat_ravi");
        meera = insertUser("it_chat_meera");
        outsider = insertUser("it_chat_outsider");
        ticketId = insertTicket("ITC-26-00001");

        ticketThread = insertThread("TICKET", ticketId, null, "Ticket thread");
        directThread = insertThread("DIRECT", null, null, null);
        projectThread = insertThread("PROJECT", null, projectId, "Project channel");

        join(ticketThread, ravi);
        join(ticketThread, meera);
        join(directThread, ravi);
        join(directThread, meera);
        join(projectThread, ravi);
        join(projectThread, meera);
    }

    // ------------------------------------------------- three surfaces, one engine

    @Test
    @DisplayName("a ticket thread broadcasts to that ticket's room")
    void ticketThreadAddressing() {
        chat.post(ticketThread, ravi, "moving to QA");
        assertThat(published()).containsExactly("/topic/ticket." + ticketId);
    }

    @Test
    @DisplayName("a project channel broadcasts to that project's room")
    void projectChannelAddressing() {
        chat.post(projectThread, ravi, "standup in five");
        assertThat(published()).containsExactly("/topic/project." + projectId);
    }

    @Test
    @DisplayName("a direct message goes to each participant's own queue, not a shared topic")
    void directMessageAddressing() {
        chat.post(directThread, ravi, "got a minute?");

        // There is no room only these two are in. Inventing one would mean a
        // topic anybody could subscribe to, or a second rule for D-013.
        assertThat(published()).containsExactlyInAnyOrder(
                "/user/" + ravi + "/queue/events",
                "/user/" + meera + "/queue/events");
    }

    // ------------------------------------------------------------- authorisation

    @Test
    @DisplayName("a thread you are not in is indistinguishable from one that does not exist")
    void nonParticipantSeesNothing() {
        assertThat(chat.messages(ticketThread, outsider, null, 50)).isEmpty();
        assertThat(chat.post(ticketThread, outsider, "let me in")).isEmpty();
        assertThat(chat.messages(999_999L, ravi, null, 50))
                .as("a missing thread and a forbidden one must answer identically")
                .isEmpty();
    }

    @Test
    void aRejectedPostIsNeitherStoredNorBroadcast() {
        chat.post(ticketThread, outsider, "let me in");

        assertThat(messageCount(ticketThread)).isZero();
        verify(realtime, never()).publish(anyString(), any());
    }

    @Test
    void theThreadListOnlyShowsThreadsYouAreIn() {
        assertThat(chat.threads(ravi, null)).hasSize(3);
        assertThat(chat.threads(outsider, null)).isEmpty();
    }

    @Test
    void theThreadListCanBeFilteredToOneSurface() {
        assertThat(chat.threads(ravi, ChatKind.PROJECT))
                .singleElement()
                .extracting(ChatDtos.ChatThread::kind)
                .isEqualTo(ChatKind.PROJECT);
    }

    // -------------------------------------------------------------- unread counts

    @Test
    void yourOwnMessageIsNotUnreadToYou() {
        chat.post(ticketThread, ravi, "first");

        assertThat(unreadFor(ravi)).isZero();
        assertThat(unreadFor(meera)).isEqualTo(1);
    }

    @Test
    @DisplayName("a participant who has never opened a thread still sees its messages as unread")
    void neverOpenedThreadCountsFromZero() {
        chat.post(ticketThread, ravi, "one");
        chat.post(ticketThread, ravi, "two");

        // last_read_message_id is NULL here, and `id > NULL` is NULL — without
        // the COALESCE in the query this reports zero and the badge never
        // appears for a brand-new thread.
        assertThat(unreadFor(meera)).isEqualTo(2);
    }

    @Test
    void readingTheNewestPageClearsTheBadge() {
        chat.post(ticketThread, ravi, "one");
        chat.post(ticketThread, ravi, "two");
        assertThat(unreadFor(meera)).isEqualTo(2);

        chat.messages(ticketThread, meera, null, 50);

        assertThat(unreadFor(meera)).isZero();
    }

    @Test
    @DisplayName("paging back through history does not mark newer messages read")
    void pagingBackwardsDoesNotAdvanceTheCursor() {
        chat.post(ticketThread, ravi, "one");
        long second = idOfLatest();
        chat.post(ticketThread, ravi, "two");

        chat.messages(ticketThread, meera, second, 50);

        assertThat(unreadFor(meera))
                .as("scrolling up must not silently mark the unread tail as seen")
                .isEqualTo(2);
    }

    // ------------------------------------------------------- evidence properties

    @Test
    @DisplayName("the edit window is offered to the author and to nobody else")
    void editableUntilIsAuthorOnly() {
        chat.post(ticketThread, ravi, "mine");

        ChatDtos.ChatMessage asAuthor = onlyMessageFor(ravi);
        ChatDtos.ChatMessage asOther = onlyMessageFor(meera);

        assertThat(asAuthor.editableUntil())
                .isEqualTo(asAuthor.createdAt().plus(ChatService.EDIT_WINDOW));
        assertThat(asOther.editableUntil())
                .as("offering an edit deadline on someone else's message invites a UI that tries")
                .isNull();
    }

    @Test
    @DisplayName("a deleted message leaves a tombstone rather than vanishing")
    void deletionLeavesATombstone() {
        chat.post(ticketThread, ravi, "said something regrettable");
        jdbc.update("UPDATE chat_messages SET deleted_at = CURRENT_TIMESTAMP(6), deleted_by = ? WHERE thread_id = ?",
                ravi, ticketThread);

        ChatDtos.ChatMessage tombstone = onlyMessageFor(meera);

        assertThat(tombstone.isDeleted()).isTrue();
        assertThat(tombstone.body())
                .as("the words are withheld, but the fact that something was said is not")
                .isNull();
        assertThat(tombstone.editableUntil()).isNull();
    }

    // ----------------------------------------------------------- schema guardrail

    @Test
    @DisplayName("a project channel cannot also claim a ticket")
    void aThreadHasExactlyOneAnchor() {
        // If this constraint is ever dropped, destinationsFor() would address a
        // message to whichever anchor it checked first — delivering it to a room
        // the participants are not in, which is a disclosure rather than a
        // rendering bug.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chat_threads (thread_type, ticket_id, project_id, subject)
                VALUES ('PROJECT', ?, ?, 'confused')
                """, ticketId, projectId))
                .hasMessageContaining("ck_chat_threads_one_anchor");
    }

    // ------------------------------------------------------ D-057 · immutability

    /**
     * §7.6 — "immutable after five minutes, deletions leave a tombstone. This
     * keeps chat admissible as project evidence."
     *
     * <p>The property under test is not "editing works". It is that editing
     * <em>stops</em> working, on time, for everyone, and that nothing removes
     * what was said.
     */
    @org.junit.jupiter.api.Nested
    class Immutability {

        @Test
        void theAuthorCanEditInsideTheWindow() {
            long id = postAndId("frist");

            ChatService.Outcome outcome = chat.edit(ticketThread, id, ravi, "first");

            assertThat(outcome).isInstanceOf(ChatService.Outcome.Applied.class);
            ChatDtos.ChatMessage edited = ((ChatService.Outcome.Applied) outcome).message();
            assertThat(edited.body()).isEqualTo("first");
            assertThat(edited.isEdited()).isTrue();
        }

        @Test
        @DisplayName("five minutes later the message is frozen")
        void theWindowCloses() {
            long id = postAndId("said in haste");
            backdate(id, 6);

            ChatService.Outcome outcome = chat.edit(ticketThread, id, ravi, "rewritten at leisure");

            assertThat(outcome).isInstanceOf(ChatService.Outcome.Immutable.class);
            assertThat(rawBody(id))
                    .as("a rejected edit must not have written anything")
                    .isEqualTo("said in haste");
        }

        @Test
        @DisplayName("the boundary is the database's clock, not the caller's")
        void justInsideTheWindowStillEdits() {
            long id = postAndId("original");
            backdate(id, 4);

            assertThat(chat.edit(ticketThread, id, ravi, "amended"))
                    .isInstanceOf(ChatService.Outcome.Applied.class);
        }

        @Test
        void nobodyElseCanEditYourMessage() {
            long id = postAndId("mine");

            // 404, not 403 — a conflict answer would confirm the message
            // exists and that Ravi wrote it.
            assertThat(chat.edit(ticketThread, id, meera, "not mine to change"))
                    .isInstanceOf(ChatService.Outcome.NotFound.class);
            assertThat(chat.edit(ticketThread, id, outsider, "nor mine"))
                    .isInstanceOf(ChatService.Outcome.NotFound.class);
            assertThat(rawBody(id)).isEqualTo("mine");
        }

        @Test
        void aDeletedMessageCannotBeEdited() {
            long id = postAndId("regrettable");
            chat.delete(ticketThread, id, ravi);

            assertThat(chat.edit(ticketThread, id, ravi, "less regrettable"))
                    .isInstanceOf(ChatService.Outcome.Immutable.class);
        }

        @Test
        @DisplayName("deletion withholds the body but keeps the evidence")
        void deletionLeavesEverythingButTheWords() {
            long id = postAndId("something said");

            ChatService.Outcome outcome = chat.delete(ticketThread, id, ravi);

            ChatDtos.ChatMessage tombstone = ((ChatService.Outcome.Applied) outcome).message();
            assertThat(tombstone.isDeleted()).isTrue();
            assertThat(tombstone.body()).isNull();

            // The row survives and so does the original text. Withholding it on
            // read is presentation; destroying it would throw away the record
            // this rule exists to preserve.
            assertThat(rawBody(id)).isEqualTo("something said");
            assertThat(deletedBy(id)).isEqualTo(ravi);
        }

        @Test
        @DisplayName("deleting is not limited to five minutes — a tombstone adds to the record")
        void deletionHasNoWindow() {
            long id = postAndId("an old message");
            backdate(id, 120);

            assertThat(chat.delete(ticketThread, id, ravi))
                    .isInstanceOf(ChatService.Outcome.Applied.class);
        }

        @Test
        void deletingTwiceDoesNotRestampTheTime() {
            long id = postAndId("once");
            chat.delete(ticketThread, id, ravi);
            String first = deletedAt(id);

            ChatService.Outcome second = chat.delete(ticketThread, id, ravi);

            assertThat(second)
                    .as("the caller asked for a state that already holds")
                    .isInstanceOf(ChatService.Outcome.Applied.class);
            assertThat(deletedAt(id))
                    .as("the record must show when it was actually removed")
                    .isEqualTo(first);
        }

        @Test
        void nobodyElseCanDeleteYourMessage() {
            long id = postAndId("mine");

            assertThat(chat.delete(ticketThread, id, meera))
                    .isInstanceOf(ChatService.Outcome.NotFound.class);
            assertThat(deletedAt(id)).isNull();
        }

        @Test
        @DisplayName("an edit is broadcast as an edit, not as a new message")
        void editAndDeleteAnnounceThemselvesDistinctly() {
            long id = postAndId("draft");

            chat.edit(ticketThread, id, ravi, "final");
            chat.delete(ticketThread, id, ravi);

            // A viewer that cannot tell an edit from a new message appends a
            // duplicate to everyone's scrollback.
            assertThat(publishedEvents())
                    .containsExactly("chat.message", "chat.message.edited", "chat.message.deleted");
        }

        private long postAndId(String body) {
            chat.post(ticketThread, ravi, body);
            return idOfLatest();
        }
    }

    // ------------------------------------------- D-051 · receipts and typing

    @org.junit.jupiter.api.Nested
    class ReceiptsAndTyping {

        @Test
        void nobodyHasReadAMessageThatHasJustBeenPosted() {
            chat.post(ticketThread, ravi, "hello");

            assertThat(onlyMessageFor(ravi).readBy())
                    .as("the author reading their own page must not count as a receipt")
                    .isEmpty();
        }

        @Test
        void aReaderAppearsOnTheReceipt() {
            chat.post(ticketThread, ravi, "hello");

            chat.messages(ticketThread, meera, null, 50);

            assertThat(onlyMessageFor(ravi).readBy()).containsExactly(meera);
        }

        @Test
        @DisplayName("the author is never in their own readBy")
        void theAuthorIsExcluded() {
            chat.post(ticketThread, ravi, "hello");
            chat.messages(ticketThread, ravi, null, 50);
            chat.messages(ticketThread, meera, null, 50);

            // Ravi's cursor is past his own message — he posted it — but
            // rendering your own avatar on everything you send is noise.
            assertThat(onlyMessageFor(meera).readBy()).containsExactly(meera);
        }

        @Test
        @DisplayName("a receipt is announced once, not on every glance at the thread")
        void readIsBroadcastOnlyWhenTheCursorMoves() {
            chat.post(ticketThread, ravi, "hello");

            chat.messages(ticketThread, meera, null, 50);
            chat.messages(ticketThread, meera, null, 50);
            chat.messages(ticketThread, meera, null, 50);

            assertThat(publishedEvents().stream().filter("chat.read"::equals).toList())
                    .as("re-opening a thread you have read must not spray receipts at everyone")
                    .hasSize(1);
        }

        @Test
        void anOlderPageDoesNotAnnounceAread() {
            chat.post(ticketThread, ravi, "one");
            long first = idOfLatest();
            chat.post(ticketThread, ravi, "two");

            chat.messages(ticketThread, meera, first, 50);

            assertThat(publishedEvents()).doesNotContain("chat.read");
        }

        @Test
        void typingReachesTheThreadsOwnRoom() {
            chat.typing(ticketThread, ravi, true);

            assertThat(published()).containsExactly("/topic/ticket." + ticketId);
            assertThat(publishedEvents()).containsExactly("chat.typing");
        }

        @Test
        void typingOnADirectMessageReachesBothQueues() {
            chat.typing(directThread, ravi, true);

            assertThat(published()).containsExactlyInAnyOrder(
                    "/user/" + ravi + "/queue/events",
                    "/user/" + meera + "/queue/events");
        }

        @Test
        @DisplayName("a non-participant cannot announce that they are typing")
        void typingIsMembershipChecked() {
            chat.typing(ticketThread, outsider, true);

            // Two leaks avoided: that the thread exists, and who is active in it.
            verify(realtime, never()).publish(anyString(), any());
        }

        @Test
        void typingWritesNothing() {
            chat.typing(ticketThread, ravi, true);

            assertThat(messageCount(ticketThread))
                    .as("an indicator true for two seconds does not belong in an evidence table")
                    .isZero();
        }
    }

    // -------------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private List<String> publishedEvents() {
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(realtime, atLeastOnce()).publish(anyString(), payloads.capture());
        return payloads.getAllValues().stream()
                .map(p -> String.valueOf(((java.util.Map<String, Object>) p).get("event")))
                .toList();
    }

    private void backdate(long messageId, int minutes) {
        jdbc.update("UPDATE chat_messages SET created_at = created_at - INTERVAL ? MINUTE WHERE id = ?",
                minutes, messageId);
    }

    private String rawBody(long messageId) {
        return jdbc.queryForObject("SELECT body FROM chat_messages WHERE id = ?", String.class, messageId);
    }

    private Long deletedBy(long messageId) {
        return jdbc.queryForObject("SELECT deleted_by FROM chat_messages WHERE id = ?", Long.class, messageId);
    }

    private String deletedAt(long messageId) {
        return jdbc.queryForObject(
                "SELECT CAST(deleted_at AS CHAR) FROM chat_messages WHERE id = ?", String.class, messageId);
    }


    private List<String> published() {
        ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
        verify(realtime, atLeastOnce()).publish(destinations.capture(), any());
        return destinations.getAllValues();
    }

    private ChatDtos.ChatMessage onlyMessageFor(long viewer) {
        Optional<List<ChatDtos.ChatMessage>> page = chat.messages(ticketThread, viewer, null, 50);
        assertThat(page).isPresent();
        assertThat(page.get()).hasSize(1);
        return page.get().getFirst();
    }

    private int unreadFor(long userId) {
        return chat.threads(userId, null).stream()
                .filter(thread -> thread.id() == ticketThread)
                .findFirst()
                .orElseThrow()
                .unreadCount();
    }

    private int messageCount(long threadId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE thread_id = ?", Integer.class, threadId);
        return count == null ? 0 : count;
    }

    private long idOfLatest() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM chat_messages", Long.class);
        return id == null ? 0L : id;
    }

    private long insertProject() {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITC', 'Chat fixture')");
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
                VALUES (?, ?, 'chat fixture', 'MEDIUM', 'MEDIUM')
                """, code, projectId);
        return lastId();
    }

    private long insertThread(String type, Long ticket, Long project, String subject) {
        jdbc.update("""
                INSERT INTO chat_threads (thread_type, ticket_id, project_id, subject)
                VALUES (?, ?, ?, ?)
                """, type, ticket, project, subject);
        return lastId();
    }

    private void join(long threadId, long userId) {
        jdbc.update("INSERT INTO chat_participants (thread_id, user_id) VALUES (?, ?)", threadId, userId);
    }

    private long lastId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
