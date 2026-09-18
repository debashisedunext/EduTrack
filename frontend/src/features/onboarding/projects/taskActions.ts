import type { ObCompletionGateProblemAllOf } from '@/api/generated/model/obCompletionGateProblemAllOf'
import { ApiError } from '@/api/http'

import type { ProjectTask } from './useProjectTasks'

/**
 * Everything that can be done to one task, and what the server says when it
 * refuses — the vocabulary of {@link ObTaskActionBar}, kept pure and tested
 * apart from the row that draws it.
 *
 * <h2>Why the list is computed rather than written out in JSX</h2>
 *
 * <p>Which buttons exist depends on the task's status, which are enabled
 * depends on three separate things — whose task it is, whether it is closed,
 * and what the check list still owes — and each of those has a sentence that
 * has to reach the reader when it disables something. Written inline that is
 * seven conditionals interleaved with markup, and the first person to add an
 * action gets one of them wrong. Written here it is a table, and the table is
 * what the tests assert on.
 */

export type TaskActionKey =
  | 'start'
  | 'send'
  | 'complete'
  | 'waitingOnClient'
  | 'resume'
  | 'waitingOnService'
  | 'block'
  | 'reassign'
  | 'attach'
  | 'communication'

/**
 * `state` changes the task and its TAT clock; `other` does not.
 *
 * <p>The bar draws a divider between them. Seven undifferentiated buttons is a
 * toolbar somebody has to read every time; four that move the task and three
 * that do not is two short lists.
 */
export type TaskActionGroup = 'state' | 'other'

export interface TaskAction {
  key: TaskActionKey
  label: string
  /** Decorative — every button carries its words. */
  icon: string
  tone: 'primary' | 'outline' | 'danger' | 'plain'
  group: TaskActionGroup
  enabled: boolean
  /**
   * Why not, when not.
   *
   * <p>Always set when `enabled` is false, and always rendered as `title`. The
   * bar also prints Complete's in text, because "the button is grey" is not an
   * answer a reader can act on and a tooltip is not one they will find.
   */
  reason?: string
  /** `0/2` on Attach — satisfied over required. */
  badge?: string
  /** Pressing it opens a dialog rather than acting. */
  opensDialog: boolean
}

export interface TaskActionContext {
  task: ProjectTask
  /** Owner or backup owner — `isMine`, the rule the server enforces on writes. */
  yours: boolean
  /**
   * `OB_ADMIN` or `OB_MANAGER`.
   *
   * <p>Reassign is theirs rather than the owner's:
   * `ObJourneyStepLifecycleService.update` is gated by `requireModerator`
   * precisely so an owner cannot reassign work off themselves.
   */
  isModerator: boolean
  /** What Complete is waiting on, already computed by the panel. */
  blockers: readonly string[]
  /**
   * Which half of the round the owner's one button is in.
   *
   * <p>`SEND` — there are check-list rows the manager has not passed yet, so
   * the button says **Send for Verification** and hands the list over.
   * `COMPLETE` — every row is verified, or the task has no check list at all,
   * so it says **Mark Complete** and closes the task.
   *
   * <p>One button rather than two because it is one idea — *I am finished with
   * my part* — and which thing that means is never the reader's to choose: a
   * task with unverified rows cannot be completed, and a task whose rows are
   * all verified has nothing left to send. Two buttons would put one
   * permanently grey beside the other.
   */
  checklistPhase: 'SEND' | 'COMPLETE'
  /**
   * What holds **Send for Verification** — unanswered rows, in `blockers`'
   * own shape.
   *
   * <p>Separate from `blockers` because the two gates are not the same gate.
   * `POST /checklist/submit` asks only that every open row is answered;
   * required documents and the client sign-off are `POST /complete`'s
   * business, and holding the send on them would stop a list reaching its
   * reviewer over a file nobody needs until the task closes.
   */
  sendBlockers: readonly string[]
  /** For the "only X can do this" sentence. */
  ownerName: string
  /** A mutation is in flight; everything that writes is held. */
  busy: boolean
}

function isTerminal(task: ProjectTask): boolean {
  return task.status === 'DONE' || task.status === 'SKIPPED'
}

function isPaused(task: ProjectTask): boolean {
  return task.status === 'WAITING_ON_CLIENT' || task.status === 'BLOCKED'
}

