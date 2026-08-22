import type { StatusCode } from '@/api/generated/model/statusCode'
import type { WorkflowStage } from '@/api/generated/model/workflowStage'
import { SegmentState } from '@/api/generated/model/segmentState'
import { ROLE_LABEL } from '@/lib/roleLabel'

/**
 * B-051 · the compact ribbon of blueprint line 984 — "eight small dots per
 * row (filled = done, ringed = current, hollow = pending, amber = reworked)
 * so a manager can scan a whole grid and see exactly where every ticket sits
 * without opening any of them. Hovering a dot names the stage and its owner."
 *
 * Pure, for the reason `segmentState.ts` is pure: the part that has to be
 * *right* — which dot gets which state, and what a screen reader is told about
 * a row — is testable without a DOM or a query client, and `RibbonDots` only
 * ever renders what this returns.
 *
 * ## It reuses `SegmentState`, and uses four of its six values
 *
 * `segmentState.ts`'s own header asks for this: "B-051's compact dot and
 * B-053's collapsed group read the same vocabulary instead of inventing a
 * second one three weeks from now". So a dot's state is the wire enum, not a
 * private four-value union that would have to be mapped at every boundary.
 *
 * `SKIPPED` and `BLOCKED` are the two it cannot produce, and that is a fact
 * about the payload rather than a simplification. Both are facts about what
 * *happened* to a ticket, and they live in `ticket_stage_transitions`, which
 * S-17's row shape does not carry — see the header on `buildCompactDots`.
 *
 * ## The four states are distinguishable without colour
 *
 * `tokens.css`'s ribbon block says colour is never the only signal, and
 * CLAUDE.md's WCAG AA line says it again. A 7px dot has no room for an icon or
 * a word, so the second channel here is **shape**: completed is a filled
 * circle, current is a larger filled circle inside a ring, pending is a hollow
 * outline, and reworked is a **diamond** — a filled mark rotated 45°, which
 * reads as different from a completed dot in greyscale and on a printed
 * screenshot. The word itself is on every dot's `title` and in the cell's
 * accessible name.
 *
 * No new token: `--ribbon-done|current|pending|reworked` are all four of them,
 * and `styles/tokens.css` is Stream C's and frozen — "others request, never
 * add". Nothing here is waiting on one.
 */

/** One dot. `state` is always one of the four `buildCompactDots` produces. */
export interface CompactDot {
  stageCode: string
  /** The stage's display name — what a hover names first. */
  label: string
  /** `roles.code`, not the six-value enum: S-09 lets an Admin add a seventh. */
  ownerRole?: string
  state: SegmentState
}

/**
 * The fields of a `TicketSummary` this needs, and no more — so the ticket
 * list, a story and a test can all supply one without building a whole row.
 */
export interface CompactDotTicket {
  currentStageCode?: string | null
  status: StatusCode
  /** Increments on a backward move within the cycle. Above 1 is "sent back". */
  iterationNo?: number
}

/** `ROLE_LABEL` is total over the six of §2; a seventh role reads as its code
 * rather than as `undefined`, the same degradation `levelLabel` makes. */
function roleLabel(code: string | undefined): string | undefined {
  if (!code) return undefined
  return ROLE_LABEL[code as keyof typeof ROLE_LABEL] ?? code
}

/** Stored order is `seq` 10, 20, 30 …; `sequence` is the 1-based position the
 * vocabulary shape carries. Sorted rather than trusted, because the dots are
 * a *sequence* and an array that arrived out of order would render a journey
 * that never happened. Ties keep their arrival order. */
function inRibbonOrder(stages: WorkflowStage[]): WorkflowStage[] {
  return stages
    .map((stage, index) => ({ stage, index }))
    .sort(
      (a, b) =>
        (a.stage.sequence ?? a.index) - (b.stage.sequence ?? b.index) || a.index - b.index,
    )
    .map((entry) => entry.stage)
}

