import { http } from 'msw';
import type { Db, ObClient, ObJourney, ObProjectRow } from '../db';
import { getDb } from '../db';
import {
  currentUser, noContent, notFound, ok, paginate, problem, url, userRef, validationFailed,
} from './util';
import { criticalPathDays } from './onboardingJourneys';
import { publishedStages, stageOfStep } from './obStageFold';

/**
 * `/onboarding/projects` — the grid, the create, the header and the edit.
 *
 * <h2>The two computed columns are computed here, in working days</h2>
 *
 * `delayedByDays` and `tentativeCompletion` are the server's working-calendar
 * arithmetic, and this mock reproduces the *shape* of both rather than their
 * exactness: Saturdays and Sundays are skipped, org holidays are not, because
 * the mock has no holiday table and inventing one would make the fixtures
 * disagree with `WorkingHoursService` in a way nobody could reason about. What
 * matters for a screen built against this is that a delay is a positive integer
 * or **null**, never zero, and that a locked project reports neither.
 *
 * <h2>Stages fold onto the implementation stage, as the server's roll-up does</h2>
 *
 * A project boarded through two module services has two "Configuration" groups;
 * counted separately a six-stage master would report twelve. The fold is on
 * `implementationStageId`, with the group id as a negative fallback — the same
 * key the SQL builds.
 */

type ProjectStatus = ObProjectRow['status'];

const SETTLED_STEP_STATUSES = new Set(['DONE', 'SKIPPED']);
const RUNNING_STEP_STATUSES = new Set(['IN_PROGRESS', 'WAITING_ON_CLIENT']);
/** The three a task is still owed on. `WAITING_ON_CLIENT` is absent: that clock is paused. */
const OWED_STEP_STATUSES = new Set(['PENDING', 'IN_PROGRESS', 'BLOCKED']);

function clientOf(db: Db, project: ObProjectRow): ObClient | undefined {
  return db.obClients.find((c) => c.id === project.obClientId);
}

function journeysOf(db: Db, project: ObProjectRow): ObJourney[] {
  const client = clientOf(db, project);
  if (!client) return [];
  return client.journeys.filter(
    (j) => j.productId === project.productId && j.archivedAt == null,
  );
}

/** One stage of one project, folded across the project's module services. */
interface StageRollup {
  stageKey: number;
  name: string;
  sequence: number;
  taskCount: number;
  tasksOutstanding: number;
  minActiveSequence: number | null;
}

/**
 * The stage roll-up over a given set of journeys.
 *
 * Called twice: once with every journey of the project, which is the folded
 * roll-up the header reads, and once per journey for the tree's own branch.
 * The server does the same thing from one query grouped by journey — see
 * `STAGE_ROLLUP` — and this mirrors the *result* rather than the SQL, which is
 * all a caller can tell apart.
 */
function stagesOfJourneys(db: Db, journeys: ObJourney[]): StageRollup[] {
  const byKey = new Map<number, StageRollup>();

  for (const journey of journeys) {
    /*
      Seeded from the template's published stages, so a stage that holds no
      task still answers with `taskCount: 0`. Driving from the tasks — which
      this used to do — made a published-but-unscheduled stage indistinguishable
      from one that does not exist, and the ribbon a different length for every
      module service.
    */
    for (const stage of publishedStages(db, journey)) {
      if (!byKey.has(stage.key)) {
        byKey.set(stage.key, {
          stageKey: stage.key,
          name: stage.name,
          sequence: stage.sequence,
          taskCount: 0,
          tasksOutstanding: 0,
          minActiveSequence: null,
        });
      }
    }

    for (const step of journey.steps) {
      const stage = stageOfStep(db, journey, step);
      const existing = byKey.get(stage.key) ?? {
        stageKey: stage.key,
        name: stage.name,
        sequence: stage.sequence,
        taskCount: 0,
        tasksOutstanding: 0,
        minActiveSequence: null,
      };
      existing.taskCount += 1;
      if (!SETTLED_STEP_STATUSES.has(step.status)) existing.tasksOutstanding += 1;
      if (RUNNING_STEP_STATUSES.has(step.status)) {
        existing.minActiveSequence =
          existing.minActiveSequence == null
            ? step.sequence
            : Math.min(existing.minActiveSequence, step.sequence);
      }
      byKey.set(stage.key, existing);
    }
  }

  return [...byKey.values()].sort(
    (a, b) => a.sequence - b.sequence || a.stageKey - b.stageKey,
  );
}

