import { http } from 'msw';
import type { Db, ObStep, ObStepItem } from '../db';
import { getDb } from '../db';
import { findStep, stepDetailDto, stepItemDto } from './onboardingSteps';
import { notFound, ok, problem, url, validationFailed } from './util';

/**
 * OB-16 · mocks for the manager review gate — submit, send-back, verdict,
 * close, and the owner's outcomes-seen stamp.
 *
 * ⚠️ **Stream B, in Stream D's `mocks/` (D-004)** — `onboardingSteps.ts` and
 * `onboardingJourneyInstances.ts` both carry this note for the same reason:
 * `coverage.test.ts` refuses a contract operation with no MSW handler, so the
 * alternative to this file is a red `develop` the moment these five routes
 * land. Flagged rather than done quietly (TEAM-PLAN.md §6).
 *
 * <h2>The two states, and why the mock keeps them apart</h2>
 *
 * `rowState` is where the row *is* — `DRAFT` on the implementor's desk, `SENT`
 * on the manager's, `VERIFIED`/`REJECTED` once settled. `reviewState` is the
 * *verdict*, and it means nothing until the row is `SENT`. Collapsing the two
 * is the mistake `V20260917_1210__ob_step_item_row_state.sql` exists to undo:
 * a review used to be a property of the task, which could express neither
 * finishing two rows of five and wanting those looked at now, nor reading
 * three of the five that arrived and leaving the rest for after lunch.
 *
 * <h2>Rules mirrored from `ObJourneyStepLifecycleService`, not approximated</h2>
 *
 * 1. **Ownership is 422, capability is 403.** `submit` and `outcomes-seen` are
 *    the owner's, and a non-owner gets `ob-step-owner-required` (422) — the
 *    same status `completeObJourneyStep` answers, because the refusal depends
 *    on the row. `review`, `send-back` and `review/complete` are the
 *    reviewer's, and a caller who is not one gets `step-moderator-required`
 *    (403), because that refusal depends only on their role.
 * 2. **Nothing settles on a verdict.** `reviewItem` marks a row and stops; the
 *    reviewer may cycle any row as often as they like. `closeReview` is the
 *    only way an accepted review closes, which is why it exists as its own
 *    route rather than firing on the last verdict.
 * 3. **A rejection must say why, and a verification must not overwrite.**
 *    `remark` is required on `REJECTED` and written to the row's own remark;
 *    on the other two states it is ignored rather than refused, so verifying
 *    can never wipe the implementor's note.
 * 4. **On a return, only the rejected rows reopen.** Verified rows are locked
 *    for good and are not put in front of the manager again on resubmission.
 *
 * What is deliberately *not* mirrored: the server decides a reviewer from the
 * project's `implementorManagerUserId`, and the fixture db has no such link on
 * every project. `isReviewer` below reads the module role instead, which is the
 * half of `requireReviewer` that `npm run dev` can actually exercise — named
 * here rather than left to be discovered, on `onboardingJourneyInstances.ts`'s
 * own precedent for flagging a gap.
 */

/** The module roles that may review. `requireReviewer`'s role half. */
const REVIEWER_ROLES = ['OB_ADMIN', 'OB_MANAGER'];

function moduleRoleOf(db: Db): string | null {
  const grant = db.obModuleAccess.find(
    (g) => g.userId === db.currentUserId && g.module === 'ONBOARDING' && g.revokedAt === null,
  );
  return grant?.moduleRole ?? null;
}

/**
 * A caller with no onboarding standing at all is answered 404, not 403, so
 * neither route confirms that an id exists to somebody outside the module —
 * `requireModuleStanding`'s own comment, and the reason these paths can sit in
 * `ROWLESS_403` honestly.
 */
function reviewerGate(db: Db) {
  const role = moduleRoleOf(db);
  if (!role) return notFound('Journey step');
  if (!REVIEWER_ROLES.includes(role)) {
    return problem(403, 'step-moderator-required',
      'Only an onboarding Manager or Admin may do this', {
        detail: `module role ${role} may not review another implementor's work`,
      });
  }
  return null;
}

function mayAct(step: ObStep, db: Db): boolean {
  return step.ownerUserId === db.currentUserId || step.backupOwnerUserId === db.currentUserId;
}

/** `NotStepOwnerException` — 422, never 403. The refusal depends on the row. */
function notStepOwner(step: ObStep) {
  return problem(422, 'step-owner-required',
    "Only the step's owner or backup owner may update it", {
      detail: `journey step ${step.id} may only be updated by its owner`,
    });
}