/**
 * The template's stages plus one row of S-17 → one dot per stage, or `null`
 * when the row cannot be placed on the ribbon at all.
 *
 * ## Deprecated stages are dots, unlike in the stage *filter*
 *
 * `WorkflowTemplateDetail.stageCount`'s own doc comment: "**Every stage,
 * deprecated ones included** — that is the length a historical ticket renders,
 * and a retired stage keeps rendering on every ribbon it is already on
 * (B-042)." `TicketListPage`'s stage *filter* drops them for the opposite and
 * equally correct reason — nobody should filter to a stage nothing new can
 * enter. Dropping one here would shorten the journey of exactly the tickets
 * that are standing in it.
 *
 * `CLOSED` is a dot too, for the same reason and one more: §4A.1 lists it as
 * the eighth stage of Standard Dev Flow, and it is what makes the blueprint's
 * "eight small dots" eight.
 *
 * ## `null` is the answer when the row cannot be placed
 *
 * A `currentStageCode` the template does not contain means the two disagree —
 * most likely a ticket that started on a template whose stages have since been
 * re-cut, since nothing versions a template yet (B-042's note: "there is no
 * version column to clone into"). Eight hollow dots would be a claim that the
 * ticket has not started; a guess at an index would be a claim about where it
 * is. The column renders an em dash instead, which is the honest one.
 *
 * ## What the amber dot means here, and why it is not the prototype's
 *
 * `docs/prototype/index.html`'s `DOTS(done, cur, rew)` paints a **completed**
 * dot amber — and hardcodes its index at 2, because the prototype knows the
 * one walkthrough it is drawing. This cannot: which earlier stage a ticket
 * bounced out of lives in `ticket_stage_transitions`, and `TicketSummary`
 * carries `iterationNo` and nothing else about the loop. Picking an index
 * would be inventing the fact the reader would most trust the colour for.
 *
 * So amber marks the stage the ticket is in **now**, when it has been round
 * the loop at least once — the prototype's own sentence for the colour is
 * "amber means it has been sent back", which is a claim about the ticket
 * rather than about a particular earlier stage. `status === 'REWORK'` counts
 * as well as `iterationNo > 1`: a ticket failed back this moment has the
 * status before the iteration counter moves.
 *
 * ## A closed ticket has no current dot
 *
 * `CLOSED` is terminal, so its dot is completed rather than ringed: a ring
 * says "the work is here", and on a closed ticket the work is nowhere. Every
 * dot up to and including the last one it reached fills. A closed ticket with
 * no `currentStageCode` at all — which the contract permits, the field is
 * nullable — fills every dot, because finishing the journey is exactly what
 * closing it means. `RESOLVED` is deliberately not treated this way: resolved
 * is a stage a ticket sits in, and it can still be reopened or sent back.
 */
export function buildCompactDots(
  stages: WorkflowStage[] | undefined,
  ticket: CompactDotTicket,
): CompactDot[] | null {
  const ordered = inRibbonOrder(stages ?? []).filter((stage) => stage.stageCode)
  if (ordered.length === 0) return null

  const isClosed = ticket.status === 'CLOSED'
  const currentIndex = ticket.currentStageCode
    ? ordered.findIndex((stage) => stage.stageCode === ticket.currentStageCode)
    : -1

  // Placeable only if the code resolved, or if the ticket is closed — the one
  // state whose journey is complete whatever the last code was.
  if (currentIndex < 0 && !isClosed) return null

  const reworked = (ticket.iterationNo ?? 1) > 1 || ticket.status === 'REWORK'
  const filledThrough = currentIndex < 0 ? ordered.length - 1 : currentIndex

  return ordered.map((stage, index) => ({
    stageCode: stage.stageCode as string,
    label: stage.displayName ?? (stage.stageCode as string),
    ownerRole: stage.ownerRole,
    state:
      index < filledThrough || (isClosed && index === filledThrough)
        ? SegmentState.COMPLETED
        : index === filledThrough
          ? reworked
            ? SegmentState.REWORKED
            : SegmentState.CURRENT
          : SegmentState.PENDING,
  }))
}

/** The word on a dot's hover and in the cell's accessible name. Only the four
 * `buildCompactDots` emits are spelled; anything else reads as pending, the
 * same neutral fallback `treatmentFor` makes for an unknown wire state. */
const DOT_WORD: Partial<Record<SegmentState, string>> = {
  [SegmentState.COMPLETED]: 'completed',
  [SegmentState.CURRENT]: 'current stage',
  [SegmentState.PENDING]: 'not started',
  [SegmentState.REWORKED]: 'current stage, sent back',
}

export function dotWord(state: SegmentState): string {
  return DOT_WORD[state] ?? 'not started'
}

/**
 * §S-17: "Hovering a dot names the stage and its owner." Both, plus the state,
 * because a dot whose colour is the only thing saying "done" is unreadable to
 * the reader this hover most helps.
 */
export function dotTitle(dot: CompactDot): string {
  const owner = roleLabel(dot.ownerRole)
  return `${dot.label}${owner ? ` — ${owner}` : ''} · ${dotWord(dot.state)}`
}

/**
 * One sentence per row, not eight.
 *
 * The dots are a single cell in a 25-row grid, and a screen reader walking
 * eight focusable marks per row would read 200 of them to cross the page. So
 * the strip is one image with one name — where the ticket is, how far along,
 * and whether it has been sent back — and the per-dot detail stays on the
 * hover, where a pointer reader asks for it one at a time.
 *
 * B-052 owns keyboard navigation across the *detail* ribbon, where every
 * segment is a control with something to click. Nothing in this cell is.
 */
export function compactDotsAriaLabel(dots: CompactDot[]): string {
  const total = dots.length
  const current = dots.find(
    (dot) => dot.state === SegmentState.CURRENT || dot.state === SegmentState.REWORKED,
  )
  const completed = dots.filter((dot) => dot.state === SegmentState.COMPLETED).length

  if (!current) return `Journey: all ${total} stages completed`

  const position = dots.indexOf(current) + 1
  const owner = roleLabel(current.ownerRole)
  const sentBack = current.state === SegmentState.REWORKED ? ', sent back' : ''
  return `Journey: ${current.label}${owner ? ` (${owner})` : ''}, stage ${position} of ${total}${sentBack}. ${completed} completed`
}