/** The whole project's roll-up — every journey folded onto one set of stages. */
function stagesOf(db: Db, project: ObProjectRow): StageRollup[] {
  return stagesOfJourneys(db, journeysOf(db, project));
}

/**
 * Roll-up rows as the contract's `ObProjectStage`.
 *
 * `isCurrent` is resolved within whatever set was passed, which is what makes
 * this usable for both readings: over the project it names the project's
 * running stage, and over one journey it names that service's.
 */
function stageDtos(stages: StageRollup[]) {
  const earliestActive = stages
    .map((s) => s.minActiveSequence)
    .filter((s): s is number => s != null)
    .sort((a, b) => a - b)[0];

  return stages.map((s) => ({
    stageKey: s.stageKey,
    name: s.name,
    sequence: s.sequence,
    taskCount: s.taskCount,
    tasksOutstanding: s.tasksOutstanding,
    // An empty stage has nothing outstanding either, so `taskCount > 0` is what
    // separates "finished" from "never set up".
    isComplete: s.taskCount > 0 && s.tasksOutstanding === 0,
    isCurrent: earliestActive != null && s.minActiveSequence === earliestActive,
  }));
}

function gateStatusOfProject(db: Db, project: ObProjectRow): 'OPEN' | 'LOCKED' {
  const journeys = journeysOf(db, project);
  return journeys.some((j) => j.gateStatus === 'OPEN') ? 'OPEN' : 'LOCKED';
}

/**
 * How long this project **takes**, in working days — `ObProjectTatPath` and
 * `JourneyTatCalculator` folded together, mirroring the Java exactly.
 *
 * <p>It was Σ over every task of every journey, which overstated a project the
 * moment anything ran alongside anything else: two four-day services waiting on
 * nothing reported eight days and put the tentative completion a week late.
 *
 * <p>Two levels, both of them "a dependency adds, a parallel branch does not":
 * inside a service, the critical path through its tasks; across services, the
 * heaviest chain through the templates' own `dependsOnTemplateIds`. A
 * dependency on a service this client did not buy holds nothing up.
 */
function totalTatDaysOf(db: Db, project: ObProjectRow): number {
  const journeys = journeysOf(db, project);

  const ownDays = new Map(journeys.map((j) => [j.id, criticalPathDays(j.steps)] as const));

  const waitsOn = new Map(
    journeys.map((journey) => {
      const template = db.obJourneyTemplates.find((t) => t.id === journey.templateId);
      const ids = (template?.dependsOnTemplateIds ?? [])
        .map((templateId) => journeys.find((o) => o.templateId === templateId && o.id !== journey.id))
        .filter((o): o is ObJourney => o != null)
        .map((o) => o.id);
      return [journey.id, ids] as const;
    }),
  );

  /*
    Longest path, memoised, with the same cycle guard the Java keeps: a graph
    that should be acyclic but is not must still return rather than hang.
  */
  const cost = new Map<number, number>();
  const walk = (id: number, visiting: Set<number>): number => {
    const cached = cost.get(id);
    if (cached != null) return cached;
    if (visiting.has(id) || !ownDays.has(id)) return 0;

    visiting.add(id);
    let heaviest = 0;
    for (const dependency of waitsOn.get(id) ?? []) {
      heaviest = Math.max(heaviest, walk(dependency, visiting));
    }
    visiting.delete(id);

    const total = (ownDays.get(id) ?? 0) + heaviest;
    cost.set(id, total);
    return total;
  };

  let longest = 0;
  for (const journey of journeys) longest = Math.max(longest, walk(journey.id, new Set()));
  return longest;
}

/** The earliest due date this project is already past, or null. */
function earliestOverdue(db: Db, project: ObProjectRow, now: Date): string | null {
  let earliest: string | null = null;
  for (const journey of journeysOf(db, project)) {
    if (journey.gateStatus === 'LOCKED' || journey.completedAt != null) continue;
    // The mock has no `releasedAt`: a held journey clears `heldByJourneyId`
    // outright when its holder completes, so the one column says both things.
    if (journey.heldByJourneyId != null) continue;
    for (const step of journey.steps) {
      if (!OWED_STEP_STATUSES.has(step.status) || !step.dueAt) continue;
      if (new Date(step.dueAt) >= now) continue;
      if (earliest == null || step.dueAt < earliest) earliest = step.dueAt;
    }
  }
  return earliest;
}

