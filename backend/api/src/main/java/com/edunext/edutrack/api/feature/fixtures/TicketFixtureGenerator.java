package com.edunext.edutrack.api.feature.fixtures;

import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
import com.edunext.edutrack.domain.masters.TaskType;
import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * B-007 · walks 200 fixture tickets through their workflow templates, producing
 * a real journey per ticket — cycles, stage transitions, history, effort logs —
 * rather than a flat row of columns. This is the part of B-007 the whole team
 * is actually waiting on: Debashis's SLA scanner and Divyansh's ribbon both need
 * data shaped like the real system will eventually write it.
 *
 * <p>This class is only the loop. Each ticket's actual construction —
 * everything from allocating its code to sealing its final transition — is
 * {@link SingleTicketFixture#createTicket}, a <b>separate bean</b> called once
 * per iteration so each ticket gets its own {@code @Transactional} boundary.
 * See that class's javadoc for why the split is required and not just tidiness
 * — a self-invoked {@code @Transactional} method silently runs with no
 * transaction at all, which is exactly the failure {@code TicketCodeAllocator}'s
 * {@code Propagation.MANDATORY} exists to catch loudly instead of quietly.
 *
 * <h2>Every duration is real working time</h2>
 *
 * <p>Stage entry/exit instants come from {@link WorkingHoursService#addWorkingHours}
 * — the same B-024 service the SLA scanner and the transition service will call
 * — never from adding wall-clock hours. CLAUDE.md: "never write your own date
 * maths." A fixture that faked durations would teach D's scanner to expect
 * numbers the real system will never produce.
 *
 * <h2>Hash-chain columns are left NULL</h2>
 *
 * <p>{@code ticket_history}, {@code ticket_effort_logs} and
 * {@code ticket_stage_transitions} are hash-chained, but the algorithm does not
 * exist anywhere yet — {@code AppendOnlyImpl.insert} is bare {@code persist()},
 * and the actual chaining is Stream A's A-040/A-044, due week 8-9. Inventing a
 * hashing scheme here — in Sprint 0, weeks ahead of that work — risks writing
 * something Stream A then has to reconcile with or migrate away from. {@code
 * prevHash}/{@code rowHash} are left NULL on every fixture-inserted row; nothing
 * reads or verifies them today. <b>Once A-040/A-044 land, this corpus needs a
 * backfill (or a re-run of this loader) to bring those columns in line —
 * flagged here so it is not forgotten.</b>
 *
 * <h2>What "varied" means for 200 tickets</h2>
 *
 * <ul>
 *   <li>Every stage of every one of the 3 templates is visited — INTAKE-only
 *       tickets sit next to fully CLOSED ones.</li>
 *   <li>~14% get exactly one rework loop (iteration 2) at whichever reworkable
 *       stage their walk reaches first.</li>
 *   <li>~15% of CLOSED tickets get reopened into a second cycle.</li>
 *   <li>~20% are deliberately breached — reported 45-65 days back so their
 *       {@code sla_policies} resolution target is unambiguously in the past —
 *       so D's scanner has real cases without waiting on D-020 to exist yet.</li>
 *   <li>~40% carry client attribution.</li>
 * </ul>
 */
@Component
@Profile("fixtures")
class TicketFixtureGenerator {

    private static final int TOTAL_TICKETS = 200;

    /** Fixed, not {@code new Random()} — a re-run of this loader (after a DB reset) produces the same corpus. */
    private static final long SEED = 2026_08_07L;

    private final TaskTypeRepository taskTypeRepository;
    private final PriorityRepository priorityRepository;
    private final SingleTicketFixture singleTicketFixture;

    TicketFixtureGenerator(TaskTypeRepository taskTypeRepository, PriorityRepository priorityRepository,
                           SingleTicketFixture singleTicketFixture) {
        this.taskTypeRepository = taskTypeRepository;
        this.priorityRepository = priorityRepository;
        this.singleTicketFixture = singleTicketFixture;
    }

    void generate(FixtureContext ctx) {
        Random random = new Random(SEED);
        List<TaskType> taskTypes = taskTypeRepository.findByIsActiveTrueOrderBySeqAsc();
        Map<String, Priority> priorityByCode = new LinkedHashMap<>();
        for (Priority p : priorityRepository.findByIsActiveTrueOrderBySeqAsc()) {
            priorityByCode.put(p.getCode(), p);
        }
        Instant now = Instant.now();

        for (int i = 0; i < TOTAL_TICKETS; i++) {
            FixtureContext.ProjectRef project = ctx.projects().get(i % ctx.projects().size());
            TaskType taskType = taskTypes.get(random.nextInt(taskTypes.size()));
            // ~1 in 5, spread deterministically rather than by chance, so the
            // corpus always contains exactly the promised proportion.
            boolean forceBreach = i % 5 == 3;
            boolean forceRework = i % 7 == 0;
            singleTicketFixture.createTicket(ctx, project, taskType, priorityByCode, random, now, forceBreach,
                    forceRework);
        }
    }
}
