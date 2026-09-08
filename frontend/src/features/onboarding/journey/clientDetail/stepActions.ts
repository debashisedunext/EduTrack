import type { ObJourneyStepDoc, ObJourneyStepItem, ObJourneyStepView } from '@/api/generated/model'

import type { JourneyHold } from './journeyStrip'

/**
 * C-111 · OB-06's rules, as data — what a step owner may do to this step right
 * now, and what would refuse them if they tried.
 *
 * ## This file mirrors the server; it does not decide
 *
 * Every rule below has a counterpart in
 * `ObJourneyStepLifecycleService`/`ObStepOwnership`, and the server is the one
 * that holds. CLAUDE.md's line — "enforce it in the transition service, not in
 * the UI" — is not softened by any of this: what a screen owes the user is to
 * stop offering a control whose only outcome is a refusal, and to say *why*
 * before the click rather than after it.
 *
 * The failure this avoids is specific. `POST /complete` on a step with an
 * unticked mandatory item answers `422 ob-step-items-outstanding`, and a panel
 * that renders Complete as an enabled button is a panel that teaches its user
 * to press it and read an error. The Task List is right there; the gate should
 * be visible in it.
 */

/** The five transitions `ObJourneyStepLifecycleController` exposes. */
export type StepAction = 'start' | 'complete' | 'block' | 'waiting' | 'resume'

/**
 * What the server will accept from each status, transcribed from
 * `ObJourneyStepLifecycleService` and its mock.
 *
 * `PENDING` covers both "gate still locked" and "dependency not met"
 * (`ObJourneyStepStatus`'s own note), so `start` appearing here is necessary
 * but not sufficient — `startRefusal` below is the rest of that answer.
 *
 * `DONE` and `SKIPPED` are terminal and deliberately have no entry: there is
 * no reopen transition in the module, and offering one that 422s would be
 * inventing a lifecycle the plan does not have.
 */
const ALLOWED: Record<string, readonly StepAction[]> = {
  PENDING: ['start'],
  IN_PROGRESS: ['complete', 'block', 'waiting'],
  BLOCKED: ['resume'],
  WAITING_ON_CLIENT: ['resume'],
  DONE: [],
  SKIPPED: [],
}

export function allowedActions(status: string | undefined): readonly StepAction[] {
  return ALLOWED[status ?? ''] ?? []
}

/**
 * `ObStepOwnership.mayAct`, exactly — the owner **or the backup owner**, and
 * nobody else.
 *
 * **No Manager/Admin override, deliberately.** `NotStepOwnerException`'s
 * javadoc is explicit that the plan's "override steps with logged reason" is
 * absent because the module has no role vocabulary wired to an authority yet,
 * and that faking it with a ticketing role "that means something else
 * entirely" is not this task's to do. A UI that showed the buttons to an Admin
 * would be promising an override the server does not implement.
 *
 * **False while `useGetMe()` is still resolving.** `levelChange.ts` draws the
 * same line one module over and for the same reason: a null caller is not a
 * permitted caller, and rendering the actions optimistically for the half
 * second before identity arrives means a button that appears and then vanishes
 * under the cursor.
 */
export function mayActOnStep(
  step: Pick<ObJourneyStepView, 'ownerUserId' | 'backupOwnerUserId'> | undefined,
  meId: number | null | undefined,
): boolean {
  if (!step || meId == null) return false
  return step.ownerUserId === meId || step.backupOwnerUserId === meId
}

/**
 * Why `start` would be refused even though the status permits it.
 *
 * A journey instantiates `LOCKED` — every step visible, owners resolved, TATs
 * shown, and the clocks dead (plan §5.2) — and a journey past the gate can
 * still be held behind a sibling (§5.5). The server answers both with
 * `journey-not-open`, one problem code covering two quite different things a
 * reader would act on differently: clearing prerequisites is work somebody can
 * do today, waiting for a sibling journey is not.
 *
 * So the screen separates them here, from the hold the accordion already
 * computed, rather than parsing the refusal text after the fact.
 */
export function startRefusal(hold: JourneyHold): string | null {
  if (hold === 'GATE_LOCKED') {
    return 'This journey is still behind the client’s prerequisites. Clear the gate above to start work.'
  }
  if (hold === 'HELD_BY_SIBLING') {
    return 'This journey is held until another of the client’s services finishes.'
  }
  return null
}

/**
 * The completion gate (C-106), computed from the step's own Task List and
 * required documents so the panel can show it *before* Complete is pressed.
 *
 * Both halves are the server's `ObCompletionGateProblem` read forwards: it
 * answers with `unansweredMandatoryItems` and `missingRequiredDocs`, and these
 * are the same two facts derived from the same rows. `signoffMissing` is the
 * third and is **not** computed here — nothing in this read says whether a
 * `SIGNED` sign-off exists, and guessing would be worse than the server's own
 * refusal, which the panel surfaces verbatim.
 */
export interface CompletionGate {
  unansweredMandatory: string[]
  missingRequiredDocs: number
  /** Whether the two gates this screen *can* see are satisfied. Never a
   * promise that `complete` will succeed — see `signoffMissing` above. */
  isSatisfied: boolean
}

export function completionGate(
  items: readonly ObJourneyStepItem[] | undefined,
  docs: readonly ObJourneyStepDoc[] | undefined,
): CompletionGate {
  const unansweredMandatory = (items ?? [])
    .filter((item) => item.isMandatory && !item.isDone)
    .map((item) => item.label)

  const missingRequiredDocs = (docs ?? []).filter((doc) => doc.isRequired && !doc.isSatisfied).length

  return {
    unansweredMandatory,
    missingRequiredDocs,
    isSatisfied: unansweredMandatory.length === 0 && missingRequiredDocs === 0,
  }
}

/**
 * One sentence naming what is outstanding, or null when nothing this screen
 * can see is.
 *
 * Named rather than counted. "2 items outstanding" sends the reader hunting
 * through a checklist they are already looking at; the labels are what tells
 * them which two, and `ObCompletionGateProblem` carries labels rather than a
 * count for exactly the same reason.
 */
export function completionGateSummary(gate: CompletionGate): string | null {
  if (gate.isSatisfied) return null

  const parts: string[] = []
  if (gate.unansweredMandatory.length > 0) {
    parts.push(
      gate.unansweredMandatory.length === 1
        ? `“${gate.unansweredMandatory[0]}” is not ticked`
        : `${gate.unansweredMandatory.length} mandatory items are not ticked: ${gate.unansweredMandatory
            .map((label) => `“${label}”`)
            .join(', ')}`,
    )
  }
  if (gate.missingRequiredDocs > 0) {
    parts.push(
      gate.missingRequiredDocs === 1
        ? '1 required document has nothing against it'
        : `${gate.missingRequiredDocs} required documents have nothing against them`,
    )
  }
  return parts.join(' · ')
}

export const ACTION_LABELS: Record<StepAction, string> = {
  start: 'Start',
  complete: 'Complete',
  block: 'Block',
  waiting: 'Waiting on client',
  resume: 'Resume',
}

/**
 * Why an action the status permits is nonetheless unavailable, or null when it
 * is available. One function so the button and its tooltip cannot disagree.
 */
export function actionRefusal(
  action: StepAction,
  args: { hold: JourneyHold; gate: CompletionGate },
): string | null {
  if (action === 'start') return startRefusal(args.hold)
  if (action === 'complete') {
    const summary = completionGateSummary(args.gate)
    return summary && `This service cannot be completed yet — ${summary}.`
  }
  return null
}
