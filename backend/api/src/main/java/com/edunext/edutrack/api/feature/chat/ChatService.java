package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D-050 · one engine, three surfaces (blueprint §7.6).
 *
 * <p>The surfaces differ in exactly one behaviour — which §9.3 room a new
 * message is broadcast to — and {@link #destinationsFor} is that difference,
 * isolated. Everything else here runs unchanged for a ticket thread, a direct
 * message and a project channel.
 *
 * <p><strong>Membership is the authorisation.</strong> Every entry point goes
 * through {@link ChatRepository#threadForParticipant}, which returns empty both
 * for a thread that does not exist and for one the caller is not in. The
 * controller turns either into a 404, never a 403 — the same no-existence-leak
 * rule CLAUDE.md sets for tickets. A 403 on a direct message would confirm that
 * two named people are talking, which is itself the private part.
 */
@Service
public class ChatService {

    /**
     * §7.6's edit window. The enforcement is D-057; what this class does is
     * publish the deadline, so the UI can stop offering "edit" at the right
     * moment rather than letting someone type a correction that gets rejected.
     */
    static final Duration EDIT_WINDOW = Duration.ofMinutes(5);

    private final ChatRepository repository;
    private final RealtimePublisher realtime;

    ChatService(ChatRepository repository, RealtimePublisher realtime) {
        this.repository = repository;
        this.realtime = realtime;
    }

    // --------------------------------------------------------------- threads

    @Transactional(readOnly = true)
    public List<ChatDtos.ChatThread> threads(long userId, ChatKind kind) {
        List<ChatRepository.ThreadRow> rows = repository.threadsFor(userId, kind);
        if (rows.isEmpty()) {
            return List.of();
        }

        // One query for every thread's participants rather than one per thread.
        // A user with forty threads is forty round trips otherwise, and the
        // thread list is the first thing the chat screen loads.
        Map<Long, List<ChatDtos.UserRef>> participants = new LinkedHashMap<>();
        for (ChatRepository.ParticipantRow row
                : repository.participantsOf(rows.stream().map(ChatRepository.ThreadRow::id).toList())) {
            participants
                    .computeIfAbsent(row.threadId(), key -> new ArrayList<>())
                    .add(new ChatDtos.UserRef(row.userId(), row.fullName()));
        }

        return rows.stream()
                .map(row -> new ChatDtos.ChatThread(
                        row.id(),
                        kindOf(row.threadType()),
                        title(row),
                        row.ticketCode(),
                        row.unreadCount(),
                        row.lastMessageInstant(),
                        participants.getOrDefault(row.id(), List.of())))
                .toList();
    }

    // -------------------------------------------------------------- messages

    /**
     * A page of messages, newest first, and the read cursor advanced to match.
     *
     * <p>Reading the newest page is what "I have seen this thread" means, so
     * the cursor moves here rather than in a separate endpoint the client could
     * forget to call. Only when there is no {@code cursor} — paging backwards
     * through history must not mark newer messages read.
     */
    @Transactional
    public Optional<List<ChatDtos.ChatMessage>> messages(long threadId, long userId, Long cursor, int limit) {
        if (repository.threadForParticipant(threadId, userId).isEmpty()) {
            return Optional.empty();
        }

        List<ChatRepository.MessageRow> rows = repository.messages(threadId, cursor, limit);
        if (cursor == null && !rows.isEmpty()) {
            long newest = rows.stream().mapToLong(ChatRepository.MessageRow::id).max().orElseThrow();
            repository.advanceReadCursor(threadId, userId, newest);
        }

        return Optional.of(rows.stream().map(row -> toMessage(row, userId)).toList());
    }

    /**
     * Post, then broadcast.
     *
     * <p>The broadcast is deliberately outside the persistence concern: the
     * message is committed first and published second, so a Redis outage costs
     * a live update, never the message. The reader who missed the push sees it
     * on their next load, because the database is the record and the socket is
     * only a nudge (see {@code RealtimePublisher}).
     */
    @Transactional
    public Optional<ChatDtos.ChatMessage> post(long threadId, long senderId, String body) {
        Optional<ChatRepository.ThreadAnchor> anchor = repository.threadForParticipant(threadId, senderId);
        if (anchor.isEmpty()) {
            return Optional.empty();
        }

        long messageId = repository.insertMessage(threadId, senderId, body, MessageKind.TEXT);

        // The author has self-evidently read their own message. Without this
        // the sender's own post counts as unread to them.
        repository.advanceReadCursor(threadId, senderId, messageId);

        List<ChatRepository.MessageRow> written = repository.messages(threadId, messageId + 1, 1);
        ChatDtos.ChatMessage message = written.isEmpty()
                ? null
                : toMessage(written.getFirst(), senderId);
        if (message == null) {
            throw new IllegalStateException("chat: message " + messageId + " vanished immediately after insert");
        }

        broadcast(anchor.get(), message, "chat.message");
        return Optional.of(message);
    }

    // ----------------------------------------------------- D-057 · evidence

    /**
     * What happened to an edit or a delete.
     *
     * <p>Sealed rather than {@code Optional} because the two failures must not
     * collapse into one answer: "not yours" is a 404 and "too late" is a 409,
     * and a caller that cannot tell them apart shows the author a message
     * saying their own post does not exist.
     */
    public sealed interface Outcome {

        /** No such message, not in this thread, or not written by the caller. */
        record NotFound() implements Outcome {
        }

        /** It exists and it is yours, but it is no longer yours to change. */
        record Immutable(String reason) implements Outcome {
        }

        record Applied(ChatDtos.ChatMessage message) implements Outcome {
        }
    }

    /**
     * Edit inside the five-minute window (blueprint §7.6).
     *
     * <p>The window is enforced here <em>and</em> in the UPDATE's WHERE clause.
     * That is not redundant caution: the check and the write are two statements,
     * and the whole point of this task is that the limit cannot be talked
     * around. A refactor that reorders them still cannot widen the window.
     */
    @Transactional
    public Outcome edit(long threadId, long messageId, long userId, String body) {
        Optional<ChatRepository.Editability> state =
                repository.editability(threadId, messageId, userId);
        if (state.isEmpty()) {
            return new Outcome.NotFound();
        }
        if (state.get().deleted()) {
            return new Outcome.Immutable("the message was deleted");
        }
        if (!state.get().withinWindow()) {
            return new Outcome.Immutable("the five-minute edit window has closed");
        }

        if (!repository.editBody(threadId, messageId, userId, body)) {
            // The row moved between the two statements — the window closed
            // mid-request, or a concurrent delete landed. Reporting it as
            // immutable is truthful; reporting success would not be.
            return new Outcome.Immutable("the message is no longer editable");
        }

        return applied(threadId, messageId, userId, "chat.message.edited");
    }

    /**
     * Delete, leaving a tombstone.
     *
     * <p>Not limited to five minutes, and that is not an inconsistency. The
     * window exists so nobody can rewrite what was said; a tombstone adds to
     * the record rather than altering it, and the body is retained in the
     * database — only withheld on read. Removing the row, or blanking the
     * column, would destroy the evidence this rule exists to keep.
     *
     * <p>Author only. Moderator deletion is not in §7.6, and inventing an
     * authority to erase other people's words is not a gap to fill quietly.
     */
    @Transactional
    public Outcome delete(long threadId, long messageId, long userId) {
        Optional<ChatRepository.Editability> state =
                repository.editability(threadId, messageId, userId);
        if (state.isEmpty()) {
            return new Outcome.NotFound();
        }
        if (state.get().deleted()) {
            // Already a tombstone. Idempotent rather than a conflict — the
            // caller asked for a state that already holds.
            return applied(threadId, messageId, userId, null);
        }

        repository.softDelete(threadId, messageId, userId);
        return applied(threadId, messageId, userId, "chat.message.deleted");
    }

    private Outcome applied(long threadId, long messageId, long viewerId, String event) {
        ChatDtos.ChatMessage message = repository.messageById(messageId)
                .map(row -> toMessage(row, viewerId))
                .orElseThrow(() -> new IllegalStateException(
                        "chat: message " + messageId + " vanished mid-transaction"));

        if (event != null) {
            repository.threadForParticipant(threadId, viewerId)
                    .ifPresent(anchor -> broadcast(anchor, message, event));
        }
        return new Outcome.Applied(message);
    }

    // ---------------------------------------------------------------- rooms

    /**
     * Where a message on this thread is delivered — the one thing the three
     * surfaces do differently.
     *
     * <p>A direct message fans out to each participant's own queue rather than
     * a shared topic, because there is no room only those two people are in.
     * Inventing one would mean either a topic anybody could subscribe to, or a
     * second authorisation rule for D-013 to get right.
     */
    List<String> destinationsFor(ChatRepository.ThreadAnchor anchor) {
        return switch (anchor.kind()) {
            case TICKET -> List.of(RealtimeDestinations.ticket(required(anchor.ticketId(), "ticketId")));
            case PROJECT -> List.of(RealtimeDestinations.project(required(anchor.projectId(), "projectId")));
            case DIRECT -> repository.participantIds(anchor.id()).stream()
                    .sorted(Comparator.naturalOrder())
                    .map(RealtimeDestinations::user)
                    .toList();
        };
    }

    /**
     * @param eventName {@code chat.message}, {@code chat.message.edited} or
     *                  {@code chat.message.deleted} — a viewer has to be told
     *                  which, because an edit that arrives looking like a new
     *                  message appends a duplicate to everyone's scrollback.
     */
    private void broadcast(ChatRepository.ThreadAnchor anchor,
                           ChatDtos.ChatMessage message,
                           String eventName) {
        Map<String, Object> event = Map.of(
                "event", eventName,
                "threadId", anchor.id(),
                "message", message);
        for (String destination : destinationsFor(anchor)) {
            realtime.publish(destination, event);
        }
    }

    // --------------------------------------------------------------- mapping

    private ChatDtos.ChatMessage toMessage(ChatRepository.MessageRow row, long viewerId) {
        boolean deleted = row.deletedAt() != null;
        Instant createdAt = row.createdAt().toInstant();
        boolean mine = row.senderId() != null && row.senderId() == viewerId;

        return new ChatDtos.ChatMessage(
                row.id(),
                // The tombstone: the row is still here, the words are not.
                deleted ? null : row.body(),
                row.senderId() == null
                        ? null
                        : new ChatDtos.UserRef(row.senderId(), row.senderName()),
                MessageKind.valueOf(row.kind()),
                row.editedAt() != null,
                deleted,
                // Only ever offered to the author, and never on a message that
                // is already gone.
                mine && !deleted ? createdAt.plus(EDIT_WINDOW) : null,
                List.of(),
                createdAt);
    }

    private String title(ChatRepository.ThreadRow row) {
        if (row.subject() != null && !row.subject().isBlank()) {
            return row.subject();
        }
        // A DM has no anchor to name it after, so the participant list is the
        // only sensible title and the client renders that from `participants`.
        return switch (kindOf(row.threadType())) {
            case TICKET -> row.ticketCode();
            case PROJECT -> row.projectName();
            case DIRECT -> null;
        };
    }

    private static ChatKind kindOf(String threadType) {
        return "ASK_STATUS".equals(threadType) ? ChatKind.TICKET : ChatKind.valueOf(threadType);
    }

    private static long required(Long value, String field) {
        if (value == null) {
            // The schema's ck_chat_threads_one_anchor makes this unreachable;
            // if it fires, the constraint was dropped and messages are about to
            // be addressed to the wrong room.
            throw new IllegalStateException("chat: thread anchor is missing " + field);
        }
        return value;
    }
}