/** Whole working days between two instants, weekends excluded, rounded up. */
function workingDaysBetween(from: Date, to: Date): number {
  if (to <= from) return 0;
  let days = 0;
  const cursor = new Date(from);
  cursor.setUTCHours(0, 0, 0, 0);
  const end = new Date(to);
  end.setUTCHours(0, 0, 0, 0);
  while (cursor < end) {
    cursor.setUTCDate(cursor.getUTCDate() + 1);
    const day = cursor.getUTCDay();
    if (day !== 0 && day !== 6) days += 1;
  }
  // Ceiling: a task due Friday and open a minute into Monday has accrued a
  // fraction of a working day, and reporting zero would agree with a naive
  // calendar subtraction for exactly the case that subtraction gets wrong.
  return Math.max(days, to > from ? 1 : 0);
}

/** `startDate` plus N working days, as a plain date. */
function addWorkingDays(startDate: string, days: number): string | null {
  if (days <= 0) return null;
  const cursor = new Date(`${startDate}T00:00:00.000Z`);
  if (Number.isNaN(cursor.getTime())) return null;
  let remaining = days;
  while (remaining > 0) {
    cursor.setUTCDate(cursor.getUTCDate() + 1);
    const day = cursor.getUTCDay();
    if (day !== 0 && day !== 6) remaining -= 1;
  }
  return cursor.toISOString().slice(0, 10);
}

function projectDto(project: ObProjectRow, db: Db, now: Date) {
  const client = clientOf(db, project);
  const product = db.obProducts.find((p) => p.id === project.productId);
  const stages = stagesOf(db, project);
  const gateStatus = gateStatusOfProject(db, project);
  const totalTatDays = totalTatDaysOf(db, project);

  const overdueAt = project.status === 'RUNNING' ? earliestOverdue(db, project, now) : null;
  const delayedByDays = overdueAt ? workingDaysBetween(new Date(overdueAt), now) || null : null;
  const current = stages
    .filter((s) => s.minActiveSequence != null)
    .sort((a, b) => (a.minActiveSequence ?? 0) - (b.minActiveSequence ?? 0))[0];

  return {
    id: project.id,
    name: project.name,
    client: {
      id: client?.id ?? project.obClientId,
      name: client?.name ?? `Client ${project.obClientId}`,
      clientCode: client?.clientCode ?? null,
      city: client?.city ?? null,
    },
    product: product
      ? { id: product.id, code: product.code, name: product.name }
      : { id: project.productId, code: 'UNKNOWN', name: `Product ${project.productId}` },
    startDate: project.startDate,
    salesPerson: userRef(project.salesPersonId, db),
    implementor: userRef(project.implementorUserId, db),
    implementorManager: userRef(project.implementorManagerUserId, db),
    status: project.status,
    gateStatus,
    currentStage: current?.name ?? null,
    // `taskCount > 0` as well: an empty stage has no outstanding work
    // either, and counting it complete would report six of seven stages done
    // on a project where one was finished and five were never set up.
    stagesComplete: stages.filter((s) => s.taskCount > 0 && s.tasksOutstanding === 0).length,
    stagesTotal: stages.length,
    journeyCount: journeysOf(db, project).length,
    delayedByDays,
    tentativeCompletion: addWorkingDays(project.startDate, totalTatDays),
    totalTatDays,
  };
}

function projectDetailDto(project: ObProjectRow, db: Db, now: Date) {
  return {
    ...projectDto(project, db, now),
    statusReason: project.statusReason,
    stages: stageDtos(stagesOf(db, project)),
    moduleServices: journeysOf(db, project).map((j) => {
      const template = db.obJourneyTemplates.find((t) => t.id === j.templateId);
      return {
        journeyId: j.id,
        templateId: j.templateId ?? 0,
        // The mock keeps no pinned name on the journey — it resolves through
        // the template, exactly as `onboarding.ts`'s own journey DTO does.
        serviceName: template?.name ?? 'Onboarding',
        gateStatus: j.gateStatus,
        isComplete: j.completedAt != null,
        // This service's own stages, unfolded. Summed across the services they
        // reproduce `stages` above, which is the property the server gets from
        // grouping one query by journey and folding it back.
        stages: stageDtos(stagesOfJourneys(db, [j])),
      };
    }),
    createdBy: userRef(project.createdById, db),
    createdAt: project.createdAt,
  };
}

