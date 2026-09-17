import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import type { ObJourneyStepItem } from '@/api/generated/model/obJourneyStepItem'
import type { ObStepRowState } from '@/api/generated/model/obStepRowState'
import type { ObStepReviewState } from '@/api/generated/model/obStepReviewState'
import type { UserRef } from '@/api/generated/model/userRef'
import {
  useReviewObJourneyStepItem,
  useMarkObJourneyStepOutcomesSeen,
  useSendBackObJourneyStepItem,
  useSubmitObJourneyStepItem,
  useUpdateObJourneyStepItem,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { ApiError } from '@/api/http'
import { cn } from '@/lib/utils'

import { ObTaskActionBar } from './ObTaskActionBar'
import { formatDue } from './taskDates'
import { invalidateAfterTaskWrite } from './taskQueries'
import type { ProjectTask } from './useProjectTasks'

/**
 * One open task: its check list, its transitions, its timeline.
 *
 * <h2>The vital signs are not here any more</h2>
 *
 * <p>Responsible, position, TAT, due, TAT used and the answered count were a
 * six-fact band at the top of this panel. They are on the task's own strip now
 * — `TaskFacts`, drawn by `ObProjectStageBody` — which removes a line per open
 * task and, more to the point, makes the facts readable without opening the
 * task at all. The band's `Responsible` fact was also the second place this
 * screen printed the owner, the first being the strip's right-hand edge.
 *
 * <h2>The check list is open, and its header still says what is in it</h2>
 *
 * <p>It was a disclosure, closed on every open task, from the days when the
 * panel unfolded inline under a row and a long list pushed the transitions off
 * the screen. The task has a section of its own now, with room, and a reader
 * who picked it came to answer these rows — so they are drawn open. The header
 * keeps stating the count, what is outstanding and how many items came back Not
 * completed, because those are the three facts a reader checks before reading the
 * rows, and the strip's colour says the same thing for the eye.
 *
 * <h2>Four columns, and a header row that names them</h2>
 *
 * <p>Number, item, action, remark — an aligned grid with its own header rather
 * than a list of rows that happen to line up. A step carrying twenty items is
 * then read down a column instead of across twenty loose lines. Below 640px the
 * row folds to item over status over remark, which is the only shape that fits
 * a phone without the panel scrolling sideways.
 *
 * <h2>One status button, which flips</h2>
 *
 * <p>It was a pair — Verified and Unverified, side by side, one of them always
 * the wrong one to press. It is one button now, and it says the same thing
 * whatever it holds: <b>Mark complete</b>, greyed and dashed until it is
 * pressed and solid green afterwards. Pressing it again rejects the row, which
 * returns it to the grey it started on. The ↻ that fades in on hover is part
 * of that button, not a second control; without it a chip stating a status
 * looks like a label rather than something to press.
 *
 * <p>The words changed with the control. <b>Verified</b> / <b>Unverified</b>
 * described an auditor checking somebody else's work; an implementor answering
 * their own task list is recording whether they did the thing.
 *
 * <h2>Three states, because the question has three answers</h2>
 *
 * <p>`answer` is true, false or null, and the gate counts *answered* — so an
 * untouched row sits grey behind a dashed border and the first press sets
 * Completed, which is the answer nine rows in ten get. The button draws false
 * the same way it draws null; ↺, and the header's own counts, are what tell a
 * rejected row from one nobody has reached. ↺ puts a row
 * back to unanswered, and is why the request accepts null. Were null dropped,
 * "5 of 5 answered" would be true the moment the task opened and the completion
 * gate would stop meaning anything.
 *
 * <h2>The remark is optional on both answers</h2>
 *
 * <p>It was mandatory on a False — blueprint §5.8, held by a CHECK constraint
 * and refused as `ob-step-item-remark-required`. That rule is gone (PLAN.md §4,
 * D-17): an implementor records the reason where there is one to record, and is
 * not held at a text box to say the obvious. Nothing here gates on it any more,
 * and the field saves on blur exactly as it did.
 */
export interface ObProjectTaskPanelProps {
  task: ProjectTask
  users: readonly UserRef[]
  /** Whether the viewer owns this task, and may therefore change it. */
  yours: boolean
  /**
   * Whether the viewer holds `OB_MANAGER` or `OB_ADMIN`, and may therefore
   * record verdicts on a task that has been submitted.
   *
   * <p>Advisory, like everything read off `moduleRoles` — see `viewerScope.ts`.
   * A stale claim here can only draw a column whose writes the server then
   * refuses with `step-moderator-required`; it can never perform one. The
   * guarantee is `requireModerator` in `ObJourneyStepLifecycleService`.
   */
  canReview?: boolean
  /** This task's open client escalation, where it has one. */
  escalation?: { id: number; raisedBy: string; raisedAt: string; note: string } | null
  onResolveEscalation?: (escalationId: number) => void
}

const OUTSTANDING_CLASS = 'text-caption text-danger-text'

/**
 * The columns, and the two they fold into.
 *
 * <p>One function because the header row and every item row have to agree —
 * two copies of a column template is one edit away from a header that no
 * longer sits over its column.
 *
 * <p>Below `sm` the row is a 26px gutter and everything else: the number keeps
 * the first column, and the action and remark cells move under the label with
 * `col-start-2`. The alternative — letting them wrap into the gutter — puts a
 * control under the roman numeral rather than under the item it answers.
 *
 * <p><b>Action is 186px</b> — the answer button and the clear beside it. It
 * was 84px for as long as the answer was a 12px ring; a control reading
 * <b>Mark complete</b> needs the width back, and 186px is what that word, its
 * mark and the ↺ take without wrapping.
 *
 * <p><b>Five columns while a review is on the table</b>, four otherwise. The
 * Review column is not drawn on a task nobody has submitted: an empty column
 * headed "Review" on every check list in the module would be four hundred
 * pixels of nothing on the screens where the feature does not apply. The
 * folded layout is unchanged either way — a phone stacks the cells whatever
 * their number.
 */
const rowGrid = (withReview: boolean, withSend: boolean) =>
  cn(
    'grid grid-cols-[26px_minmax(0,1fr)] items-start gap-x-2.5 gap-y-1.5 px-3 py-2.5',
    'sm:items-center sm:py-2',
    withReview && withSend
      ? 'sm:grid-cols-[34px_minmax(0,1.1fr)_170px_162px_minmax(0,1fr)_96px]'
      : withReview
        ? 'sm:grid-cols-[34px_minmax(0,1.2fr)_186px_178px_minmax(0,1.2fr)]'
        : withSend
          ? 'sm:grid-cols-[34px_minmax(0,1.25fr)_186px_minmax(0,1.3fr)_96px]'
          : 'sm:grid-cols-[34px_minmax(0,1.35fr)_186px_minmax(0,1.5fr)]',
  )

export function ObProjectTaskPanel({
  task,
  users,
  yours,
  canReview = false,
  escalation,
  onResolveEscalation,
}: ObProjectTaskPanelProps) {
  const queryClient = useQueryClient()
  const [drafts, setDrafts] = React.useState<Record<number, string>>({})
  const inputs = React.useRef<Record<number, HTMLInputElement | null>>({})

  const send = useSubmitObJourneyStepItem({
    mutation: {
      onSuccess: () => {
        invalidateAfterTaskWrite(queryClient, task.journeyId, task.id)
      },
    },
  })

  const sendBack = useSendBackObJourneyStepItem({
    mutation: {
      onSuccess: () => {
        invalidateAfterTaskWrite(queryClient, task.journeyId, task.id)
      },
    },
  })

  /*
    The read receipt behind the signal.

    "2 rows came back" has to stop saying so once they have been read, or it is
    a permanent label rather than a notification — and it has to survive a
    refresh, or it is a flicker. Both need the same stamp on the row, and this
    is where it is set: the owner has the task open, so they have seen it.

    Fired from an effect rather than from a click because opening the task *is*
    the reading; there is no "dismiss" for the reader to find. Guarded on the
    task id and on there being anything unseen, so it runs once per task rather
    than on every render, and `onSuccess` deliberately does not invalidate the
    journey — repainting the banner out from under somebody mid-read is the one
    thing worse than leaving it up.
  */
  const seen = useMarkObJourneyStepOutcomesSeen()
  const unseen = yours && task.items.some((i) => i.unseenOutcome === true)
  const markSeen = seen.mutate
  React.useEffect(() => {
    if (!unseen) return
    markSeen({ stepId: task.id })
  }, [unseen, task.id, markSeen])

  const update = useUpdateObJourneyStepItem({
    mutation: {
      onSuccess: () => {
        invalidateAfterTaskWrite(queryClient, task.journeyId, task.id)
      },
    },
  })

  const review = useReviewObJourneyStepItem({
    mutation: {
      onSuccess: () => {
        invalidateAfterTaskWrite(queryClient, task.journeyId, task.id)
      },
    },
  })

  /**
   * The three readings the review gate adds, worked out once.
   *
   * <p>`underReview` shuts the check list for everybody — an answer that
   * changed while a manager was reading it would make their verdict describe
   * something they never saw, and the server refuses it with
   * `ob-step-under-review` regardless of what the screen draws.
   *
   * <p>`reviewing` is the manager actually holding this one. `showReview`
   * is wider on purpose: the column stays visible after a review has closed,
   * so an implementor opening a returned task can see which rows were verified
   * and which came back, rather than being told only in the banner.
   */
  const underReview = task.status === 'PENDING_REVIEW'
  const reviewing = canReview && underReview

  /**
   * Where one row is, defaulting the way the server does.
   *
   * <p>`rowState` is the unit of work now — a task may hold a row being
   * worked, a row waiting on the manager and a row already approved at once,
   * and every gate below asks this rather than asking about the task. The
   * `?? 'DRAFT'` is for a client reading a response older than the field.
   */
  const stateOf = (item: ObJourneyStepItem): ObStepRowState => item.rowState ?? 'DRAFT'
  const isOut = (item: ObJourneyStepItem) => stateOf(item) === 'SENT'
  const outRows = task.items.filter(isOut)

  const showReview =
    reviewing ||
    task.items.some((i) => (i.reviewState && i.reviewState !== 'NOT_REVIEWED') || isOut(i))
  /** The Send column exists for whoever has something to send on this task. */
  const showSend = (yours || canReview) && task.items.length > 0
  const ROW_GRID = rowGrid(showReview, showSend)

  /**
   * Shut to everybody — verified by a review that has already closed.
   *
   * <p>The server's own reading, carried on the row, rather than
   * `reviewState === 'VERIFIED'`. The two differ for exactly as long as a
   * review is open, and that difference is the reviewer's chance to change
   * their mind: Rejected sits one press past Verified on the same control, so
   * treating a fresh verdict as locked would disable the button on the press
   * that set it — which is precisely what it did before this field existed.
   */
  const isLocked = (item: ObJourneyStepItem) => item.reviewLocked === true

  // Rows that have actually come back, not rows carrying a draft verdict: a
  // reviewer who has pressed Rejected and not yet sent it has changed nothing
  // that its implementor may see.
  const rejectedRows = task.items.filter((i) => stateOf(i) === 'REJECTED')
  /** Rows with no verdict yet. */
  // Only rows actually out for review. A row still with its implementor has no
  // verdict because it has not been asked for one, and counting it would hold
  // the reviewer's press on work that was never sent to them.
  const unreviewedCount = outRows.filter(
    (i) => (i.reviewState ?? 'NOT_REVIEWED') === 'NOT_REVIEWED',
  ).length

  /**
   * What holds the reviewer's **Mark complete**, in the same shape `blockers`
   * names for the implementor's — so the bar can say why either one is grey
   * without knowing which of the two people is reading it.
   */
  const reviewBlockers = React.useMemo(() => {
    if (!reviewing) return []
    const out: string[] = []
    if (unreviewedCount > 0) {
      out.push(`${unreviewedCount} row${unreviewedCount === 1 ? '' : 's'} still to review`)
    }
    const noReason = outRows.filter(
      (i) => i.reviewState === 'REJECTED' && (i.remark ?? '').trim() === '',
    ).length
    if (noReason > 0) {
      out.push(`${noReason} rejected row${noReason === 1 ? '' : 's'} with no reason`)
    }
    return out
  }, [reviewing, unreviewedCount, outRows])
  /**
   * A task that came back, read off the rows rather than off a round counter.
   *
   * <p>The rows are the thing the rule is actually about, and they are already
   * on this screen — a `reviewRound` would be a second fact to keep in step
   * with them, and the first time the two disagreed the screen would open rows
   * the server refuses or shut rows it would accept.
   */
  const reopened = rejectedRows.length > 0

  /**
   * Whether there is a "rest" for the banner to say is locked.
   *
   * <p>On a one-item check list there is not, and the sentence that claimed
   * there was described rows the reader could see were not on the screen.
   */
  const othersLocked = task.items.length > rejectedRows.length

  /**
   * A review that came back accepted, with the closing press still to come.
   *
   * <p>Every row verified and the task running again is only reachable one
   * way — the manager accepted the whole check list and the server handed it
   * straight back rather than closing it, because closing a task is its
   * implementor's act. So there is nothing left to answer here and nothing
   * left to wait for: **Mark complete** is enabled, and pressing it is what
   * finishes the task.
   *
   * <p>Read off the rows rather than a flag, for the same reason `reopened`
   * is: the rows are what the rule is about and they are already on screen.
   * `IN_PROGRESS` rather than "not under review" so the banner goes quiet the
   * moment the task closes — every row stays verified afterwards, and without
   * this the screen would still be asking for a press that has happened.
   */
  const accepted =
    task.status !== 'DONE' &&
    task.items.length > 0 &&
    task.items.every((i) => stateOf(i) === 'VERIFIED')

  /**
   * Who may answer this row right now.
   *
   * <p>Not the owner alone any more. A verified row is shut for good, and on a
   * returned task only the rows that came back are open — every other one is
   * work the manager has already passed. The server enforces both
   * (`ob-step-item-verified`, and the rejected-only rule that follows from it);
   * this is the same rule drawn, so a control is never offered for a write
   * that would be refused.
   */
  const mayAnswer = (item: ObJourneyStepItem) =>
    yours && (stateOf(item) === 'DRAFT' || stateOf(item) === 'REJECTED')

  /** This row is finished and is the caller's to hand over. */
  const mayReview = (item: ObJourneyStepItem) => canReview && isOut(item)
  const maySend = (item: ObJourneyStepItem) => mayAnswer(item) && item.answer != null
  const mayRelease = (item: ObJourneyStepItem) =>
    mayReview(item) &&
    item.reviewState != null &&
    item.reviewState !== 'NOT_REVIEWED' &&
    (item.reviewState !== 'REJECTED' || remarkOf(item).trim() !== '')

  /**
   * The remark box, and who owns it right now.
   *
   * <p>One field with two authors. The implementor's while the row is theirs;
   * the manager's <em>only</em> on a row they have rejected — never on one
   * they are verifying, so a verdict cannot quietly rewrite the note it is
   * passing.
   */
  const mayRemark = (item: ObJourneyStepItem) =>
    mayAnswer(item) || (mayReview(item) && item.reviewState === 'REJECTED')

  /**
   * The caption over the remark box, where the text in it is the reviewer's.
   *
   * <p>There is no author column on a remark, so this is read off the two facts
   * that stand in for one: the row came back, and nobody has answered it since.
   * A `REJECTED` row is handed back <em>unanswered</em> — that is what the state
   * means — so while `answer` is still null the only text that can be in the
   * field is the reason the reviewer wrote. The moment its implementor answers
   * the row again, `flip` sends whatever the box holds as theirs, and the
   * caption stops being true and stops being drawn.
   *
   * <p>Null where there is nothing to attribute, so the caller can render it or
   * not on one test.
   */
  const reasonFromReviewer = (item: ObJourneyStepItem): string | null => {
    if (stateOf(item) !== 'REJECTED' || item.answer != null) return null
    if ((item.remark ?? '').trim() === '') return null
    const who = item.reviewedBy?.displayName
    return who
      ? `${who}’s reason for sending it back`
      : 'The reviewer’s reason for sending it back'
  }

  const ownerName = (id: number | null | undefined) =>
    id == null ? null : (users.find((u) => u.id === id)?.displayName ?? null)

  const remarkOf = (item: ObJourneyStepItem) => drafts[item.id] ?? item.remark ?? ''

  /**
   * The flip: an untouched row goes to Completed, and after that the one
   * button swaps between the two answers.
   *
   * <p>Completed first because it is the answer nine rows in ten get — the
   * alternative, going to Not completed on a first press, would make the
   * common case two presses. Clearing is `clear` below rather than a third
   * position on this button, since undoing a mis-click is a different act from
   * answering and should not be reachable by pressing on past the answer you
   * wanted.
   */
  const flip = (item: ObJourneyStepItem) => {
    const next = item.answer === true ? false : true
    const remark = remarkOf(item).trim()
    update.mutate({ itemId: item.id, data: { answer: next, remark: remark || null } })
  }

  /**
   * Back to unanswered. The server drops the remark with the answer it
   * explained — except on a row that came back from review, where the remark
   * is the reviewer's reason rather than the answer's and outlives it. Sending
   * `null` there is refused (`ob-step-reject-reason-required`), so the reason
   * rides along and the press does what the arrow promises.
   */
  const clear = (item: ObJourneyStepItem) => {
    const keep = item.reviewState === 'REJECTED' ? remarkOf(item).trim() || null : null
    update.mutate({ itemId: item.id, data: { answer: null, remark: keep } })
  }

  /**
   * The reviewer's press: Not reviewed → Verified → Rejected → Not reviewed.
   *
   * <p>Verified first because it is the verdict most rows get, so the common
   * case is one press. Rejected is second, and deliberately not what a stray
   * click lands on — it sends work back to somebody.
   *
   * <p>A rejection needs a reason, and the row's remark is where it goes —
   * but the verdict lands first and the reason follows. The row has to *look*
   * rejected for the reader to know the box is now required, and a reason
   * cannot be typed into a box that only opens once the row is rejected. The
   * rule is kept where it can be kept without that deadlock: **Mark complete**
   * refuses while any rejected row is unexplained.
   */
  const cycleVerdict = (item: ObJourneyStepItem) => {
    const next: ObStepReviewState =
      item.reviewState === 'VERIFIED'
        ? 'REJECTED'
        : item.reviewState === 'REJECTED'
          ? 'NOT_REVIEWED'
          : 'VERIFIED'
    /*
      The state goes on its own. The reason is typed into the remark box that
      opens once the row is rejected, and sending an empty one with the verdict
      is what made this press fail silently before: the server refused the
      write, the row stayed Verified, and the button looked dead.
    */
    const remark = remarkOf(item).trim()
    review.mutate({
      itemId: item.id,
      data: next === 'REJECTED' && remark ? { state: next, remark } : { state: next },
    })
    if (next === 'REJECTED') {
      // Straight to the box they now have to fill. Without this the required
      // field is a red border somewhere below the press that caused it.
      window.setTimeout(() => inputs.current[item.id]?.focus(), 0)
    }
  }

  const owner = ownerName(task.ownerUserId) ?? 'the task owner'
  const answered = task.items.filter((i) => i.answer !== null && i.answer !== undefined).length

  const exceptions = task.items.filter((i) => i.answer === false).length
  /*
    Mandatory and unanswered — the same filter `blockers` applies below, and the
    only count that is a reason to open a closed check list. An optional item
    left blank holds nothing up, so reporting it outstanding on the header would
    send a reader in to answer something the gate does not want.
  */
  const outstanding = task.items.filter((i) => i.isMandatory && i.answer == null).length

  /**
   * The Check List strip's own colour, and why it has one.
   *
   * <p>It was `bg-subtle` — the same grey as the transition bar, the owner row,
   * the upload row and the communications header stacked under it, so an open
   * task read as six identical grey bands and the one that gates completion was
   * indistinguishable from the one that attaches a file.
   *
   * <p>Rather than pick a colour to break the tie, the strip says what it knows:
   * red where an item was answered False and somebody recorded a problem, amber
   * where mandatory answers are still outstanding, green where the gate is
   * clear. That is the question a reader opens this section to ask, answered
   * before they open it.
   *
   * <p>Never colour alone — blueprint §12.1. The counts beside it say the same
   * thing in words, which is what a reader with no colour vision reads and what
   * the tests assert on.
   */
  const gate = exceptions
    ? {
        state: 'exceptions',
        strip: 'border-l-level-critical bg-level-critical-soft',
        text: 'text-level-critical-text',
      }
    : outstanding > 0
      ? {
          state: 'outstanding',
          strip: 'border-l-level-high bg-level-high-soft',
          text: 'text-level-high-text',
        }
      : {
          state: 'clear',
          strip: 'border-l-level-low bg-level-low-soft',
          text: 'text-level-low-text',
        }

  /*
    What Complete is waiting on, named rather than left to a 422. The mandatory
    filter matches `requireCompletionGate`, which counts an item answered either
    way as answered — so a False with a reason does not hold the gate.
  */
  const blockers = React.useMemo(() => {
    const out: string[] = []
    const unanswered = task.items.filter((i) => i.isMandatory && i.answer == null).length
    if (unanswered) out.push(`${unanswered} unanswered`)
    const missing = task.docs.filter((d) => d.isRequired && !d.isSatisfied).length
    if (missing) out.push(`${missing} required document${missing === 1 ? '' : 's'}`)
    return out
  }, [task.items, task.docs])

  return (
    <div className="mt-2 flex flex-col gap-2.5">
      {/* ---- what the review did ----
          Said before the rows rather than only on them. A reader opening a
          returned task needs to know what came back and why before they start
          reading twenty lines to find out which two are theirs again; the rows
          then say the same thing where the work is. */}
      {outRows.length > 0 && (
        <div
          role="status"
          className="flex flex-wrap items-start gap-2 rounded-control border border-level-medium bg-level-medium-soft px-3 py-2 text-caption text-level-medium-text"
        >
          <span aria-hidden="true">⌛</span>
          <span>
            {canReview ? (
              <>
                <strong className="font-semibold">
                  {outRows.length === task.items.length
                    ? `${owner} sent this check list for verification.`
                    : `${owner} sent ${outRows.length} of ${task.items.length} rows for verification.`}
                </strong>{' '}
                Their answers are read-only — set each row to Verified or Rejected in the Review
                column and press its <b>Send</b>. A rejection needs a line in its remark. Review what
                you have time for; the rest keeps.
              </>
            ) : (
              <>
                <strong className="font-semibold">
                  {outRows.length === task.items.length
                    ? 'Marked complete — with your reviewer.'
                    : `${outRows.length} of ${task.items.length} rows are with your reviewer.`}
                </strong>{' '}
                {outRows.length === task.items.length
                  ? 'The check list is locked until a verdict comes back, and the TAT clock is paused.'
                  : 'Those rows are locked until a verdict comes back. The rest are still yours to work on.'}
              </>
            )}
          </span>
        </div>
      )}

      {accepted && (
        <div
          role="status"
          className="flex flex-wrap items-start gap-2 rounded-control border border-success bg-success-soft px-3 py-2 text-caption text-success-text"
        >
          <span aria-hidden="true">✓</span>
          <span>
            <strong className="font-semibold">
              {task.items.length === 1
                ? 'Your row was approved.'
                : `All ${task.items.length} rows were approved.`}
            </strong>{' '}
            {yours
              ? 'The check list is closed — press Mark complete to finish the task.'
              : `The check list is closed — ${owner} presses Mark complete to finish the task.`}
          </span>
        </div>
      )}

      {reopened && (
        <div
          role="status"
          className="flex flex-wrap items-start gap-2 rounded-control border border-status-delayed bg-status-delayed-soft px-3 py-2 text-caption text-status-delayed-text"
        >
          <span aria-hidden="true">↻</span>
          <span>
            <strong className="font-semibold">
              {rejectedRows.length === 1
                ? '1 item came back'
                : `${rejectedRows.length} items came back`}
            </strong>{' '}
            {/*
              Said only where it is true. A one-item check list whose only item
              came back was being told "the rest were verified and are locked"
              — there is no rest, and a banner describing rows that do not
              exist is the first thing a reader stops believing.
            */}
            {othersLocked
              ? `— only ${rejectedRows.length === 1 ? 'this one is' : 'these are'} open again; the rest were verified and are locked.`
              : rejectedRows.length === 1
                ? '— the check list is open again, with the reviewer’s reason on its row.'
                : '— the whole check list is open again, with the reviewer’s reason on each row.'}{' '}
            {/*
              And what to do about it. The banner said what had happened and
              stopped there, which leaves the reader to work out that a
              returned row is answered again and sent again rather than, say,
              waited on.
            */}
            <span className="font-medium">
              {yours
                ? rejectedRows.length === 1
                  ? 'Fix it, answer it again, then press Send on the row.'
                  : 'Fix each one, answer it again, then press Send on its row.'
                : `${owner} answers ${rejectedRows.length === 1 ? 'it' : 'each one'} again and sends it back.`}
            </span>
            <ul className="mt-1 mb-0 list-disc pl-5">
              {rejectedRows.map((item) => (
                <li key={item.id}>
                  <span className="font-semibold">{item.label}</span>
                  {item.remark ? <> — &ldquo;{item.remark}&rdquo;</> : null}
                </li>
              ))}
            </ul>
          </span>
        </div>
      )}

      {/* ---- client escalation ---- */}
      {escalation && (
        <div
          role="status"
          className="flex flex-wrap items-start gap-2 rounded-control border border-danger bg-danger-soft px-3 py-2 text-caption text-danger-text"
        >
          <span aria-hidden="true">🔔</span>
          <span>
            <strong className="font-semibold">Client escalation</strong> —{' '}
            <em>“{escalation.note}”</em>{' '}
            <span className="text-content-muted">
              ({escalation.raisedBy}, {formatDue(escalation.raisedAt)})
            </span>
          </span>
          <span className="min-w-0 flex-1" aria-hidden="true" />
          {onResolveEscalation && (
            <button
              type="button"
              onClick={() => onResolveEscalation(escalation.id)}
              className="shrink-0 rounded-chip border border-current px-2.5 py-0.5 text-[11px] font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            >
              ✓ Resolve &amp; acknowledge
            </button>
          )}
        </div>
      )}

      {/* ---- check list ---- */}
      {task.items.length > 0 && (
        <section
          aria-labelledby={`ob-check-list-heading-${task.id}`}
          className="overflow-hidden rounded-control border border-border bg-surface"
        >
          <div
            data-testid="ob-check-list-header"
            // The tone as a value rather than as a class, so a test can pin the
            // three states without asserting on Tailwind output.
            data-gate={gate.state}
            className={cn(
              'flex w-full flex-wrap items-center gap-x-2 gap-y-1 border-b border-b-border border-l-[3px] px-3 py-1.5',
              gate.strip,
            )}
          >
            <h5 id={`ob-check-list-heading-${task.id}`} className="m-0 text-caption font-semibold text-content">
              Check List
            </h5>
            {/*
              The counts say on the header what the rows say in colour: a Not
              completed is a recorded problem, and the header is what a reader
              scans before they read anything.
            */}
            <span className={cn('text-caption font-medium tabular-nums', gate.text)}>
              {`${answered} of ${task.items.length} answered`}
              {outstanding > 0 ? ` · ${outstanding} outstanding` : ''}
              {exceptions > 0 ? ` · ${exceptions} not completed` : ''}
            </span>
            <span className={cn('ml-auto text-[10.5px]', gate.text)}>
              Every item must be answered
            </span>
          </div>

          <div id={`ob-check-list-${task.id}`} role="table" aria-labelledby={`ob-check-list-heading-${task.id}`}>
            {/* The column names, on the wide layout only — the folded row puts
                each cell under the item it belongs to, where a header naming
                four columns that are no longer beside each other would be a
                fifth row of nothing. */}
            <div
              role="row"
              className={cn(
                ROW_GRID,
                'hidden border-b border-border bg-subtle py-1.5',
                'text-[10.5px] font-semibold uppercase tracking-[0.08em] text-content-muted sm:grid',
              )}
            >
              <span role="columnheader">#</span>
              <span role="columnheader">Checklist item</span>
              <span role="columnheader">Action</span>
              {showReview && (
                // Named for whose mark it is. "Review" alone, next to a column
                // of the implementor's own answers, does not say which of the
                // two people the mark belongs to.
                <span role="columnheader" className="text-level-medium-text">
                  Review · manager
                </span>
              )}
              <span role="columnheader">Remark</span>
              {showSend && (
                // Last, because it is the last thing done to a row: answer it,
                // let it be reviewed, say why, hand it over.
                <span role="columnheader">Send</span>
              )}
            </div>

            {task.items.map((item, index) => {
              const busy = update.isPending && update.variables?.itemId === item.id
              const reviewBusy = review.isPending && review.variables?.itemId === item.id
              const sendBusy =
                (send.isPending && send.variables?.itemId === item.id) ||
                (sendBack.isPending && sendBack.variables?.itemId === item.id)
              const answeredHere = item.answer === true || item.answer === false
              const canAnswer = mayAnswer(item)
              const canReviewRow = mayReview(item)
              const rejectedHere = item.reviewState === 'REJECTED'
              return (
                <div
                  key={item.id}
                  role="row"
                  data-testid="ob-check-list-row"
                  data-answer={item.answer == null ? 'unset' : String(item.answer)}
                  data-review={item.reviewState ?? 'NOT_REVIEWED'}
                  className={cn(
                    ROW_GRID,
                    'border-b border-border last:border-b-0',
                    // One expression rather than a zebra class an item state has
                    // to out-specify: a Not completed row is tinted whichever
                    // side of the stripe it lands on.
                    //
                    // The manager's verdict outranks the implementor's answer
                    // for the row's tone: a rejected row is the one going back,
                    // whatever it claimed about itself. Rejected wears the
                    // delayed orange rather than the danger red, so the two
                    // marks never read as the same thing twice.
                    item.reviewState === 'REJECTED'
                      ? 'bg-danger-soft'
                      : item.reviewState === 'VERIFIED'
                        ? 'bg-level-low-soft'
                        : item.answer === false
                          ? 'bg-danger-soft'
                          : index % 2 === 1
                            ? 'bg-[color-mix(in_srgb,var(--bg-subtle)_45%,transparent)]'
                            : 'bg-surface',
                  )}
                >
                  {/* i, ii, iii — the item's place on the list. Steps are
                      numbered and tasks lettered, so the third level takes
                      roman numerals; decorative, the order is the list's own. */}
                  <span role="cell" className="text-caption tabular-nums text-content-muted">
                    {roman(index + 1)}.
                  </span>

                  <span
                    role="cell"
                    id={`ob-check-item-${item.id}`}
                    className={cn(
                      'text-caption font-medium',
                      item.answer === false ? 'text-danger-text' : 'text-content',
                    )}
                  >
                    {item.label}
                    {item.isMandatory ? (
                      <span className="ml-1 font-bold text-danger-text" title="Mandatory — blocks completion">
                        *
                      </span>
                    ) : (
                      // Said in a word rather than by the absence of a star:
                      // "no asterisk" is not something a reader notices.
                      <span className="ml-1.5 rounded-chip border border-border px-1.5 text-[10.5px] font-semibold text-content-muted">
                        optional
                      </span>
                    )}
                  </span>

                  <span role="cell" className="col-start-2 flex min-w-0 items-center gap-1 sm:col-start-auto">
                    <StatusButton
                      answer={item.answer ?? null}
                      label={item.label}
                      disabled={!canAnswer || busy}
                      title={
                        canAnswer
                          ? undefined
                          : isLocked(item)
                            ? 'Verified by the reviewer — this row is closed'
                            : underReview
                              ? 'Locked while this task is under review'
                              : reopened
                                ? 'Not one of the rows that came back'
                                : `Only ${owner} can answer this`
                      }
                      onClick={() => flip(item)}
                    />
                    {/* Rendered only where there is an answer to clear, rather
                        than hidden with `invisible`: a control nobody can see
                        is still a tab stop, and tabbing a twenty-item list
                        through twenty of them is the sort of thing only a
                        keyboard user ever finds. The gap holds the column
                        steady so the statuses stay in one line. */}
                    {answeredHere ? (
                      <button
                        type="button"
                        disabled={!canAnswer || busy}
                        onClick={() => clear(item)}
                        aria-label={`Clear the answer for ${item.label}`}
                        title="Clear this answer"
                        className={cn(
                          'shrink-0 rounded-[6px] px-1 py-1.5 text-[13px] leading-none text-content-muted',
                          'hover:bg-subtle hover:text-content',
                          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                          !canAnswer && 'cursor-not-allowed opacity-55',
                        )}
                      >
                        ↺
                      </button>
                    ) : (
                      <span aria-hidden="true" className="w-[23px] shrink-0" />
                    )}
                  </span>

                  {/* ---- the manager's verdict ----
                      A control for the one person who may record it, and the
                      verdict itself for everybody else — `RowSendCell`'s rule,
                      applied to the other column that has two audiences. An
                      implementor opening a returned task was being shown a
                      greyed **Rejected** button, which is a control that
                      invites them to work out why they cannot press it; the
                      answer is that it was never theirs to press. */}
                  {showReview && (
                    <span
                      role="cell"
                      className="col-start-2 flex min-w-0 items-center gap-1 sm:col-start-auto"
                    >
                      {canReviewRow ? (
                        <ReviewButton
                          state={item.reviewState ?? 'NOT_REVIEWED'}
                          label={item.label}
                          disabled={reviewBusy}
                          onClick={() => cycleVerdict(item)}
                        />
                      ) : (
                        <VerdictMark
                          state={item.reviewState ?? 'NOT_REVIEWED'}
                          rowState={stateOf(item)}
                          reviewer={item.reviewedBy?.displayName ?? null}
                          title={
                            isLocked(item)
                              ? 'Verified in an earlier round — locked, and not read again'
                              : canReview
                                ? `Not with you yet — ${owner} sends a row when it is ready`
                                : 'The reviewer records this'
                          }
                        />
                      )}
                    </span>
                  )}

                  {/* After the status, so the row reads item, answer, reason.
                      Saved on blur, and only once an answer stands — the server
                      drops a remark on an item cleared to unanswered, so sending
                      one before then writes a sentence about nothing. */}
                  <span
                    role="cell"
                    className="col-start-2 flex min-w-0 flex-col gap-0.5 sm:col-start-auto"
                  >
                    {/*
                      Whose words are in the box. One field has two authors, and
                      on a returned row it is holding the reviewer's — which the
                      screen was not saying anywhere: the reader saw a red-bordered
                      text box with a sentence in it and no clue that replacing the
                      sentence would replace the reason they were given.

                      Only while the row is still unanswered. Answering it again
                      is what makes the box the implementor's — `flip` sends
                      whatever it holds — so past that point the caption would be
                      naming the wrong author.
                    */}
                    {reasonFromReviewer(item) && (
                      <span
                        data-testid="ob-check-item-reason-from"
                        className="text-[10px] font-semibold leading-3 text-danger-text"
                      >
                        <span aria-hidden="true">↻</span> {reasonFromReviewer(item)}
                      </span>
                    )}
                    {!mayRemark(item) ? (
                      /*
                        A disabled text box is a form field nobody can fill, and
                        on a row the reader has no part in that is what the
                        remark column was: the reviewer's reason, styled as an
                        input, greyed. It is a sentence somebody wrote — so it
                        is drawn as one.
                      */
                      <span
                        data-testid="ob-check-item-remark-text"
                        className={cn(
                          'min-w-0 break-words py-1.5 text-caption',
                          item.remark ? 'text-content' : 'text-content-muted',
                        )}
                      >
                        {item.remark ? `“${item.remark}”` : '—'}
                      </span>
                    ) : (
                    <input
                      ref={(el) => {
                        inputs.current[item.id] = el
                      }}
                      type="text"
                      value={remarkOf(item)}
                      disabled={busy || reviewBusy}
                      aria-label={
                        rejectedHere && canReviewRow
                          ? `Reason for rejecting ${item.label}`
                          : `Remark for ${item.label}`
                      }
                      aria-invalid={rejectedHere && remarkOf(item).trim() === '' ? true : undefined}
                      placeholder={
                        rejectedHere && canReviewRow
                          ? `Why it is going back — ${owner} sees this…`
                          : 'Remark (optional)…'
                      }
                      onChange={(e) => setDrafts((d) => ({ ...d, [item.id]: e.target.value }))}
                      onBlur={() => {
                        const text = remarkOf(item).trim()
                        if (text === (item.remark ?? '')) return
                        /*
                          Two writers, two routes. A manager typing the reason
                          on a rejected row is recording a verdict, not
                          answering a check list — `answerItem` would refuse
                          them for not owning the task. Sending it as the same
                          verdict again is what carries the text, and is also
                          what lets the server settle the review the moment the
                          reason arrives.
                        */
                        if (rejectedHere && canReviewRow) {
                          if (text === '') return
                          review.mutate({ itemId: item.id, data: { state: 'REJECTED', remark: text } })
                          return
                        }
                        if (item.answer != null) {
                          update.mutate({ itemId: item.id, data: { answer: item.answer, remark: text || null } })
                        }
                      }}
                      className={cn(
                        'w-full min-w-0 rounded-[6px] border border-border bg-surface px-2 py-1.5 text-caption text-content',
                        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                        // A rejection with nothing in it is the one thing the
                        // server will not take, so the box says so before the
                        // manager leaves it.
                        rejectedHere && 'border-danger',
                      )}
                    />
                    )}
                  </span>

                  {/* ---- Send, for whoever the row belongs to ----
                      One cell, two owners, never both at once: a row is either
                      with its implementor or with the reviewer. Rendering the
                      state instead of a dead button where neither applies —
                      a disabled control on a row nobody can move is an
                      invitation to work out why, and the answer is never
                      about this row. */}
                  {showSend && (
                    <span role="cell" className="col-start-2 flex min-w-0 sm:col-start-auto">
                      <RowSendCell
                        item={item}
                        state={stateOf(item)}
                        mine={mayAnswer(item)}
                        theirs={mayReview(item)}
                        ready={mayAnswer(item) ? maySend(item) : mayRelease(item)}
                        busy={sendBusy}
                        owner={owner}
                        onSend={() =>
                          mayAnswer(item)
                            ? send.mutate({ itemId: item.id })
                            : sendBack.mutate({ itemId: item.id })
                        }
                      />
                    </span>
                  )}
                </div>
              )
            })}
          </div>
        </section>
      )}

      {update.isError && (
        <p className={OUTSTANDING_CLASS} role="alert">
          {/* The server's own sentence, not a guess at which rule it was. The
              guess was wrong the first time it ran: it named a closed service
              while the actual refusal was the remark rule this release drops,
              and a reader following it would have reloaded a task that was
              open all along. `title` is the human half of the problem document
              (`ApiError`'s own note: branch on `type`, read `title`). */}
          {update.error instanceof ApiError
            ? `That answer was not saved — ${update.error.problem.title}.`
            : 'That answer was not saved. Check your connection and try again.'}
        </p>
      )}

      {/* ---- the action bar ----
          One row. It carries the read-only sentence for a non-owner too, since
          that sentence is about the buttons rather than about the task. */}
      <ObTaskActionBar
        task={task}
        users={users}
        yours={yours}
        blockers={blockers}
        reviewing={reviewing}
        reviewBlockers={reviewBlockers}
      />

    </div>
  )
}

/** 1 → "i", 4 → "iv", 9 → "ix" — lower-case, the way a check list is numbered by hand. */
function roman(n: number): string {
  const table: [number, string][] = [
    [1000, 'm'], [900, 'cm'], [500, 'd'], [400, 'cd'], [100, 'c'], [90, 'xc'],
    [50, 'l'], [40, 'xl'], [10, 'x'], [9, 'ix'], [5, 'v'], [4, 'iv'], [1, 'i'],
  ]
  let rest = n
  let out = ''
  for (const [value, glyph] of table) {
    while (rest >= value) {
      out += glyph
      rest -= value
    }
  }
  return out
}

/** What each answer is called. */
const ANSWER_WORD = { unset: 'Not answered', true: 'Completed', false: 'Not completed' } as const

/**
 * One word on the button, whatever it holds — <b>Mark complete</b>.
 *
 * <h2>Why the label does not change with the answer</h2>
 *
 * <p>A control whose text is its own current value has to be read before it
 * can be pressed, and on a twenty-item list that is twenty readings to find
 * the rows still owed. This one always says the thing pressing it does, and
 * says what it holds in the fill: grey and dashed for a row not marked
 * complete, solid green for one that is. A reader scanning the column is then
 * counting green rather than reading three different words down it.
 *
 * <p><b>Never colour alone</b> — blueprint §12.1. The mark changes with the
 * fill, ✓ against ○, and the control's accessible name carries the whole
 * sentence in words for a reader who sees neither.
 */
const ANSWER_BUTTON_WORD = 'Mark complete'

/**
 * The mark each answer wears.
 *
 * <p><b>A rejected row wears the unmarked one.</b> `answer` is still true,
 * false or null underneath — the gate counts <em>answered</em> and the header
 * counts exceptions, both off `false` — but the button draws false exactly as
 * it draws an untouched row, because to the person working the list the two
 * say the same thing: this item is not complete. Pressing green is how a row
 * is rejected, and it lands back on the look it started from.
 */
const ANSWER_MARK = { unset: '○', true: '✓', false: '○' } as const

/**
 * The one control on a row: press to mark it complete, press again to reject it.
 *
 * <h2>It was a ring, and a ring is not a button</h2>
 *
 * <p>The answer was a 12px hollow circle in one of three hues. It was compact
 * and it was honest, and nobody pressed it: a decorative dot in an Action
 * column reads as a status light rather than as the thing that changes the
 * status. The row carries a control that says <b>Mark complete</b> in words
 * now, greyed and dashed so it reads as an empty slot waiting to be filled,
 * and turns solid green the moment it is.
 *
 * <h2>Two looks over three states</h2>
 *
 * <p>`answer` is true, false or null, and that has not changed — the
 * completion gate counts <em>answered</em>, so an untouched row is not the
 * same row as one answered No, and the header still reports both. What the
 * button draws is two: complete, or not complete. A rejection is the second
 * press and it returns the control to its unfilled look, which is what the
 * implementor means by it.
 *
 * <p>↺ beside the button is the only way back to genuinely unanswered, and it
 * is drawn only on a row that has an answer to clear — so a grey button with a
 * ↺ beside it is a rejected row, and a grey button alone is one nobody has
 * touched. The row's own red tint and the header's exception count say the
 * same thing again, in colour and in words.
 *
 * <p>`aria-pressed` would be a lie here. It has two values and this has three,
 * and "pressed: false" on an unanswered item would announce it as answered No.
 * The name carries the state instead — <em>Admission data verification — Not
 * completed. Press to set Completed.</em> — which is also where the difference
 * between rejected and untouched survives for a reader who cannot see that the
 * two now look alike.
 *
 * <p>The ↻ that fades in on hover is part of the button rather than a second
 * control; without it a chip stating a status looks like a label.
 */
function StatusButton({
  answer,
  label,
  disabled,
  title,
  onClick,
}: {
  answer: boolean | null
  label: string
  disabled: boolean
  title?: string
  onClick: () => void
}) {
  const state = answer === null ? 'unset' : (String(answer) as 'true' | 'false')
  const flipsTo = answer === true ? ANSWER_WORD.false : ANSWER_WORD.true

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-label={`${label} — ${ANSWER_WORD[state]}. Press to set ${flipsTo}.`}
      title={title ?? `Press to set ${flipsTo}`}
      data-answer={state}
      data-testid="ob-check-item-answer"
      className={cn(
        'group flex w-full max-w-[158px] items-center justify-between gap-2',
        'rounded-control border px-2.5 py-1.5 text-[12.5px] font-semibold',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        // Complete is the only filled state. Everything else — never touched,
        // or pressed back to rejected — is the dashed grey slot, because both
        // of them mean the same thing to the person working the list.
        state === 'true'
          ? 'border-success bg-success text-white'
          : 'border-dashed border-border bg-subtle text-content-muted',
        disabled ? 'cursor-not-allowed opacity-55' : 'hover:border-primary',
      )}
    >
      <span aria-hidden="true" className="text-[13px] leading-none">
        {ANSWER_MARK[state]}
      </span>
      <span className="flex-1 whitespace-nowrap text-left">{ANSWER_BUTTON_WORD}</span>
      <span
        aria-hidden="true"
        className={cn(
          'text-[12px] opacity-75 transition-opacity sm:opacity-0',
          !disabled && 'sm:group-hover:opacity-75 sm:group-focus-visible:opacity-75',
        )}
      >
        ↻
      </span>
    </button>
  )
}