export function taskActions(ctx: TaskActionContext): TaskAction[] {
  const { task, yours, isModerator, blockers, sendBlockers, checklistPhase, ownerName, busy } = ctx
  const terminal = isTerminal(task)
  const paused = isPaused(task)

  const notYours = `Only ${ownerName} can do this`
  const closed = 'This task is already closed'

  /** The three reasons a write is refused, in the order a reader meets them. */
  const writeBlock = (extra?: string): string | undefined => {
    if (!yours) return notYours
    if (terminal) return closed
    if (busy) return 'Working…'
    return extra
  }

  /*
    The fourth reason: the server's own status rule, mirrored.

    `ObJourneyStepLifecycleService` accepts complete, block and waiting-on-client
    from IN_PROGRESS and nothing else — `requireStatus` refuses everything else
    with "cannot mark waiting-on-client from status PENDING", which is exactly
    what a reader saw when this bar offered that button on an unstarted task.
    A button whose only possible outcome is a refusal teaches that the bar's
    buttons are suggestions rather than facts, so each state action now asks
    this first.

    Two states are not IN_PROGRESS and are not closed. Not started wants Start;
    waiting on the client or blocked wants Resume, and while paused *only*
    Resume moves the task — that is the product rule ("if waiting on client,
    disable everything not required until it is done"), and it is also simply
    what the server will accept.
  */
  const notRunning = (): string | undefined => {
    if (task.status === 'PENDING') return 'Start the task first'
    if (task.status === 'WAITING_ON_CLIENT') {
      return 'Waiting on the client — resume the task first'
    }
    if (task.status === 'BLOCKED') return 'Blocked — resume the task first'
    // A submitted task is not the owner's to move again. Every one of these
    // endpoints is refused from PENDING_REVIEW, and the check list is locked
    // with them — the only thing that moves it now is the reviewer's verdict.
    if (task.status === 'PENDING_REVIEW') return 'With your reviewer — wait for the verdict'
    return undefined
  }

  const out: TaskAction[] = []

  /*
    Start replaces Complete on an unstarted task. Offering Complete there is
    offering a transition the server refuses, which teaches a reader that the
    bar's buttons are suggestions rather than facts.
  */
  if (task.status === 'PENDING') {
    const reason = writeBlock()
    out.push({
      key: 'start',
      label: 'Start task',
      icon: '▶',
      tone: 'primary',
      group: 'state',
      enabled: !reason,
      reason,
      opensDialog: false,
    })
  } else if (checklistPhase === 'SEND') {
    /*
      The hand-over, as the owner's one button.

      There is no per-row Send any more: a check list goes to its reviewer as a
      unit, because a half-sent list is a state neither side has a control for
      — `submitChecklist`'s own note. So this is the press that moves the task,
      and it is held by exactly what that endpoint refuses on: a row with no
      answer. Required documents are not asked about here; they hold Complete,
      which is a different press on a different day.
    */
    const reason = writeBlock(
      notRunning() ??
        (sendBlockers.length > 0 ? `Outstanding: ${sendBlockers.join(', ')}` : undefined),
    )
    out.push({
      key: 'send',
      label: 'Send for Verification',
      icon: '↑',
      tone: 'primary',
      group: 'state',
      enabled: !reason,
      reason,
      opensDialog: false,
    })
  } else {
    const reason = writeBlock(
      notRunning() ?? (blockers.length > 0 ? `Outstanding: ${blockers.join(', ')}` : undefined),
    )
    out.push({
      key: 'complete',
      label: 'Mark Complete',
      icon: '✓',
      tone: 'primary',
      group: 'state',
      enabled: !reason,
      reason,
      opensDialog: false,
    })
  }

  /*
    Resume replaces Waiting on client while the task is paused or blocked —
    the same slot, because both are the answer to "this task is not moving".
  */
  if (paused) {
    const reason = writeBlock()
    out.push({
      key: 'resume',
      label: 'Resume',
      icon: '▶',
      tone: 'outline',
      group: 'state',
      enabled: !reason,
      reason,
      opensDialog: false,
    })
  } else {
    const reason = writeBlock(notRunning())
    out.push({
      key: 'waitingOnClient',
      label: 'Waiting on client',
      icon: '⏸',
      tone: 'outline',
      group: 'state',
      enabled: !reason,
      reason,
      opensDialog: false,
    })
  }

  /*
    Waiting on service and Block are one endpoint — "waiting on another
    service" is `dependency-not-ready`, one of five block reasons, not a
    transition of its own. Two buttons because it is the reason owners pick
    most and naming it saves a dialog step; the tests assert they send the
    same call so that stays honest.
  */
  const blockReason = writeBlock(notRunning())
  out.push({
    key: 'waitingOnService',
    label: 'Waiting on service',
    icon: '↳',
    tone: 'plain',
    group: 'state',
    enabled: !blockReason,
    reason: blockReason,
    opensDialog: true,
  })
  out.push({
    key: 'block',
    label: 'Block',
    icon: '⛔',
    tone: 'danger',
    group: 'state',
    enabled: !blockReason,
    reason: blockReason,
    opensDialog: true,
  })

  /*
    A moderator's, and it survives a task not being yours — reassigning is the
    action somebody takes about *somebody else's* task.
  */
  const reassignReason = !isModerator
    ? 'Reassigning a task off its owner is an OB Manager or OB Admin action'
    : terminal
      ? closed
      : busy
        ? 'Working…'
        : undefined
  out.push({
    key: 'reassign',
    label: 'Reassign',
    icon: '👤',
    tone: 'plain',
    group: 'other',
    enabled: !reassignReason,
    reason: reassignReason,
    opensDialog: true,
  })

  /*
    Only where the template defines required documents. A task with none has
    nothing to attach *against* — the upload is per required document, not a
    general file drop — so the button would open a dialog with an empty list.

    Enabled even though uploading is not possible yet: the dialog answers
    "which documents does this task need, and are they in?", which is what
    holds Complete, and hiding it leaves that question nowhere on the screen.
    The upload control inside carries the caveat.
  */
  if (task.docs.length > 0) {
    const satisfied = task.docs.filter((d) => d.isSatisfied).length
    out.push({
      key: 'attach',
      label: 'Attach',
      icon: '📎',
      tone: 'plain',
      group: 'other',
      enabled: true,
      badge: `${satisfied}/${task.docs.length}`,
      opensDialog: true,
    })
  }

  /*
    Enabled for everybody, always, including on a closed task and on somebody
    else's: recording that a call happened is a log entry rather than a task
    mutation, and `ObStepCommunication.recordedBy` exists because anyone might
    add one. It is the one thing a non-owner can still do here.
  */
  out.push({
    key: 'communication',
    label: 'Communication',
    icon: '💬',
    tone: 'plain',
    group: 'other',
    enabled: true,
    opensDialog: true,
  })

  return out
}

