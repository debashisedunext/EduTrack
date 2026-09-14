import { http } from 'msw';
import type { Db, ObClient, ObJourney, ObProjectRow } from '../db';
import { getDb } from '../db';
import { noContent, notFound, ok, paginate, problem, url, userRef, validationFailed } from './util';

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

function stagesOf(db: Db, project: ObProjectRow): StageRollup[] {
  const byKey = new Map<number, StageRollup>();

  for (const journey of journeysOf(db, project)) {
    const templateSteps = db.obJourneyTemplateSteps.filter((t) => t.templateId === journey.templateId);
    for (const step of journey.steps) {
      /*
        By id where the fixture carries one, by name otherwise. The clone copies
        the name, so the fallback is exact for every seeded journey — and a step
        that matches neither still lands in the `0` bucket below rather than
        vanishing out of both the numerator and the denominator.
      */
      const templateStep =
        templateSteps.find((t) => t.id === step.templateStepId) ??
        templateSteps.find((t) => t.name === step.name);
      const group = db.obJourneyTemplateStages.find((g) => g.id === templateStep?.templateStageId);
      /*
        Three fallbacks, and each is a real case the server's COALESCE covers:
        the implementation stage where there is one, the negated group id for
        the "Ungrouped" bucket, and 0 for a step whose template row has gone.
        A step in that last state must still be counted — dropping it would
        leave a plausible-looking percentage computed over the wrong total.
      */
      const stageKey = group?.implementationStageId ?? (group ? -group.id : 0);
      const existing = byKey.get(stageKey) ?? {
        stageKey,
        name: group?.name ?? 'Ungrouped',
        sequence: group?.sequence ?? 9999,
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
      byKey.set(stageKey, existing);
    }
  }

  return [...byKey.values()].sort(
    (a, b) => a.sequence - b.sequence || a.stageKey - b.stageKey,
  );
}

function gateStatusOfProject(db: Db, project: ObProjectRow): 'OPEN' | 'LOCKED' {
  const journeys = journeysOf(db, project);
  return journeys.some((j) => j.gateStatus === 'OPEN') ? 'OPEN' : 'LOCKED';
}

function totalTatDaysOf(db: Db, project: ObProjectRow): number {
  return journeysOf(db, project).reduce(
    (total, journey) => total + journey.steps.reduce((sum, step) => sum + (step.tatDays ?? 0), 0),
    0,
  );
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
    status: project.status,
    gateStatus,
    currentStage: current?.name ?? null,
    stagesComplete: stages.filter((s) => s.tasksOutstanding === 0).length,
    stagesTotal: stages.length,
    journeyCount: journeysOf(db, project).length,
    delayedByDays,
    tentativeCompletion: addWorkingDays(project.startDate, totalTatDays),
    totalTatDays,
  };
}

function projectDetailDto(project: ObProjectRow, db: Db, now: Date) {
  const stages = stagesOf(db, project);
  const earliestActive = stages
    .map((s) => s.minActiveSequence)
    .filter((s): s is number => s != null)
    .sort((a, b) => a - b)[0];

  return {
    ...projectDto(project, db, now),
    statusReason: project.statusReason,
    stages: stages.map((s) => ({
      stageKey: s.stageKey,
      name: s.name,
      sequence: s.sequence,
      taskCount: s.taskCount,
      tasksOutstanding: s.tasksOutstanding,
      isComplete: s.tasksOutstanding === 0,
      isCurrent: earliestActive != null && s.minActiveSequence === earliestActive,
    })),
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
      };
    }),
    createdBy: userRef(project.createdById, db),
    createdAt: project.createdAt,
  };
}

export const onboardingProjectHandlers = [
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
      moduleServiceIds?: number[];
    };

    const errors: Record<string, string[]> = {};
    if (!body.name?.trim()) errors.name = ['Give the project a name.'];
    if (body.clientId == null) errors.clientId = ['Choose a client.'];
    if (body.productId == null) errors.productId = ['Choose the product bought.'];
    if (!body.startDate) errors.startDate = ['Give the project a start date.'];
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
    if (body.status != null) {
      project.status = body.status;
      project.statusReason = body.statusReason ?? null;
    }
    return ok(projectDetailDto(project, db, new Date()));
  }),
];
