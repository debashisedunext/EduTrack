package com.edunext.edutrack.api.feature.tickets;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

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
 * <p>🔴 <b>Two component names drifted from the contract this class claims to
 * implement, and every one of the eight routes above was serving both wrong.</b>
 * {@code required: [ticketId, title, level, status, cycleNo]} on the contract's
 * {@code Ticket} schema — this record answered with {@code ticketCode} and
 * {@code currentCycleNo} instead, so the generated client's {@code ticket.ticketId}
 * and {@code ticket.cycleNo} were {@code undefined} on every response this class
 * ever produced, for a field the schema guarantees is always there. Found on a
 * ticket with no cycles at all (a perf-corpus row, {@code cycles: []}): the detail
 * page went to a blank screen rather than an error card, because nothing in this
 * frontend catches a render exception and {@code ticket.ticketId} is read
 * unguarded throughout the detail page and its dialogs — {@code HandoffDialog},
 * {@code TicketLevelControl}, every {@code EntityLink}. {@code GET /tickets}
 * (built later, {@code TicketListDtos.TicketSummary}, a separate record) already
 * used the contract's names correctly; this one did not follow when the schema
 * was written to match it. Fixed with {@code @JsonProperty} rather than a
 * component rename — nineteen files across eight routes call
 * {@code ticket.ticketCode()}, and renaming the accessor would have meant
 * touching every one for a fix that only needs to change what goes over the
 * wire.
 *
 * <p><b>Not fixed here, and still a real gap:</b> the contract's {@code Ticket}
 * also declares {@code project} ({@code ProjectRef}) and {@code client}
 * ({@code ClientRef}) as nested objects; this record has neither, only the bare
 * {@code projectId}. Both are read behind {@code ticket.project?.id != null}
 * guards on the frontend, so the Summary panel renders a quiet "—" instead of
 * the ticket's project and client rather than crashing — a real, silent
 * omission, but not the one that produced a blank screen. Filling it in needs a
 * join this class does not have and is a separate task.
 */
public final class TicketWire {

    private TicketWire() {
    }

    /*
     * ⚠ All three carry an explicit schema name, and it is not decoration.
     * springdoc keys `components.schemas` by **simple class name**, so a nested
     * record called `Project` silently overwrites Stream B's
     * `ProjectDtos.Project` in the served document — and `GET /projects` then
     * appears to answer three properties instead of eleven, on an endpoint this
     * file never touched. That is not hypothetical: it is what
     * `ContractConformanceTest` caught the moment `TicketListDtos` added its
     * own `Project`. Same remedy here rather than rediscovering it.
     */

    /** The contract's {@code UserRef}, at the properties this schema renders. */
    @Schema(name = "TicketUserRef")
    public record UserRef(long id, String displayName, String role) {
    }

    /** The contract's {@code Project}, at its three required properties. */
    @Schema(name = "TicketProject")
    public record Project(long id, String projectCode, String name) {
    }

    /** The contract's {@code ClientRef}. */
    @Schema(name = "TicketClientRef")
    public record ClientRef(long id, String clientCode, String name) {
    }

    /**
     * Turns the ids the entity carries into the objects the contract declares.
     *
     * <p>An interface rather than repositories injected here, because
     * {@link #of} is static and reached from eleven call sites across eight
     * routes — the alternative was making every one of those services resolve
     * four references before it could answer, for a shape most of them only
     * pass straight through.
     *
     * <p>{@link #NONE} answers null to everything and is what
     * {@link #of(com.edunext.edutrack.domain.tickets.Ticket)} passes, so every
     * existing caller keeps compiling. That is deliberately a <em>weaker</em>
     * promise than the contract makes: those routes now answer
     * {@code "project": null} rather than a bare id under a name the schema
     * says is an object. A null renders as a dash; the wrongly-shaped value is
     * what white-screened the page. Routes move onto a real resolver one at a
     * time, and the detail endpoint — the one screen that reads all four — is
     * first.
     */
    public record Refs(UserRef reportedBy, UserRef assignee, Project project, ClientRef client) {

