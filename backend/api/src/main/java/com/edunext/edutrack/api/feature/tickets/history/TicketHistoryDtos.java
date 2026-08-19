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
     * @param entryHash     <b>declared, and always null.</b> See the field's own
     *                      note below — the property has to exist for
     *                      {@code ContractConformanceTest} to agree the response
     *                      matches the contract; the value is withheld on purpose
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
            String entryHash,
            Instant createdAt) {

        /**
         * {@code prevHash} and {@code rowHash} are not surfaced through
         * {@code entryHash}, which is declared on every row and always null.
         *
         * <p>{@link com.edunext.edutrack.domain.journal.TicketJournal#historyFor}'s
         * own javadoc is explicit: "callers rendering these to a client should
         * drop the hashes — they are what A-044 verifies, and publishing them
         * tells an attacker the shape of what a forgery would have to
         * reproduce." That is Stream A's guidance on the table this reads, and
         * it is not this task's to overrule.
         *
         * <p>The field cannot simply be dropped, though — {@code ContractConformanceTest}
         * (D-005) compares {@code HistoryEntry}'s declared property <em>names</em>
         * against what springdoc generates from this very record, on every {@code GET},
         * and a name the server never serves fails the build with "declared but not
         * served: [entryHash]" exactly as it did here. That check cannot tell a
         * deliberate security omission from the typo/rename drift it exists to
         * catch — {@code KNOWN_DRIFT} in that file is for the latter, and marking a
         * permanent, reasoned withholding as debt-to-be-closed would misstate it.
         *
         * <p>So the property stays declared — a client asking for it gets an honest
         * {@code null} rather than {@code undefined} — and the value stays withheld
         * until Stream A says otherwise. {@code history/README.md} carries the same
         * flag for whoever reviews this next.
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
                    null,
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
                    null,
                    row.getCreatedAt());
        }
    }

    record HistoryListResponse(List<HistoryEntryDto> data, PageMeta meta) {
    }
}