/** What each verdict is called, and the mark that carries it for the eye. */
const VERDICT_WORD: Record<ObStepReviewState, string> = {
  NOT_REVIEWED: 'Not reviewed',
  VERIFIED: 'Verified',
  REJECTED: 'Rejected',
}

const VERDICT_MARK: Record<ObStepReviewState, string> = {
  NOT_REVIEWED: '○',
  VERIFIED: '✓',
  REJECTED: '↻',
}

const VERDICT_NEXT: Record<ObStepReviewState, ObStepReviewState> = {
  NOT_REVIEWED: 'VERIFIED',
  VERIFIED: 'REJECTED',
  REJECTED: 'NOT_REVIEWED',
}

/**
 * The row's own Send — the control that makes the row the unit of work.
 *
 * <h2>One cell, two owners, never both</h2>
 *
 * <p>A row is either with its implementor or with the reviewer, so there is
 * never a moment when two people could press this. Which of them is looking
 * decides what it says: *Send for review* on the way out, *Send back* on the
 * way home.
 *
 * <h2>What it costs to press, and why it is a second press</h2>
 *
 * <p>Answering a row does not send it and choosing a verdict does not release
 * one. The first press is a thought and stays reversible; this one is a
 * message somebody else starts acting on. Collapsing them would turn a
 * mis-click into work another person begins doing.
 *
 * <h2>A row nobody can move shows its state, not a dead button</h2>
 *
 * <p>Out with the manager, or approved and shut: there is no press available
 * and there is nothing the reader can do about it. A greyed button there
 * invites somebody to work out why it is grey, and the answer is never about
 * this row.
 */
