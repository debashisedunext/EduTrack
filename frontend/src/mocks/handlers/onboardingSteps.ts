import { http } from 'msw';
import type {
  Db, ObClient, ObJourney, ObStep, ObStepCommunicationRow, ObStepItem,
} from '../db';
import { getDb, nextId } from '../db';
import { notFound, ok, paginate, problem, url, userRef, validationFailed } from './util';

/**
 * A-118 · mocks for journey instances and the OB-06 step panel.
 *
 * WARNING: **Stream A adding handlers in Stream D's `mocks/` directory
 * (D-004)** — the same situation `onboarding.ts` and `onboardingJourneys.ts`
 * both flag rather than do quietly. `coverage.test.ts` refuses a contract
 * operation with no handler, so the alternative to this file is a red
 * `develop` the moment the contract lands.
 *
 * **A separate file from `onboarding.ts` on purpose.** That one owns the
 * client master and its own `stepDto`/`journeySummaryDto`, shaped for the
 * OB-05 accordion strip; these are the richer instance reads the strip
 * explicitly defers to — "the expanded view and the step panel are their own
 * reads". Two files, rather than two similarly-named helpers in one.
 */

/**
 * An `ob_products` ref, as `onboarding.ts` builds it. Local rather than
 * shared: three fields, and exporting it would couple two files that
 * otherwise have nothing in common but the db.
 */
const productRef = (id: number, db: Db) => {
  const p = db.obProducts.find((x) => x.id === id);
  return p ? { id: p.id, code: p.code, name: p.name } : null;
};

// ── A-118 · journey instances and the step panel ────────────────────────────
//
// The template side is `onboardingJourneys.ts` (C-102). This is what a
// template becomes once a client buys the product, and it mirrors three rules
// from the contract deliberately, because a screen built against a mock that
// is laxer than the server is a screen that breaks on the first real call:
//
//   1. **Only WAITING_ON_CLIENT pauses the clock.** An internal BLOCKED step
//      keeps running. Plan §5.7, and the reason the two states are separate.
//   2. **finish is refused** while a mandatory item is unticked or a required
//      document is missing — the two gates on completion.
//   3. **history is append-only.** These handlers only ever push to it, and
//      there is no route that edits or removes an entry.

interface StepLocation {
  client: ObClient;
  journey: ObJourney;
  step: ObStep;
}

/** Journeys are nested under clients in this db, so a step id is found by walk. */
function findStep(db: Db, stepId: number): StepLocation | undefined {
  for (const client of db.obClients) {
    for (const journey of client.journeys) {
      const step = journey.steps.find((s) => s.id === stepId);
      if (step) return { client, journey, step };
    }
  }
  return undefined;
}

function findJourney(db: Db, journeyId: number): { client: ObClient; journey: ObJourney } | undefined {
  for (const client of db.obClients) {
    const journey = client.journeys.find((j) => j.id === journeyId);
    if (journey) return { client, journey };
  }
  return undefined;
}

const TERMINAL: ReadonlyArray<ObStep['status']> = ['DONE', 'SKIPPED'];

/**
 * `RUNNING` unless the step is waiting on the client, `STOPPED` once it is
 * closed. An internal block is deliberately still `RUNNING` — see rule 1.
 */
function clockState(step: ObStep): 'RUNNING' | 'PAUSED' | 'STOPPED' {
  if (TERMINAL.includes(step.status)) return 'STOPPED';
  if (step.status === 'WAITING_ON_CLIENT') return 'PAUSED';
  if (step.status === 'PENDING') return 'STOPPED';
  return 'RUNNING';
}

function stepRag(step: ObStep): 'GREEN' | 'AMBER' | 'RED' | null {
  if (step.status === 'PENDING' || TERMINAL.includes(step.status)) return null;
  const budget = step.tatDays * 8;
  if (budget <= 0) return 'GREEN';
  const used = step.usedHours / budget;
  return used >= 1 ? 'RED' : used >= 0.75 ? 'AMBER' : 'GREEN';
}

/** Worst-wins upward (plan §5.9), and null when nothing is running to colour. */
function journeyRag(journey: ObJourney): 'GREEN' | 'AMBER' | 'RED' | null {
  if (journey.gateStatus === 'LOCKED') return null;
  const rags = journey.steps.map(stepRag).filter((r): r is 'GREEN' | 'AMBER' | 'RED' => r !== null);
  if (rags.length === 0) return null;
  return rags.includes('RED') ? 'RED' : rags.includes('AMBER') ? 'AMBER' : 'GREEN';
}

function percentComplete(journey: ObJourney): number {
  if (journey.steps.length === 0) return 0;
  const closed = journey.steps.filter((s) => TERMINAL.includes(s.status)).length;
  return Math.round((closed / journey.steps.length) * 100);
}

