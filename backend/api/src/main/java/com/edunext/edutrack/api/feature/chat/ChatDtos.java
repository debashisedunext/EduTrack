package com.edunext.edutrack.api.feature.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The wire shapes, matching {@code contracts/openapi.yaml} §chat.
 *
 * <p>Grouped in one file because they are one contract read together, and
 * because splitting eight records across eight files makes a diff that changes
 * the shape harder to review, not easier.
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /**
     * {@code UserRef} in the contract.
     *
     * <p>{@code handle} is the {@code @mention} name — {@code users.username}.
     * It is populated where the client has to compose or resolve a mention: the
     * participant list a type-ahead offers, and the mentions on a message it
     * highlights in the body. It is null on a message author, who is identified
     * by an avatar rather than typed.
     */
    public record UserRef(long id, String displayName, String handle) {

        static UserRef of(long id, String displayName) {
            return new UserRef(id, displayName, null);
        }
    }

    /**
     * A thread as the list screen sees it.
     *
     * <p>{@code ticketId} is the human ticket code (CRM-26-00347), not the row
     * id — the contract types it as a string, and the code is what the UI links
     * on. Sending the numeric id here would render a link nobody can follow.
     */
    public record ChatThread(
            long id,
            ChatKind kind,
            String title,
            String ticketId,
            int unreadCount,
            Instant lastMessageAt,
            List<UserRef> participants) {
    }

    /**
     * One message.
     *
     * <p>{@code body} is null on a deleted message and {@code isDeleted} is
     * true — the row survives as a tombstone so the conversation still shows
     * that something was said and removed. Withholding the body while keeping
     * the row is the whole point of §7.6: a thread anybody can quietly rewrite
     * proves nothing.
     */
    public record ChatMessage(
            long id,
            String body,
            UserRef author,
            MessageKind kind,
            boolean isEdited,
            boolean isDeleted,
            Instant editableUntil,
            List<Long> readBy,
            /*
             * D-052. UserRef rather than bare ids, matching Comment.mentions in
             * the contract — a client rendering a highlight needs the handle to
             * find the substring and the display name to show on hover, and
             * neither is derivable from an id.
             *
             * Kept on a deleted message: the body is withheld, but who was
             * called into the conversation is part of the record that survives.
             */
            List<UserRef> mentions,
            Instant createdAt) {
    }

    /**
     * D-053 · one search hit.
     *
     * <p>A distinct shape rather than a reused {@link ChatMessage}: a hit is
     * read out of context, so it has to name its thread — and it has no
     * {@code readBy}, {@code mentions} or {@code editableUntil}, which would
     * cost a query each to populate honestly and would be empty lies if they
     * were not.
     */
    public record ChatSearchHit(
            long messageId,
            long threadId,
            ChatKind threadKind,
            String threadTitle,
            String ticketId,
            String body,
            UserRef author,
            Instant createdAt) {
    }

    /** Cursor pagination for search, matching {@code Meta} in the contract. */
    public record SearchMeta(String nextCursor, boolean hasMore) {
    }

    /** Request body for {@code PATCH …/messages/{messageId}} (D-057). */
    public record EditMessage(
            @NotBlank
            @Size(min = 1, max = 20_000)
            String body) {
    }

    /** Request body for {@code POST /chat/threads/{id}/messages}. */
    public record PostMessage(
            @NotBlank
            @Size(min = 1, max = 20_000)
            String body,

            List<Long> attachmentIds) {
    }
}