function RowSendCell({
  item,
  state,
  mine,
  theirs,
  ready,
  busy,
  owner,
  onSend,
}: {
  item: ObJourneyStepItem
  state: ObStepRowState
  mine: boolean
  theirs: boolean
  ready: boolean
  busy: boolean
  owner: string
  onSend: () => void
}) {
  if (!mine && !theirs) {
    const word =
      state === 'VERIFIED'
        ? '✓ approved'
        : state === 'SENT'
          ? '⌛ with reviewer'
          : state === 'REJECTED'
            ? `↻ with ${owner}`
            : '—'
    return (
      <span
        data-testid="ob-check-list-row-sent"
        data-state={state}
        className={cn(
          'w-full text-center text-[11px] font-semibold',
          state === 'VERIFIED'
            ? 'text-success-text'
            : state === 'REJECTED'
              ? 'text-danger-text'
              : 'text-content-muted',
        )}
      >
        {word}
      </span>
    )
  }

  const why = mine
    ? item.answer == null
      ? 'Answer this row first'
      : 'Send this row for verification'
    : item.reviewState == null || item.reviewState === 'NOT_REVIEWED'
      ? 'Choose Verified or Rejected first'
      : !ready
        ? 'A rejection needs a reason'
        : `Send this row back to ${owner} now`

  return (
    <button
      type="button"
      data-testid="ob-check-list-row-send"
      disabled={!ready || busy}
      title={why}
      aria-label={`${mine ? 'Send' : 'Send back'} ${item.label}`}
      onClick={onSend}
      className={cn(
        'inline-flex w-full shrink-0 items-center justify-center gap-1 rounded-control border',
        'px-2 py-1.5 text-[11px] font-semibold',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        ready && !busy
          ? 'border-primary bg-primary text-white hover:bg-primary/90'
          : 'cursor-not-allowed border-border bg-subtle text-content-muted',
      )}
    >
      <span aria-hidden="true">↑</span>
      {mine ? 'Send' : 'Send back'}
    </button>
  )
}

