package com.edunext.edutrack.api.feature.chat;

import io.swagger.v3.oas.annotations.media.Schema;
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
    /**
     * D-062 · named explicitly because <strong>springdoc keys
     * {@code components.schemas} by simple class name</strong>, and three
     * unrelated records in this codebase are called {@code StatusRequest} —
     * this one, {@code ResourceDtos.StatusRequest} ({@code isActive},
     * {@code reason}) and {@code ClientDtos.StatusRequest}. They collapsed into
     * one published schema and the last registered won, so the served document
     * described this endpoint with somebody else's two fields.
     *
     * <p>Invisible from the committed contract, which is hand-authored, and
     * invisible to D-005, which compares the client to that contract rather
     * than to what the server publishes. D-062 is what found it.
     */
    @Schema(name = "TicketStatusRequest")
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
