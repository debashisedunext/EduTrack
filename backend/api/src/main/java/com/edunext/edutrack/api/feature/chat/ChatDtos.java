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

    /** {@code UserRef} in the contract. */
    public record UserRef(long id, String displayName) {
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
            Instant createdAt) {
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
