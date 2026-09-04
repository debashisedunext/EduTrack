import { http } from 'msw';
import type { Db, ObJourney, ObStep } from '../db';
import { getDb } from '../db';
import { currentUser, notFound, ok, problem, url, validationFailed } from './util';

/**
 * C-104 · mocks for the step-lifecycle routes — start, complete,
 * block-with-mandatory-reason, waiting-on-client, resume.
 *
 * ⚠ **Stream C, in Stream D's `mocks/` directory** — `onboardingJourneys.ts`'s
 * own note applies verbatim: `coverage.test.ts` refuses a contract operation
 * with no MSW handler, so the alternative to this file is a red `develop` the
 * moment these five routes land.
 *
 * **Mirrors `ObJourneyStepLifecycleService` rule for rule**, not a
 * looser approximation:
 *
 * 1. **Row-scope, not a role check.** Only `step.ownerUserId` or
 *    `step.backupOwnerUserId` may act — `mayAct` below is
 *    `ObStepOwnership.mayAct` — everyone else gets a 422, never a 403.
 * 2. **`start` alone checks the journey's gate/hold**, `422` if `gateStatus`
 *    is not `OPEN` or `heldByJourneyId` is set. No dependency-graph check —
 *    that refusal, "naming the blocker", is C-119's, on both sides of the
 *    contract.
 * 3. **No completion gate on `complete`, no `due_at` maths anywhere** — C-106
 *    and C-105's own jobs, mirrored by omission here too.
 *
 * Fixture rows are `db.ts`'s `ObStep`/`ObJourney`, nested under `ObClient` —
 * reused rather than duplicated, since a second instance-row model next to
 * the real one is exactly the kind of drift this file's own warning is about.
 */

function findStepAndJourney(db: Db, stepId: number): { journey: ObJourney; step: ObStep } | undefined {
  for (const client of db.obClients) {
    for (const journey of client.journeys) {
      const step = journey.steps.find((s) => s.id === stepId);
      if (step) return { journey, step };
    }
  }
  return undefined;
}

function mayAct(step: ObStep, db: Db): boolean {
  const me = currentUser(db).id;
  return step.ownerUserId === me || step.backupOwnerUserId === me;
}

function stepDto(step: ObStep, journey: ObJourney) {
  return {
    id: step.id,
    journeyId: journey.id,
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
  };
}

/** `NotStepOwnerException`'s own wording and contract type, mirrored. */
function notStepOwnerProblem(step: ObStep) {
  const owner = step.ownerUserId != null ? ` (user ${step.ownerUserId})` : ' (unresolved)';
  const backup = step.backupOwnerUserId != null ? `, or its backup owner (user ${step.backupOwnerUserId})` : '';
  return problem(422, 'step-owner-required', "Only the step's owner or backup owner may update it", {
    detail: `journey step ${step.id} may only be updated by its owner${owner}${backup}`,
  });
}

/** `InvalidStepTransitionException`'s own wording and contract type, mirrored. */
function invalidTransitionProblem(step: ObStep, action: string) {
  return problem(422, 'invalid-step-transition', 'This step cannot make that move from its current status', {
    detail: `journey step ${step.id} cannot ${action} from status ${step.status}`,
  });
}

export const onboardingJourneyInstanceHandlers = [
  http.post(url('/onboarding/journey-steps/:stepId/start'), ({ params }) => {
    const db = getDb();
    const found = findStepAndJourney(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { journey, step } = found;

    if (!mayAct(step, db)) return notStepOwnerProblem(step);
    if (step.status !== 'PENDING') return invalidTransitionProblem(step, 'start');
    if (journey.gateStatus !== 'OPEN' || journey.heldByJourneyId != null) {
      return problem(422, 'journey-not-open', 'This journey is not open for step activity yet', {
        detail: `journey ${journey.id} is not open for step activity`
          + (journey.gateStatus !== 'OPEN' ? ' (gate LOCKED)' : '')
          + (journey.heldByJourneyId != null ? ` (held by journey ${journey.heldByJourneyId})` : ''),
      });
    }

    step.status = 'IN_PROGRESS';
    step.startedAt = new Date().toISOString();
    return ok(stepDto(step, journey));
  }),

  http.post(url('/onboarding/journey-steps/:stepId/complete'), ({ params }) => {
    const db = getDb();
    const found = findStepAndJourney(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { journey, step } = found;

    if (!mayAct(step, db)) return notStepOwnerProblem(step);
    if (step.status !== 'IN_PROGRESS') return invalidTransitionProblem(step, 'complete');

    step.status = 'DONE';
    step.finishedAt = new Date().toISOString();
    return ok(stepDto(step, journey));
  }),

  http.post(url('/onboarding/journey-steps/:stepId/block'), async ({ params, request }) => {
    const db = getDb();
    const found = findStepAndJourney(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { journey, step } = found;

    if (!mayAct(step, db)) return notStepOwnerProblem(step);
    if (step.status !== 'IN_PROGRESS') return invalidTransitionProblem(step, 'block');

    const body = (await request.json()) as { reasonCode?: string; note?: string | null };
    if (!body.reasonCode) return validationFailed({ reasonCode: ['Reason is required'] });

    step.status = 'BLOCKED';
    step.blockedReasonCode = body.reasonCode;
    step.blockedNote = body.note ?? null;
    return ok(stepDto(step, journey));
  }),

  http.post(url('/onboarding/journey-steps/:stepId/waiting-on-client'), ({ params }) => {
    const db = getDb();
    const found = findStepAndJourney(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { journey, step } = found;

    if (!mayAct(step, db)) return notStepOwnerProblem(step);
    if (step.status !== 'IN_PROGRESS') return invalidTransitionProblem(step, 'mark waiting-on-client');

    step.status = 'WAITING_ON_CLIENT';
    return ok(stepDto(step, journey));
  }),

  http.post(url('/onboarding/journey-steps/:stepId/resume'), ({ params }) => {
    const db = getDb();
    const found = findStepAndJourney(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { journey, step } = found;

    if (!mayAct(step, db)) return notStepOwnerProblem(step);
    if (step.status !== 'BLOCKED' && step.status !== 'WAITING_ON_CLIENT') {
      return invalidTransitionProblem(step, 'resume');
    }

    step.status = 'IN_PROGRESS';
    step.blockedReasonCode = null;
    step.blockedNote = null;
    return ok(stepDto(step, journey));
  }),
];
