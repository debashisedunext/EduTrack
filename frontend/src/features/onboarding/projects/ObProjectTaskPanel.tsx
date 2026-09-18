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
 * <p><b>Neither column is always there, and the reviewer has only one.</b>
 * Action is the implementor's control and is not drawn for the manager
 * reviewing the list — it was a greyed button in a column they have no part
 * in. Review is not drawn on a task nobody has submitted, where it would be a
 * heading over four hundred pixels of nothing. So the reviewer reads three
 * columns and a wide one: their verdict is three option buttons rather than
 * one cycling button and needs 282px to hold them.
 *
 * <p>The folded layout is unchanged whatever the count — a phone stacks the
 * cells under the item they belong to.
 */
const rowGrid = (withAction: boolean, withReview: boolean) =>
  cn(
    'grid grid-cols-[26px_minmax(0,1fr)] items-start gap-x-2.5 gap-y-1.5 px-3 py-2.5',
    'sm:items-center sm:py-2',
    withAction && withReview
      ? 'sm:grid-cols-[34px_minmax(0,1.15fr)_186px_178px_minmax(0,1.2fr)]'
      : withAction
        ? 'sm:grid-cols-[34px_minmax(0,1.35fr)_186px_minmax(0,1.5fr)]'
        : withReview
          ? 'sm:grid-cols-[34px_minmax(0,1.2fr)_282px_minmax(0,1.2fr)]'
          : 'sm:grid-cols-[34px_minmax(0,1.4fr)_minmax(0,1.5fr)]',
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
  /**
   * Rows the reviewer has just pressed Rejected on, before the server agrees.
   *
   * <p>The reason box opens only on a rejected row, and until this existed
   * "rejected" meant *the server has told us so* — which arrives a refetch
   * later. So the press focused a box that was not on the screen yet, and the
   * manager was left looking at a red row with nowhere to type. Holding the
   * press locally opens the box in the same render, and the server's answer
   * then agrees with what the screen already shows.
   *
   * <p>Cleared when another verdict is chosen, and when the row leaves.
   */
  const [rejecting, setRejecting] = React.useState<Record<number, true>>({})

  /*
    The reviewer's release, with no button of its own any more.

    Rejecting a row *is* sending it back — the manager presses Rejected, types
    why, and the row goes the moment the reason is saved. The two presses this
    used to take existed to keep a mis-click reversible, and that argument only
    ever held for the other verdict: Verified stays reversible for as long as
    the review is open and is released by the task's own **Verified**, while a
    rejection is already deliberate by the time somebody has written a sentence
    explaining it.
  */
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
  /**
   * The Action column is the implementor's, and the reviewer does not get it.
   *
   * <p>It holds one control — <b>Mark complete</b> on a row — and a manager
   * reading the list may not press it in any state: `mayAnswer` is false for
   * them by the same rule the server enforces. So for as long as the task is
   * on their desk the column was a greyed button and a heading, which is the
   * most expensive thing a screen can show. Their reading of the list is the
   * item, their verdict and the remark.
   */
  const showAction = !reviewing
  const ROW_GRID = rowGrid(showAction, showReview)

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
   * What holds the reviewer's **Verified**, in the same shape `blockers` names
   * for the implementor's — so the bar can say why either one is grey without
   * knowing which of the two people is reading it.
   *
   * <p>A rejected row is not usually among them: pressing Rejected sends that
   * row home as soon as its reason is written, so by the time this is empty
   * every row still on the desk is verified. The unexplained-rejection clause
   * stays for the one row caught between the two halves of that press.
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
   * left to wait for: the owner's button turns into **Mark Complete**, and
   * pressing it is what finishes the task.
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

  /** This row is on the reviewer's desk and the verdict is theirs to set. */
  const mayReview = (item: ObJourneyStepItem) => canReview && isOut(item)

  /**
   * The remark box, and who owns it right now.
   *
   * <p>One field with two authors. The implementor's while the row is theirs;
   * the manager's <em>only</em> on a row they have rejected — never on one
   * they are verifying, so a verdict cannot quietly rewrite the note it is
   * passing.
   */
  const isRejectedHere = (item: ObJourneyStepItem) =>
    item.reviewState === 'REJECTED' || rejecting[item.id] === true

  const mayRemark = (item: ObJourneyStepItem) =>
    mayAnswer(item) || (mayReview(item) && isRejectedHere(item))

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
   * The reviewer's choice, as one of three rather than the next of three.
   *
   * <p>It was a single button cycling Not reviewed → Verified → Rejected, so
   * rejecting a verified row took two presses and reaching either verdict
   * meant reading the button first to find out where the cycle stood. The
   * column now offers all three at once and this records whichever was
   * pressed.
   *
   * <h2>Rejected sends the row back by itself</h2>
   *
   * <p>There is no Send back beside it any more: a rejection is a decision
   * somebody else has to act on, and holding it behind a second control only
   * ever produced rows sitting rejected on a manager's screen that their
   * implementor could not see.
   *
   * <p>But it may not go without a reason — the server refuses a release that
   * has none, and rightly. So the press does half of it: the verdict lands,
   * the remark box empties, opens and takes focus, and the row leaves the
   * moment that reason is saved ({@link releaseWithReason}). The box is
   * emptied rather than left holding the implementor's own note, which would
   * otherwise be sent back to them as the reviewer's reason for sending it
   * back.
   *
   * <p>Verified does <em>not</em> release. It stays reversible for as long as
   * the review is open — pressing Rejected next to it is one click away — and
   * the task's own <b>Verified</b> is the deliberate press that hands
   * everything back at once.
   */
  /** This row is no longer mid-rejection — the verdict changed, or it left. */
  const stopRejecting = (id: number) =>
    setRejecting((r) => {
      if (!(id in r)) return r
      const rest: Record<number, true> = {}
      for (const key of Object.keys(r)) {
        if (Number(key) !== id) rest[Number(key)] = true
      }
      return rest
    })

  const chooseVerdict = (item: ObJourneyStepItem, next: ObStepReviewState) => {
    if (next === 'REJECTED') {
      setDrafts((d) => ({ ...d, [item.id]: '' }))
      setRejecting((r) => ({ ...r, [item.id]: true }))
      review.mutate(
        { itemId: item.id, data: { state: 'REJECTED' } },
        /*
          And put the row back as it was if the server refuses. The optimistic
          half of this press opens the reason box a refetch early, which is the
          whole point of it — but left standing over a refusal it draws a
          rejected-looking row, with a red box to type a reason into, on a row
          the server still holds as Not Reviewed. One of those two has to be a
          lie, and it must not be the screen.
        */
        { onError: () => stopRejecting(item.id) },
      )
      // Straight to the box they now have to fill. Without this the required
      // field is a red border somewhere below the press that caused it.
      window.setTimeout(() => inputs.current[item.id]?.focus(), 0)
      return
    }
    stopRejecting(item.id)
    review.mutate({ itemId: item.id, data: { state: next } })
  }

  /**
   * The second half of a rejection: the reason, and the row going home.
   *
   * <p>One call writes the reason onto the row and a second releases it, in
   * that order — `releaseItem` refuses a rejection whose remark is blank, so
   * the reason has to be on the row before the row can move. Chained on
   * success rather than fired together, because a release that overtook its
   * reason would be refused and leave the row parked.
   */
  const releaseWithReason = (item: ObJourneyStepItem, reason: string) => {
    review.mutate(
      { itemId: item.id, data: { state: 'REJECTED', remark: reason } },
      {
        onSuccess: () =>
          sendBack.mutate(
            { itemId: item.id },
            { onSuccess: () => stopRejecting(item.id) },
          ),
      },
    )
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

  /**
   * Which of its two jobs the owner's one button is doing.
   *
   * <p>`SEND` while any row is still short of `VERIFIED` — there is a check
   * list the manager has not passed, so the button hands it over. `COMPLETE`
   * once every row is verified, and on a task with no check list at all, where
   * there was never anything to verify and the button is the plain close it
   * has always been.
   *
   * <p>Read off the rows rather than off the task's status, for the reason
   * `reopened` gives: the rows are what the rule is about and they are already
   * on the screen.
   */
  const openRows = task.items.filter((i) => stateOf(i) !== 'VERIFIED')
  const checklistPhase: 'SEND' | 'COMPLETE' = openRows.length === 0 ? 'COMPLETE' : 'SEND'

  /**
   * What holds **Send for Verification** — and only what holds it.
   *
   * <p>`submitChecklist` asks one thing: that every row going out is answered.
   * Not only the mandatory ones, unlike the completion gate — the list travels
   * as a unit, so an optional row left blank is a blank line in front of a
   * reviewer. Required documents are deliberately not counted here; they hold
   * <b>Mark Complete</b>, which is a later press.
   */
  const sendBlockers = React.useMemo(() => {
    const unanswered = openRows.filter((i) => i.answer == null).length
    return unanswered > 0
      ? [`${unanswered} unanswered row${unanswered === 1 ? '' : 's'}`]
      : []
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [task.items])

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
                Their answers are read-only — set each row to <b>Verified</b> or <b>Rejected</b> in
                the Review column. A rejection needs a line in its remark and goes back to {owner} as
                soon as you save it; once every row left here is verified, press <b>Verified</b> on
                the task to hand it back.
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
              ? 'The check list is closed — press Mark Complete to finish the task.'
              : `The check list is closed — ${owner} presses Mark Complete to finish the task.`}
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
                  ? 'Fix it, answer it again, then press Send for Verification.'
                  : 'Fix each one, answer them again, then press Send for Verification.'
                : `${owner} answers ${rejectedRows.length === 1 ? 'it' : 'each one'} again and sends the list back.`}
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
              {showAction && <span role="columnheader">Action</span>}
              {showReview && (
                // Named for whose mark it is. "Review" alone, next to a column
                // of the implementor's own answers, does not say which of the
                // two people the mark belongs to.
                <span role="columnheader" className="text-level-medium-text">
                  Review · manager
                </span>
              )}
              <span role="columnheader">Remark</span>
            </div>

            {task.items.map((item, index) => {
              const busy = update.isPending && update.variables?.itemId === item.id
              const reviewBusy =
                (review.isPending && review.variables?.itemId === item.id) ||
                (sendBack.isPending && sendBack.variables?.itemId === item.id)
              const answeredHere = item.answer === true || item.answer === false
              const canAnswer = mayAnswer(item)
              const canReviewRow = mayReview(item)
              const rejectedHere = isRejectedHere(item)
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

                  {showAction && (
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
                  )}

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
                        <ReviewChoice
                          state={item.reviewState ?? 'NOT_REVIEWED'}
                          label={item.label}
                          disabled={reviewBusy}
                          onChoose={(next) => chooseVerdict(item, next)}
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
                      /*
                        Enter commits, because on a rejected row leaving the box
                        is what sends the row and "click somewhere else" is not
                        an instruction anybody reads. `blur` rather than a
                        second write path, so there is one commit and it cannot
                        drift from the other.
                      */
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault()
                          e.currentTarget.blur()
                        }
                      }}
                      onBlur={() => {
                        const text = remarkOf(item).trim()
                        if (text === (item.remark ?? '')) return
                        /*
                          Two writers, two routes. A manager typing the reason
                          on a rejected row is recording a verdict, not
                          answering a check list — `answerItem` would refuse
                          them for not owning the task. It is also the second
                          half of the rejection itself: the reason lands on the
                          row and the row goes straight back, which is why
                          there is no Send back button beside it.
                        */
                        if (rejectedHere && canReviewRow) {
                          if (text === '') return
                          releaseWithReason(item, text)
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

                </div>
              )
            })}
          </div>
        </section>
      )}

      {/*
          A refused verdict used to be silent. Only `update` — the implementor's
          own answer — ever reported a refusal, so a manager whose Rejected was
          turned down saw the row stay Not Reviewed and nothing else: no
          message, no reason, and a press that looked broken rather than
          answered. Both of the reviewer's writes report here now, in the
          server's own words.
        */}
      {(review.isError || sendBack.isError) && (
        <p className={OUTSTANDING_CLASS} role="alert">
          {(() => {
            const failed = sendBack.isError ? sendBack.error : review.error
            const what = sendBack.isError ? 'That row was not sent back' : 'That verdict was not saved'
            return failed instanceof ApiError
              ? `${what} — ${failed.problem.detail ?? failed.problem.title}.`
              : `${what}. Check your connection and try again.`
          })()}
        </p>
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
        sendBlockers={sendBlockers}
        checklistPhase={checklistPhase}
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
  NOT_REVIEWED: 'Not Reviewed',
  VERIFIED: 'Verified',
  REJECTED: 'Rejected',
}

const VERDICT_MARK: Record<ObStepReviewState, string> = {
  NOT_REVIEWED: '○',
  VERIFIED: '✓',
  REJECTED: '↻',
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
 * The reviewer's verdict — three options, all of them visible.
 *
 * <h2>Why it is not one button any more</h2>
 *
 * <p>It was {@link StatusButton}'s shape: one control naming its current
 * verdict, cycling Not reviewed → Verified → Rejected on each press. That
 * works for the implementor's column, where there are two answers and the
 * button can always say what pressing it does. It does not work for three: the
 * reader had to read the button to learn where the cycle stood before they
 * could aim it, rejecting a row already marked Verified took two presses, and
 * a press past the verdict somebody wanted took them round again.
 *
 * <p>So the three are laid out side by side and the pressed one is the
 * verdict. Nothing is hidden behind a state, and the manager aims rather than
 * counts.
 *
 * <h2>A radio group, because that is what it is</h2>
 *
 * <p>Three mutually exclusive choices over one value — `role="radiogroup"`
 * with `aria-checked` on each, so a screen reader announces "Verified,
 * selected, 2 of 3" rather than three unrelated buttons. Every option stays a
 * tab stop: the roving-tabindex pattern saves keystrokes on long lists and
 * costs them here, where there are three and they are the point of the column.
 *
 * <p><b>Never colour alone</b> — blueprint §12.1. Each option carries its word
 * and its mark, and the chosen one is also the only one with a filled
 * background, so the verdict survives both a greyscale screen and a reader who
 * hears the page rather than sees it.
 *
 * <h2>Rejected is the one that acts</h2>
 *
 * <p>Pressing it sends the row back — see `chooseVerdict`. The other two are
 * marks on a row that stays where it is, which is why only this one is
 * described as an action in its own hint.
 */
const VERDICT_CHOICES: readonly ObStepReviewState[] = ['NOT_REVIEWED', 'VERIFIED', 'REJECTED']

function ReviewChoice({
  state,
  label,
  disabled,
  onChoose,
}: {
  state: ObStepReviewState
  label: string
  disabled: boolean
  onChoose: (next: ObStepReviewState) => void
}) {
  return (
    <div
      role="radiogroup"
      data-testid="ob-check-list-row-verdict-choice"
      aria-label={`Review ${label}`}
      className="flex w-full min-w-0 items-stretch gap-1"
    >
      {VERDICT_CHOICES.map((choice) => {
        const chosen = state === choice
        return (
          <button
            key={choice}
            type="button"
            role="radio"
            aria-checked={chosen}
            data-verdict={choice}
            disabled={disabled}
            onClick={() => {
              if (!chosen) onChoose(choice)
            }}
            title={
              choice === 'REJECTED'
                ? 'Send this row back with a reason'
                : choice === 'VERIFIED'
                  ? 'This row holds'
                  : 'No verdict yet'
            }
            className={cn(
              'inline-flex min-w-0 flex-1 items-center justify-center gap-1 whitespace-nowrap',
              'rounded-control border px-1.5 py-1.5 text-[11px] font-semibold',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
              !chosen && 'border-border bg-surface text-content-muted',
              chosen &&
                choice === 'NOT_REVIEWED' &&
                'border-dashed border-level-medium bg-surface text-level-medium-text',
              chosen && choice === 'VERIFIED' && 'border-success bg-level-low-soft text-success-text',
              chosen && choice === 'REJECTED' && 'border-danger bg-danger-soft text-danger-text',
              disabled ? 'cursor-not-allowed opacity-55' : !chosen && 'hover:border-primary',
            )}
          >
            <span aria-hidden="true" className="text-[12px] leading-none">
              {VERDICT_MARK[choice]}
            </span>
            {VERDICT_WORD[choice]}
          </button>
        )
      })}
    </div>
  )
}
