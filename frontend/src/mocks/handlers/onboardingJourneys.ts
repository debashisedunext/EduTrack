import { http } from 'msw';
import type {
  Db,
  ObJourneyTemplateRow,
  ObJourneyTemplateStepDocRow,
  ObJourneyTemplateStepItemRow,
  ObJourneyTemplateStepRow,
} from '../db';
import { getDb } from '../db';
import { noContent, notFound, ok, problem, url, validationFailed } from './util';

/**
 * C-102 · mocks for the OB-07 journey template designer.
 *
 * ⚠ **Stream C, in Stream D's `mocks/` directory** — the same situation
 * `onboarding.ts` (A-118) and `db.ts`'s `reportSchedules` (A-065) both flag
 * rather than do quietly. `coverage.test.ts` refuses a contract operation
 * with no MSW handler, so the alternative to this file is a red `develop`
 * the moment `onboarding-journeys` lands. Fixture rows are `db.ts`'s
 * `OB_JOURNEY_TEMPLATES` and its three siblings — see that file for why two
 * templates (one published, one draft) are seeded rather than one.
 *
 * ## What this mirrors from `ObJourneyTemplateService`, deliberately
 *
 * 1. **`publishedAt == null` is the only editability test**, never
 *    `isActive`. A retired version (`publishedAt` set, `isActive: false`)
 *    is exactly as frozen as the currently active one — both are "has ever
 *    been published" — and a mock that keyed off `isActive` instead would
 *    let a screen built against it offer to edit a retired version.
 * 2. **The reorder route's `If-Match`/`428`/`412` pair**, `steps/order`'s own
 *    contract note that a missing precondition must be refused rather than
 *    treated as "no conflict" — `stageQueries.ts`'s `useReorderStages`
 *    already relies on the analogous masters route behaving this way.
 * 3. **`removeStep`'s dependents check, naming the blocking step ids** on the
 *    `409` body's `dependentStepIds` — the one shape the designer's remove
 *    flow needs to render something more useful than a bare conflict.
 */

// ── mappers to the contract's shapes ────────────────────────────────────────

function templateDto(t: ObJourneyTemplateRow) {
  return {
    id: t.id,
    productId: t.productId,
    name: t.name,
    version: t.version,
    isActive: t.isActive,
    sequence: t.sequence,
    dependsOnTemplateIds: [...t.dependsOnTemplateIds].sort((a, b) => a - b),
    publishedBy: t.publishedBy,
    publishedAt: t.publishedAt,
  };
}

function itemDto(i: ObJourneyTemplateStepItemRow) {
  return { id: i.id, sequence: i.sequence, label: i.label, mandatory: i.mandatory };
}

function docDto(d: ObJourneyTemplateStepDocRow) {
  return { id: d.id, sequence: d.sequence, label: d.label, required: d.required };
}

/**
 * `JourneyTatCalculator#criticalPathDays` — how long a service **takes**, in
 * working days.
 *
 * <p>Not the sum of the task TATs, which is the different question the row
 * used to answer: tasks that wait for nothing run alongside each other, so 1
 * day beside 2 days is 2, and 1 day followed by 2 days is 3. Kept in step with
 * the Java and with `journeyTemplateTree.ts`, which walks the same shape to
 * draw the designer's Schedule column.
 *
 * <p>Exported, and typed on the three fields it reads rather than on one row
 * shape: a project's own TAT folds this over each of its journeys' steps
 * before chaining the services — see `onboardingProjects.ts`. One walk, so the
 * two handlers cannot answer differently.
 */
export function criticalPathDays(
  tasks: readonly { id: number; tatDays?: number | null; dependsOnStepId: number | null }[],
): number {
  const byId = new Map(tasks.map((t) => [t.id, t] as const));
  const endDays = new Map<number, number>();

  const endDay = (id: number, resolving: Set<number>): number => {
    const cached = endDays.get(id);
    if (cached != null) return cached;
    const task = byId.get(id);
    if (!task) return 0;

    let startDay = 1;
    const parentId = task.dependsOnStepId;
    // A dependency leading back into the chain being walked starts on day 1
    // rather than recursing for ever. The service refuses a cycle, which is
    // exactly why the guard is cheap to keep.
    resolving.add(id);
    if (parentId != null && byId.has(parentId) && !resolving.has(parentId)) {
      startDay = endDay(parentId, resolving) + 1;
    }
    resolving.delete(id);

    const end = startDay + Math.max(1, task.tatDays ?? 1) - 1;
    endDays.set(id, end);
    return end;
  };

  let span = 0;
  for (const task of tasks) span = Math.max(span, endDay(task.id, new Set()));
  return span;
}

function stepDetailDto(s: ObJourneyTemplateStepRow, db: Db) {
  return {
    id: s.id,
    sequence: s.sequence,
    name: s.name,
    templateStageId: s.templateStageId,
    description: s.description,
    tatDays: s.tatDays,
    ownerUserId: s.ownerUserId,
    requiresSignoff: s.requiresSignoff,
    dependsOnStepId: s.dependsOnStepId,
    items: db.obJourneyTemplateStepItems
      .filter((i) => i.stepId === s.id)
      .sort((a, b) => a.sequence - b.sequence)
      .map(itemDto),
    docs: db.obJourneyTemplateStepDocs
      .filter((d) => d.stepId === s.id)
      .sort((a, b) => a.sequence - b.sequence)
      .map(docDto),
  };
}

