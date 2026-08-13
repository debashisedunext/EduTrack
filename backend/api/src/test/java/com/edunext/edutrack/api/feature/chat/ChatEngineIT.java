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
    StatusRequestService statusRequests;

    @Autowired
    TicketCardResolver ticketCards;

    @Autowired
    StatusRequestRepository statusRequestRepository;

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
        jdbc.update("DELETE FROM ticket_status_requests");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_participants");
        jdbc.update("DELETE FROM chat_threads");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM project_members WHERE user_id IN "
                + "(SELECT id FROM users WHERE username LIKE 'it_chat_%')");
        // users.reporting_manager_id is a self-referencing FK, so a fixture that
        // wires a reporting line makes its own users undeletable. D-055's tests
        // are the first to set one; clearing it here rather than in their
        // teardown means a test that fails midway still leaves a droppable
        // fixture behind.
        jdbc.update("UPDATE users SET reporting_manager_id = NULL WHERE username LIKE 'it_chat_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_chat_%'");
        jdbc.update("DELETE FROM projects WHERE project_code IN ('ITC', 'ITC2')");

        // Fixture ids are forced past 127 deliberately. Java caches boxed Long
        // values in -128..127, so comparing two boxed ids with `==` is
        // accidentally correct below that and wrong above it. D-051's readBy
        // shipped exactly that bug and every test passed, because a fresh
        // fixture never got past user id 20.
        jdbc.update("ALTER TABLE users AUTO_INCREMENT = 100000");

        projectId = insertProject();
        ravi = insertUser("it_chat_ravi", "Ravi Kumar");
        meera = insertUser("it_chat_meera", "Meera Prasad");
        outsider = insertUser("it_chat_outsider", "Nobody Here");
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

    // ---------------------------------------------------- D-052 · @mentions

    /**
     * §7.6 — "@mentions (fires a notification)"; §11 puts the mentioned user on
     * the popup, bell and email channels.
     *
     * <p>The property that matters is not that a mention notifies. It is
     * <em>who</em> it can notify: the fan-out is driven entirely by the message
     * body, and the only thing standing between {@code @anybody} and a
     * notification is thread membership.
     */
    @org.junit.jupiter.api.Nested
    class Mentions {

        @Test
        void mentioningAParticipantNotifiesThem() {
            chat.post(ticketThread, ravi, "can you take a look @it_chat_meera");

            assertThat(notificationsFor(meera)).singleElement().satisfies(row -> {
                assertThat(row.get("event_code")).isEqualTo("MENTIONED");
                assertThat(row.get("title")).isEqualTo("Ravi Kumar mentioned you");
            });
        }

        @Test
        @DisplayName("the resolved mention is stored on the message and surfaces on read")
        void theMentionIsStoredAndReturned() {
            chat.post(ticketThread, ravi, "over to you @it_chat_meera");

            assertThat(onlyMessageFor(ravi).mentions()).singleElement().satisfies(mentioned -> {
                assertThat(mentioned.id()).isEqualTo(meera);
                assertThat(mentioned.displayName()).isEqualTo("Meera Prasad");
                // The handle is what the client highlights in the body; without
                // it there is no way to find the substring to mark up.
                assertThat(mentioned.handle()).isEqualTo("it_chat_meera");
            });
        }

        @Test
        @DisplayName("mentioning somebody who is not in the thread notifies nobody")
        void aNonParticipantIsNotMentionable() {
            chat.post(ticketThread, ravi, "what do you think @it_chat_outsider");

            // Notifying them would deep-link to a thread that answers 404, and
            // would make @ a probe for which usernames exist.
            assertThat(notificationsFor(outsider)).isEmpty();
            assertThat(onlyMessageFor(ravi).mentions()).isEmpty();
            assertThat(onlyMessageFor(ravi).body())
                    .as("the message still posts — the handle is simply text")
                    .contains("@it_chat_outsider");
        }

        @Test
        void mentioningYourselfDoesNotNotifyYou() {
            chat.post(ticketThread, ravi, "note to self @it_chat_ravi");

            // Naming yourself addresses the room, it is not a request to be told.
            assertThat(notificationsFor(ravi)).isEmpty();
        }

        @Test
        void anUnknownHandleIsJustText() {
            chat.post(ticketThread, ravi, "@nobody_at_all are you there");

            assertThat(allNotifications()).isEmpty();
            assertThat(onlyMessageFor(ravi).mentions()).isEmpty();
        }

        @Test
        @DisplayName("an email address in the body is not a mention")
        void emailAddressesDoNotNotify() {
            chat.post(ticketThread, ravi, "forward it to it_chat_meera@example.com");

            assertThat(notificationsFor(meera)).isEmpty();
        }

        @Test
        @DisplayName("a deactivated colleague is not mentionable")
        void inactiveUsersAreNotMentionable() {
            jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", meera);

            chat.post(ticketThread, ravi, "@it_chat_meera one more thing");

            // A bell entry nobody will ever open is not a notification.
            assertThat(notificationsFor(meera)).isEmpty();
        }

        @Test
        void namingSomebodyTwiceNotifiesThemOnce() {
            chat.post(ticketThread, ravi, "@it_chat_meera and again @IT_CHAT_MEERA");

            assertThat(notificationsFor(meera)).hasSize(1);
        }

        @Test
        @DisplayName("the notification says where, and never quotes what was said")
        void theNotificationCarriesNoMessageText() {
            chat.post(ticketThread, ravi, "@it_chat_meera the password is hunter2");

            // §7.6 lets an author delete their words. A bell entry quoting the
            // body would be the one copy the tombstone cannot reach.
            assertThat(notificationsFor(meera)).singleElement().satisfies(row -> {
                assertThat(row.get("body")).isEqualTo("in ticket ITC-26-00001");
                assertThat(String.valueOf(row.get("body"))).doesNotContain("hunter2");
                assertThat(String.valueOf(row.get("title"))).doesNotContain("hunter2");
                assertThat(row.get("ticket_id")).isEqualTo(ticketId);
                assertThat(row.get("link_url")).isEqualTo("/chat/threads/" + ticketThread);
            });
        }

        @Test
        void aMentionOnAProjectChannelNamesTheChannelAndCarriesNoTicket() {
            chat.post(projectThread, ravi, "@it_chat_meera standup in five");

            assertThat(notificationsFor(meera)).singleElement().satisfies(row -> {
                assertThat(row.get("body")).isEqualTo("in the Chat fixture channel");
                assertThat(row.get("ticket_id")).isNull();
            });
        }

        @Test
        void aMentionInADirectMessageSaysSoWithoutNamingTheThread() {
            chat.post(directThread, ravi, "@it_chat_meera got a minute?");

            assertThat(notificationsFor(meera)).singleElement()
                    .satisfies(row -> assertThat(row.get("body")).isEqualTo("in a direct message"));
        }

        @Test
        @DisplayName("a mention pushes to the mentioned user's own queue, separately from the thread broadcast")
        void aMentionPushesToTheirQueue() {
            chat.post(ticketThread, ravi, "@it_chat_meera please look");

            assertThat(published()).containsExactly(
                    "/topic/ticket." + ticketId,
                    "/user/" + meera + "/queue/events");
            // A toast is a different surface with a different lifetime from the
            // message landing in the thread, so it is its own event.
            assertThat(publishedEvents()).containsExactly("chat.message", "notification.created");
        }

        @Test
        @DisplayName("an edit that adds a mention notifies only the person it added")
        void editingInAMentionNotifiesOnlyTheNewPerson() {
            chat.post(ticketThread, ravi, "@it_chat_meera have a look");
            long id = idOfLatest();
            join(ticketThread, outsider);

            chat.edit(ticketThread, id, ravi, "@it_chat_meera @it_chat_outsider have a look");

            assertThat(notificationsFor(outsider)).hasSize(1);
            assertThat(notificationsFor(meera))
                    .as("fixing a typo must not ring everybody's bell a second time")
                    .hasSize(1);
        }

        @Test
        @DisplayName("removing a mention by editing does not retract the notification")
        void anEditCannotUnNotify() {
            chat.post(ticketThread, ravi, "@it_chat_meera have a look");
            long id = idOfLatest();

            chat.edit(ticketThread, id, ravi, "never mind");

            // A delivered notification is a record. Quietly withdrawing it is
            // the same kind of rewrite the five-minute window exists to stop.
            assertThat(notificationsFor(meera)).hasSize(1);
            assertThat(onlyMessageFor(ravi).mentions())
                    .as("the message itself no longer mentions anyone")
                    .isEmpty();
        }

        @Test
        void aDeletedMessageWithholdsTheBodyButKeepsWhoWasCalledIn() {
            chat.post(ticketThread, ravi, "@it_chat_meera urgent");
            long id = idOfLatest();

            chat.delete(ticketThread, id, ravi);

            ChatDtos.ChatMessage tombstone = onlyMessageFor(meera);
            assertThat(tombstone.body()).isNull();
            assertThat(tombstone.mentions()).extracting(ChatDtos.UserRef::id).containsExactly(meera);
        }
    }

    // ------------------------------------------------------ D-053 · search

    /**
     * §7.6 message search.
     *
     * <p>Two properties carry this feature, and both fail silently if dropped:
     * a search must not reach conversations the caller is not in, and it must
     * not return the body of a message somebody deleted.
     */
    @org.junit.jupiter.api.Nested
    class Search {

        @Test
        void aWordInAMessageFindsIt() {
            chat.post(ticketThread, ravi, "the deployment rolled back overnight");
            chat.post(ticketThread, ravi, "nothing to do with that");

            assertThat(bodies(search(ravi, "deployment")))
                    .containsExactly("the deployment rolled back overnight");
        }

        @Test
        @DisplayName("every word must appear, and a partial word matches")
        void allTermsAreRequiredAndPrefixesMatch() {
            chat.post(ticketThread, ravi, "deployment finished");
            chat.post(ticketThread, ravi, "deployment failed on staging");

            assertThat(bodies(search(ravi, "deployment stag")))
                    .containsExactly("deployment failed on staging");
        }

        @Test
        @DisplayName("you cannot search a conversation you are not in")
        void searchIsScopedToYourOwnThreads() {
            chat.post(ticketThread, ravi, "confidential deployment plans");

            // Search is the one chat surface with no thread id in the request,
            // so the participant join is all that narrows it. Without it this
            // returns the company's direct messages to anyone typing a common
            // word.
            assertThat(search(outsider, "deployment")).isEmpty();
        }

        @Test
        @DisplayName("a deleted message is not findable — search must not defeat the tombstone")
        void deletedMessagesNeverMatch() {
            chat.post(ticketThread, ravi, "the deployment password is hunter2");
            long id = idOfLatest();
            chat.delete(ticketThread, id, ravi);

            // §7.6 keeps the body in the row and withholds it on read. A search
            // that matched on it would hand it straight back, and would be the
            // one path the tombstone does not cover.
            assertThat(search(ravi, "deployment")).isEmpty();
            assertThat(rawBody(id))
                    .as("the evidence is still in the database, only unfindable")
                    .contains("hunter2");
        }

        @Test
        void anEditedMessageIsFoundByItsNewText() {
            chat.post(ticketThread, ravi, "the depolyment failed");
            long id = idOfLatest();

            chat.edit(ticketThread, id, ravi, "the deployment failed");

            assertThat(bodies(search(ravi, "deployment"))).containsExactly("the deployment failed");
            assertThat(search(ravi, "depolyment")).isEmpty();
        }

        @Test
        void searchSpansEverySurfaceYouAreIn() {
            chat.post(ticketThread, ravi, "deployment on the ticket");
            chat.post(directThread, ravi, "deployment in a DM");
            chat.post(projectThread, ravi, "deployment in the channel");

            assertThat(search(ravi, "deployment")).hasSize(3);
        }

        @Test
        void searchCanBeNarrowedToOneThread() {
            chat.post(ticketThread, ravi, "deployment on the ticket");
            chat.post(directThread, ravi, "deployment in a DM");

            assertThat(bodies(chat.search(ravi, "deployment", directThread, null, 25).data()))
                    .containsExactly("deployment in a DM");
        }

        @Test
        @DisplayName("a hit names its thread, because it is read out of context")
        void aHitCarriesItsThread() {
            chat.post(ticketThread, ravi, "deployment done");

            assertThat(search(ravi, "deployment")).singleElement().satisfies(hit -> {
                assertThat(hit.threadId()).isEqualTo(ticketThread);
                assertThat(hit.threadKind()).isEqualTo(ChatKind.TICKET);
                assertThat(hit.ticketId()).isEqualTo("ITC-26-00001");
                assertThat(hit.author().displayName()).isEqualTo("Ravi Kumar");
            });
        }

        @Test
        @DisplayName("a direct message says what it is rather than inventing a title")
        void directMessagesAreNamedHonestly() {
            chat.post(directThread, ravi, "deployment chat");

            assertThat(search(ravi, "deployment")).singleElement()
                    .satisfies(hit -> assertThat(hit.threadTitle()).isEqualTo("Direct message"));
        }

        @Test
        void resultsAreNewestFirstAndPageBackwards() {
            chat.post(ticketThread, ravi, "deployment one");
            chat.post(ticketThread, ravi, "deployment two");
            chat.post(ticketThread, ravi, "deployment three");

            ChatService.SearchPage first = chat.search(ravi, "deployment", null, null, 2);
            assertThat(bodies(first.data())).containsExactly("deployment three", "deployment two");
            assertThat(first.meta().hasMore()).isTrue();

            ChatService.SearchPage second = chat.search(
                    ravi, "deployment", null, Long.valueOf(first.meta().nextCursor()), 2);
            assertThat(bodies(second.data())).containsExactly("deployment one");
            assertThat(second.meta().hasMore()).isFalse();
            assertThat(second.meta().nextCursor()).isNull();
        }

        @Test
        @DisplayName("a query this index cannot serve is empty, not an error")
        void anUnusableQueryReturnsNothing() {
            chat.post(ticketThread, ravi, "deployment done");

            assertThat(search(ravi, "")).isEmpty();
            assertThat(search(ravi, "!!!")).isEmpty();
            assertThat(search(ravi, null)).isEmpty();
            // Below innodb_ft_min_token_size, so unfindable rather than absent.
            assertThat(search(ravi, "qa")).isEmpty();
        }

        @Test
        @DisplayName("our minimum term length still matches the server's")
        void theTermFloorMatchesTheServer() {
            // If somebody lowers innodb_ft_min_token_size in my.cnf and
            // ChatSearch stays at 3, search silently keeps discarding words it
            // could now find — and nothing else would ever report it.
            assertThat(ChatSearch.MIN_TERM_LENGTH).isEqualTo(
                    jdbc.queryForObject("SELECT @@innodb_ft_min_token_size", Integer.class));
        }

        private List<ChatDtos.ChatSearchHit> search(long userId, String query) {
            return chat.search(userId, query, null, null, 25).data();
        }

        private List<String> bodies(List<ChatDtos.ChatSearchHit> hits) {
            return hits.stream().map(ChatDtos.ChatSearchHit::body).toList();
        }
    }

    // -------------------------------------------------------------------- helpers

    private List<java.util.Map<String, Object>> notificationsFor(long userId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? ORDER BY id", userId);
    }

    private List<java.util.Map<String, Object>> allNotifications() {
        return jdbc.queryForList("SELECT * FROM notifications ORDER BY id");
    }

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

    // ------------------------------------------ D-054 · ticket link preview

    /**
     * §7.6 — "link preview of any ticket mention into a rich ticket card".
     *
     * <p>The card itself is the easy half. What these pin is the half that can
     * be wrong without looking wrong:
     *
     * <ul>
     *   <li><strong>Per reader.</strong> Two people reading one message must be
     *       able to get different answers. A stored card would be identical for
     *       everybody, and the leak would be invisible.</li>
     *   <li><strong>Silent about what it withheld.</strong> A code the reader
     *       may not see must be indistinguishable from one that was never
     *       issued, or a message full of guesses becomes a probe for which
     *       tickets exist.</li>
     *   <li><strong>Live, not remembered.</strong> The card reflects the ticket
     *       now, not when somebody typed its code.</li>
     * </ul>
     */
    @org.junit.jupiter.api.Nested
    class TicketLinkPreview {

        private long otherProject;
        private long otherTicket;
        private String otherCode;

        @BeforeEach
        void aTicketOnAProjectRaviIsNotOn() {
            jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITC2', 'Another project')");
            otherProject = lastId();
            otherCode = "ITD-26-00042";
            jdbc.update("""
                    INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status)
                    VALUES (?, ?, 'Payment gateway timeout', 'CRITICAL', 'HIGH', 'IN_PROGRESS')
                    """, otherCode, otherProject);
            otherTicket = lastId();

            jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", ravi, ticketId);
        }

        @Test
        @DisplayName("a code the reader can see unfurls into a card")
        void theCardAppears() {
            chat.post(ticketThread, ravi, "same root cause as ITC-26-00001, look there first");

            ChatDtos.TicketCard card = onlyCardFor(ravi);
            assertThat(card.ticketId()).isEqualTo("ITC-26-00001");
            assertThat(card.title()).isEqualTo("chat fixture");
            assertThat(card.assignee().id()).isEqualTo(ravi);
        }

        @Test
        @DisplayName("the card carries what §4A.1's own row shows, and the stage")
        void theCardCarriesTheRowFields() {
            jdbc.update("UPDATE tickets SET level = 'CRITICAL', status = 'IN_PROGRESS', "
                    + "current_stage = 'DEVELOPMENT', is_delayed = 1 WHERE id = ?", ticketId);
            chat.post(ticketThread, ravi, "see ITC-26-00001");

            ChatDtos.TicketCard card = onlyCardFor(ravi);
            assertThat(card.level()).isEqualTo("CRITICAL");
            assertThat(card.status()).isEqualTo("IN_PROGRESS");
            assertThat(card.currentStageCode()).isEqualTo("DEVELOPMENT");
            assertThat(card.isDelayed()).isTrue();
        }

        @Test
        @DisplayName("the card is read now, not remembered from when the code was typed")
        void theCardIsLive() {
            chat.post(ticketThread, ravi, "see ITC-26-00001");
            assertThat(onlyCardFor(ravi).level()).isEqualTo("MEDIUM");

            // D-020 escalates it an hour later. The message is untouched.
            jdbc.update("UPDATE tickets SET level = 'CRITICAL', is_delayed = 1 WHERE id = ?", ticketId);

            assertThat(onlyCardFor(ravi).level())
                    .as("a stored card would still say MEDIUM, and the reader would act on it")
                    .isEqualTo("CRITICAL");
            assertThat(onlyCardFor(ravi).isDelayed()).isTrue();
        }

        @Test
        @DisplayName("two readers of one message get different cards")
        void theSameMessageResolvesDifferentlyPerReader() {
            // Meera is a PM on the other project and can see both tickets; Ravi
            // is a Developer and sees only what is assigned to him.
            makePmOn(meera, otherProject);
            chat.post(ticketThread, ravi, "ITC-26-00001 is a duplicate of " + otherCode);

            assertThat(codesOnCardsFor(ravi))
                    .as("Ravi holds ITC-26-00001 and has never been near the other project")
                    .containsExactly("ITC-26-00001");
            assertThat(codesOnCardsFor(meera))
                    .as("Meera's projects cover the referenced ticket")
                    .contains(otherCode);
        }

        @Test
        @DisplayName("a code you may not see stays plain text, exactly like one that does not exist")
        void anInvisibleCodeIsIndistinguishableFromAMissingOne() {
            chat.post(ticketThread, ravi,
                    "compare " + otherCode + " with ITC-26-09999");

            // ITD-26-00042 exists and is out of scope; ITC-26-09999 was never
            // issued. If these two answered differently, pasting a range of
            // codes would report back which of them are real.
            assertThat(codesOnCardsFor(ravi)).isEmpty();
            assertThat(cardsFor(ravi)).isEmpty();
        }

        @Test
        @DisplayName("the body is untouched — the code stays in the text it was typed in")
        void theBodyKeepsTheCode() {
            chat.post(ticketThread, ravi, "see ITC-26-00001 for the trace");

            assertThat(onlyMessageFor(ravi).body())
                    .as("§7.6 keeps chat as evidence; rewriting the body into a token "
                            + "would make the message unreadable without our renderer")
                    .isEqualTo("see ITC-26-00001 for the trace");
        }

        @Test
        @DisplayName("a deleted message unfurls nothing")
        void aTombstoneHasNoCards() {
            chat.post(ticketThread, ravi, "see ITC-26-00001");
            long messageId = idOfLatest();

            chat.delete(ticketThread, messageId, ravi);

            assertThat(cardsFor(ravi)).isEmpty();
        }

        @Test
        @DisplayName("a tombstone that still carried its body would unfurl nothing either")
        void theTombstoneGuardIsNotOnlyTheWithheldBody() {
            // Mutation-found. Deleting the isDeleted check changed nothing,
            // because a tombstone's body is already null by the time the
            // resolver sees it — so the test above proves the withholding, not
            // the guard. Handed a deleted message that DOES carry a body, the
            // guard is the only thing between a reader and part of what the
            // author removed. Unreachable through the service today, and it
            // stops being unreachable the moment anyone gives moderators the
            // deleted text.
            ChatDtos.ChatMessage undead = new ChatDtos.ChatMessage(
                    1L, "see ITC-26-00001", null, MessageKind.TEXT, false, true,
                    null, List.of(), List.of(), List.of(), java.time.Instant.now());

            assertThat(ticketCards.attach(auth(ravi), List.of(undead)).getFirst().ticketRefs())
                    .isEmpty();
        }

        @Test
        @DisplayName("a message naming no ticket costs no lookup and carries no cards")
        void theOrdinaryMessage() {
            chat.post(ticketThread, ravi, "on it, will update after standup");

            assertThat(onlyMessageFor(ravi).ticketRefs()).isEmpty();
        }

        @Test
        @DisplayName("the same ticket named twice is one card")
        void repeatsCollapse() {
            chat.post(ticketThread, ravi, "ITC-26-00001 blocks ITC-26-00001");

            assertThat(cardsFor(ravi)).hasSize(1);
        }

        @Test
        @DisplayName("a page stops unfurling at the cap, and the surplus stays plain text")
        void thePageCapHolds() {
            // TicketRefParser caps ONE message at MAX_REFS; this is the other
            // bound, across the page. A reader sees a handful of cards without
            // scrolling, so the surplus stays plain text rather than costing a
            // sixty-key lookup on every thread open.
            int total = TicketCardResolver.MAX_CODES_PER_PAGE + 10;
            for (int i = 1; i <= total; i++) {
                jdbc.update("""
                        INSERT INTO tickets (ticket_code, project_id, title, level, original_level, assigned_to)
                        VALUES (?, ?, 'bulk fixture', 'MEDIUM', 'MEDIUM', ?)
                        """, "ITC-26-%05d".formatted(i + 1000), projectId, ravi);
            }
            for (int message = 0; message < total / TicketRefParser.MAX_REFS; message++) {
                StringBuilder body = new StringBuilder();
                for (int i = 1; i <= TicketRefParser.MAX_REFS; i++) {
                    body.append("ITC-26-%05d ".formatted(message * TicketRefParser.MAX_REFS + i + 1000));
                }
                chat.post(ticketThread, ravi, body.toString());
            }

            assertThat(cardsFor(ravi))
                    .as("every one of these is visible to Ravi, so only the cap can bound it")
                    .hasSize(TicketCardResolver.MAX_CODES_PER_PAGE);
        }

        @Test
        @DisplayName("the explicit lookup is scoped the same way as the read path")
        void theLiveLookupIsScopedToo() {
            // The endpoint the socket path uses. It takes codes from the caller,
            // which is exactly why it must scope them — otherwise it would be a
            // way to ask which ticket codes exist, with no message required.
            assertThat(cardCodes(auth(ravi), "ITC-26-00001," + otherCode))
                    .containsExactly("ITC-26-00001");

            makePmOn(meera, otherProject);
            assertThat(cardCodes(auth(meera), "ITC-26-00001," + otherCode))
                    .contains(otherCode);
        }

        @Test
        @DisplayName("the explicit lookup drops anything that is not a ticket code")
        void theLiveLookupParsesRatherThanSplits() {
            // One definition of a ticket code, shared with the read path — so
            // this endpoint cannot be handed a pattern a message body would not
            // have matched either.
            assertThat(cardCodes(auth(ravi), "'; DROP TABLE tickets; --,TKT-000871,crm-26-00347"))
                    .isEmpty();
        }

        // ----------------------------------------------------------- helpers

        private void makePmOn(long userId, long projectId) {
            jdbc.update("UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'PM') WHERE id = ?",
                    userId);
            jdbc.update("""
                    INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                    VALUES (?, ?, 'PM', 1)
                    """, projectId, userId);
        }

        private org.springframework.security.core.Authentication auth(long userId) {
            String role = jdbc.queryForObject(
                    "SELECT r.code FROM users u JOIN roles r ON r.id = u.role_id WHERE u.id = ?",
                    String.class, userId);
            List<Long> projectIds = jdbc.queryForList(
                    "SELECT project_id FROM project_members WHERE user_id = ? AND is_active = 1",
                    Long.class, userId);
            return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    new com.edunext.edutrack.api.security.dev.DevPrincipal(
                            userId, "it_chat", "Fixture", role, projectIds, List.of()),
                    null, List.of());
        }

        private List<ChatDtos.TicketCard> cardsFor(long userId) {
            List<ChatDtos.ChatMessage> page =
                    ticketCards.attach(auth(userId), chat.messages(ticketThread, userId, null, 50).orElseThrow());
            return page.stream().flatMap(m -> m.ticketRefs().stream()).toList();
        }

        private List<String> codesOnCardsFor(long userId) {
            return cardsFor(userId).stream().map(ChatDtos.TicketCard::ticketId).toList();
        }

        private ChatDtos.TicketCard onlyCardFor(long userId) {
            List<ChatDtos.TicketCard> cards = cardsFor(userId);
            assertThat(cards).hasSize(1);
            return cards.getFirst();
        }

        private List<String> cardCodes(org.springframework.security.core.Authentication caller, String codes) {
            return ticketCards
                    .cardsFor(caller, TicketRefParser.codesIn(codes.replace(',', ' ')))
                    .stream()
                    .map(ChatDtos.TicketCard::ticketId)
                    .toList();
        }
    }

    // ------------------------------------ D-055 / D-056 · Ask Status

    /**
     * §7.6 — a Reporting Manager or PM asks, the card lands in the thread, the
     * reply is timestamped, and the wait becomes a reportable number.
     *
     * <p>Three properties carry the feature, and each is a way it could look
     * right and be wrong:
     *
     * <ul>
     *   <li><strong>Who may ask.</strong> Not everyone who can post in the
     *       thread — a developer who can chat about a ticket is not thereby
     *       entitled to demand an update from a colleague.</li>
     *   <li><strong>What counts as an answer.</strong> The whole metric rests
     *       on this clause, and the tempting simplifications (any reply; the
     *       assignee only) are each wrong in a way that only shows up months
     *       later in a scorecard.</li>
     *   <li><strong>Working hours.</strong> A wait measured in wall clock
     *       charges people for weekends, and a test whose window sits inside a
     *       working day cannot tell the two apart.</li>
     * </ul>
     */
    @org.junit.jupiter.api.Nested
    class StatusRequests {

        private long anil;

        @BeforeEach
        void assignAndSetTheChain() {
            jdbc.update("DELETE FROM ticket_status_requests");

            anil = insertUser("it_chat_anil", "Anil Shah");
            join(ticketThread, anil);

            // Ravi is the assignee; Meera is his reporting manager, which is
            // now the only way she qualifies to ask. Anil is in the thread and
            // is neither — the case that separates "can talk about this ticket"
            // from "may demand an update on it".
            jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", ravi, ticketId);
            jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?", meera, ravi);
        }

        // ---------------------------------------------------------- the ask

        @Test
        @DisplayName("the manager asks, and the card lands in the ticket's own thread")
        void theCardLandsInTheThread() {
            StatusRequestService.Outcome outcome = statusRequests.ask(ticketId, meera, null);

            assertThat(outcome).isInstanceOf(StatusRequestService.Outcome.Asked.class);
            ChatDtos.ChatMessage card = latestMessageOn(ticketThread);
            assertThat(card.kind()).isEqualTo(MessageKind.STATUS_REQUEST);
            assertThat(card.body()).isEqualTo(StatusRequestService.DEFAULT_NOTE);
            assertThat(card.author().id()).isEqualTo(meera);
        }

        @Test
        @DisplayName("the manager's own wording is what gets posted")
        void theManagersWording() {
            statusRequests.ask(ticketId, meera, "  Client call at four — where are we?  ");

            assertThat(latestMessageOn(ticketThread).body())
                    .isEqualTo("Client call at four — where are we?");
        }

        @Test
        @DisplayName("the card broadcasts to the ticket's room like any other message")
        void theCardIsBroadcast() {
            statusRequests.ask(ticketId, meera, null);

            assertThat(published()).contains("/topic/ticket." + ticketId);
        }

        @Test
        @DisplayName("clicking twice asks once")
        void repeatClicksAreIdempotent() {
            StatusRequestService.Outcome first = statusRequests.ask(ticketId, meera, "any update?");
            StatusRequestService.Outcome second = statusRequests.ask(ticketId, meera, "any update?");

            assertThat(asked(first).request().id()).isEqualTo(asked(second).request().id());
            assertThat(asked(first).alreadyOpen()).isFalse();
            assertThat(asked(second).alreadyOpen()).isTrue();
            assertThat(openRequestCount()).isEqualTo(1);
            assertThat(cardCount())
                    .as("a second card in the thread would be the manager nagging on our behalf")
                    .isEqualTo(1);
            assertThat(notificationsFor(ravi, "STATUS_REQUESTED"))
                    .as("nor a second bell entry")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("two different managers may each have one open, and each is owed an answer")
        void twoManagersMayBothAsk() {
            // Anil is a PM on the project, so he qualifies a different way from
            // Meera — and they are two people waiting, not one question asked
            // twice.
            makePm(anil);

            statusRequests.ask(ticketId, meera, "status?");
            statusRequests.ask(ticketId, anil, "status?");

            assertThat(openRequestCount()).isEqualTo(2);
        }

        // ------------------------------------------------------ who may ask

        @Test
        @DisplayName("somebody in the thread who is neither the manager nor a PM cannot ask")
        void aColleagueInTheThreadCannotAsk() {
            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.NotFound.class);
            assertThat(openRequestCount()).isZero();
            assertThat(cardCount()).isZero();
        }

        @Test
        @DisplayName("a PM on the ticket's project may ask, without reporting-line involvement")
        void aProjectPmMayAsk() {
            makePm(anil);

            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.Asked.class);
        }

        @Test
        @DisplayName("an Admin may ask, with no reporting line and no project membership")
        void anAdminMayAsk() {
            jdbc.update("UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'ADMIN') "
                    + "WHERE id = ?", anil);

            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.Asked.class);
        }

        @Test
        @DisplayName("a PM on a different project may not")
        void aPmElsewhereMayNot() {
            jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITC2', 'Another')");
            long otherProject = lastId();
            jdbc.update("""
                    INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                    VALUES (?, ?, 'PM', 1)
                    """, otherProject, anil);

            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.NotFound.class);
        }

        @Test
        @DisplayName("a deactivated project membership does not qualify")
        void aFormerPmMayNot() {
            jdbc.update("""
                    INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                    VALUES (?, ?, 'PM', 0)
                    """, projectId, anil);

            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.NotFound.class);
        }

        @Test
        @DisplayName("not being entitled to ask answers exactly like a ticket that is not there")
        void refusalIsIndistinguishableFromAbsence() {
            // CONVENTIONS.md §7: 403 only where the failure does not depend on
            // a row. Whether Anil is Ravi's manager depends entirely on the
            // row, so a 403 here would confirm the ticket exists to anyone
            // willing to try ids.
            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isEqualTo(statusRequests.ask(9_999_999L, anil, null));
        }

        @Test
        @DisplayName("an unassigned ticket has nobody to ask")
        void nobodyToAsk() {
            makePm(anil);
            jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE id = ?", ticketId);

            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.Rejected.class);
            assertThat(cardCount())
                    .as("a question in the thread with nobody's name against it, and a clock "
                            + "that can never be stopped")
                    .isZero();
        }

        @Test
        @DisplayName("a reporting-line manager loses the ticket when it loses its assignee")
        void anUnassignedTicketHasNoReportingLine() {
            jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE id = ?", ticketId);

            // Meera qualifies only because Ravi reports to her and Ravi has the
            // ticket. With nobody assigned there is no reporting line to this
            // ticket at all, so she is refused before the "nobody to ask" check
            // is ever reached — 404, not 422. Pinned because it is the kind of
            // ordering that looks like a bug the first time somebody hits it:
            // a PM or Admin on the same ticket gets the 422 above.
            assertThat(statusRequests.ask(ticketId, meera, null))
                    .isInstanceOf(StatusRequestService.Outcome.NotFound.class);
        }

        @Test
        @DisplayName("you cannot ask yourself")
        void theAssigneeCannotAskThemselves() {
            makePm(anil);
            jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", anil, ticketId);

            assertThat(statusRequests.ask(ticketId, anil, null))
                    .isInstanceOf(StatusRequestService.Outcome.Rejected.class);
        }

        // ------------------------------------------------- thread and notice

        @Test
        @DisplayName("a ticket nobody has ever chatted about still gets its card")
        void aThreadIsCreatedIfThereIsNone() {
            jdbc.update("DELETE FROM chat_messages WHERE thread_id = ?", ticketThread);
            jdbc.update("DELETE FROM chat_participants WHERE thread_id = ?", ticketThread);
            jdbc.update("DELETE FROM chat_threads WHERE id = ?", ticketThread);

            StatusRequestService.Outcome outcome = statusRequests.ask(ticketId, meera, null);

            // Nothing else in the system creates a ticket thread. Refusing here
            // would mean "you may not ask for a status because nobody has
            // chatted about this ticket yet".
            assertThat(asked(outcome).request().threadId()).isNotEqualTo(ticketThread);
            assertThat(participantsOf(asked(outcome).request().threadId()))
                    .containsExactlyInAnyOrder(meera, ravi);
        }

        @Test
        @DisplayName("the assignee is put in the thread before being sent a link to it")
        void theAssigneeIsAddedToTheThread() {
            jdbc.update("DELETE FROM chat_participants WHERE thread_id = ? AND user_id = ?",
                    ticketThread, ravi);

            statusRequests.ask(ticketId, meera, null);

            // A notification that deep-links somebody to a 404 is worse than
            // none: membership is what makes the thread readable at all.
            assertThat(participantsOf(ticketThread)).contains(ravi);
        }

        @Test
        @DisplayName("the assignee gets a bell entry in the Status requests tab")
        void theAssigneeIsNotified() {
            statusRequests.ask(ticketId, meera, null);

            assertThat(notificationsFor(ravi, "STATUS_REQUESTED")).isEqualTo(1);
            assertThat(notificationsFor(meera, "STATUS_REQUESTED"))
                    .as("the person asking is not told they asked")
                    .isZero();
        }

        // ------------------------------------------------------- the answer

        @Test
        @DisplayName("the assignee's reply closes it and the wait is recorded")
        void theReplyClosesIt() {
            statusRequests.ask(ticketId, meera, null);
            chat.post(ticketThread, ravi, "Fix is in review, closing today.");

            StatusRequestDtos.StatusRequest answered = onlyRequest();
            assertThat(answered.isAnswered()).isTrue();
            assertThat(answered.answeredAt()).isNotNull();
            assertThat(answered.responseWorkingMinutes()).isNotNull();
            assertThat(statusRequests.awaiting(meera)).isEmpty();
        }

        @Test
        @DisplayName("the manager chasing their own question does not answer it")
        void theManagersFollowUpDoesNotClose() {
            statusRequests.ask(ticketId, meera, null);
            chat.post(ticketThread, meera, "any update on this?");

            // Without this the metric would record a flattering response time
            // measured against the manager's own impatience.
            assertThat(onlyRequest().isAnswered()).isFalse();
        }

        @Test
        @DisplayName("a manager who ends up holding the ticket still cannot answer their own question")
        void theManagerCannotAnswerEvenWhenTheTicketBecomesTheirs() {
            statusRequests.ask(ticketId, meera, null);
            // S-24 reassigns the ticket to Meera herself — she asked Ravi, and
            // now she owns the work.
            jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", meera, ticketId);

            chat.post(ticketThread, meera, "never mind, I'll take it");

            // Mutation-found. theManagersFollowUpDoesNotClose does NOT cover
            // this: with Meera neither the assignee nor the person asked, the
            // other half of the clause already refuses her, so deleting
            // `requested_by_id <> :senderId` changed nothing and the test still
            // passed. This is the only arrangement that reaches it — and it is
            // reachable in production, where it would close a question nobody
            // answered and record a response time measured against the
            // manager's own typing.
            assertThat(onlyRequest().isAnswered()).isFalse();
        }

        @Test
        @DisplayName("two replies racing the same request close it once")
        void theClaimIsSafeWithoutTheCandidateQuery() {
            statusRequests.ask(ticketId, meera, null);
            long id = onlyRequest().id();
            long messageId = idOfLatest();

            assertThat(statusRequestRepository.close(id, messageId, ravi, java.time.Instant.now(), 5))
                    .isTrue();
            // Mutation-found, and the same shape as D-025's claim guard: the
            // candidate query already filters answered rows out, so nothing
            // reaching close() through a chat post can exercise the WHERE
            // clause — deleting `answered_at IS NULL` from the UPDATE broke
            // nothing. It is there for two replies committed together, both
            // having read the row as open, and without it the manager is
            // notified twice and the second wins the recorded duration.
            assertThat(statusRequestRepository.close(id, messageId, anil, java.time.Instant.now(), 9))
                    .as("the second caller must learn it was not the one that closed it")
                    .isFalse();
            assertThat(onlyRequest().responseWorkingMinutes()).isEqualTo(5);
        }

        @Test
        @DisplayName("a bystander talking in the thread does not answer it")
        void aBystanderDoesNotClose() {
            statusRequests.ask(ticketId, meera, null);
            chat.post(ticketThread, anil, "I saw something similar last week");

            assertThat(onlyRequest().isAnswered()).isFalse();
        }

        @Test
        @DisplayName("one reply answers every manager who was waiting")
        void oneReplyClosesThemAll() {
            makePm(anil);
            statusRequests.ask(ticketId, meera, "status?");
            statusRequests.ask(ticketId, anil, "status?");

            chat.post(ticketThread, ravi, "Deployed to staging, verifying now.");

            assertThat(openRequestCount()).isZero();
            assertThat(notificationsFor(meera, "STATUS_REQUEST_ANSWERED")).isEqualTo(1);
            assertThat(notificationsFor(anil, "STATUS_REQUEST_ANSWERED")).isEqualTo(1);
        }

        @Test
        @DisplayName("after a reassignment the new owner can close what the old one was asked")
        void theNewOwnerCanAnswer() {
            statusRequests.ask(ticketId, meera, null);
            // The ticket moves to Anil. Ravi has no reason to answer any more,
            // and without this clause nobody can clear the row — the manager's
            // list would fill with questions that cannot be closed.
            jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", anil, ticketId);

            chat.post(ticketThread, anil, "Picked this up this morning — ETA Thursday.");

            assertThat(onlyRequest().isAnswered()).isTrue();
            assertThat(onlyRequest().askedOf().id())
                    .as("who was asked is a fact about the past and must survive the reassignment")
                    .isEqualTo(ravi);
        }

        @Test
        @DisplayName("after a reassignment the person who was asked can still answer")
        void theOriginalOwnerCanStillAnswer() {
            statusRequests.ask(ticketId, meera, null);
            jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", anil, ticketId);

            // The mirror of theNewOwnerCanAnswer, and the reason the clause is
            // an OR rather than just "the current assignee": Ravi was asked, and
            // handing the ticket on does not unask him. Without this the
            // question he answers stays open and his manager keeps chasing it.
            chat.post(ticketThread, ravi, "Handed to Anil, but the root cause is the token refresh.");

            assertThat(onlyRequest().isAnswered()).isTrue();
        }

        @Test
        @DisplayName("a second reply neither reopens it nor rings the manager again")
        void aSecondReplyChangesNothing() {
            statusRequests.ask(ticketId, meera, null);
            chat.post(ticketThread, ravi, "Fix is in review.");
            java.time.Instant firstAnswer = onlyRequest().answeredAt();

            chat.post(ticketThread, ravi, "…and now merged.");

            assertThat(onlyRequest().answeredAt()).isEqualTo(firstAnswer);
            assertThat(notificationsFor(meera, "STATUS_REQUEST_ANSWERED")).isEqualTo(1);
        }

        @Test
        @DisplayName("the manager is told their question was answered")
        void theManagerIsNotified() {
            statusRequests.ask(ticketId, meera, null);
            chat.post(ticketThread, ravi, "Fix is in review.");

            assertThat(notificationsFor(meera, "STATUS_REQUEST_ANSWERED")).isEqualTo(1);
            assertThat(notificationsFor(ravi, "STATUS_REQUEST_ANSWERED"))
                    .as("the person who answered does not need telling")
                    .isZero();
        }

        // ---------------------------------------------- D-027 · the calendar

        @Test
        @DisplayName("the wait is working minutes, so a weekend is not held against anybody")
        void theWaitIsMeasuredAgainstTheWorkingCalendar() {
            statusRequests.ask(ticketId, meera, null);
            // Asked at 18:00 IST on Friday 7 Aug — half an hour before the
            // 18:30 close on the seeded calendar.
            jdbc.update("UPDATE ticket_status_requests SET requested_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(java.time.Instant.parse("2026-08-07T12:30:00Z")),
                    onlyRequest().id());

            chat.post(ticketThread, ravi, "Sorry — was off over the weekend. Looking now.");

            // Monday morning. Wall clock is over 60 hours; the working answer is
            // the 30 minutes left on Friday plus whatever of Monday has passed.
            // Bounded on both sides deliberately: a one-sided "< 600" would
            // also pass if the calendar returned 0, which is exactly what a
            // misconfigured calendar with no working days gives — and the test
            // would then certify the rule while proving nothing.
            assertThat(onlyRequest().responseWorkingMinutes())
                    .isNotNull()
                    .isGreaterThan(0)
                    .isLessThan(60 * 60);
        }

        // -------------------------------------------------------- the lists

        @Test
        @DisplayName("the awaiting list is longest wait first, and only your own")
        void theAwaitingList() {
            makePm(anil);
            statusRequests.ask(ticketId, meera, "older");
            long older = onlyRequest().id();
            jdbc.update("UPDATE ticket_status_requests SET requested_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(java.time.Instant.parse("2026-08-01T09:00:00Z")), older);
            statusRequests.ask(ticketId, anil, "newer");

            assertThat(statusRequests.awaiting(meera))
                    .extracting(StatusRequestDtos.StatusRequest::id)
                    .as("the list exists to be cleared, so the longest-ignored question comes first")
                    .containsExactly(older);
            assertThat(statusRequests.awaiting(ravi))
                    .as("the assignee asked nobody anything")
                    .isEmpty();
        }

        @Test
        @DisplayName("the badge shows what is outstanding on the ticket, whoever asked")
        void theBadge() {
            makePm(anil);
            statusRequests.ask(ticketId, meera, "one");
            statusRequests.ask(ticketId, anil, "two");

            assertThat(statusRequests.openOnTicket(ticketId, ravi)).hasSize(2);

            chat.post(ticketThread, ravi, "both answered at once");
            assertThat(statusRequests.openOnTicket(ticketId, ravi)).isEmpty();
        }

        @Test
        @DisplayName("somebody who cannot see the conversation sees no badge")
        void theBadgeIsScopedByMembership() {
            statusRequests.ask(ticketId, meera, null);

            assertThat(statusRequests.openOnTicket(ticketId, outsider)).isEmpty();
        }

        @Test
        @DisplayName("deleting the question withholds it here too")
        void theTombstoneReachesTheNote() {
            statusRequests.ask(ticketId, meera, "Where are we on this?");
            long messageId = onlyRequest().requestMessageId();
            assertThat(onlyRequest().note()).isEqualTo("Where are we on this?");

            chat.delete(ticketThread, messageId, meera);

            // A copy of the note in this row would be the one place §7.6's
            // tombstone does not reach, and it would sit in the manager's list
            // indefinitely.
            assertThat(onlyRequest().note()).isNull();
        }

        // ----------------------------------------------------------- helpers

        private void makePm(long userId) {
            jdbc.update("""
                    INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                    VALUES (?, ?, 'PM', 1)
                    """, projectId, userId);
        }

        private StatusRequestService.Outcome.Asked asked(StatusRequestService.Outcome outcome) {
            assertThat(outcome).isInstanceOf(StatusRequestService.Outcome.Asked.class);
            return (StatusRequestService.Outcome.Asked) outcome;
        }

        private StatusRequestDtos.StatusRequest onlyRequest() {
            Long id = jdbc.queryForObject("SELECT MIN(id) FROM ticket_status_requests", Long.class);
            assertThat(id).as("exactly one status request was expected").isNotNull();
            return requestById(id);
        }

        private StatusRequestDtos.StatusRequest requestById(long id) {
            return java.util.stream.Stream
                    .concat(statusRequests.awaiting(meera).stream(), answeredRequests().stream())
                    .filter(r -> r.id() == id)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no status request " + id));
        }

        /**
         * Answered rows have no endpoint of their own — the badge and the
         * awaiting list both show open ones — so the assertions read them back
         * through the repository the metric will be reported from.
         */
        private List<StatusRequestDtos.StatusRequest> answeredRequests() {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM ticket_status_requests", Long.class);
            return ids.stream()
                    .map(id -> statusRequestRepository.byId(id).orElseThrow())
                    .map(StatusRequestService::toDto)
                    .toList();
        }

        private int openRequestCount() {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ticket_status_requests WHERE answered_at IS NULL",
                    Integer.class);
            return count == null ? 0 : count;
        }

        private int cardCount() {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE kind = 'STATUS_REQUEST'", Integer.class);
            return count == null ? 0 : count;
        }

        private int notificationsFor(long userId, String eventCode) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND event_code = ?",
                    Integer.class, userId, eventCode);
            return count == null ? 0 : count;
        }

        private List<Long> participantsOf(long threadId) {
            return jdbc.queryForList(
                    "SELECT user_id FROM chat_participants WHERE thread_id = ?", Long.class, threadId);
        }

        private ChatDtos.ChatMessage latestMessageOn(long threadId) {
            return chat.messages(threadId, meera, null, 1).orElseThrow().getFirst();
        }
    }

    private long insertProject() {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITC', 'Chat fixture')");
        return lastId();
    }

    /**
     * Fixture users are DEVELOPERs, and that is load-bearing rather than tidy.
     *
     * <p>This used to take {@code SELECT id FROM roles ORDER BY id LIMIT 1},
     * and A-006 seeds {@code ADMIN} first — so <em>every</em> user any test in
     * this class created was an Admin. Two separate features have since written
     * authorisation tests against that fixture and had them quietly answered on
     * the admin branch: D-055's four "may not ask" cases, and D-054's scope
     * tests, which handed a Developer a card for a ticket on a project he had
     * never been near. Both failed loudly only by luck of branch ordering.
     *
     * <p>The least-privileged role is the right default for a fixture: a test
     * that needs more says so, in its own body, where a reviewer sees it.
     */
    private long insertUser(String username, String fullName) {
        return insertUser(username, fullName, "DEVELOPER");
    }

    private long insertUser(String username, String fullName, String roleCode) {
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        assertThat(roleId).as("seeded role %s", roleCode).isNotNull();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", fullName, roleId);
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
