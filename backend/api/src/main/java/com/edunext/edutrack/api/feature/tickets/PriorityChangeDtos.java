package com.edunext.edutrack.api.feature.tickets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * C-020 · the wire shapes for {@code PATCH /tickets/{ticketId}/priority}, per
 * {@code contracts/openapi.yaml}.
 */
final class PriorityChangeDtos {

    private PriorityChangeDtos() {
    }

    /**
     * The contract's {@code changeTicketPriority} body.
     *
     * <h2>Why {@code reason} is optional <em>here</em> and mandatory in the
     * service</h2>
     *
     * <p>§4B.1's rule is "mandatory free-text reason when the ticket is already
     * assigned", and Bean Validation cannot express it: the condition is a
     * column on a row this request names by id and does not carry. A
     * {@code @NotBlank} here would refuse the legitimate case — an unassigned
     * ticket being triaged, where §4B.1 says a reason is optional — and a
     * cross-field {@code @AssertTrue} could not see the ticket either.
     *
     * <p>So the constraint lives in {@link PriorityChangeService}, where the
     * ticket has been read, and it is still reported as a 400 keyed onto
     * {@code reason} so the dialog marks the textarea rather than a banner. See
     * {@link LevelReasonRequiredException}.
     *
     * <p>{@code level} is {@code @NotBlank} rather than an enum for
     * {@link UnknownLevelException}'s reason: S-12 lets an administrator add a
     * level without a release, so the valid set is data and the check is a
     * lookup against the priority master, not a parse.
     *
     * @param level  the priority code to move to — validated against the master
     * @param reason free text, required once the ticket is assigned
     */
    record ChangePriorityRequest(
            @NotBlank String level,
            @Size(max = 500) String reason) {
    }

    /** The contract's {@code TicketResponse} — {@code { data: Ticket }}. */
    record TicketResponse(TicketWire.Ticket data) {
    }
}
