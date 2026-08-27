package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The contract's {@code Ticket} schema, and the one mapping from the entity onto
 * it.
 *
 * <p>Extracted by C-038 rather than invented by it. A-052 wrote this record
 * inside {@code detail/TicketDetailDtos} because the detail page was the only
 * thing answering with a ticket; {@code reopenTicket} answers
 * {@code TicketResponse}, which is {@code { data: Ticket }} — the same schema —
 * and so does every other lifecycle route the contract declares
 * ({@code assignTicket}, {@code changeStatus}, {@code closeTicket},
 * {@code resolveTicket}, eight in total). A second copy of a 24-field wire
 * record is a second place for a field to be forgotten, and the forgetting is
 * silent: the response still serialises, and the client reads {@code undefined}
 * from a field the schema says is always there.
 *
 * <p>So the record lives beside the feature rather than inside one route's DTO
 * file, and {@link #of} is the only place an entity becomes a wire ticket.
 *
 * <p><b>Field order is the contract's, and the JSON is byte-identical to what
 * A-052 shipped</b> — the record was moved, not rewritten, so the detail
 * endpoint's response does not change.
 */
public final class TicketWire {

    private TicketWire() {
    }

    /**
     * @param level         current, and the escalation engine may raise it
     * @param originalLevel what it was raised at; never mutated (A-070's
     *                      "born critical vs became critical")
     * @param totalEffortHrs Σ across <em>all</em> cycles, which is why a reopen
     *                      never touches it — see {@code ReopenService}
     * @param currentCycleNo increments on reopen after closure;
     *                      {@code currentIteration} increments on a backward
     *                      move within a cycle. Two independent counters
     *                      (§4A.2), the most misread pair in the spec
     * @param pctComplete   C-036 · S-21's slider. Added after A-052 first wrote
     *                      this record, hence out of the contract's own field
     *                      order — appending rather than reordering keeps every
     *                      existing caller's JSON a prefix of its new one
     */
    public record Ticket(
            long id,
            // B-066 · the contract's field is `ticketId` (blueprint's human-
            // readable code, TicketId-shaped) — this component kept the Java
            // name the entity accessor uses, and nothing was annotated, so
            // every one of the eight routes below has been serving this as
            // `ticketCode` in the actual JSON. No test caught it: every test
            // on this record calls the accessor directly or reads it from a
            // Java object before serialization, and ContractConformanceTest's
            // GET-body check only compares one level of nesting, where this
            // field sits two deep (`data.ticket.ticketId` on the detail read,
            // `data.tickets[].ticketId` on B-066's own Client 360). Caught
            // here because B-066 is the first caller to actually render the
            // nested field in a browser. Flagged for Stream C's sign-off
            // (Divyansh) rather than fixed quietly — it touches all eight
            // lifecycle routes' wire shape, even though the Java accessor
            // name (`ticketCode()`) is unchanged and every call site compiles
            // as-is.
            @JsonProperty("ticketId") String ticketCode,
            String title,
            String description,
            Integer taskTypeId,
            String level,
            String originalLevel,
            String status,
            String environment,
            Instant dateReported,
            // C-0xx · the contract's `Ticket.reportedBy`/`Ticket.assignee` are both
            // `UserRef` — `{id, displayName}`, `displayName` required — not the bare
            // FK this record carried until now. Every one of this record's eight
            // callers was serving `reportedBy`/`assignedTo` as a number under the
            // wrong field name for `assignee` besides, which the frontend's
            // generated `Ticket` type reads as a `UserRef` and calls `.displayName`
            // straight off — `PersonCell`/`AvatarStack`'s `initials()` crashes the
            // whole app on the resulting `undefined.split(...)`, uncaught, for
            // every ticket that has a reporter. Found via `S-20` blank-screening
            // for every role on every ticket; `of` below now resolves both through
            // `UserRepository` the same way `TicketListRefs`/`CommentUserRefs`
            // already do for their own screens.
            UserRef reportedBy,
            @JsonProperty("assignee") UserRef assignee,

            /*
             * The four below carry the entity's accessor name in Java and the
             * contract's name on the wire, annotated rather than renamed.
             *
             * WHAT THIS WAS DOING. The contract has declared `cycleNo`,
             * `iterationNo`, `currentStageCode` and `estimatedHrs` since D-001;
             * springdoc emits a record component's own name, so the server was
             * sending `currentCycleNo`, `currentIteration`, `currentStage` and
             * `estimatedEffortHrs`. The generated client binds the contract's
             * names, so all four read `undefined` on every ticket — silently,
             * because an absent optional field is indistinguishable from one the
             * server chose not to send.
             *
             * The visible symptom was the cycle selector: `TicketDetailPage`
             * computes `cycle ?? ticket.cycleNo ?? 1`, so with `cycleNo`
             * undefined it fell through to 1 and highlighted "Cycle 1" while the
             * ribbon beside it — which reads `Ribbon.cycleNo`, a different record
             * that was mapped correctly — drew cycle 2. Two controls over one
             * ticket, disagreeing, with nothing failing. The header's
             * "Iteration N" chip and My Tasks' "↺ Iteration N" were dead for the
             * same reason and nobody noticed, because both only render when the
             * value is > 1.
             *
             * This is D-061's blank-ID column exactly: the server internally
             * consistent, the client internally consistent, and the two never
             * introduced. @JsonProperty rather than renaming the components,
             * on the `assignee` line above's own precedent — the Java names
             * match the entity accessors they are read from, and renaming them
             * would move the mismatch rather than remove it.
             */
            @JsonProperty("estimatedHrs") BigDecimal estimatedEffortHrs,
            BigDecimal totalEffortHrs,
            Instant plannedCloseDate,
            Instant actualCloseDate,
            boolean isReopened,
            int reopenCount,
            @JsonProperty("cycleNo") int currentCycleNo,
            boolean isDelayed,
            @JsonProperty("currentStageCode") String currentStage,
            @JsonProperty("iterationNo") int currentIteration,
            int reworkCount,
            int pctComplete,

            /*
             * C-067 · §7.5's "Where it happened", appended rather than slotted in
             * beside description. The contract's Ticket schema is additive by
             * convention, and a record's component order is its JSON order under
             * springdoc — inserting mid-record would reorder four other fields in
             * the emitted spec and churn the generated client for nothing.
             *
             * moduleId is the id, not the name. The contract says so and gives the
             * reason: a ticket raised against a module since retired still has to
             * render, and the name is resolved through GET /masters/modules where
             * the deactivated rows are also returned.
             */
            Integer moduleId,
            String screenName,
            String feature,
            String stepsToGenerate,

            // C-0xx · the contract's `Ticket.project`/`Ticket.client` are the
            // `Project`/`ClientRef` objects, not the bare `project_id` this
            // record carried until now (and no client field at all) — so every
            // one of this record's eight callers has served both as absent
            // JSON, which is why the detail page's Summary panel has rendered
            // "—" for Project and Client since A-052 first wrote this record.
            // Appended rather than inserted where the contract places them, on
            // the same precedent `pctComplete` above states. `TicketListRefs`
            // already solved this exact shape for the list screen; `of` below
            // resolves both the same way, at the "two lookups per ticket" cost
            // the reportedBy/assignee fix already accepts for this method.
            // `client` is null for a client-less ticket (Internal Bug does not
            // require one, §4B.2) — the contract marks it optional for exactly
            // that reason.
            Project project,
            ClientRef client) {
    }

    /** The contract's {@code UserRef}, at the two properties it requires. */
    public record UserRef(long id, String displayName) {
    }

    /**
     * The contract's {@code Project}, at the three properties {@code Ticket} embeds.
     *
     * <h2>Why the explicit schema name</h2>
     *
     * <p>springdoc keys {@code components.schemas} by <b>simple class name</b>, so
     * a record called {@code Project} here silently overwrites Stream B's
     * {@code ProjectDtos.Project} in the served document — {@code
     * TicketListDtos.Project}'s own javadoc names this exact failure, caught by
     * {@code ContractConformanceTest} the moment that record was added, reporting
     * eight properties "declared but not served" on {@code GET /projects}, an
     * endpoint that change never touched. Same remedy here, on the same
     * precedent: named for this feature's view of a project, not renamed away
     * from a collision.
     */
    @Schema(name = "TicketProjectRef")
    public record Project(long id, String projectCode, String name) {
    }

    /** The contract's {@code ClientRef}, at the three properties {@code Ticket}
     * embeds. Named explicitly for the reason above. */
    @Schema(name = "TicketClientRef")
    public record ClientRef(long id, String clientCode, String name) {
    }

    /**
     * The entity as the contract's {@code Ticket}, with {@code project}/
     * {@code client} left {@code null}.
     *
     * <p>{@code users} resolves {@code reportedBy}/{@code assignedTo} into the
     * {@code UserRef} the contract declares — see the record's own note on why a
     * bare id was wrong for every one of this method's eight callers. Two lookups
     * at most, which is the right cost here and would not be at
     * {@code TicketListRefs}' scale: this method answers for one ticket, not a
     * page of fifty, so there is nothing to batch.
     *
     * <p>Delegates to the four-argument overload below rather than duplicating
     * the field list a third time. Every existing caller reaches this one —
     * they answer from a lifecycle mutation the frontend re-fetches the detail
     * page after anyway — and {@code TicketDetailService} is the only one that
     * needs {@code project}/{@code client} populated, so it alone calls the
     * four-argument form. Widening every caller to carry two more repositories
     * for a field none of them render was a bigger change than this bug fix
     * should make; {@code Client360Service}'s own note above states the
     * identical restraint for the same reason.
     */
    public static Ticket of(com.edunext.edutrack.domain.tickets.Ticket t, UserRepository users) {
        return of(t, users, null, null);
    }

    /**
     * The entity as the contract's {@code Ticket}, {@code project}/{@code client}
     * included — see the two-argument overload's note on why only
     * {@code TicketDetailService} calls this one today.
     */
    public static Ticket of(com.edunext.edutrack.domain.tickets.Ticket t, UserRepository users,
                             ProjectRepository projects, ClientRepository clients) {
        return new Ticket(
                t.getId(), t.getTicketCode(), t.getTitle(), t.getDescription(),
                t.getTaskTypeId(), t.getLevel(), t.getOriginalLevel(), t.getStatus(),
                t.getEnvironment(), t.getDateReported(), userRef(t.getReportedBy(), users),
                userRef(t.getAssignedTo(), users),
                t.getEstimatedEffortHrs(), t.getTotalEffortHrs(), t.getPlannedCloseDate(),
                t.getActualCloseDate(), t.isReopened(), t.getReopenCount(), t.getCurrentCycleNo(),
                t.isDelayed(), t.getCurrentStage(), t.getCurrentIteration(), t.getReworkCount(),
                t.getPctComplete(),
                t.getModuleId(), t.getScreenName(), t.getFeature(), t.getStepsToGenerate(),
                projectRef(t.getProjectId(), projects), clientRef(t.getClientId(), clients));
    }

    /**
     * An id with no matching row resolves to {@code null} rather than a
     * placeholder — {@code TicketListRefs}' own reasoning: a deleted account
     * should render the ticket without a reporter rather than inventing "Unknown
     * user".
     */
    private static UserRef userRef(Long userId, UserRepository users) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId).map(u -> new UserRef(u.getId(), displayNameOf(u))).orElse(null);
    }

    /** {@code project_id} is {@code NOT NULL} on {@code tickets}, but a row with no
     * matching project still resolves to {@code null} rather than throwing — the
     * same restraint {@link #userRef} applies, and a wire method is the wrong
     * place to enforce a foreign key the database already enforces. */
    private static Project projectRef(Long projectId, ProjectRepository projects) {
        if (projectId == null || projects == null) {
            return null;
        }
        return projects.findById(projectId)
                .map(p -> new Project(p.getId(), p.getProjectCode(), p.getName()))
                .orElse(null);
    }

    /** {@code client_id} is nullable — an Internal Bug ticket genuinely has none
     * (§4B.2) — so {@code null} here is the ordinary case, not a lookup miss. */
    private static ClientRef clientRef(Long clientId, ClientRepository clients) {
        if (clientId == null || clients == null) {
            return null;
        }
        return clients.findById(clientId)
                .map(c -> new ClientRef(c.getId(), c.getClientCode(), c.getName()))
                .orElse(null);
    }

    /**
     * {@code full_name}, falling back to the username — {@code TicketListRefs}'
     * own fallback, for the same reason: {@code full_name} is nullable and an
     * SSO-provisioned account can arrive without one.
     */
    private static String displayNameOf(User user) {
        String fullName = blankToNull(user.getFullName());
        if (fullName != null) {
            return fullName;
        }
        return Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