/**
 * The three ways a task belongs to somebody — owner, backup owner, or
 * inherited from the project's implementor where nobody was pinned.
 *
 * Mirrored from the server's own predicate rather than simplified: the
 * inherited case is the rule `ObJourneyStepLifecycle` authorises by, so a
 * queue that dropped it would show fewer tasks than the person can actually
 * act on, and the screen built against this mock would look right and be
 * wrong on the first real login.
 */
function belongsTo(step: ObJourney['steps'][number], project: ObProjectRow, userId: number): boolean {
  if (step.ownerUserId === userId || step.backupOwnerUserId === userId) return true;
  return (
    step.ownerUserId == null &&
    step.backupOwnerUserId == null &&
    project.implementorUserId === userId
  );
}

const OPEN_STEP_STATUSES = new Set(['PENDING', 'IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT']);

/** Null due dates sort last — see the endpoint's own note on why. */
const NO_DUE_DATE = '9999-12-31T00:00:00.000Z';

/**
 * Every task of one user, whatever its state — the shape both My Tasks reads
 * project from.
 *
 * Built once rather than twice because the two reads differ only in what they
 * filter out, and two constructions of one row is how a list and the page it
 * opens onto come to disagree about a due date.
 */
function myTaskRows(db: Db, userId: number, now: Date) {
  return db.obProjects
    .flatMap((project) =>
      journeysOf(db, project)
        .flatMap((journey) =>
          journey.steps
            .filter((step) => belongsTo(step, project, userId))
            .map((step) => {
              const client = clientOf(db, project);
              const stage = stageOfStep(db, journey, step);
              const template = db.obJourneyTemplates.find((t) => t.id === journey.templateId);
              const dueAt = step.dueAt ?? null;
              return {
                projectStatus: project.status,
                taskId: step.id,
                taskName: step.name,
                status: step.status,
                dueAt,
                isOverdue: dueAt != null && new Date(dueAt) < now,
                projectId: project.id,
                projectName: project.name,
                obClientId: project.obClientId,
                obClientName: client?.name ?? `Client ${project.obClientId}`,
                obClientCode: client?.clientCode ?? null,
                journeyId: journey.id,
                serviceName: template?.name ?? 'Onboarding',
                stepKey: stage.key,
                stepName: stage.name,
                stepSequence: stage.sequence,
              };
            }),
        ),
    )
    .sort(
      (a, b) =>
        (a.dueAt ?? NO_DUE_DATE).localeCompare(b.dueAt ?? NO_DUE_DATE) || a.taskId - b.taskId,
    );
}

/** `projectStatus` is the list's own filter and is not on the contract's row. */
function myTaskDto(row: ReturnType<typeof myTaskRows>[number]) {
  const { projectStatus, ...dto } = row;
  void projectStatus;
  return dto;
}

