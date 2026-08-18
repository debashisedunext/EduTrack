package com.edunext.edutrack.api.feature.tickets.quickupdate;

import com.edunext.edutrack.api.feature.tickets.StatusCode;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * C-036 · the wire shapes for {@code quickUpdateTicket}, per {@code
 * contracts/openapi.yaml}'s {@code QuickUpdateRequest} schema.
 */
final class QuickUpdateDtos {

    private QuickUpdateDtos() {
    }

    /**
     * Every field is optional and every field is send-if-touched — the
     * contract's own words, echoed by {@code quickUpdateForm.ts}'s doc comment
     * on the client side: an unfilled effort field must not log a zero-hour
     * entry, an unmoved slider must not overwrite {@code pctComplete}. {@code
     * null}/absent uniformly means "leave this alone", not "clear it" — there
     * is nothing on this panel a resource clears, only fields they move.
     *
     * @param effortHours   the contract's own bound is {@code minimum: 0}, but
     *                      {@code ck_effort_hours} — the constraint
     *                      {@link com.edunext.edutrack.domain.journal.TicketJournal}
     *                      writes under — rejects zero exactly as {@code
     *                      EffortLogDtos.EffortLogRequest} does, so this
     *                      tightens to the same {@code DecimalMin(0)} exclusive
     *                      bound that one declares, rather than let a caller's
     *                      zero reach the journal as an opaque 500
     * @param effortDate    defaults to today when {@code effortHours} is sent
     *                      without it — see {@code QuickUpdateService.append}
     * @param revisedEta    reason becomes mandatory the moment this is sent —
     *                      {@link RevisedEtaReasonRequiredException}, mirroring
     *                      the client's own {@code superRefine}
     * @param attachmentIds files already uploaded straight to the ticket
     *                      through {@code POST /tickets/{id}/attachments} —
     *                      see {@code QuickUpdateService} for why nothing here
     *                      links them to anything; they are validated as
     *                      belonging to this ticket and nothing more
     */
    record QuickUpdateRequest(
            StatusCode status,
            @DecimalMin(value = "0", inclusive = false)
            @DecimalMax(value = "24")
            BigDecimal effortHours,
            LocalDate effortDate,
            @Size(max = 4000) String workNote,
            @Min(0) @Max(100) Integer pctComplete,
            Instant revisedEta,
            @Size(max = 500) String revisedEtaReason,
            List<Long> attachmentIds) {
    }

    record TicketResponse(TicketWire.Ticket data) {
    }
}