/**
 * What the server actually refused, in the words it used.
 *
 * <h2>Why this is not one sentence about reloading</h2>
 *
 * <p>It used to be: <em>"That transition was refused. The server may have moved
 * this task since the page loaded — reload and try again."</em> That is a
 * stale-read message, and completion is almost never refused for a stale read.
 * The common refusal is `completion-gate-not-satisfied`, a 422 that names its
 * cause in three fields — `unansweredMandatoryItems`, `missingRequiredDocs` and
 * `signoffMissing` — every one of which was being discarded in favour of a guess
 * that sent the reader to reload a page that will refuse again identically.
 *
 * <p>The sign-off clause is the one that has to be surfaced here rather than
 * predicted by the panel above. `ObProjectTaskPanel`'s `blockers` names the
 * other two ahead of time and disables Complete, but it cannot name this one:
 * `ObJourneyStepView` carries `requiresSignoff` and no sign-off <em>status</em>,
 * so the page cannot tell a task whose sign-off is already accepted from one
 * still waiting. Complete is therefore correctly offered, and the server is the
 * only thing that knows — so what it says is what gets shown.
 *
 * <p>Matched on `problem.type`, per `contracts/CONVENTIONS.md` §3: the URI is
 * stable and the title is not.
 */
/*
  `error` is typed `unknown` rather than `ApiError` on purpose. Orval annotates
  every generated hook's error as the `Problem` *body*, but the mutator these
  hooks are generated against is our own `http`, which throws an `ApiError`
  wrapping that body — so the declared type and the runtime value disagree, and
  reading `error.signoffMissing` off the declared shape would compile and then be
  `undefined` for ever. Narrowing with `instanceof` is what makes this read the
  object that actually arrives.
*/
export function refusalMessage(
  mutations: readonly { isError: boolean; error: unknown; submittedAt: number }[],
): string | null {
  /*
    The most recently *submitted* mutation decides, not the first one that ever
    failed. A refusal stays on its own mutation object until that same action is
    tried again, so "cannot mark waiting-on-client from status PENDING" used to
    sit under the bar after the reader had pressed Start and the task was
    plainly In progress — a message describing a state that no longer existed.
    `submittedAt` is 0 on a mutation that has never run, so an untouched bar
    reads as nothing to say.
  */
  const latest = mutations.reduce<(typeof mutations)[number] | null>(
    (best, m) => (m.submittedAt > (best?.submittedAt ?? 0) ? m : best),
    null,
  )
  if (!latest?.isError) return null
  const failed = latest.error
  if (!(failed instanceof ApiError)) return 'That transition was refused.'

  if (failed.is('completion-gate-not-satisfied')) {
    const gate = failed.problem as ObCompletionGateProblemAllOf
    const reasons: string[] = []

    const unanswered = gate.unansweredMandatoryItems ?? []
    if (unanswered.length > 0) {
      reasons.push(
        `${unanswered.length} mandatory item${unanswered.length === 1 ? '' : 's'} still unanswered ` +
          `(${unanswered.join(', ')})`,
      )
    }
    const docs = gate.missingRequiredDocs ?? 0
    if (docs > 0) {
      reasons.push(`${docs} required document${docs === 1 ? '' : 's'} not yet attached`)
    }
    if (gate.signoffMissing) {
      reasons.push(
        'the client has not accepted their sign-off for this task yet — it is requested and ' +
          'accepted from the client sign-off panel, not from here',
      )
    }

    if (reasons.length > 0) {
      return `This task is not ready to complete: ${reasons.join('; ')}.`
    }
  }

  // A real stale read does exist — the 409 the ETag guard raises — and for that
  // the original advice was the right advice.
  if (failed.status === 409) {
    return 'Somebody else changed this task while the page was open — reload and try again.'
  }

  return failed.problem.detail ?? failed.problem.title ?? 'That transition was refused.'
}