/**
 * `ObJourneyTemplateService#parallelGroups` — longest-path layering of
 * `dependsOnStepId`, mirrored rather than approximated: layer 0 is every
 * step with no dependency, layer N is one more than the step it depends on.
 * A depth cap stands in for the service's cycle-detection guard — the
 * composite FK is what actually prevents a cycle, and this mock has no FK.
 */
function parallelGroups(templateId: number, db: Db): number[][] {
  const steps = db.obJourneyTemplateSteps
    .filter((s) => s.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence);
  const byId = new Map(steps.map((s) => [s.id, s]));

  const layerOf = new Map<number, number>();
  const layerOfStep = (step: ObJourneyTemplateStepRow, depth: number): number => {
    const memoized = layerOf.get(step.id);
    if (memoized != null) return memoized;
    if (depth > steps.length) return 0; // cycle guard — should be unreachable
    let layer = 0;
    if (step.dependsOnStepId != null) {
      const dependency = byId.get(step.dependsOnStepId);
      layer = dependency ? 1 + layerOfStep(dependency, depth + 1) : 0;
    }
    layerOf.set(step.id, layer);
    return layer;
  };

  for (const step of steps) layerOfStep(step, 0);
  const maxLayer = Math.max(-1, ...Array.from(layerOf.values()));
  const groups: number[][] = [];
  for (let layer = 0; layer <= maxLayer; layer++) {
    groups.push(steps.filter((s) => layerOf.get(s.id) === layer).map((s) => s.id));
  }
  return groups;
}

/**
 * `sequence` 1..N over a template so ascending order walks stage groups in
 * order, then tasks within a group — `ObJourneyTemplateService#renumberByStage`.
 *
 * Mirrored here rather than approximated, because it is what makes a
 * template-wide sort the right sort everywhere else: `parallelGroups`, the
 * designer tree and the revision clone all read this one column.
 */
function renumberByStage(templateId: number, db: Db) {
  const groupOrder = new Map<number, number>();
  db.obJourneyTemplateStages
    .filter((g) => g.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence || a.id - b.id)
    .forEach((g, index) => groupOrder.set(g.id, index));

  db.obJourneyTemplateSteps
    .filter((s) => s.templateId === templateId)
    .sort((a, b) =>
      (groupOrder.get(a.templateStageId) ?? Number.MAX_SAFE_INTEGER)
        - (groupOrder.get(b.templateStageId) ?? Number.MAX_SAFE_INTEGER)
      || a.sequence - b.sequence
      || a.id - b.id)
    .forEach((s, index) => { s.sequence = index + 1; });
}

function detailDto(templateId: number, db: Db) {
  const template = db.obJourneyTemplates.find((t) => t.id === templateId);
  if (!template) return null;
  const steps = db.obJourneyTemplateSteps
    .filter((s) => s.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence);
  const stages = db.obJourneyTemplateStages
    .filter((g) => g.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence || a.id - b.id);
  return {
    ...templateDto(template),
    // Empty groups included, deliberately — the usual state of a new service,
    // and where the designer hangs "+ Add a task".
    stages: stages.map((g) => ({
      id: g.id,
      name: g.name,
      sequence: g.sequence,
      implementationStageId: g.implementationStageId,
    })),
    steps: steps.map((s) => stepDetailDto(s, db)),
    parallelGroups: parallelGroups(templateId, db),
  };
}

/**
 * Content-derived, not timestamp-derived — the same call the real
 * `ObJourneyTemplateController#etagOf` makes, so a save that changes nothing
 * about the detail moves nothing here either. The exact digits need never
 * agree with the server's own `Integer.toHexString(hashCode())`; both sides
 * only ever compare a tag against itself.
 */
