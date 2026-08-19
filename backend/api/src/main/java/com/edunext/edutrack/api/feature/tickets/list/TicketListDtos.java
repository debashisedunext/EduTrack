package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.common.pagination.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The shapes {@code GET /tickets} answers with.
 *
 * <p>These are the contract's {@code TicketSummary} property names, and that is
 * the whole point of the type. Until D-061 they were not: the record carried
 * {@code ticketCode}, {@code assignedTo}, {@code projectId}, {@code clientId},
 * {@code currentStage} and {@code estimatedEffortHrs}, while the document the
 * frontend generates its client from declares {@code ticketId},
 * {@code assignee}, {@code project}, {@code client}, {@code currentStageCode}
 * and {@code estimatedHrs}. Six fields read {@code undefined} in the browser,
 * which is what rendered S-17's <b>ID</b>, <b>assignee</b>, <b>project</b> and
 * <b>client</b> columns blank and put the same two blanks in S-06's drill-down,
 * since it reads this same payload.
 *
 * <p>The names are now checked rather than remembered:
 * {@code ContractConformanceTest} compares what springdoc serves against the
 * contract for every operation, so this cannot drift again in silence. Its
 * {@code KNOWN_DRIFT} entry for {@code GET /tickets} is removed in the same
 * change that removes the drift.
 *
 * <h2>Nested refs, resolved in a batch</h2>
 *
 * <p>This file used to argue for flat ids, and the argument deserves answering
 * rather than deleting: "one row per ticket becomes one project lookup per
 * ticket, and S-17 renders 50 rows". That is an objection to the <b>n+1</b>,
 * and {@link TicketListRefs} removes the n+1 without removing the nesting —
 * one {@code IN} query per reference type per page, so fifty rows cost three
 * queries, the same as one.
 *
 * <p>The nesting itself was not open here. The contract's {@code Ticket} embeds
 * the whole {@code Project} schema and says so explicitly — "Stream C's call,
 * and not one to relitigate from here" — and {@code TicketSummary} is a strict
 * subset of {@code Ticket}'s names by construction. A list that named its
 * fields differently from the detail payload would make every consumer hold two
 * mental models of one ticket.
 */
final class TicketListDtos {

    private TicketListDtos() {
    }

    record ListResponse(List<TicketSummary> data, PageMeta meta) {
    }

    /**
     * The contract's {@code UserRef}.
     *
     * <p>Declared here rather than imported from the comments or attachments
     * package, following the precedent {@code AttachmentDtos.UserRef} states and
     * {@code CommentUserRefs} repeats: a feature declares its own view of a
     * contract shape, because a shared one across four streams has to be
     * renegotiated whenever any of them wants another field.
     *
     * <p>{@code avatarUrl} and {@code handle} are in the contract and
     * deliberately not here — both are optional, neither is drawn by the grid,
     * and a list is the wrong place to start paying for columns nothing renders.
     */
    @Schema(name = "TicketListUserRef")
    record UserRef(long id, String displayName, String role) {
    }

    /**
     * The contract's {@code Project}, at its three required properties.
     *
     * <h2>Why the explicit schema name</h2>
     *
     * <p>springdoc keys {@code components.schemas} by <b>simple class name</b>,
     * so a record called {@code Project} here silently overwrites Stream B's
     * {@code ProjectDtos.Project} in the served document — and the served
     * {@code GET /projects} then appears to answer three properties instead of
     * eleven. That is not a hypothetical: it is what
     * {@code ContractConformanceTest} caught the moment this record was added,
     * reporting eight properties "declared but not served" on an endpoint this
     * change never touched.
     *
     * <p>Named rather than renamed because the record's job here is to be the
     * tickets feature's view of a project, and {@code TicketListProject} says
     * that. Same remedy as {@code StatusRequestDtos}, for the same reason.
     */
    @Schema(name = "TicketListProject")
    record Project(long id, String projectCode, String name) {
    }

    /** The contract's {@code ClientRef}. Named explicitly for the reason above. */
    @Schema(name = "TicketListClientRef")
    record ClientRef(long id, String clientCode, String name) {
    }

    /**
     * One grid row.
     *
     * <p>Deliberately not the 58-property {@code Ticket} schema. S-17 shows a
     * row, not a record: description, steps-to-generate and the rest are the
     * detail page's job, and shipping 20 KB of sanitised HTML per row to render
     * a title would make the list slower than the waterfall A-052 exists to
     * remove.
     *
     * <p>Property order follows the contract's, so the two can be read side by
     * side when one of them changes.
     *
     * <p>{@code moduleId} carries the real column since C-070. It was
     * unconditionally null while C-065's {@code module_id} did not exist, which
     * was honest then — emitting the declared name as null says "not recorded",
     * where omitting it would have made the payload disagree with the document.
     * It is the only one of §7.5's four fields on this row: the module is what
     * S-17 filters and groups by, while a screen name, a feature and kilobytes
     * of repro steps are the detail page's job.
     */
    record TicketSummary(
            String ticketId,
            String title,
            Project project,
            ClientRef client,
            Long clientContactId,
            boolean isClientRaised,
            Integer taskTypeId,
            Long moduleId,
            String level,
            String originalLevel,
            String status,
            String currentStageCode,
            UserRef assignee,
            UserRef reportedBy,
            int cycleNo,
            int iterationNo,
            int reopenCount,
            boolean isReopened,
            Instant dateReported,
            Instant plannedCloseDate,
            Instant actualCloseDate,
            boolean isDelayed,
            Instant delayedSince,
            BigDecimal estimatedHrs,
            BigDecimal totalEffortHrs,
            int commentCount,
            int attachmentCount,
            Instant createdAt) {
    }
}