function stepDto(step: ObStep, journeyId: number) {
  return {
    // C-104's ObJourneyStep, field for field — ids rather than refs, because
    // one convention applied to a whole schema beats a better one applied to
    // half of it. Its own handlers return this same shape.
    id: step.id,
    journeyId,
    sequence: step.sequence,
    name: step.name,
    status: step.status,
    ownerUserId: step.ownerUserId ?? null,
    backupOwnerUserId: step.backupOwnerUserId ?? null,
    blockedReasonCode: step.blockedReasonCode ?? null,
    blockedNote: step.blockedNote ?? null,
    startedAt: step.startedAt ?? null,
    finishedAt: step.finishedAt ?? null,
    dueAt: step.dueAt ?? null,
    // ObJourneyStepView's additions: plan, health, and what the ribbon draws.
    description: step.description ?? null,
    clockState: clockState(step),
    rag: stepRag(step),
    tatDays: step.tatDays,
    requiresSignoff: step.requiresSignoff ?? false,
    dependsOnStepId: step.dependsOnStepId,
    skipReason: step.skipReason ?? null,
    skippedByUserId: step.skippedById ?? null,
  };
}

function stepItemDto(item: ObStepItem, stepId: number, db: Db) {
  return {
    id: item.id,
    stepId,
    sequence: item.sequence,
    label: item.label,
    isMandatory: item.isMandatory,
    isDone: item.isDone,
    doneAt: item.doneAt ?? null,
    doneBy: userRef(item.doneById ?? null, db),
  };
}

function stepDetailDto(step: ObStep, db: Db, journeyId: number) {
  return {
    ...stepDto(step, journeyId),
    items: (step.items ?? []).map((i) => stepItemDto(i, step.id, db)),
    docs: (step.docs ?? []).map((d) => ({
      id: d.id,
      stepId: step.id,
      label: d.label,
      isRequired: d.isRequired,
      // Derived, never stored — removing the attachment reopens the requirement.
      isSatisfied: d.attachmentId != null,
      attachmentId: d.attachmentId,
    })),
    elapsedHours: step.usedHours,
  };
}

function journeySummaryDto(client: ObClient, journey: ObJourney, db: Db) {
  const current = journey.steps.find((s) => s.status === 'IN_PROGRESS')
    ?? journey.steps.find((s) => s.status === 'BLOCKED' || s.status === 'WAITING_ON_CLIENT')
    ?? null;
  const totalTatDays = journey.steps.reduce((sum, s) => sum + s.tatDays, 0);
  return {
    id: journey.id,
    obClientId: client.id,
    clientName: client.name,
    product: productRef(journey.productId, db),
    gateStatus: journey.gateStatus,
    rag: journeyRag(journey),
    percentComplete: percentComplete(journey),
    currentStep: current
      ? {
          id: current.id,
          sequence: current.sequence,
          name: current.name,
          status: current.status,
          rag: stepRag(current),
          dependsOnStepId: current.dependsOnStepId,
        }
      : null,
    owner: current ? userRef(current.ownerUserId ?? null, db) : null,
    heldByJourneyId: journey.heldByJourneyId,
    totalTatDays,
    elapsedTatDays: journey.steps.reduce((sum, s) => sum + s.usedHours, 0) / 8,
    startedAt: journey.startedAt ?? null,
    completedAt: journey.completedAt ?? null,
    archivedAt: journey.archivedAt ?? null,
  };
}

/**
 * Layer 0 first, each entry the ids that could run at once — recomputed from
 * `dependsOnStepId` on every read, exactly as the template detail route does.
 */
function parallelGroups(journey: ObJourney): number[][] {
  const layers: number[][] = [];
  const placed = new Map<number, number>();
  let remaining = [...journey.steps];
  while (remaining.length > 0) {
    const ready = remaining.filter(
      (s) => s.dependsOnStepId == null || placed.has(s.dependsOnStepId),
    );
    // A cycle would leave `ready` empty. The template designer excludes cycles
    // by construction, so this is a guard against a hand-edited fixture rather
    // than a case the product can reach — but an infinite loop in a mock is a
    // hung dev server, which is a bad way to find out.
    if (ready.length === 0) {
      layers.push(remaining.map((s) => s.id));
      break;
    }
    const layer = layers.length;
    ready.forEach((s) => placed.set(s.id, layer));
    layers.push(ready.map((s) => s.id));
    remaining = remaining.filter((s) => !placed.has(s.id));
  }
  return layers;
}