/**
 * The verdict as a fact, for everybody who is not the one recording it.
 *
 * <h2>Why the column stops being a control</h2>
 *
 * <p>{@link RowSendCell} settled this for the Send column and the argument is
 * the same one: a greyed button is an invitation to work out why it is grey,
 * and the answer — <em>this was never yours to press</em> — is not about the
 * row. An implementor opening a returned task was shown a disabled
 * <b>Rejected</b> button in a column headed <i>Review · manager</i>, which is
 * two signals that it might become pressable and none that it is simply the
 * manager's answer.
 *
 * <p>Same marks and same words as the button ({@link VERDICT_MARK},
 * {@link VERDICT_WORD}) so the two readings of one column cannot drift, and the
 * reviewer's name where there is one — the verdict is somebody's, and on a
 * returned row that is the person the reader will be replying to.
 *
 * <h2>Not reviewed is two different silences</h2>
 *
 * <p>A row still with its implementor has no verdict because nobody was asked
 * for one, and drawing "Not reviewed" on it reads as a manager who has not got
 * round to it. It gets the em dash; only a row actually out for review says it
 * is waiting.
 */
function VerdictMark({
  state,
  rowState,
  reviewer,
  title,
}: {
  state: ObStepReviewState
  rowState: ObStepRowState
  reviewer: string | null
  title: string
}) {
  const unread = state === 'NOT_REVIEWED'
  return (
    <span
      data-testid="ob-check-list-row-verdict"
      data-review={state}
      title={title}
      className={cn(
        'min-w-0 text-[11px] font-semibold',
        unread
          ? 'text-content-muted'
          : state === 'VERIFIED'
            ? 'text-success-text'
            : 'text-danger-text',
      )}
    >
      {unread ? (
        rowState === 'SENT' ? (
          <>
            <span aria-hidden="true">⌛</span> Not read yet
          </>
        ) : (
          <span>—</span>
        )
      ) : (
        <>
          <span aria-hidden="true">{VERDICT_MARK[state]}</span> {VERDICT_WORD[state]}
          {reviewer ? (
            <span className="block font-medium text-content-muted">by {reviewer}</span>
          ) : null}
        </>
      )}
    </span>
  )
}

