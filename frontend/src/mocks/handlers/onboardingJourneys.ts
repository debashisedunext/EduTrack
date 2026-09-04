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
    dependsOnTemplateId: t.dependsOnTemplateId,
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

function stepDetailDto(s: ObJourneyTemplateStepRow, db: Db) {
  return {
    id: s.id,
    sequence: s.sequence,
    name: s.name,
    description: s.description,
    tatDays: s.tatDays,
    ownerUserId: s.ownerUserId,
    ownerRole: s.ownerRole,
    backupOwnerUserId: s.backupOwnerUserId,
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

function detailDto(templateId: number, db: Db) {
  const template = db.obJourneyTemplates.find((t) => t.id === templateId);
  if (!template) return null;
  const steps = db.obJourneyTemplateSteps
    .filter((s) => s.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence);
  return {
    ...templateDto(template),
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

// ── handlers ────────────────────────────────────────────────────────────────

export const onboardingJourneyHandlers = [
  http.post(url('/onboarding/journey-templates'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as {
      productId?: number; name?: string; sequence?: number; dependsOnTemplateId?: number | null;
    };
    const errors: Record<string, string[]> = {};
    if (body.productId == null) errors.productId = ['Product is required'];
    if (!body.name) errors.name = ['Name is required'];
    if (Object.keys(errors).length) return validationFailed(errors);

    if (db.obJourneyTemplates.some((t) => t.productId === body.productId)) {
      return problem(409, 'conflict', 'Conflict', {
        detail: `Product ${body.productId} already has a journey template — begin a revision instead.`,
      });
    }

    const created: ObJourneyTemplateRow = {
      id: nextRowId(db.obJourneyTemplates),
      productId: body.productId!,
      name: body.name!,
      version: 1,
      isActive: false,
      sequence: body.sequence ?? 1,
      dependsOnTemplateId: body.dependsOnTemplateId ?? null,
      publishedBy: null,
      publishedAt: null,
    };
    db.obJourneyTemplates.push(created);
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
      dependsOnTemplateId: active.dependsOnTemplateId,
      publishedBy: null,
      publishedAt: null,
    };
    db.obJourneyTemplates.push(draft);

    // Clone steps, items and docs — `dependsOnStepId` re-pointed at the clones,
    // in the same two-pass shape `ObJourneyTemplateService#cloneSteps` uses:
    // every clone needs to exist before any of them can point at another.
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
        description: source.description,
        tatDays: source.tatDays,
        ownerUserId: source.ownerUserId,
        ownerRole: source.ownerRole,
        backupOwnerUserId: source.backupOwnerUserId,
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

    const current = db.obJourneyTemplates.find((t) => t.productId === draft.productId && t.isActive);
    if (current) current.isActive = false;

    draft.isActive = true;
    draft.publishedBy = db.currentUserId;
    draft.publishedAt = new Date().toISOString();
    return ok(templateDto(draft));
  }),

  http.post(url('/onboarding/journey-templates/:templateId/steps'), async ({ params, request }) => {
    const db = getDb();
    const templateId = Number(params.templateId);
    const template = db.obJourneyTemplates.find((t) => t.id === templateId);
    const conflict = editabilityConflict(template);
    if (conflict) return conflict;

    const body = (await request.json()) as {
      name?: string; description?: string | null; tatDays?: number;
      ownerUserId?: number | null; ownerRole?: string | null; backupOwnerUserId?: number | null;
      requiresSignoff?: boolean; dependsOnStepId?: number | null;
    };
    const errors: Record<string, string[]> = {};
    if (!body.name) errors.name = ['Name is required'];
    if (body.tatDays == null || body.tatDays < 1) errors.tatDays = ['TAT must be at least 1 day'];
    if (Object.keys(errors).length) return validationFailed(errors);

    const siblingSequences = db.obJourneyTemplateSteps
      .filter((s) => s.templateId === templateId)
      .map((s) => s.sequence);
    const created: ObJourneyTemplateStepRow = {
      id: nextRowId(db.obJourneyTemplateSteps),
      templateId,
      sequence: siblingSequences.length ? Math.max(...siblingSequences) + 1 : 1,
      name: body.name!,
      description: body.description ?? null,
      tatDays: body.tatDays!,
      ownerUserId: body.ownerUserId ?? null,
      ownerRole: body.ownerRole ?? null,
      backupOwnerUserId: body.backupOwnerUserId ?? null,
      requiresSignoff: body.requiresSignoff ?? false,
      dependsOnStepId: body.dependsOnStepId ?? null,
    };
    db.obJourneyTemplateSteps.push(created);
    return ok(stepDetailDto(created, db), undefined, { status: 201 });
  }),

  http.put(url('/onboarding/journey-templates/:templateId/steps/order'), async ({ params, request }) => {
    const db = getDb();
    const templateId = Number(params.templateId);
    const template = db.obJourneyTemplates.find((t) => t.id === templateId);
    if (!template) return notFound('Journey template');

    // The ETag is read off the template as it stands *before* this write, the
    // same instant `GET .../{templateId}` would answer — `steps/order`'s own
    // contract note: `If-Match` is required, not optional, so a missing one
    // is `428` rather than treated as "no conflict".
    const currentDetail = detailDto(templateId, db);
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

    const body = (await request.json()) as { stepIds?: number[] };
    const stepIds = body.stepIds ?? [];
    const current = db.obJourneyTemplateSteps.filter((s) => s.templateId === templateId);
    const currentIds = new Set(current.map((s) => s.id));
    const requestedIds = new Set(stepIds);
    if (requestedIds.size !== stepIds.length) {
      return problem(400, 'validation', "Reorder list does not match the template's current steps", {
        detail: 'The same step id appears more than once.',
      });
    }
    if (requestedIds.size !== currentIds.size || [...requestedIds].some((id) => !currentIds.has(id))) {
      return problem(400, 'validation', "Reorder list does not match the template's current steps", {
        detail: "The given ids are not exactly this template's current step set.",
      });
    }

    stepIds.forEach((id, index) => {
      const step = current.find((s) => s.id === id);
      if (step) step.sequence = index + 1;
    });
    return noContent();
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
