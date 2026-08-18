package com.edunext.edutrack.api.feature.tickets.history;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketHistory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * C-059 · the wire shapes for {@code listTicketHistory}, per
 * {@code contracts/openapi.yaml}'s {@code HistoryEntry} schema.
 */
final class TicketHistoryDtos {

    private TicketHistoryDtos() {
    }

    /**
     * The contract's {@code UserRef}, declared locally rather than shared —
     * following {@code CommentDtos.UserRef} and {@code EffortLogDtos.UserRef}: a
     * common DTO across four streams' features is shared surface that has to be
     * renegotiated whenever any one of them wants another field.
     */
    record UserRef(long id, String displayName) {
    }

    /**
     * @param action        {@code ticket_history.event_type}, verbatim —
     *                      {@code CREATED}, {@code STATUS_CHANGED},
     *                      {@code FIELD_CHANGED}, {@code LEVEL_CHANGED},
     *                      {@code REOPENED} — plus {@code COMMENTED} for a row
     *                      synthesised from {@code include=comments}, which names
     *                      no real {@code ticket_history} entry (see
     *                      {@link TicketHistoryService})
     * @param actor         null when {@code actorType} is {@code SYSTEM} — an
     *                      auto-escalation or a scanner, not a person
     * @param note          {@code ticket_history.remarks}, or the comment body for
     *                      a synthesised {@code COMMENTED} row
     * @param stageCode     <b>always null today.</b> A handoff's stage lives on
     *                      {@code ticket_stage_transitions}, which C-042 has not
     *                      built, and no {@code ticket_history} row carries a
     *                      stage of its own either
     * @param iterationNo   <b>always null today</b>, for the same reason as
     *                      {@code stageCode} — {@code CommentDtos.CommentDto}
     *                      documents the identical absence for the identical
     *                      reason and this follows it rather than inventing a
     *                      value a real first iteration would be indistinguishable
     *                      from
     */
    record HistoryEntryDto(
            long id,
            String action,
            UserRef actor,
            String actorType,
            String fieldName,
            String oldValue,
            String newValue,
            String note,
            String stageCode,
            Integer cycleNo,
            Integer iterationNo,
            boolean isCorrection,
            Long correctsEntryId,
            Instant createdAt) {

        /**
         * {@code prevHash} and {@code rowHash} are not surfaced, deliberately
         * diverging from the contract's optional {@code entryHash} field.
         * {@link com.edunext.edutrack.domain.journal.TicketJournal#historyFor}'s
         * own javadoc is explicit: "callers rendering these to a client should
         * drop the hashes — they are what A-044 verifies, and publishing them
         * tells an attacker the shape of what a forgery would have to
         * reproduce." {@code entryHash} is not in the contract's {@code required}
         * list, so a client omitting it degrades to "field absent" rather than a
         * broken response. Flagged for Stream A/security sign-off rather than
         * resolved quietly, following this package's {@code README.md}
         * convention for a contract/implementation mismatch.
         */
        static HistoryEntryDto of(TicketHistory row, Map<Long, UserRef> people) {
            return new HistoryEntryDto(
                    row.getId(),
                    row.getEventType(),
                    // people is built by Map.copyOf/Map.of in TicketHistoryUserRefs, and
                    // an immutable map's get() throws NullPointerException on a null key
                    // rather than answering null — unlike a SYSTEM row's actorId, which
                    // is null by design (an escalation or a scanner, not a person).
                    row.getActorId() == null ? null : people.get(row.getActorId()),
                    row.getActorType(),
                    row.getFieldName(),
                    row.getOldValue(),
                    row.getNewValue(),
                    row.getRemarks(),
                    null,
                    row.getCycleNo() == null ? null : (int) row.getCycleNo(),
                    null,
                    row.isCorrection(),
                    row.getCorrectsEntryId(),
                    row.getCreatedAt());
        }

        /**
         * A comment, rendered as the interleaved stream's {@code COMMENTED} row —
         * {@code include=comments}, blueprint §4B.5: "comments are interleaved
         * into the History tab alongside field changes and handoffs, in one
         * chronological stream". {@code syntheticId} is
         * {@code 100_000 + comment.id}, the same offset
         * {@code frontend/src/mocks/handlers/tickets.ts} already uses, so a real
         * history id and a synthesised one never collide within one ticket's
         * lifetime and a cursor built from either resumes unambiguously.
         */
        static HistoryEntryDto ofComment(TicketComment row, long syntheticId, UserRef actor) {
            return new HistoryEntryDto(
                    syntheticId,
                    "COMMENTED",
                    actor,
                    "USER",
                    null,
                    null,
                    null,
                    row.getBodyText(),
                    null,
                    row.getCycleNo() == null ? null : (int) row.getCycleNo(),
                    null,
                    false,
                    null,
                    row.getCreatedAt());
        }
    }

    record HistoryListResponse(List<HistoryEntryDto> data, PageMeta meta) {
    }
}
