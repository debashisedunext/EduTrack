import { http } from 'msw';
import type { ObImplementationStage } from '../db';
import { getDb } from '../db';
import { notFound, ok, problem, url, validationFailed } from './util';

/**
 * OB-15 · mocks for `/onboarding/implementation-stages`.
 *
 * ⚠️ **Stream B in Stream D's `mocks/` (D-004)**, flagged rather than quiet —
 * see `onboardingPrereqs.ts` and `onboardingAdmin.ts`, which carry the same
 * note for the same reason.
 *
 * Its own file rather than four more handlers in `onboarding.ts`, for the
 * reason `onboardingPrereqs.ts` and `onboardingJourneys.ts` are their own
 * files: the master carries a rule none of its neighbours do — the renumbering
 * below — and a rule worth reproducing is a rule worth being able to find.
 *
 * <h2>The renumbering is the whole point of mocking this properly</h2>
 *
 * `sequence` is a **position**, not a stored value: the server moves the row
 * and rewrites 1..N over the master, so a `PATCH` sending `sequence: 2` gets
 * back a list where four other rows have moved. A mock that simply assigned
 * the number it was sent would let OB-15 be built against an ordering the
 * server never produces — gaps, duplicates, and a screen that looks correct
 * until the day it runs against MySQL. {@link renumber} is the same algorithm
 * `ObImplementationStageService.reposition` runs.
 */

/** Content-derived, mirroring `onboardingJourneys.ts#etagOf`. */
function etagOf(stage: unknown): string {
  const str = JSON.stringify(stage);
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

/** `*` matches anything, per RFC 9110 — `ObImplementationStageController#matches`. */
function ifMatchSatisfied(ifMatch: string, current: string): boolean {
  const candidate = ifMatch.trim();
  if (candidate === '*') return true;
  return candidate.replace(/^W\//, '').replace(/"/g, '') === current;
}

function preconditionFailure(ifMatch: string | null, current: string) {
  if (!ifMatch || !ifMatch.trim()) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the stage first and send back its ETag.');
  }
  if (!ifMatchSatisfied(ifMatch, current)) {
    return problem(412, 'precondition-failed',
      'This stage changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

/** Display order — `sequence`, then `id` to break the tie deterministically. */
function ordered(rows: ObImplementationStage[]): ObImplementationStage[] {
  return rows.slice().sort((a, b) => a.sequence - b.sequence || a.id - b.id);
}

/**
 * Moves `moved` to 1-based `target` and rewrites 1..N over the whole master.
 *
 * Out-of-range targets are clamped rather than refused, as the service clamps
 * them: a large number means last, which is what somebody typing it into a
 * position box intends.
 */
function renumber(stage: ObImplementationStage, target: number): void {
  const db = getDb();
  const working = ordered(db.obImplementationStages).filter((s) => s.id !== stage.id);
  const index = Math.min(Math.max(target - 1, 0), working.length);
  working.splice(index, 0, stage);
  working.forEach((row, i) => {
    row.sequence = i + 1;
  });
}

/** Case-insensitive, like `uq_ob_implementation_stages_name` under utf8mb4_0900_ai_ci. */
function nameTaken(name: string, exceptId?: number): boolean {
  return getDb().obImplementationStages.some(
    (s) => s.id !== exceptId && s.name.toLowerCase() === name.trim().toLowerCase(),
  );
}

const duplicate = (name: string) =>
  problem(409, 'ob-implementation-stage-name-duplicate',
    'Implementation stage already exists', {
      detail: `An implementation stage named '${name}' already exists`,
      field: 'name',
      errors: { name: ['Already in use'] },
    });

interface StageWrite {
  name?: string;
  sequence?: number;
  isActive?: boolean;
}

export const obImplementationStageHandlers = [
  http.get(url('/onboarding/implementation-stages'), ({ request }) => {
    const db = getDb();
    const isActive = new URL(request.url).searchParams.get('isActive');
    let rows = ordered(db.obImplementationStages);
    if (isActive != null) rows = rows.filter((s) => s.isActive === (isActive === 'true'));
    // No `meta`: unpaginated by CONVENTIONS.md §6, and the absent meta is the
    // signal that the list is complete.
    return ok(rows);
  }),

  http.post(url('/onboarding/implementation-stages'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as StageWrite;
    if (!body.name || !body.name.trim()) {
      return validationFailed({ name: ['Name is required'] });
    }
    if (nameTaken(body.name)) return duplicate(body.name.trim());

    const created: ObImplementationStage = {
      id: Math.max(0, ...db.obImplementationStages.map((s) => s.id)) + 1,
      name: body.name.trim(),
      // Appended, then moved only if the caller asked for a position — the
      // service's own two-step, and the reason the common case renumbers
      // nothing at all.
      sequence: db.obImplementationStages.length + 1,
      isActive: body.isActive ?? true,
    };
    db.obImplementationStages.push(created);
    if (body.sequence != null) renumber(created, body.sequence);

    return ok(created, undefined, { status: 201, headers: { ETag: etagOf(created) } });
  }),

  http.get(url('/onboarding/implementation-stages/:stageId'), ({ params }) => {
    const db = getDb();
    const stage = db.obImplementationStages.find((s) => s.id === Number(params.stageId));
    if (!stage) return notFound('Implementation stage');
    return ok(stage, undefined, { headers: { ETag: etagOf(stage) } });
  }),

  http.patch(url('/onboarding/implementation-stages/:stageId'), async ({ params, request }) => {
    const db = getDb();
    const stage = db.obImplementationStages.find((s) => s.id === Number(params.stageId));
    if (!stage) return notFound('Implementation stage');

    // Read before the write, against the row as it stands — the tag the caller
    // is holding was minted from exactly this content.
    const precondition = preconditionFailure(request.headers.get('If-Match'), etagOf(stage));
    if (precondition) return precondition;

    const body = (await request.json()) as StageWrite;
    if (body.name != null && !body.name.trim()) {
      return validationFailed({ name: ['Name is required'] });
    }
    if (body.name != null && nameTaken(body.name, stage.id)) return duplicate(body.name.trim());

    if (body.name != null) stage.name = body.name.trim();
    // Retiring drops the stage out of the pickers and changes nothing else —
    // it keeps its slot, which is why nothing below touches the ordering.
    if (body.isActive != null) stage.isActive = body.isActive;
    if (body.sequence != null) renumber(stage, body.sequence);

    return ok(stage, undefined, { headers: { ETag: etagOf(stage) } });
  }),
];