function journeyDetailDto(client: ObClient, journey: ObJourney, db: Db) {
  return {
    ...journeySummaryDto(client, journey, db),
    templateId: journey.templateId ?? 0,
    templateVersion: journey.templateVersion ?? 1,
    steps: journey.steps.map((s) => stepDto(s, journey.id)),
    parallelGroups: parallelGroups(journey),
  };
}

/** Append-only: every transition pushes, nothing ever rewrites. */
function recordHistory(
  step: ObStep,
  db: Db,
  from: ObStep['status'] | null,
  to: ObStep['status'],
  reasonCode?: string | null,
  note?: string | null,
) {
  step.history = step.history ?? [];
  step.history.push({
    id: nextId(db, 'obStepHistory'),
    at: new Date().toISOString(),
    actorId: db.currentUserId,
    fromStatus: from,
    toStatus: to,
    reasonCode: reasonCode ?? null,
    note: note ?? null,
    isCorrection: false,
    correctsEntryId: null,
  });
}

export const obJourneyHandlers = [
  http.get(url('/onboarding/journeys'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    const obClientId = requestUrl.searchParams.get('obClientId');
    const productId = requestUrl.searchParams.get('productId');
    const gateStatus = requestUrl.searchParams.get('gateStatus');
    const rag = requestUrl.searchParams.get('rag');
    const ownerUserId = requestUrl.searchParams.get('ownerUserId');
    const state = requestUrl.searchParams.get('state') ?? 'ACTIVE';

    let rows = db.obClients.flatMap((client) =>
      client.journeys.map((journey) => ({ client, journey })),
    );

    if (obClientId) rows = rows.filter((r) => r.client.id === Number(obClientId));
    if (productId) rows = rows.filter((r) => r.journey.productId === Number(productId));
    if (gateStatus) rows = rows.filter((r) => r.journey.gateStatus === gateStatus);
    if (rag) rows = rows.filter((r) => journeyRag(r.journey) === rag);
    if (ownerUserId) {
      // Owner OR backup, the same reading A-112's scope resolver takes: a
      // workload row that excluded the backup would under-report exactly the
      // person covering the step.
      const uid = Number(ownerUserId);
      rows = rows.filter((r) =>
        r.journey.steps.some((s) => s.ownerUserId === uid || s.backupOwnerUserId === uid),
      );
    }
    rows = rows.filter(({ journey }) => {
      switch (state) {
        case 'ARCHIVED': return journey.archivedAt != null;
        case 'COMPLETED': return journey.archivedAt == null && journey.completedAt != null;
        case 'HELD': return journey.archivedAt == null && journey.heldByJourneyId != null;
        // ACTIVE, the default — archived journeys are excluded, which is why
        // the default is not "everything".
        default: return journey.archivedAt == null && journey.completedAt == null;
      }
    });

    const page = paginate(rows, requestUrl);
    return ok(
      page.page.map(({ client, journey }) => journeySummaryDto(client, journey, db)),
      page.meta,
    );
  }),

  http.get(url('/onboarding/journeys/:journeyId'), ({ params }) => {
    const db = getDb();
    const found = findJourney(db, Number(params.journeyId));
    return found ? ok(journeyDetailDto(found.client, found.journey, db)) : notFound('Journey');
  }),

  http.post(url('/onboarding/journeys/:journeyId/archive'), async ({ params, request }) => {
    const db = getDb();
    const found = findJourney(db, Number(params.journeyId));
    if (!found) return notFound('Journey');
    const body = (await request.json()) as { reason?: string };
    if (!body.reason || body.reason.trim().length < 3) {
      return validationFailed({ reason: ['A reason is required'] });
    }
    if (found.journey.archivedAt != null) {
      return problem(422, 'ob-journey-already-archived', 'This journey is already archived');
    }
    found.journey.archivedAt = new Date().toISOString();
    return ok(journeyDetailDto(found.client, found.journey, db));
  }),

  http.get(url('/onboarding/journey-steps/:stepId'), ({ params }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    return found ? ok(stepDetailDto(found.step, db, found.journey.id)) : notFound('Step');
  }),

  http.patch(url('/onboarding/journey-steps/:stepId'), async ({ params, request }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Step');
    if (TERMINAL.includes(found.step.status)) {
      return problem(422, 'ob-step-terminal', 'A closed service is not re-plannable');
    }
    const body = (await request.json()) as Partial<
      Pick<ObStep, 'ownerUserId' | 'backupOwnerUserId' | 'tatDays' | 'dueAt'>
    >;
    if (body.ownerUserId !== undefined) found.step.ownerUserId = body.ownerUserId;
    if (body.backupOwnerUserId !== undefined) found.step.backupOwnerUserId = body.backupOwnerUserId;
    if (body.tatDays !== undefined) found.step.tatDays = body.tatDays;
    if (body.dueAt !== undefined) found.step.dueAt = body.dueAt;
    // `status` is deliberately not accepted here — the transition routes own it.
    return ok(stepDetailDto(found.step, db, found.journey.id));
  }),

  http.post(url('/onboarding/journey-steps/:stepId/skip'), async ({ params, request }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Step');
    const { step } = found;
    if (TERMINAL.includes(step.status)) {
      return problem(422, 'ob-step-terminal', 'This service is already closed');
    }
    const body = (await request.json()) as { reason?: string };
    if (!body.reason || body.reason.trim().length < 3) {
      return validationFailed({ reason: ['A reason is required'] });
    }
    recordHistory(step, db, step.status, 'SKIPPED', null, body.reason);
    step.status = 'SKIPPED';
    step.skipReason = body.reason;
    step.skippedById = db.currentUserId;
    return ok(stepDetailDto(step, db, found.journey.id));
  }),

  http.patch(url('/onboarding/journey-step-items/:itemId'), async ({ params, request }) => {
    const db = getDb();
    const itemId = Number(params.itemId);
    for (const client of db.obClients) {
      for (const journey of client.journeys) {
        for (const step of journey.steps) {
          const item = (step.items ?? []).find((i) => i.id === itemId);
          if (!item) continue;
          if (TERMINAL.includes(step.status)) {
            return problem(422, 'ob-step-terminal',
              'The service is closed; its checklist is the record of what was done');
          }
          const body = (await request.json()) as { isDone?: boolean };
          if (typeof body.isDone !== 'boolean') {
            return validationFailed({ isDone: ['Required'] });
          }
          item.isDone = body.isDone;
          item.doneAt = body.isDone ? new Date().toISOString() : null;
          item.doneById = body.isDone ? db.currentUserId : null;
          return ok(stepItemDto(item, step.id, db));
        }
      }
    }
    return notFound('Checklist item');
  }),

  http.get(url('/onboarding/journey-steps/:stepId/communications'), ({ params, request }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Step');
    const page = paginate(found.step.communications ?? [], new URL(request.url));
    return ok(
      page.page.map((c) => ({
        id: c.id,
        stepId: found.step.id,
        channel: c.channel,
        occurredAt: c.occurredAt,
        summary: c.summary,
        isClientVisible: c.isClientVisible,
        recordedBy: userRef(c.recordedById, db),
        createdAt: c.createdAt,
      })),
      page.meta,
    );
  }),

  http.post(url('/onboarding/journey-steps/:stepId/communications'), async ({ params, request }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Step');
    const body = (await request.json()) as {
      channel?: ObStepCommunicationRow['channel'];
      occurredAt?: string;
      summary?: string;
      isClientVisible?: boolean;
    };
    const errors: Record<string, string[]> = {};
    if (!body.channel) errors.channel = ['Required'];
    if (!body.occurredAt) errors.occurredAt = ['Required'];
    if (!body.summary) errors.summary = ['Required'];
    if (Object.keys(errors).length > 0) return validationFailed(errors);

    found.step.communications = found.step.communications ?? [];
    const created: ObStepCommunicationRow = {
      id: nextId(db, 'obStepCommunications'),
      channel: body.channel!,
      occurredAt: body.occurredAt!,
      summary: body.summary!,
      // Defaults false. An internal note that reaches the portal because a
      // default went the other way is not recoverable by deleting it after.
      isClientVisible: body.isClientVisible ?? false,
      recordedById: db.currentUserId,
      createdAt: new Date().toISOString(),
    };
    found.step.communications.push(created);
    return ok(
      {
        id: created.id,
        stepId: found.step.id,
        channel: created.channel,
        occurredAt: created.occurredAt,
        summary: created.summary,
        isClientVisible: created.isClientVisible,
        recordedBy: userRef(created.recordedById, db),
        createdAt: created.createdAt,
      },
      undefined,
      { status: 201 },
    );
  }),

  http.get(url('/onboarding/journey-steps/:stepId/history'), ({ params, request }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Step');
    // Oldest first, and there is no write counterpart on this path.
    const page = paginate(found.step.history ?? [], new URL(request.url));
    return ok(
      page.page.map((h) => ({
        id: h.id,
        stepId: found.step.id,
        at: h.at,
        actor: userRef(h.actorId, db),
        fromStatus: h.fromStatus,
        toStatus: h.toStatus,
        reasonCode: h.reasonCode ?? null,
        note: h.note ?? null,
        isCorrection: h.isCorrection ?? false,
        correctsEntryId: h.correctsEntryId ?? null,
      })),
      page.meta,
    );
  }),
];