function etagOf(detail: unknown): string {
  const str = JSON.stringify(detail);
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

/** `*` matches anything, per RFC 9110 — `ObJourneyTemplateController#matches`. */
function ifMatchSatisfied(ifMatch: string, current: string): boolean {
  const candidate = ifMatch.trim();
  if (candidate === '*') return true;
  return candidate.replace(/^W\//, '').replace(/"/g, '') === current;
}

const nextRowId = (rows: { id: number }[]): number => Math.max(0, ...rows.map((r) => r.id)) + 1;

/** `ObJourneyTemplateService#requireEditable` — `publishedAt == null` only. Returns the problem response, or null if editable. */
function editabilityConflict(template: ObJourneyTemplateRow | undefined) {
  if (!template) return notFound('Journey template');
  if (template.publishedAt != null) {
    return problem(409, 'conflict', 'Conflict', {
      detail: `Journey template ${template.id} has already been published — only a draft may be edited.`,
    });
  }
  return null;
}

/**
 * C-124 · every version of one *service* — `(productId, name)`, the key
 * `uq_ob_journey_templates_version` uses and the key the catalogue page groups
 * its cards on. The unit both the rename and the delete act on.
 */
function serviceChain(head: ObJourneyTemplateRow, db: Db): ObJourneyTemplateRow[] {
  return db.obJourneyTemplates.filter(
    (t) => t.productId === head.productId && t.name === head.name,
  );
}

/**
 * Client journeys instantiated from any version of a chain — the number that
 * decides whether a Module Service can be edited or deleted at all.
 *
 * Archived journeys count, matching `countJourneysForTemplates`: a finished
 * onboarding is as much evidence the service was sold as a running one, and
 * its steps still render from the template rows a delete would remove.
 */
function journeysOnChain(chain: ObJourneyTemplateRow[], db: Db): number {
  const ids = new Set(chain.map((t) => t.id));
  return db.obClients.reduce(
    (total, client) =>
      total + client.journeys.filter((j) => j.templateId != null && ids.has(j.templateId)).length,
    0,
  );
}

/**
 * The `409` C-124's *delete* answers when a client is on the service —
 * `ModuleServiceInUseException`'s shape.
 *
 * `PATCH` no longer answers it at all. The server renames the chain and
 * re-stamps the journeys' denormalised `service_name` in the same transaction,
 * and a product move re-files the templates while leaving those journeys under
 * the product their client bought — so neither field is refused for being in
 * use. Deleting still is: a running journey renders its steps from these very
 * rows.
 */
function moduleServiceInUse(serviceName: string, journeyCount: number) {
  const journeys = `${journeyCount} client journey${journeyCount === 1 ? ' has' : 's have'}`;
  return problem(409, 'module-service-in-use', 'Module Service is in use', {
    detail: `"${serviceName}" cannot be deleted — ${journeys} already been instantiated from it. `
      + 'Retire it by publishing over it instead.',
    journeyCount,
  });
}

/** `428`/`412`, or null when the precondition holds — the depends-on route's pair, shared. */
function preconditionFailure(ifMatch: string | null, current: string) {
  if (!ifMatch || !ifMatch.trim()) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the template first and send back its ETag.');
  }
  if (!ifMatchSatisfied(ifMatch, current)) {
    return problem(412, 'precondition-failed',
      'This template changed since you read it. Reload and reapply the change.');
  }
  return null;
}

// ── handlers ────────────────────────────────────────────────────────────────

export const onboardingJourneyHandlers = [
  /*
    `listObJourneyTemplates` — every version of every service, newest first
    within each, optionally narrowed to one product. The catalogue groups the
    rows into one card per service; it is deliberately not filtered here,
    because an admin reviewing history wants the retired rows too.
  */
  http.get(url('/onboarding/journey-templates'), ({ request }) => {
    const db = getDb();
    const productId = new URL(request.url).searchParams.get('productId');
    const rows = db.obJourneyTemplates
      .filter((t) => productId == null || t.productId === Number(productId))
      .slice()
      .sort(
        (a, b) =>
          a.productId - b.productId || a.name.localeCompare(b.name) || b.version - a.version,
      )
      .map((t) => {
        const steps = db.obJourneyTemplateSteps.filter((s) => s.templateId === t.id);
        return {
          id: t.id,
          productId: t.productId,
          name: t.name,
          version: t.version,
          isActive: t.isActive,
          sequence: t.sequence,
          dependsOnTemplateIds: [...(t.dependsOnTemplateIds ?? [])].sort((a, b) => a - b),
          publishedAt: t.publishedAt ?? null,
          stepCount: steps.length,
          totalTatDays: criticalPathDays(steps),
          /*
            C-124 · chain-wide, so every row of one service reports the same
            total. Measured over the whole `obJourneyTemplates` table rather
            than over `rows`, because the productId filter above may have
            narrowed the result — and the count a card disables its buttons by
            must not depend on which filter the admin happens to have selected.
          */
          serviceJourneyCount: journeysOnChain(serviceChain(t, db), db),
          /*
            The stage groups, on the summary rather than only on the detail:
            OB-07's Category column and the New Project form's service picker
            both draw every service of a product at once, and reading these per
            row would be a request per card.
          */
          stages: db.obJourneyTemplateStages
            .filter((g) => g.templateId === t.id)
            .sort((a, b) => a.sequence - b.sequence || a.id - b.id)
            .map((g) => ({
              id: g.id,
              name: g.name,
              sequence: g.sequence,
              implementationStageId: g.implementationStageId ?? null,
            })),
        };
      });
    return ok(rows);
  }),

  http.post(url('/onboarding/journey-templates'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as {
      productId?: number; name?: string; sequence?: number; dependsOnTemplateIds?: number[];
    };
    const errors: Record<string, string[]> = {};
    if (body.productId == null) errors.productId = ['Product is required'];
    if (!body.name) errors.name = ['Name is required'];
    if (Object.keys(errors).length) return validationFailed(errors);

    /*
      A product sells several named services, so only a duplicate *name*
      within one product collides — the server's rule since
      `uq_ob_journey_templates_version` was re-keyed to
      (product_id, name, version). This used to refuse any second service on
      a product, which is the behaviour the real backend no longer has.
    */
    if (db.obJourneyTemplates.some((t) => t.productId === body.productId && t.name === body.name)) {
      return problem(409, 'conflict', 'Conflict', {
        detail: `Product ${body.productId} already has a service named "${body.name}" — begin a revision instead.`,
      });
    }

    const created: ObJourneyTemplateRow = {
      id: nextRowId(db.obJourneyTemplates),
      productId: body.productId!,
      name: body.name!,
      version: 1,
      isActive: false,
      sequence: body.sequence ?? 1,
      dependsOnTemplateIds: [...new Set(body.dependsOnTemplateIds ?? [])],
      publishedBy: null,
      publishedAt: null,
    };
    db.obJourneyTemplates.push(created);

    /*
      The server seeds one empty stage GROUP per active implementation stage,
      inside the same transaction as the create —
      `ObJourneyTemplateService#seedStageGroups`. Groups, not tasks: a new
      Module Service arrives holding six stages and no work, because
      "Configuration" names a phase rather than something to do, and seeding a
      task per stage would leave an admin clearing six out before writing the
      real ones.
    */
    for (const stage of [...db.obImplementationStages]
      .filter((st) => st.isActive)
      .sort((a, b) => a.sequence - b.sequence || a.id - b.id)) {
      db.obJourneyTemplateStages.push({
        id: nextRowId(db.obJourneyTemplateStages),
        templateId: created.id,
        sequence: stage.sequence,
        name: stage.name,
        implementationStageId: stage.id,
      });
    }
    return ok(templateDto(created), undefined, { status: 201 });
  }),

  http.get(url('/onboarding/journey-templates/:templateId'), ({ params }) => {
    const db = getDb();
    const detail = detailDto(Number(params.templateId), db);
    if (!detail) return notFound('Journey template');
    return ok(detail, undefined, { headers: { ETag: etagOf(detail) } });
  }),

  http.post(url('/onboarding/journey-templates/:templateId/revisions'), ({ params }) => {
    const db = getDb();
    const active = db.obJourneyTemplates.find((t) => t.id === Number(params.templateId));
    if (!active) return notFound('Journey template');
    if (!active.isActive) {
      return problem(409, 'conflict', 'Conflict', {
        detail: `Journey template ${active.id} is not the product's currently active version.`,
      });
    }

    const nextVersion =
      Math.max(...db.obJourneyTemplates.filter((t) => t.productId === active.productId).map((t) => t.version)) + 1;
    const draft: ObJourneyTemplateRow = {
      id: nextRowId(db.obJourneyTemplates),
      productId: active.productId,
      name: active.name,
      version: nextVersion,
      isActive: false,
      sequence: active.sequence,
      // Copied, not shared: the draft is its own template version and its
      // picker must not reach back and change what the active one waits for.
      dependsOnTemplateIds: [...active.dependsOnTemplateIds],
      publishedBy: null,
      publishedAt: null,
    };
    db.obJourneyTemplates.push(draft);

    // Clone steps, items and docs — `dependsOnStepId` re-pointed at the clones,
    // in the same two-pass shape `ObJourneyTemplateService#cloneSteps` uses:
    // every clone needs to exist before any of them can point at another.
    // Groups first: a task clone points at the clone of its group, never at
    // the source version's, or editing the draft would edit what the
    // published version renders.
    const sourceToClonedGroup = new Map<number, number>();
    for (const sourceGroup of db.obJourneyTemplateStages
      .filter((g) => g.templateId === active.id)
      .sort((a, b) => a.sequence - b.sequence || a.id - b.id)) {
      const groupClone = {
        id: nextRowId(db.obJourneyTemplateStages) + sourceToClonedGroup.size,
        templateId: draft.id,
        sequence: sourceGroup.sequence,
        name: sourceGroup.name,
        implementationStageId: sourceGroup.implementationStageId,
      };
      db.obJourneyTemplateStages.push(groupClone);
      sourceToClonedGroup.set(sourceGroup.id, groupClone.id);
    }

    const sourceSteps = db.obJourneyTemplateSteps
      .filter((s) => s.templateId === active.id)
      .sort((a, b) => a.sequence - b.sequence);
    const sourceToClone = new Map<number, number>();
    for (const source of sourceSteps) {
      const clone: ObJourneyTemplateStepRow = {
        id: nextRowId(db.obJourneyTemplateSteps) + sourceToClone.size,
        templateId: draft.id,
        sequence: source.sequence,
        name: source.name,
        templateStageId: sourceToClonedGroup.get(source.templateStageId)!,
        description: source.description,
        tatDays: source.tatDays,
        ownerUserId: source.ownerUserId,
        requiresSignoff: source.requiresSignoff,
        dependsOnStepId: null, // re-pointed below
      };
      db.obJourneyTemplateSteps.push(clone);
      sourceToClone.set(source.id, clone.id);

      for (const item of db.obJourneyTemplateStepItems.filter((i) => i.stepId === source.id)) {
        db.obJourneyTemplateStepItems.push({
          id: nextRowId(db.obJourneyTemplateStepItems),
          stepId: clone.id,
          sequence: item.sequence,
          label: item.label,
          mandatory: item.mandatory, // carried forward — never reset to a default
        });
      }
      for (const doc of db.obJourneyTemplateStepDocs.filter((d) => d.stepId === source.id)) {
        db.obJourneyTemplateStepDocs.push({
          id: nextRowId(db.obJourneyTemplateStepDocs),
          stepId: clone.id,
          sequence: doc.sequence,
          label: doc.label,
          required: doc.required,
        });
      }
    }
    for (const source of sourceSteps) {
      if (source.dependsOnStepId == null) continue;
      const clone = db.obJourneyTemplateSteps.find((s) => s.id === sourceToClone.get(source.id));
      if (clone) clone.dependsOnStepId = sourceToClone.get(source.dependsOnStepId) ?? null;
    }

    return ok(templateDto(draft), undefined, { status: 201 });
  }),

  http.post(url('/onboarding/journey-templates/:templateId/publish'), ({ params }) => {
    const db = getDb();
    const draft = db.obJourneyTemplates.find((t) => t.id === Number(params.templateId));
    if (!draft) return notFound('Journey template');
    if (draft.publishedAt != null) {
      return problem(409, 'conflict', 'Conflict', {
        detail: `Journey template ${draft.id} has already been published once.`,
      });
    }
    const stepCount = db.obJourneyTemplateSteps.filter((s) => s.templateId === draft.id).length;
    if (stepCount === 0) {
      return problem(422, 'template-has-no-steps', 'Cannot publish a template with no steps', {
        detail: 'A published template with no steps could never activate a journey.',
      });
    }

    // Retires the active version of *this service*. By product it would
    // switch off every other service the product publishes — the bug
    // V20260910_0030 exists to fix, and a mock that kept it would let the
    // screen pass offline and fail against the API.
    const current = db.obJourneyTemplates.find(
      (t) => t.productId === draft.productId && t.name === draft.name && t.isActive,
    );
    if (current) current.isActive = false;

    draft.isActive = true;
    draft.publishedBy = db.currentUserId;
    draft.publishedAt = new Date().toISOString();
    return ok(templateDto(draft));
  }),

  http.post(url('/onboarding/journey-template-stages/:stageId/tasks'), async ({ params, request }) => {
    const db = getDb();
    const stageId = Number(params.stageId);
    const group = db.obJourneyTemplateStages.find((g) => g.id === stageId);
    if (!group) return notFound('Stage group');

    const template = db.obJourneyTemplates.find((t) => t.id === group.templateId);
    const conflict = editabilityConflict(template);
    if (conflict) return conflict;

    const body = (await request.json()) as {
      name?: string; description?: string | null; tatDays?: number;
      ownerUserId?: number | null;
      requiresSignoff?: boolean; dependsOnStepId?: number | null;
    };
    const errors: Record<string, string[]> = {};
    if (!body.name || !body.name.trim()) errors.name = ['A task name is required'];
    if (body.tatDays == null || body.tatDays < 1) errors.tatDays = ['TAT must be at least 1 day'];
    if (Object.keys(errors).length) return validationFailed(errors);

    const created: ObJourneyTemplateStepRow = {
      id: nextRowId(db.obJourneyTemplateSteps),
      templateId: group.templateId,
      templateStageId: group.id,
      sequence: 0, // renumbered below
      name: body.name!.trim(),
      description: body.description ?? null,
      tatDays: body.tatDays!,
      ownerUserId: body.ownerUserId ?? null,
      requiresSignoff: body.requiresSignoff ?? false,
      dependsOnStepId: body.dependsOnStepId ?? null,
    };
    db.obJourneyTemplateSteps.push(created);
    renumberByStage(group.templateId, db);
    return ok(stepDetailDto(created, db), undefined, { status: 201 });
  }),

  http.put(url('/onboarding/journey-template-stages/:stageId/tasks/order'), async ({ params, request }) => {
    const db = getDb();
    const stageId = Number(params.stageId);
    const group = db.obJourneyTemplateStages.find((g) => g.id === stageId);
    if (!group) return notFound('Stage group');

    const template = db.obJourneyTemplates.find((t) => t.id === group.templateId);
    if (!template) return notFound('Journey template');

    // The tag is the *template's*, read as it stands before this write: a
    // stage group has no mutable state of its own to conflict over.
    const currentDetail = detailDto(group.templateId, db);
    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch || !ifMatch.trim()) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the template first and send back its ETag.');
    }
    if (!ifMatchSatisfied(ifMatch, etagOf(currentDetail))) {
      return problem(412, 'precondition-failed',
        'This template changed since you read it. Reload and reapply the reorder.');
    }

    const conflict = editabilityConflict(template);
    if (conflict) return conflict;

    const body = (await request.json()) as { taskIds?: number[] };
    const taskIds = body.taskIds ?? [];
    const inGroup = db.obJourneyTemplateSteps.filter((s) => s.templateStageId === group.id);
    const currentIds = new Set(inGroup.map((s) => s.id));
    const requestedIds = new Set(taskIds);
    if (requestedIds.size !== taskIds.length) {
      return problem(400, 'validation', "Reorder list does not match the stage's current tasks", {
        detail: 'The same task id appears more than once.',
      });
    }
    if (requestedIds.size !== currentIds.size || [...requestedIds].some((id) => !currentIds.has(id))) {
      return problem(400, 'validation', "Reorder list does not match the stage's current tasks", {
        detail: "The given ids are not exactly this stage's current task set.",
      });
    }

    // The group's own block of positions, permuted in place — every other
    // stage's tasks keep the positions they had.
    const slots = inGroup.map((s) => s.sequence).sort((a, b) => a - b);
    taskIds.forEach((id, index) => {
      const task = inGroup.find((s) => s.id === id);
      if (task) task.sequence = slots[index];
    });
    return noContent();
  }),

  // C-123 · the Module Service catalogue's ↑/↓, spanning every active
  // template — no `editabilityConflict`/`If-Match` here, on the same
  // reasoning the real route's own contract note gives: catalogue metadata,
  // not draft content, and no single row for a precondition to protect.
  http.put(url('/onboarding/journey-templates/order'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as { templateIds?: number[] };
    const templateIds = body.templateIds ?? [];
    const active = db.obJourneyTemplates.filter((t) => t.isActive);
    const activeIds = new Set(active.map((t) => t.id));
    const requestedIds = new Set(templateIds);
    if (requestedIds.size !== templateIds.length) {
      return problem(400, 'validation', "Reorder list does not match the catalogue's active templates", {
        detail: 'The same template id appears more than once.',
      });
    }
    if (requestedIds.size !== activeIds.size || [...requestedIds].some((id) => !activeIds.has(id))) {
      return problem(400, 'validation', "Reorder list does not match the catalogue's active templates", {
        detail: "The given ids are not exactly the catalogue's current active templates.",
      });
    }
    templateIds.forEach((id, index) => {
      const template = active.find((t) => t.id === id);
      if (template) template.sequence = index;
    });
    return noContent();
  }),

  // C-123 · the "Service depends on" picker — works on a draft or the
  // active version alike, unlike a step's own fields, so no
  // `editabilityConflict` guard here either.
  http.put(url('/onboarding/journey-templates/:templateId/depends-on'), async ({ params, request }) => {
    const db = getDb();
    const templateId = Number(params.templateId);
    const template = db.obJourneyTemplates.find((t) => t.id === templateId);
    if (!template) return notFound('Journey template');

    const currentDetail = detailDto(templateId, db);
    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch || !ifMatch.trim()) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the template first and send back its ETag.');
    }
    if (!ifMatchSatisfied(ifMatch, etagOf(currentDetail))) {
      return problem(412, 'precondition-failed',
        'This template changed since you read it. Reload and reapply the change.');
    }

    const body = (await request.json()) as { dependsOnTemplateIds?: number[] };
    // Duplicates collapse rather than being refused — the server's own rule:
    // asking for the same edge twice is a request for one edge.
    const requested = [...new Set(body.dependsOnTemplateIds ?? [])];

    for (const candidate of requested) {
      if (candidate === templateId) {
        return problem(409, 'conflict', 'That dependency would close a cycle', {
          detail: `Journey template ${templateId} cannot depend on itself.`,
        });
      }
      if (!db.obJourneyTemplates.some((t) => t.id === candidate)) {
        return notFound('Journey template');
      }
      /*
        Breadth-first over the candidate's own outgoing edges —
        ObJourneyTemplateService#requireNoCycle's exact check. It was a cursor
        following one id at a time, which with a set per node misses a cycle
        down the second branch of a fork: the mock would accept what the real
        server refuses, and a screen built against it would look correct until
        it met the backend.
      */
      const seen = new Set<number>([candidate]);
      const frontier: number[] = [candidate];
      while (frontier.length > 0) {
        const current = frontier.shift()!;
        const row: ObJourneyTemplateRow | undefined =
          db.obJourneyTemplates.find((t) => t.id === current);
        for (const next of row?.dependsOnTemplateIds ?? []) {
          if (next === templateId) {
            return problem(409, 'conflict', 'That dependency would close a cycle', {
              detail: `Journey template ${candidate} already depends, directly or `
                + `transitively, on template ${templateId}.`,
            });
          }
          if (!seen.has(next)) {
            seen.add(next);
            frontier.push(next);
          }
        }
      }
    }

    // A full replace, not a delta — the route's own contract.
    template.dependsOnTemplateIds = requested.sort((a, b) => a - b);
    return ok(templateDto(template));
  }),

  /*
    C-124 · `updateObJourneyModuleService` — rename a Module Service, or move
    it to another product.

    Mirrors the server on the two things a screen built against this mock could
    otherwise get wrong:

    1. **Every version of the chain is renamed**, not the row in the path. A
       service is `(productId, name)`, so a mock that renamed one row would let
       the catalogue draw two cards for one service and nobody would notice
       until the real backend did the right thing instead.
    2. **Never `409` for being in use**, in either field. The server carries
       the journeys' denormalised `service_name` along with a rename, and a
       move re-files the templates while the journeys keep the product their
       client bought. This mock has nothing to carry either way — it derives a
       journey's service name from the template it pins — so both simply land.
       Only `DELETE` still refuses.
  */
  http.patch(url('/onboarding/journey-templates/:templateId'), async ({ params, request }) => {
    const db = getDb();
    const template = db.obJourneyTemplates.find((t) => t.id === Number(params.templateId));
    if (!template) return notFound('Journey template');

    const detail = detailDto(template.id, db);
    const precondition = preconditionFailure(request.headers.get('If-Match'), etagOf(detail!));
    if (precondition) return precondition;

    const body = (await request.json()) as { name?: string; productId?: number | null };
    if (!body.name || !body.name.trim()) {
      return validationFailed({ name: ['Name is required'] });
    }

    const chain = serviceChain(template, db);
    const name = body.name.trim();
    const productId = body.productId ?? template.productId;

    const clash = db.obJourneyTemplates.some(
      (t) => t.productId === productId && t.name === name && !chain.some((c) => c.id === t.id),
    );
    if (clash) {
      return problem(409, 'conflict', 'Conflict', {
        detail: `Product ${productId} already has a Module Service called "${name}" — two services a picker cannot tell apart.`,
      });
    }

    for (const version of chain) {
      version.name = name;
      version.productId = productId;
    }
    return ok(templateDto(template));
  }),

  /*
    C-124 · `deleteObJourneyModuleService` — the service and every version of
    it, with each version's steps, items and docs.

    No `If-Match`: the route takes none, for the reason its contract note
    gives — there is no update to lose, and the race that matters is settled
    server-side rather than by a tag.
  */
  http.delete(url('/onboarding/journey-templates/:templateId'), ({ params }) => {
    const db = getDb();
    const template = db.obJourneyTemplates.find((t) => t.id === Number(params.templateId));
    if (!template) return notFound('Journey template');

    const chain = serviceChain(template, db);
    const inUse = journeysOnChain(chain, db);
    if (inUse > 0) {
      return moduleServiceInUse(template.name, inUse);
    }

    const chainIds = new Set(chain.map((t) => t.id));
    const dependents = [
      ...new Set(
        db.obJourneyTemplates
          .filter((t) => !chainIds.has(t.id)
            && t.dependsOnTemplateIds.some((id) => chainIds.has(id)))
          .map((t) => t.name),
      ),
    ];
    if (dependents.length) {
      return problem(409, 'module-service-has-dependents', 'Module Service has dependents', {
        detail: `"${template.name}" cannot be deleted — ${dependents.join(', ')} depend on it; clear their "Depends on" first.`,
        dependentServiceNames: dependents,
      });
    }

    const stepIds = new Set(
      db.obJourneyTemplateSteps.filter((s) => chainIds.has(s.templateId)).map((s) => s.id),
    );
    db.obJourneyTemplateStepItems = db.obJourneyTemplateStepItems.filter((i) => !stepIds.has(i.stepId));
    db.obJourneyTemplateStepDocs = db.obJourneyTemplateStepDocs.filter((d) => !stepIds.has(d.stepId));
    db.obJourneyTemplateSteps = db.obJourneyTemplateSteps.filter((s) => !chainIds.has(s.templateId));
    db.obJourneyTemplates = db.obJourneyTemplates.filter((t) => !chainIds.has(t.id));
    return noContent();
  }),


  /*
    The edit the seeded stages made necessary. `If-Match` is required and the
    tag is the TEMPLATE's — a step has no read of its own to draw one from,
    and the template's tag covers every step on it, which is the tag that
    notices somebody else's dependency change.
  */
  http.patch(url('/onboarding/journey-template-steps/:stepId'), async ({ params, request }) => {
    const db = getDb();
    const stepId = Number(params.stepId);
    const step = db.obJourneyTemplateSteps.find((s) => s.id === stepId);
    if (!step) return notFound('Journey template step');

    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch || !ifMatch.trim()) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the template first and send back its ETag.');
    }
    const currentDetail = detailDto(step.templateId, db);
    if (!ifMatchSatisfied(ifMatch, etagOf(currentDetail))) {
      return problem(412, 'precondition-failed',
        'This template changed since you read it. Reload and reapply the edit.');
    }

    const conflict = editabilityConflict(db.obJourneyTemplates.find((t) => t.id === step.templateId));
    if (conflict) return conflict;

    const body = (await request.json()) as {
      description?: string | null; tatDays?: number | null;
      ownerUserId?: number | null;
      requiresSignoff?: boolean | null; dependsOnStepId?: number | null; clearDependsOn?: boolean;
      clearOwnerUserId?: boolean;
    };
    if (body.tatDays != null && body.tatDays < 1) {
      return validationFailed({ tatDays: ['TAT must be at least 1 day'] });
    }

    // Null means "say nothing about this", which is what makes a PATCH a
    // PATCH — the exception is the dependency, cleared explicitly below.
    if (body.description !== undefined) {
      step.description = body.description && body.description.trim() ? body.description : null;
    }
    if (body.tatDays != null) step.tatDays = body.tatDays;
    // Clear wins over set, matching the service: a caller that sent both
    // asked for the removal. The implementor needs a flag because a number has
    // no blank value to clear it with.
    if (body.clearOwnerUserId) step.ownerUserId = null;
    else if (body.ownerUserId != null) step.ownerUserId = body.ownerUserId;
    if (body.requiresSignoff != null) step.requiresSignoff = body.requiresSignoff;

    if (body.clearDependsOn) {
      step.dependsOnStepId = null;
    } else if (body.dependsOnStepId != null) {
      const dependency = db.obJourneyTemplateSteps.find(
        (s) => s.id === body.dependsOnStepId && s.templateId === step.templateId,
      );
      if (!dependency) return notFound('Journey template step');

      // Walking up from the proposed dependency: arriving back at this step
      // is a chain that waits on itself, which the real service refuses with
      // 409 and the designer's tree walker could not survive.
      const byId = new Map(
        db.obJourneyTemplateSteps
          .filter((s) => s.templateId === step.templateId)
          .map((s) => [s.id, s] as const),
      );
      let cursor: ObJourneyTemplateStepRow | undefined = dependency;
      for (let guard = 0; cursor && guard <= byId.size; guard += 1) {
        if (cursor.id === step.id) {
          return problem(409, 'conflict', 'Conflict', {
            detail: `"${step.name}" cannot wait for "${dependency.name}" — that step already waits for this one.`,
          });
        }
        cursor = cursor.dependsOnStepId == null ? undefined : byId.get(cursor.dependsOnStepId);
      }
      step.dependsOnStepId = dependency.id;
    }

    return ok(stepDetailDto(step, db));
  }),

  http.delete(url('/onboarding/journey-template-steps/:stepId'), ({ params }) => {
    const db = getDb();
    const stepId = Number(params.stepId);
    const step = db.obJourneyTemplateSteps.find((s) => s.id === stepId);
    if (!step) return notFound('Journey template step');

    const conflict = editabilityConflict(db.obJourneyTemplates.find((t) => t.id === step.templateId));
    if (conflict) return conflict;

    const dependents = db.obJourneyTemplateSteps
      .filter((s) => s.templateId === step.templateId && s.dependsOnStepId === stepId)
      .map((s) => s.id);
    if (dependents.length) {
      return problem(409, 'step-has-dependents', 'Step has dependents', {
        detail: `Step ${stepId} still has dependents: ${dependents.join(', ')}. Re-point them first.`,
        dependentStepIds: dependents,
      });
    }

    db.obJourneyTemplateSteps = db.obJourneyTemplateSteps.filter((s) => s.id !== stepId);
    db.obJourneyTemplateStepItems = db.obJourneyTemplateStepItems.filter((i) => i.stepId !== stepId);
    db.obJourneyTemplateStepDocs = db.obJourneyTemplateStepDocs.filter((d) => d.stepId !== stepId);
    return noContent();
  }),

  http.post(url('/onboarding/journey-template-steps/:stepId/items'), async ({ params, request }) => {
    const db = getDb();
    const stepId = Number(params.stepId);
    const step = db.obJourneyTemplateSteps.find((s) => s.id === stepId);
    if (!step) return notFound('Journey template step');
    const conflict = editabilityConflict(db.obJourneyTemplates.find((t) => t.id === step.templateId));
    if (conflict) return conflict;

    const body = (await request.json()) as { label?: string; mandatory?: boolean };
    if (!body.label) return validationFailed({ label: ['Label is required'] });

    const siblingSequences = db.obJourneyTemplateStepItems.filter((i) => i.stepId === stepId).map((i) => i.sequence);
    const created: ObJourneyTemplateStepItemRow = {
      id: nextRowId(db.obJourneyTemplateStepItems),
      stepId,
      sequence: siblingSequences.length ? Math.max(...siblingSequences) + 1 : 1,
      label: body.label,
      // Every item that predates this field is mandatory (plan §5.8) — the
      // same default the column carries — so an omitted flag matches it.
      mandatory: body.mandatory ?? true,
    };
    db.obJourneyTemplateStepItems.push(created);
    return ok(itemDto(created), undefined, { status: 201 });
  }),

  http.post(url('/onboarding/journey-template-steps/:stepId/docs'), async ({ params, request }) => {
    const db = getDb();
    const stepId = Number(params.stepId);
    const step = db.obJourneyTemplateSteps.find((s) => s.id === stepId);
    if (!step) return notFound('Journey template step');
    const conflict = editabilityConflict(db.obJourneyTemplates.find((t) => t.id === step.templateId));
    if (conflict) return conflict;

    const body = (await request.json()) as { label?: string; required?: boolean };
    if (!body.label) return validationFailed({ label: ['Label is required'] });

    const siblingSequences = db.obJourneyTemplateStepDocs.filter((d) => d.stepId === stepId).map((d) => d.sequence);
    const created: ObJourneyTemplateStepDocRow = {
      id: nextRowId(db.obJourneyTemplateStepDocs),
      stepId,
      sequence: siblingSequences.length ? Math.max(...siblingSequences) + 1 : 1,
      label: body.label,
      required: body.required ?? true,
    };
    db.obJourneyTemplateStepDocs.push(created);
    return ok(docDto(created), undefined, { status: 201 });
  }),

  http.delete(url('/onboarding/journey-template-step-items/:itemId'), ({ params }) => {
    const db = getDb();
    const itemId = Number(params.itemId);
    const item = db.obJourneyTemplateStepItems.find((i) => i.id === itemId);
    if (!item) return notFound('Task List item');
    const step = db.obJourneyTemplateSteps.find((s) => s.id === item.stepId);
    const conflict = editabilityConflict(db.obJourneyTemplates.find((t) => t.id === step?.templateId));
    if (conflict) return conflict;

    db.obJourneyTemplateStepItems = db.obJourneyTemplateStepItems.filter((i) => i.id !== itemId);
    return noContent();
  }),

  http.delete(url('/onboarding/journey-template-step-docs/:docId'), ({ params }) => {
    const db = getDb();
    const docId = Number(params.docId);
    const doc = db.obJourneyTemplateStepDocs.find((d) => d.id === docId);
    if (!doc) return notFound('Required document');
    const step = db.obJourneyTemplateSteps.find((s) => s.id === doc.stepId);
    const conflict = editabilityConflict(db.obJourneyTemplates.find((t) => t.id === step?.templateId));
    if (conflict) return conflict;

    db.obJourneyTemplateStepDocs = db.obJourneyTemplateStepDocs.filter((d) => d.id !== docId);
    return noContent();
  }),
];
