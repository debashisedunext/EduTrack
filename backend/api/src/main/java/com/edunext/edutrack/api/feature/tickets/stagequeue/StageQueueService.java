package com.edunext.edutrack.api.feature.tickets.stagequeue;

import com.edunext.edutrack.api.feature.tickets.list.TicketListRefs;
import com.edunext.edutrack.api.feature.tickets.list.TicketListService;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * C-062 - {@code GET /stages/queue}, S-31's "Waiting in QA".
 *
 * <h2>Why this class did not exist</h2>
 *
 * <p>{@link StageQueueDtos} and {@link StageQueueSpecs} were written, and the
 * two things that actually serve a request - this and
 * {@link StageQueueController} - were not, so the route 404'd while every
 * other part of the feature looked finished. {@code StageQueuePage}'s own
 * header said so in as many words ("is in the contract and in the mock; no
 * controller serves it"), and the screen worked in development because D-004's
 * mock answered it. The 404 only appears against a real backend.
 *
 * <h2>{@code queuePage}, not {@code page}</h2>
 *
 * <p>The read goes through {@link ScopedTickets#queuePage}, which applies
 * {@code ScopeResolver.stageQueueScope} - project membership rather than
 * section 10.2's {@code assigned_to = me}. That widening is the point of the
 * screen: a queue of work nobody has picked up yet cannot be scoped to the
 * person it is not yet assigned to, or it would always be empty. It is safe
 * only because {@link StageQueueSpecs} pins the stage and excludes closed
 * tickets unconditionally - see that class on why those two are scope rather
 * than filter.
 *
 * <h2>Working minutes, and one lookup per template rather than per row</h2>
 *
 * <p>{@code timeInStageMins} is working minutes against the org calendar and
 * the project's holidays (CLAUDE.md's rule; a Friday-18:00 handoff has not
 * been waiting three days on Monday morning). {@code stageSlaBreached} needs
 * {@code workflow_stages.sla_hours} for the stage <em>on the ticket's own
 * template</em>, and a page holds at most a handful of distinct templates - so
 * the SLA is resolved once per template and cached for the page, on
 * {@link TicketListRefs}' own reasoning for resolving references per page
 * rather than per row.
 */
@Service
public class StageQueueService {

    private final ScopedTickets tickets;
    private final TicketListRefs refs;
    private final WorkflowStageRepository stages;
    private final WorkingHoursService workingHours;
    private final Clock clock;

    /*
     * @Autowired is required rather than decoration - TransitionService's
     * identical note. Two constructors leaves Spring no candidate to prefer.
     */
    @Autowired
    StageQueueService(ScopedTickets tickets, TicketListRefs refs, WorkflowStageRepository stages,
                      WorkingHoursService workingHours) {
        this(tickets, refs, stages, workingHours, Clock.systemUTC());
    }

    StageQueueService(ScopedTickets tickets, TicketListRefs refs, WorkflowStageRepository stages,
                      WorkingHoursService workingHours, Clock clock) {
        this.tickets = tickets;
        this.refs = refs;
        this.stages = stages;
        this.workingHours = workingHours;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CursorPage<StageQueueDtos.QueueRow> queue(Authentication caller, String stage, Long projectId,
                                              Boolean unassignedOnly, String rawCursor, Integer rawLimit) {
        String stageCode = normalize(stage);
        int limit = PageLimit.clamp(rawLimit);
        Cursor cursor = Cursor.decode(rawCursor);

        Specification<Ticket> criteria = Specification.allOf(
                StageQueueSpecs.filters(stageCode, projectId, unassignedOnly),
                StageQueueSpecs.after(cursor));

        // Page 0 with size limit+1: a LIMIT with no OFFSET, exactly as
        // TicketListService does it - the cursor predicate is what moves the
        // window, so this never pages by counting.
        List<Ticket> fetched = tickets
                .queuePage(caller, criteria, PageRequest.of(0, PageLimit.fetchSize(limit), StageQueueSpecs.SORT))
                .getContent();

        CursorPage<Ticket> page = CursorPage.of(fetched, limit, StageQueueService::cursorFor);

        // Resolved once for the page - page.data() has already dropped the
        // limit+1 probe row, so the extra fetch costs no lookup.
        TicketListRefs.Resolved resolved = refs.resolve(page.data());
        Map<Long, Optional<BigDecimal>> slaByTemplate = new HashMap<>();
        Instant now = Instant.now(clock);

        List<StageQueueDtos.QueueRow> rows = page.data().stream()
                .map(t -> toRow(t, resolved, stageCode, now, slaByTemplate))
                .toList();

        return new CursorPage<>(rows, page.meta());
    }

    /**
     * The keyset cursor names the row by the column it was sorted on -
     * {@code stageEnteredAt}, which {@link StageQueueSpecs#after} parses back
     * with {@code Instant.parse}.
     *
     * <p>A ticket with no {@code stage_entered_at} would encode the string
     * "null" and be read back as "start from the beginning", quietly repeating
     * the first page forever. The column is stamped by the genesis hop at
     * creation and restamped by every transition, so this is unreachable -
     * {@link Instant#EPOCH} rather than {@code null} keeps it that way if a row
     * ever escapes with one missing, since a ticket with no recorded entry time
     * sorts as the oldest thing in the queue, which is also how {@link #toRow}
     * reads it.
     */
    private static Cursor cursorFor(Ticket ticket) {
        Instant enteredAt = ticket.getStageEnteredAt();
        return new Cursor((enteredAt == null ? Instant.EPOCH : enteredAt).toString(), ticket.getId());
    }

    private StageQueueDtos.QueueRow toRow(Ticket ticket, TicketListRefs.Resolved resolved, String stageCode,
                                          Instant now, Map<Long, Optional<BigDecimal>> slaByTemplate) {
        Instant enteredAt = ticket.getStageEnteredAt();
        long minutes = enteredAt == null ? 0L : workingMinutesSince(ticket, enteredAt, now);
        Optional<BigDecimal> slaHours = slaFor(ticket, stageCode, slaByTemplate);

        return new StageQueueDtos.QueueRow(
                TicketListService.toSummary(ticket, resolved),
                enteredAt,
                minutes,
                isBreached(slaHours, minutes));
    }

    /**
     * Working minutes between the two instants, capped at wall-clock -
     * {@code TransitionService.workingMinutesFor}'s own arithmetic, and the cap
     * is there for its reason: the calendar service is configuration, and a
     * working day configured longer than a real one would otherwise report a
     * ticket as having waited more hours than have actually passed.
     *
     * <p>Never negative. Clock skew, or a {@code stage_entered_at} stamped a
     * few microseconds ahead by a transaction committing across a boundary,
     * would otherwise render a ticket as having waited minus two minutes.
     */
    private long workingMinutesSince(Ticket ticket, Instant enteredAt, Instant now) {
        if (!now.isAfter(enteredAt)) {
            return 0L;
        }
        BigDecimal workingHrs = workingHours.workingHoursBetween(
                enteredAt, now, ticket.getProjectId(), ticket.getAssignedTo());
        long workingMins = workingHrs.multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        long wallClockMins = Duration.between(enteredAt, now).toMinutes();
        return Math.max(0L, Math.min(workingMins, wallClockMins));
    }

    /**
     * {@code sla_hours} for this stage on this ticket's template, resolved once
     * per template for the whole page.
     *
     * <p>{@code null} for a ticket with no template - one predating B-043's
     * designer, which {@code RibbonAssembler} already answers with an empty
     * ribbon - and for a stage the template does not declare. Both read as
     * "no SLA", which {@link #isBreached} treats as never breached.
     *
     * <p><b>The cache holds an {@link Optional}, and that is not decoration.</b>
     * {@code computeIfAbsent} does not store a mapping whose function returned
     * {@code null}, so caching the {@code BigDecimal} directly missed on every
     * single row for a stage that declares no SLA - which the seed's own note
     * says is most of them. The common case was the one that degraded into the
     * per-row query this cache exists to prevent, and it did so silently,
     * because the answer was right either way. {@code Optional} makes "looked,
     * found nothing" a value the map can hold.
     */
    private Optional<BigDecimal> slaFor(Ticket ticket, String stageCode, Map<Long, Optional<BigDecimal>> cache) {
        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId == null) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(templateId, id -> stages.findByTemplateIdAndStageCode(id, stageCode)
                .map(WorkflowStage::getSlaHours));
    }

    /**
     * <b>A stage with no {@code sla_hours} is never breached, not always
     * breached</b> - {@link StageQueueDtos}' own note, and most stages declare
     * none per the seed's. Comparing against a null read as zero would paint
     * the whole queue red on the day it shipped, which is the same as painting
     * none of it.
     */
    private static boolean isBreached(Optional<BigDecimal> slaHours, long timeInStageMins) {
        return slaHours.filter(hours -> hours.signum() > 0)
                .map(hours -> hours.multiply(BigDecimal.valueOf(60))
                        .setScale(0, RoundingMode.HALF_UP).longValue())
                .map(slaMins -> timeInStageMins > slaMins)
                .orElse(false);
    }

    /**
     * Stage codes are compared uppercase, {@code ReworkService}'s own reason: a
     * code arriving lower-case from a hand-built URL would otherwise match no
     * row and answer an empty queue, which reads as "nothing is waiting"
     * rather than as a typo.
     */
    private static String normalize(String stage) {
        return stage == null ? null : stage.trim().toUpperCase(Locale.ROOT);
    }
}