/**
 * The reviewer's control — {@link StatusButton}'s shape, a second set of words.
 *
 * <h2>Why not simply reuse StatusButton</h2>
 *
 * <p>It is the same interaction and deliberately looks like it: one control
 * that states what it holds and sets the next thing when pressed. What differs
 * is what the states mean. An implementor records whether they did the thing —
 * Completed / Not completed. A reviewer judges whether it holds — Verified /
 * Rejected. Sharing one vocabulary across the two columns would make a row read
 * "Completed / Completed" and leave nothing to tell the claim from the
 * judgement of it.
 *
 * <p>The colours differ for the same reason. Rejected wears the delayed orange
 * rather than the danger red the answer's False uses, so the two columns are
 * never mistaken for the same mark twice; Not reviewed wears the reviewer's
 * blue rather than the answer's grey, so an unread row is visibly waiting on
 * somebody rather than merely empty.
 *
 * <p>`aria-pressed` would be a lie here exactly as it would there — it has two
 * values and this has three — so the accessible name carries the state in
 * words and says what pressing will do.
 */
function ReviewButton({
  state,
  label,
  disabled,
  onClick,
}: {
  state: ObStepReviewState
  label: string
  disabled: boolean
  onClick: () => void
}) {
  const next = VERDICT_NEXT[state]

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-label={`${label} — ${VERDICT_WORD[state]}. Press to set ${VERDICT_WORD[next]}.`}
      title={`Press to set ${VERDICT_WORD[next]}`}
      className={cn(
        'group flex w-full max-w-[172px] items-center justify-between gap-2',
        'rounded-control border px-2.5 py-1.5 text-[12.5px] font-semibold',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        state === 'NOT_REVIEWED' && 'border-dashed border-level-medium bg-surface text-level-medium-text',
        state === 'VERIFIED' && 'border-success bg-level-low-soft text-success-text',
        // Red, on the reader's own call. It was the delayed orange, to keep a
        // manager's Rejected distinguishable from an implementor's own Not
        // completed — but rejection is the one verdict that sends work back,
        // and it is worth the loudest colour the palette has.
        state === 'REJECTED' && 'border-danger bg-danger-soft text-danger-text',
        disabled ? 'cursor-not-allowed opacity-55' : 'hover:border-primary',
      )}
    >
      <span aria-hidden="true" className="text-[13px] leading-none">
        {VERDICT_MARK[state]}
      </span>
      <span className="flex-1 whitespace-nowrap text-left">{VERDICT_WORD[state]}</span>
      <span
        aria-hidden="true"
        className={cn(
          'text-[12px] opacity-75 transition-opacity sm:opacity-0',
          !disabled && 'sm:group-hover:opacity-75 sm:group-focus-visible:opacity-75',
        )}
      >
        ↻
      </span>
    </button>
  )
}