interface ItemLocation {
  item: ObStepItem;
  step: ObStep;
}

function findItem(db: Db, itemId: number): ItemLocation | undefined {
  for (const client of db.obClients) {
    for (const journey of client.journeys) {
      for (const step of journey.steps) {
        const item = (step.items ?? []).find((i) => i.id === itemId);
        if (item) return { item, step };
      }
    }
  }
  return undefined;
}

const rowStateOf = (item: ObStepItem) => item.rowState ?? 'DRAFT';

/**
 * Where the task lands once a row moves — the server's `settleAfterRowMove`.
 *
 * `PENDING_REVIEW` while anything is on the manager's desk; back to
 * `IN_PROGRESS` the moment a rejection returns something to the implementor;
 * `DONE` only when every row has settled and none was rejected.
 */
function settle(step: ObStep) {
  const items = step.items ?? [];
  if (items.length === 0) return;
  const states = items.map(rowStateOf);
  if (states.includes('SENT')) {
    step.status = 'PENDING_REVIEW';
    return;
  }
  if (states.includes('REJECTED')) {
    step.status = 'IN_PROGRESS';
    return;
  }
  if (states.every((s) => s === 'VERIFIED')) {
    step.status = 'DONE';
    step.finishedAt = new Date().toISOString();
  }
}