        /**
         * Answers nothing, and is what
         * {@link TicketWire#of(com.edunext.edutrack.domain.tickets.Ticket)}
         * passes so that every existing caller keeps compiling.
         *
         * <p>Deliberately a <em>weaker</em> promise than the contract makes:
         * those routes answer {@code "project": null} rather than a bare id
         * under a name the schema says is an object. A null renders as a dash;
         * the wrongly-shaped value is what white-screened the page. Routes move
         * onto {@code TicketRefResolver} one at a time, and the detail endpoint
         * — the one screen that reads all four — is first.
         */
        public static final Refs NONE = new Refs(null, null, null, null);
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
     *                      (§4A.2), the most misread pair in the spec.
     *                      Wired to the contract's {@code cycleNo} — see the
     *                      class note — the Java name stays as every one of
     *                      the eight routes' services already calls it
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
            long projectId,

            /*
             * 🔴 The contract declares `project`, `client`, `assignee` and
             * `reportedBy` as objects. This record carried the first two not at
             * all, and the second two as bare numeric ids — which is what
             * emptied S-20's right rail: all four rows read `undefined` and
             * rendered a dash.
             *
             * `reportedBy` is the one that did real damage. A bare `17` is
             * **truthy**, so `PersonCell`'s `if (!person)` guard waved it
             * through, and the avatar beneath then split an undefined name —
             * taking the whole route down, since nothing in the SPA is wrapped
             * in an error boundary. A missing object would have rendered
             * nothing; a wrongly-shaped one white-screened the page.
             */
            Project project,
            ClientRef client,
            String title,
            String description,
            Integer taskTypeId,
            String level,
            String originalLevel,
            String status,
            String environment,
            Instant dateReported,
            UserRef reportedBy,
            @JsonProperty("assignee") UserRef assignedTo,
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
             * The five scalars the contract declares and this record simply did
             * not carry. Every one was already on the entity — no join, no
             * second query — so the detail page was rendering an empty Client
             * Contact, a blank "raised by client" and no created/updated dates
             * purely because nobody read them across.
             *
             * `delayedSince` is the one worth naming: `isDelayed` was sent
             * without it, so the page could say a ticket was late but never
             * since when.
             */
            Long clientContactId,
            boolean isClientRaised,
            Instant delayedSince,
            Instant createdAt,
            Instant updatedAt,

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
            String stepsToGenerate) {
    }

    /** The entity as the contract's {@code Ticket}, with no references resolved. */
    public static Ticket of(com.edunext.edutrack.domain.tickets.Ticket t) {
        return of(t, Refs.NONE);
    }

    /** The entity as the contract's {@code Ticket}, references resolved. */
    public static Ticket of(com.edunext.edutrack.domain.tickets.Ticket t, Refs refs) {
        return new Ticket(
                t.getId(), t.getTicketCode(), t.getProjectId(),
                refs.project(), refs.client(),
                t.getTitle(), t.getDescription(),
                t.getTaskTypeId(), t.getLevel(), t.getOriginalLevel(), t.getStatus(),
                t.getEnvironment(), t.getDateReported(),
                refs.reportedBy(), refs.assignee(),
                t.getEstimatedEffortHrs(), t.getTotalEffortHrs(), t.getPlannedCloseDate(),
                t.getActualCloseDate(), t.isReopened(), t.getReopenCount(), t.getCurrentCycleNo(),
                t.isDelayed(), t.getCurrentStage(), t.getCurrentIteration(), t.getReworkCount(),
                t.getPctComplete(),
                t.getClientContactId(), t.isClientRaised(), t.getDelayedSince(),
                t.getCreatedAt(), t.getUpdatedAt(),
                t.getModuleId(), t.getScreenName(), t.getFeature(), t.getStepsToGenerate());
    }
}
