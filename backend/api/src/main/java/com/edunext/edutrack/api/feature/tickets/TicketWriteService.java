package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import com.edunext.edutrack.api.text.RichTextSanitizer;
import com.edunext.edutrack.api.feature.masters.templates.TemplateResolver;
import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code POST /tickets} and {@code PATCH /tickets/{ticketId}} — the create and
 * update the contract has declared since D-001 with nothing behind them.
 *
 * <h2>The ID is issued by the sequence, never by a count</h2>
 *
 * <p>{@link TicketCodeGenerator} owns it, using PLAN.md §3.2's
 * {@code UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1)}. A
 * {@code COUNT(*)} races and reissues an ID under concurrency, and fails
 * silently when it does.
 *
 * <h2>isClientRaised is derived, never accepted</h2>
 *
 * <p>§4B.2. The request carries the field only so a client round-tripping a
 * {@code Ticket} it read back is not refused for an extra property; what is
 * stored is whether a client <em>and</em> a contact were both named. A caller
 * that could set the flag directly could mark an internal ticket client-raised
 * and change which reports it appears in.
 *
 * <h2>Sanitisation happens here, on both rich-text fields</h2>
 *
 * <p>PLAN.md §3.9: "the only sanitiser an attacker cannot skip is the one on the
 * write path". Both {@code description} and {@code stepsToGenerate} go through
 * {@link RichTextSanitizer} before anything is persisted — the same allow-list
 * the comment box uses, which is the point of it being shared.
 *
 * <h2>The project's own settings are obeyed on create, and only on create</h2>
 *
 * <p>C-071. B-019's Settings tab restricts which task types a project accepts
 * and which otherwise-optional fields it requires; {@link ProjectSettingsGate}
 * is what makes those two true, and its class note carries the reasoning —
 * including why the rules do not run on {@link #patch} and why
 * {@code saveAsDraft} does not waive them.
 *
 * <h2>One FIELD_CHANGED row per field that actually changed</h2>
 *
 * <p>Not one row per request. A PATCH naming four fields where two differ writes
 * two rows, because the history is a record of changes and a row saying
 * {@code screenName: "Login" → "Login"} is noise in the one place noise is most
 * expensive. Append-only throughout: {@link TicketJournal#append} is the only
 * way in, and no update or delete path exists to be called.
 */
@UnscopedAccess("""
        create() inserts a ticket that does not exist yet, so there is no row for \
        ScopedTickets to filter and nothing a scope could refuse. The guard's own \
        message names this case: "if this class really has no caller to scope by, \
        say so".

        The exemption is for the INSERT only. patch() goes through \
        ScopedTickets.requireByCode like every other write on an existing ticket, \
        so editing a ticket the caller cannot see is a 404 before any field is \
        read — the repository is never used to reach an existing row here.

        What create() must still refuse is a project the caller cannot post into. \
        That is not this rule's question and is not answered by this annotation; \
        it is the gap recorded in the PR, because ScopedTickets scopes tickets and \
        no project-scope equivalent exists yet.
        """)
@Service
public class TicketWriteService {

    private final ScopedTickets tickets;
    private final TicketRepository repository;
    private final TicketCodeGenerator codes;
    private final PlannedCloseDateService plannedCloseDates;
    private final ClientGate clients;
    private final ModuleGuard modules;
    /** C-071 · B-019's per-project settings, which until now nothing obeyed. */
    private final ProjectSettingsGate projectSettings;
    private final RichTextSanitizer sanitizer;
    private final TicketJournal journal;
    /**
     * D-037 · §4B.6 row 1, "Ticket created and assigned → Assignee". **Stream
     * D's producer, injected into Stream C's service** — the same arrangement
     * `CommentService` already has with `CommentMentionNotifier`, and flagged
     * for Divyansh in the pull request rather than added quietly.
     */
    private final TicketEventNotifier notifier;
    private final UserRepository users;

    /**
     * Resolves {@code workflow_template_id} for a new ticket from its project ×
     * task type. Built and tested (masters/templates), but never called from
     * here until now — its own javadoc says so explicitly: "wiring this into
     * ticket creation is Stream C's, not this task's." Without it every ticket
     * is created with {@code workflow_template_id = NULL}, so the ribbon
     * {@code RibbonAssembler} now assembles on the detail page has nothing to
     * show and Handoff/Skip never see a live stage to act on.
     */
    private final TemplateResolver templates;

    /** The resolved template's first stage, by {@code seq} — where a ticket
     * starts its journey once it has a template at all. */
    private final WorkflowStageRepository stages;

    TicketWriteService(ScopedTickets tickets, TicketRepository repository, TicketCodeGenerator codes,
                       PlannedCloseDateService plannedCloseDates, ClientGate clients, ModuleGuard modules,
                       ProjectSettingsGate projectSettings, RichTextSanitizer sanitizer, TicketJournal journal,
                       TicketEventNotifier notifier, UserRepository users, TemplateResolver templates,
                       WorkflowStageRepository stages) {
        this.tickets = tickets;
        this.repository = repository;
        this.codes = codes;
        this.plannedCloseDates = plannedCloseDates;
        this.clients = clients;
        this.modules = modules;
        this.projectSettings = projectSettings;
        this.sanitizer = sanitizer;
        this.journal = journal;
        this.notifier = notifier;
        this.users = users;
        this.templates = templates;
        this.stages = stages;
    }

    @Transactional
    public TicketWire.Ticket create(Authentication caller, TicketCreateDtos.CreateRequest request) {
        // Refuse the combination before issuing an ID. A sequence number burnt
        // on a rejected request is a gap in the ticket codes for that project,
        // and the gap is permanent.
        clients.requireSelectable(request.clientId());
        modules.requireActive(request.moduleId());
        // C-071 · the allow-list first, because it needs nothing computed and a
        // task type the project refuses makes every later question moot — the
        // SLA ladder resolves *by* task type, so a preview computed for one this
        // project will not accept is work done for a ticket that cannot exist.
        projectSettings.requireTaskTypeAllowed(request.projectId(), request.taskTypeId());

        // Resolved here rather than at the assignment below, so PLANNED_CLOSE_DATE
        // can be judged on the date the ticket would actually carry. It also
        // brings the SLA resolution above `nextTicketCode`, which is the same
        // discipline the paragraph above states: a preview that throws now costs
        // a lookup instead of a permanent gap in the project's ticket codes.
        Instant plannedCloseDate = request.plannedCloseDate() != null
                ? request.plannedCloseDate()
                : computedPlannedCloseDate(request);
        projectSettings.requireMandatoryFields(request.projectId(), request, plannedCloseDate);

        Ticket ticket = new Ticket();
        ticket.setTicketCode(codes.nextTicketCode(request.projectId()));
        ticket.setProjectId(request.projectId());
        ticket.setTitle(request.title());
        ticket.setDescription(sanitizer.sanitize(request.description()));
        ticket.setTaskTypeId(request.taskTypeId());
        ticket.setLevel(request.level());
        ticket.setOriginalLevel(request.level());
        ticket.setClientId(request.clientId());
        ticket.setClientContactId(request.clientContactId());
        // §4B.2 — derived, never taken from the request.
        ticket.setClientRaised(request.clientId() != null && request.clientContactId() != null);
        ticket.setAssignedTo(request.assigneeId());
        ticket.setEstimatedEffortHrs(request.estimatedHrs());
        ticket.setDateReported(Instant.now());
        ticket.setReportedBy(actorId(caller));

        // §7.5's four.
        ticket.setModuleId(request.moduleId());
        ticket.setScreenName(request.screenName());
        ticket.setFeature(request.feature());
        ticket.setStepsToGenerate(sanitizer.sanitize(request.stepsToGenerate()));

        ticket.setPlannedCloseDate(plannedCloseDate);

        // A project × task type with no rule and no default (TemplateResolver's
        // own §7.4 ladder) leaves the ticket with no template — the same
        // legacy/no-template state RibbonAssembler and SkipService.requireSkippable
        // already handle for a ticket that predates the workflow designer. Not a
        // rejection: a ticket must exist to be assigned a template in the first
        // place, per the masters/templates package's own ordering.
        templates.resolve(request.projectId(), request.taskTypeId()).ifPresent(templateId -> {
            ticket.setWorkflowTemplateId(templateId);
            List<WorkflowStage> stageSequence = stages.findByTemplateIdOrderBySeqAsc(templateId);
            if (!stageSequence.isEmpty()) {
                ticket.setCurrentStage(stageSequence.get(0).getStageCode());
            }
        });

        Ticket saved = repository.save(ticket);
        journal.append(created(saved, caller));
        // D-037 · after the history row, because the history is the record and
        // a mail is a courtesy — if the enqueue throws, the notifier swallows
        // it rather than costing somebody the ticket they just raised. Sends
        // nothing when the ticket was saved unassigned: §4B.6's row is
        // "created *and assigned*".
        notifier.createdAndAssigned(saved, actorIdOrZero(caller), request.assigneeId());
        return TicketWire.of(saved, users);
    }

    @Transactional
    public TicketWire.Ticket patch(Authentication caller, String ticketCode, TicketCreateDtos.PatchRequest request) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        modules.requireActive(request.moduleId());

        // Absent leaves the column alone; explicitly null clears it. A record
        // cannot tell the two apart, so a field is only written when the caller
        // sent something — which means clearing a module is a PATCH with an
        // explicit null and Jackson gives us null either way. The asymmetry is
        // documented on PatchRequest; the practical effect is that clearing
        // screenName and omitting it are the same request, and §7.5's fields are
        // all nullable so that is a correction, not a loss.
        changed(ticket.getTitle(), request.title(), "title", ticket, caller, ticket::setTitle);
        changedHtml(ticket.getDescription(), request.description(), "description", ticket, caller,
                ticket::setDescription);
        changed(str(ticket.getModuleId()), str(request.moduleId()), "moduleId", ticket, caller,
                v -> ticket.setModuleId(request.moduleId()));
        changed(ticket.getScreenName(), request.screenName(), "screenName", ticket, caller, ticket::setScreenName);
        changed(ticket.getFeature(), request.feature(), "feature", ticket, caller, ticket::setFeature);
        changedHtml(ticket.getStepsToGenerate(), request.stepsToGenerate(), "stepsToGenerate", ticket, caller,
                ticket::setStepsToGenerate);
        changed(str(ticket.getEstimatedEffortHrs()), str(request.estimatedHrs()), "estimatedHrs", ticket, caller,
                v -> ticket.setEstimatedEffortHrs(request.estimatedHrs()));

        return TicketWire.of(ticket, users);
    }

    /** A rich-text field: sanitise first, then compare — so a no-op edit of markup writes nothing. */
    private void changedHtml(String current, String submitted, String field, Ticket ticket,
                             Authentication caller, java.util.function.Consumer<String> apply) {
        changed(current, submitted == null ? null : sanitizer.sanitize(submitted), field, ticket, caller, apply);
    }

    private void changed(String current, String submitted, String field, Ticket ticket,
                         Authentication caller, java.util.function.Consumer<String> apply) {
        if (submitted == null || Objects.equals(current, submitted)) {
            return;
        }
        apply.accept(submitted);
        journal.append(fieldChanged(ticket, field, current, submitted, caller));
    }

    private Instant computedPlannedCloseDate(TicketCreateDtos.CreateRequest request) {
        return plannedCloseDates
                .preview(request.projectId(), request.taskTypeId(), request.level(), request.assigneeId(), null)
                .plannedCloseDate();
    }

    private static TicketHistory created(Ticket ticket, Authentication caller) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType("CREATED");
        stampActor(entry, caller);
        return entry;
    }

    private static TicketHistory fieldChanged(Ticket ticket, String field, String from, String to,
                                              Authentication caller) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType("FIELD_CHANGED");
        entry.setFieldName(field);
        entry.setOldValue(from);
        entry.setNewValue(to);
        stampActor(entry, caller);
        return entry;
    }

    private static void stampActor(TicketHistory entry, Authentication caller) {
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
    }

    /** The caller, or 0 when there is none — `dev-noauth` has no principal. */
    private static long actorIdOrZero(Authentication caller) {
        Long id = actorId(caller);
        return id == null ? 0L : id;
    }

    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller).flatMap(CallerIdentity::of).map(CallerIdentity::userId).orElse(null);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