export const onboardingProjectHandlers = [
  /**
   * `/onboarding/my-tasks` — the implementor's own queue.
   *
   * The caller is the filter and there is no parameter to say otherwise, which
   * is the endpoint's whole security model: mirrored here so a screen built
   * against the mock cannot come to rely on one.
   */
  http.get(url('/onboarding/my-tasks'), ({ request }) => {
    const db = getDb();
    const now = new Date();

    const rows = myTaskRows(db, currentUser(db).id, now)
      .filter((row) => OPEN_STEP_STATUSES.has(row.status))
      // Stopped on purpose — a dropped or held project is not work anybody is
      // waiting on, so listing its tasks would ask for what was called off.
      .filter((row) => row.projectStatus !== 'ON_HOLD' && row.projectStatus !== 'DROPPED')
      .map(myTaskDto);

    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),

  /**
   * One task of the caller's.
   *
   * Neither filter above applies: this answers "show me this one", and a task
   * just completed is still theirs to look at. A task belonging to somebody
   * else is a **404**, never a 403 — the id would otherwise confirm the task
   * exists, and these ids are sequential.
   */
  http.get(url('/onboarding/my-tasks/:taskId'), ({ params }) => {
    const db = getDb();
    const taskId = Number(params.taskId);
    const row = myTaskRows(db, currentUser(db).id, new Date())
      .find((candidate) => candidate.taskId === taskId);
    return row ? ok(myTaskDto(row)) : notFound('Task');
  }),

  http.get(url('/onboarding/projects'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url);
    const now = new Date();

    // Newest created first, which for an auto-increment id is descending id —
    // the same order the server's keyset uses, and why the cursor is that id.
    let rows = [...db.obProjects].sort((a, b) => b.id - a.id);

    const term = q.searchParams.get('q');
    if (term) {
      const needle = term.toLowerCase();
      rows = rows.filter((p) => {
        const client = clientOf(db, p);
        return (
          p.name.toLowerCase().includes(needle) ||
          (client?.name ?? '').toLowerCase().includes(needle)
        );
      });
    }
    const clientId = q.searchParams.get('clientId');
    if (clientId) rows = rows.filter((p) => p.obClientId === Number(clientId));
    const productId = q.searchParams.get('productId');
    if (productId) rows = rows.filter((p) => p.productId === Number(productId));
    const status = q.searchParams.get('status');
    if (status) rows = rows.filter((p) => p.status === status);
    const implementorId = q.searchParams.get('implementorId');
    if (implementorId) rows = rows.filter((p) => p.implementorUserId === Number(implementorId));
    const salesPersonId = q.searchParams.get('salesPersonId');
    if (salesPersonId) rows = rows.filter((p) => p.salesPersonId === Number(salesPersonId));

    const { page, meta } = paginate(rows, q);
    return ok(page.map((p) => projectDto(p, db, now)), meta);
  }),

  http.post(url('/onboarding/projects'), async ({ request }) => {
    const db = getDb();
    const now = new Date();
    const body = (await request.json()) as {
      name?: string;
      clientId?: number;
      productId?: number;
      startDate?: string;
      salesPersonId?: number | null;
      implementorUserId?: number | null;
      implementorManagerUserId?: number | null;
      moduleServiceIds?: number[];
    };

    const errors: Record<string, string[]> = {};
    if (!body.name?.trim()) errors.name = ['Give the project a name.'];
    if (body.clientId == null) errors.clientId = ['Choose a client.'];
    if (body.productId == null) errors.productId = ['Choose the product bought.'];
    if (!body.startDate) errors.startDate = ['Give the project a start date.'];
    // Required on create and nullable on the update, exactly as
    // `ObProjectCreateRequest` has it — an implementor is what an ownerless
    // task falls to, so a project cannot be born without one.
    if (body.salesPersonId == null) {
      errors.salesPersonId = ['Choose the sales person who owns this project.'];
    }
    if (body.implementorUserId == null) {
      errors.implementorUserId = ['Choose the implementor. Tasks with no responsible fall to them.'];
    }
    if (body.implementorManagerUserId == null) {
      errors.implementorManagerUserId = [
        'Choose the implementor manager this project escalates to.',
      ];
    }
    if (!body.moduleServiceIds?.length) {
      errors.moduleServiceIds = ['Keep at least one module service.'];
    }
    if (Object.keys(errors).length) return validationFailed(errors);

    const client = db.obClients.find((c) => c.id === body.clientId);
    if (!client) return validationFailed({ clientId: ['No such client.'] });
    const product = db.obProducts.find((p) => p.id === body.productId);
    if (!product) return validationFailed({ productId: ['No such product.'] });
    if (!product.isActive) {
      return validationFailed({
        productId: [`${product.name} is retired and can no longer be sold.`],
      });
    }

    const existing = db.obProjects.find(
      (p) => p.obClientId === body.clientId && p.productId === body.productId,
    );
    if (existing) {
      return problem(409, 'ob-project-duplicate',
        'Already a project for that product', {
          detail: `This client already has the project "${existing.name}" for that product`,
          existingProjectId: existing.id,
        });
    }

    // The active services of this product, which is what the form offered.
    const active = db.obJourneyTemplates.filter(
      (t) => t.productId === body.productId && t.isActive,
    );
    for (const templateId of body.moduleServiceIds!) {
      if (!active.some((t) => t.id === templateId)) {
        return problem(409, 'ob-project-unknown-module-service',
          'That module service is no longer available', {
            detail: `module service ${templateId} is not an active service of product ${body.productId}`,
            templateId,
          });
      }
    }

    /*
      The gate. Journeys instantiate LOCKED and only the prerequisite checklist
      opens them, so a project created with no published master would hold
      journeys nothing could ever start — refused, exactly as the server does,
      and only when this client has no checklist of their own yet.
    */
    const hasChecklist = db.obClientPrereqs.some((p) => p.obClientId === client.id);
    if (!hasChecklist && !db.obPrereqVersions.some((v) => v.isActive)) {
      return problem(422, 'ob-client-no-prereq-master',
        'No prerequisites are published', {
          detail: 'Publish a prerequisite master first, or this project’s journeys could never start.',
        });
    }

    // The purchase row, first — journey instantiation refuses a product the
    // client has not bought, and a project without it would 404 its own ribbon.
    if (!client.applications.some((a) => a.productId === body.productId)) {
      const applicationId =
        Math.max(0, ...db.obClients.flatMap((c) => c.applications.map((a) => a.id))) + 1;
      client.applications.push({
        id: applicationId,
        productId: body.productId!,
        licenseType: null,
        units: null,
        licenseStart: null,
        licenseEnd: null,
      });
    }

    const project: ObProjectRow = {
      id: Math.max(0, ...db.obProjects.map((p) => p.id)) + 1,
      obClientId: body.clientId!,
      productId: body.productId!,
      name: body.name!.trim(),
      startDate: body.startDate!,
      salesPersonId: body.salesPersonId ?? null,
      implementorUserId: body.implementorUserId ?? null,
      implementorManagerUserId: body.implementorManagerUserId ?? null,
      status: 'RUNNING',
      statusReason: null,
      createdById: 1,
      createdAt: now.toISOString(),
    };
    db.obProjects.push(project);

    // Who a task with nobody pinned to it goes to — the project's implementor,
    // then whoever created the project. Resolved once, like the service does.
    const defaultImplementor = project.implementorUserId ?? project.createdById ?? null;

    // One journey per checked service, cloned from the template's tasks. Born
    // LOCKED unless this client's gate is already open, which is plan §5.3's
    // "products bought after gate-open instantiate directly OPEN".
    const gateAlreadyOpen = client.journeys.some((j) => j.gateStatus === 'OPEN');
    let journeyId = Math.max(0, ...db.obClients.flatMap((c) => c.journeys.map((j) => j.id)));
    let stepId = Math.max(
      0,
      ...db.obClients.flatMap((c) => c.journeys.flatMap((j) => j.steps.map((s) => s.id))),
    );

    for (const template of active
      .filter((t) => body.moduleServiceIds!.includes(t.id))
      .sort((a, b) => a.sequence - b.sequence || a.id - b.id)) {
      const templateSteps = db.obJourneyTemplateSteps
        .filter((s) => s.templateId === template.id)
        .sort((a, b) => a.sequence - b.sequence || a.id - b.id);

      client.journeys.push({
        id: ++journeyId,
        productId: template.productId,
        templateId: template.id,
        templateVersion: template.version,
        gateStatus: gateAlreadyOpen ? 'OPEN' : 'LOCKED',
        heldByJourneyId: null,
        startedAt: null,
        completedAt: null,
        archivedAt: null,
        steps: templateSteps.map((t) => ({
          id: ++stepId,
          templateStepId: t.id,
          sequence: t.sequence,
          name: t.name,
          description: t.description ?? null,
          tatDays: t.tatDays,
          // Nothing has been worked yet, and the ribbon divides by the budget
          // rather than by this — zero is the honest starting value.
          usedHours: 0,
          /*
            The template's pinned implementor, or this project's own when the
            task names nobody — `ObJourneyInstantiationService#cloneSteps`'s
            rule, mirrored so the mocked app behaves like the real one. A task
            with no owner is not unassigned; it goes to whoever is running the
            project.

            No backup is seeded: the template no longer carries one. A live
            journey step still has the field, set per client.
          */
          ownerUserId: t.ownerUserId ?? defaultImplementor,
          backupOwnerUserId: null,
          requiresSignoff: t.requiresSignoff,
          dependsOnStepId: null,
          status: 'PENDING',
          blockedReasonCode: null,
          blockedNote: null,
          skipReason: null,
          startedAt: null,
          finishedAt: null,
          dueAt: null,
          items: [],
        })),
      });
    }

    return ok(projectDetailDto(project, db, now), undefined, { status: 201 });
  }),

  http.get(url('/onboarding/projects/:obProjectId'), ({ params }) => {
    const db = getDb();
    const project = db.obProjects.find((p) => p.id === Number(params.obProjectId));
    return project ? ok(projectDetailDto(project, db, new Date())) : notFound('Project');
  }),

  /*
    Delete, and the seven things that refuse it.

    The server asks nine tables; the mock has fewer of them modelled, so it
    asks what it has — history, clock events, communications, sign-offs,
    escalations, attachments and notifications — over the project's live
    journeys' steps. The shape is what a screen is built against: a `409` with
    a `blockers` array, or a `204`.
  */
  http.delete(url('/onboarding/projects/:obProjectId'), ({ params }) => {
    const db = getDb();
    const id = Number(params.obProjectId);
    const project = db.obProjects.find((p) => p.id === id);
    if (!project) return notFound('Project');

    const client = clientOf(db, project);
    const journeys = journeysOf(db, project);
    const stepIds = new Set(journeys.flatMap((j) => j.steps.map((s) => s.id)));
    const blockers: string[] = [];

    if (journeys.some((j) => j.steps.some((s) => (s.history ?? []).length > 0))) {
      blockers.push('recorded step history');
    }
    if (journeys.some((j) => j.steps.some((s) => s.startedAt != null || (s.usedHours ?? 0) > 0))) {
      blockers.push('time logged against its steps');
    }
    if (db.obSignoffs.some((g) => g.stepId != null && stepIds.has(g.stepId))) {
      blockers.push('client sign-offs');
    }
    if (journeys.some((j) => j.steps.some((s) => (s.communications ?? []).length > 0))) {
      blockers.push('recorded communications');
    }
    if (db.obClientEscalations.some((e) => e.stepId != null && stepIds.has(e.stepId))) {
      blockers.push('escalations');
    }

    if (blockers.length > 0) {
      return problem(409, 'ob-project-in-use',
        'The project has history', {
          detail: `This project has ${blockers.join(', ')}, so it cannot be deleted. `
            + 'Set it to Dropped instead — that keeps the record and takes it off the running list.',
          blockers,
        });
    }

    // The journeys go with it; the client's purchase row deliberately does not.
    if (client) {
      client.journeys = client.journeys.filter(
        (j) => !(j.productId === project.productId && j.archivedAt == null),
      );
    }
    db.obProjects = db.obProjects.filter((p) => p.id !== id);
    return noContent();
  }),

  http.patch(url('/onboarding/projects/:obProjectId'), async ({ params, request }) => {
    const db = getDb();
    const project = db.obProjects.find((p) => p.id === Number(params.obProjectId));
    if (!project) return notFound('Project');

    const body = (await request.json()) as {
      name?: string;
      startDate?: string;
      salesPersonId?: number | null;
      implementorUserId?: number | null;
      implementorManagerUserId?: number | null;
      status?: ProjectStatus;
      statusReason?: string | null;
    };

    if (body.status === 'COMPLETED') {
      return problem(422, 'ob-project-status-not-earned',
        'That status is earned, not set', {
          detail: "COMPLETED is stamped when the project's last journey completes, not set by hand",
        });
    }
    if ((body.status === 'ON_HOLD' || body.status === 'DROPPED') && !body.statusReason) {
      return validationFailed({ statusReason: ['A reason is required for this status'] });
    }
    if (body.name != null && !body.name.trim()) {
      return validationFailed({ name: ['Give the project a name.'] });
    }

    if (body.name != null) project.name = body.name.trim();
    if (body.startDate != null) project.startDate = body.startDate;
    // The whole representation, not a sparse patch: absent means cleared, which
    // is how an implementor is unassigned.
    project.salesPersonId = body.salesPersonId ?? null;
    project.implementorUserId = body.implementorUserId ?? null;
    project.implementorManagerUserId = body.implementorManagerUserId ?? null;
    if (body.status != null) {
      project.status = body.status;
      project.statusReason = body.statusReason ?? null;
    }
    return ok(projectDetailDto(project, db, new Date()));
  }),
];