export const obStepReviewHandlers = [
  /**
   * One row onto the manager's desk. The implementor's, so ownership decides
   * it — and an unanswered row cannot be submitted, because there is nothing
   * on it to review.
   */
  http.post(url('/onboarding/journey-step-items/:itemId/submit'), ({ params }) => {
    const db = getDb();
    const found = findItem(db, Number(params.itemId));
    if (!found) return notFound('Checklist item');
    const { item, step } = found;

    if (!mayAct(step, db)) return notStepOwner(step);
    if (step.status === 'DONE' || step.status === 'SKIPPED') {
      return problem(422, 'ob-step-terminal', 'This service is already closed');
    }
    if (rowStateOf(item) === 'VERIFIED') {
      return problem(422, 'ob-step-item-already-verified',
        'This entry has been verified and is final');
    }
    if (rowStateOf(item) === 'SENT') {
      return problem(422, 'ob-step-under-review', 'This entry is already with its reviewer');
    }
    if (item.answer == null) {
      return problem(422, 'ob-step-items-outstanding', 'Answer the entry before submitting it', {
        detail: `checklist item ${item.id} has no answer on it`,
      });
    }

    item.rowState = 'SENT';
    item.reviewState = 'NOT_REVIEWED';
    item.submittedAt = new Date().toISOString();
    item.submittedById = db.currentUserId;
    // A resubmission is a fresh look: last round's verdict and its stamp go.
    item.reviewedAt = null;
    item.reviewedById = null;
    item.outcomeSeenAt = null;
    settle(step);
    return ok(stepItemDto(item, step.id, db));
  }),

  /**
   * One row back to its implementor without a verdict — the reviewer's per-row
   * Send. Distinct from a rejection: nothing is being judged, the row is simply
   * returned, so no reason is required and the implementor's remark is left
   * exactly as they wrote it.
   */
  http.post(url('/onboarding/journey-step-items/:itemId/send-back'), ({ params }) => {
    const db = getDb();
    const found = findItem(db, Number(params.itemId));
    if (!found) return notFound('Checklist item');
    const { item, step } = found;

    const refused = reviewerGate(db);
    if (refused) return refused;
    if (rowStateOf(item) !== 'SENT') {
      return problem(422, 'invalid-step-transition',
        'This entry is not on a reviewer\'s desk', {
          detail: `checklist item ${item.id} is ${rowStateOf(item)}, not SENT`,
        });
    }

    item.rowState = 'DRAFT';
    item.reviewState = 'NOT_REVIEWED';
    item.submittedAt = null;
    item.submittedById = null;
    settle(step);
    return ok(stepItemDto(item, step.id, db));
  }),

  /**
   * A verdict on one row. **Settles nothing** — see rule 2 in the file header;
   * `review/complete` is where the reviewer says they are finished.
   */
  http.patch(url('/onboarding/journey-step-items/:itemId/review'), async ({ params, request }) => {
    const db = getDb();
    const found = findItem(db, Number(params.itemId));
    if (!found) return notFound('Checklist item');
    const { item, step } = found;

    const refused = reviewerGate(db);
    if (refused) return refused;

    const body = (await request.json()) as { state?: string; remark?: string | null };
    const state = body.state;
    if (!state || !['NOT_REVIEWED', 'VERIFIED', 'REJECTED'].includes(state)) {
      return validationFailed({ state: ['One of NOT_REVIEWED, VERIFIED, REJECTED'] });
    }
    if (rowStateOf(item) === 'VERIFIED') {
      return problem(422, 'ob-step-item-already-verified',
        'This entry has been verified and is final');
    }
    if (rowStateOf(item) !== 'SENT') {
      return problem(422, 'invalid-step-transition', 'This entry is not under review', {
        detail: `checklist item ${item.id} is ${rowStateOf(item)}, not SENT`,
      });
    }

    const remark = body.remark?.trim() || null;
    item.reviewState = state as ObStepItem['reviewState'];
    const decided = state !== 'NOT_REVIEWED';
    item.reviewedAt = decided ? new Date().toISOString() : null;
    item.reviewedById = decided ? db.currentUserId : null;
    // Only ever written, never cleared — a reject that arrives before the
    // reason is typed must not wipe what is already on the row, and a verdict
    // of Verified must not touch the implementor's note at all.
    if (state === 'REJECTED' && remark) item.remark = remark;

    return ok(stepItemDto(item, step.id, db));
  }),

  /**
   * OB-16 · the task's **Send for verification** — every open row onto the
   * manager's desk in one move.
   *
   * The list goes as a unit, so the unit has to be complete: one unanswered
   * open row refuses the whole send. That is the difference from the per-row
   * route above, which deliberately lets two of five go now and the rest keep.
   * Looping the per-row route from a client is what this replaces — when one
   * call of five failed it left some rows with the reviewer and the rest with
   * their implementor, and a `PENDING_REVIEW` that was true of most of the task.
   *
   * Rows already `VERIFIED` in an earlier round are left alone and are not
   * counted against the gate: they are shut for good, and a rejection that
   * brought their neighbours back must not ask for them again.
   */
  http.post(url('/onboarding/journey-steps/:stepId/checklist/submit'), ({ params }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { step, journey } = found;

    if (!mayAct(step, db)) return notStepOwner(step);
    if (step.status === 'DONE' || step.status === 'SKIPPED') {
      return problem(422, 'ob-step-terminal', 'This service is already closed');
    }

    const open = (step.items ?? []).filter((i) => rowStateOf(i) === 'DRAFT');
    if (open.length === 0) {
      return problem(422, 'ob-step-under-review',
        'There is nothing left to send — the list is already out, or every row is shut');
    }
    const blank = open.filter((i) => i.answer == null).map((i) => i.label);
    if (blank.length > 0) {
      return problem(422, 'completion-gate-not-satisfied',
        'Answer every entry before sending the list', { unansweredMandatoryItems: blank });
    }

    const now = new Date().toISOString();
    for (const item of open) {
      item.rowState = 'SENT';
      item.reviewState = 'NOT_REVIEWED';
      item.submittedAt = now;
      item.submittedById = db.currentUserId;
      // A resubmission is a fresh look: last round's verdict and stamp go.
      item.reviewedAt = null;
      item.reviewedById = null;
      item.outcomeSeenAt = null;
    }
    settle(step);
    return ok(stepDetailDto(step, db, journey.id, journey));
  }),

  /**
   * OB-16 · **one verdict for the whole list** — the reviewer's *Verification
   * done*. A manager decides about the task, not about line four.
   *
   * **An acceptance does not close the task**: every row becomes `VERIFIED`
   * and it comes back to its owner. `review/complete` is still the deliberate
   * press that ends it, so a manager who pressed Verified meaning Reject has
   * not already released this task's dependants.
   *
   * A rejection returns the whole list unanswered, carrying its reason on
   * every row it is about — the claim each row held is withdrawn with the
   * verdict, so the implementor asserts the work again rather than
   * resubmitting what was refused. Rows shut in an earlier round stay shut.
   */
  http.post(url('/onboarding/journey-steps/:stepId/review/verdict'), async ({ params, request }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { step, journey } = found;

    const refused = reviewerGate(db);
    if (refused) return refused;

    const body = (await request.json()) as { state?: string; remark?: string | null };
    const state = body.state;
    if (!state || !['NOT_REVIEWED', 'VERIFIED', 'REJECTED'].includes(state)) {
      return validationFailed({ state: ['One of NOT_REVIEWED, VERIFIED, REJECTED'] });
    }
    if (step.status !== 'PENDING_REVIEW') {
      return problem(422, 'invalid-step-transition',
        'This step cannot make that move from its current status', {
          detail: `journey step ${step.id} is ${step.status}, not PENDING_REVIEW`,
        });
    }
    // `NOT_REVIEWED` was how a per-row verdict was taken back. There is no row
    // to take it back on here, so it is refused rather than silently ignored.
    if (state === 'NOT_REVIEWED') {
      return problem(422, 'invalid-step-transition',
        'A whole-list verdict is Verified or Rejected, never Not reviewed');
    }
    const remark = body.remark?.trim() || null;
    if (state === 'REJECTED' && !remark) {
      return problem(422, 'ob-step-reject-reason-required', 'Say why the list is going back');
    }

    const out = (step.items ?? []).filter((i) => rowStateOf(i) === 'SENT');
    if (out.length === 0) {
      return problem(422, 'ob-step-under-review', 'Nothing is out to give a verdict on');
    }

    const now = new Date().toISOString();
    for (const item of out) {
      item.reviewState = state as ObStepItem['reviewState'];
      item.reviewedAt = now;
      item.reviewedById = db.currentUserId;
      item.rowState = state === 'REJECTED' ? 'REJECTED' : 'VERIFIED';
      item.outcomeSeenAt = null;
      if (state === 'REJECTED') {
        item.remark = remark;
        // The claim is withdrawn with the verdict — the implementor asserts
        // the work again rather than resubmitting what was refused.
        item.answer = null;
        item.isDone = false;
        item.doneAt = null;
        item.doneById = null;
        item.rowState = 'DRAFT';
      }
    }
    settle(step);
    return ok(stepDetailDto(step, db, journey.id, journey));
  }),

  /**
   * `PENDING_REVIEW` → `DONE`, and the only way an accepted review closes.
   * Refuses while any row on the desk is still undecided, naming them in the
   * same shape the owner's own completion gate answers with.
   */
  http.post(url('/onboarding/journey-steps/:stepId/review/complete'), ({ params }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { step, journey } = found;

    const refused = reviewerGate(db);
    if (refused) return refused;
    if (step.status !== 'PENDING_REVIEW') {
      return problem(422, 'invalid-step-transition',
        'This step cannot make that move from its current status', {
          detail: `journey step ${step.id} cannot close review from status ${step.status}`,
        });
    }

    const onDesk = (step.items ?? []).filter((i) => rowStateOf(i) === 'SENT');
    const undecided = onDesk
      .filter((i) => (i.reviewState ?? 'NOT_REVIEWED') === 'NOT_REVIEWED')
      .map((i) => i.label);
    if (undecided.length > 0) {
      return problem(422, 'ob-step-items-outstanding',
        'Every entry needs a verdict before the review can close', {
          unansweredMandatoryItems: undecided,
        });
    }

    const now = new Date().toISOString();
    for (const item of onDesk) {
      const verdict = item.reviewState ?? 'NOT_REVIEWED';
      item.rowState = verdict === 'REJECTED' ? 'REJECTED' : 'VERIFIED';
      item.reviewedAt = item.reviewedAt ?? now;
      item.reviewedById = item.reviewedById ?? db.currentUserId;
      // Settled and not yet looked at — what `unseenOutcome` is derived from.
      item.outcomeSeenAt = null;
    }
    settle(step);
    return ok(stepDetailDto(step, db, journey.id, journey));
  }),

  /**
   * The owner's "I have read the outcomes" stamp. Idempotent, and a task with
   * nothing new answers `0` having written nothing — which is what stops "2
   * rows came back" either shouting for ever or forgetting on refresh.
   *
   * **The owner's, not the reader's**: a manager or an admin opening the task
   * does not quietly mark it read for the implementor who has not.
   */
  http.post(url('/onboarding/journey-steps/:stepId/outcomes-seen'), ({ params }) => {
    const db = getDb();
    const found = findStep(db, Number(params.stepId));
    if (!found) return notFound('Journey step');
    const { step } = found;

    if (!mayAct(step, db)) return notStepOwner(step);

    const now = new Date().toISOString();
    let cleared = 0;
    for (const item of step.items ?? []) {
      const settled = ['VERIFIED', 'REJECTED'].includes(rowStateOf(item));
      if (settled && item.outcomeSeenAt == null) {
        item.outcomeSeenAt = now;
        cleared++;
      }
    }
    return ok({ stepId: step.id, cleared });
  }),
];
