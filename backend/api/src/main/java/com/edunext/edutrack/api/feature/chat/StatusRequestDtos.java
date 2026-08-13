package com.edunext.edutrack.api.feature.chat;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * D-055 / D-056 · the wire shapes for "Ask Status", matching
 * {@code contracts/openapi.yaml} §tickets.
 */
public final class StatusRequestDtos {

    private StatusRequestDtos() {
    }

    /**
     * {@code POST /tickets/{ticketId}/ask-status}.
     *
     * <p>The note is optional and the default is blueprint §7.6's own wording.
     * A blank ask is still an ask — the value is the timestamped demand and the
     * entry in the assignee's bell, not the sentence.
     */
    public record AskStatus(
            @Size(max = 1000)
            String note) {
    }

    /**
     * One status request.
     *
     * <p>{@code note} is the request message's body as it stands, and is null on
     * a message its author has since deleted. It is read from
     * {@code chat_messages} rather than copied here for exactly that reason: a
     * second copy would be the one place §7.6's tombstone cannot reach.
     *
     * <p>{@code responseWorkingMinutes} is the D-056 metric — <strong>working
     * minutes, not wall clock</strong>. A manager who asks at 18:00 on Friday
     * and is answered at 09:30 on Monday waited half a working hour; both
     * instants are here too, so anyone who wants the wall-clock figure can still
     * derive it, while the working figure is the one stamped against the
     * calendar as it stood at the time.
     */
    public record StatusRequest(
            long id,
            String ticketId,
            String ticketTitle,
            long threadId,
            long requestMessageId,
            ChatDtos.UserRef requestedBy,
            ChatDtos.UserRef askedOf,
            Instant requestedAt,
            String note,
            boolean isAnswered,
            Long answerMessageId,
            Instant answeredAt,
            Integer responseWorkingMinutes) {
    }
}
