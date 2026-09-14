import * as React from 'react'

import type { ObProjectStage } from '@/api/generated/model/obProjectStage'
import { cn } from '@/lib/utils'

import {
  orderStages,
  stageCountLabel,
  stageFraction,
  stageState,
  type StageState,
} from './stageRibbon'

/**
 * The project's implementation-stage ribbon — the third of the four levels the
 * page is built on (Project → Prerequisites → **Stage** → Task → Task list).
 *
 * ## It draws the roll-up, it does not fetch it
 *
 * `ObProjectDetail.stages` already carries every figure this needs, computed by
 * `ObProjectReadRepository.STAGE_ROLLUP` in the same request as the header. So
 * the ribbon costs no read of its own, and — importantly — a project boarded
 * through two module services still shows *six* stages rather than twelve,
 * because the fold onto `implementation_stage_id` happened in SQL.
 *
 * ## Why stages and not tasks
 *
 * The ribbon used to step through tasks, so a template with twenty of them
 * became a twenty-stop strip with no grouping, and the question people open a
 * project with — *which part of the rollout are we in?* — had no answer on the
 * screen that should hold it. Stages answer it in seven stops, in the
 * vocabulary operations already use.
 *
 * ## An empty stage is shown, not hidden
 *
 * Five of the seven stages on the seeded corpus carry no tasks. Hiding them
 * would make the ribbon a different length for every module service and lose
 * the fact that the stage exists on the template and was never scheduled —
 * a misconfiguration somebody should see, not a tidiness problem. They render
 * recessive, and in the interactive mode they are not selectable, because there
 * is nothing underneath them to select.
 *
 * ## Presentational, or a tablist
 *
 * Omit {@link ObStageRibbonProps.onSelect} and this renders as a status strip —
 * a list, not a tablist, with nothing focusable and nothing offering a click.
 * Pass it and the ribbon becomes a real tablist with arrow-key movement, and
 * selecting a stop narrows the task list below to that stage.
 *
 * ## Locked stops are rendered, not removed
 *
 * The project page locks every stage that holds none of the reader's own tasks.
 * A locked stop keeps its counts and names its owner, carries `aria-disabled`
 * rather than `disabled` so a screen reader can still reach it and read why,
 * and is skipped by arrow-key movement — a tablist that traps focus on an inert
 * stop is worse than no arrow support at all.
 */
export interface ObStageRibbonProps {
  stages: readonly ObProjectStage[]
  /**
   * The selected stop, where one can be selected at all. Left undefined, the
   * ribbon marks the stage the server reports as current instead.
   */
  selectedKey?: number | null
  /** Omit for the presentational strip — see the note above. */
  onSelect?: (stageKey: number) => void
  /**
   * Whether a stage may be opened. Defaults to "any stage that holds tasks",
   * and is ignored entirely in the presentational mode.
   *
   * A stage this returns false for is rendered, dimmed and inert, and carries
   * `aria-disabled` rather than `disabled` so a screen reader can still reach
   * it and read who it belongs to. A control removed from the tab order is a
   * control a keyboard user cannot discover the reason for.
   */
  canOpen?: (stage: ObProjectStage) => boolean
  /** Appended to a locked stop's accessible name — "owned by Kavya Sharma". */
  lockedHint?: (stage: ObProjectStage) => string | undefined
  /**
   * Whether this stop holds the signed-in user's own work, which earns it a
   * `Yours` badge.
   *
   * <p>Separate from {@link canOpen} even though the project page derives both
   * from the same set: "this is mine" and "I may open this" are different
   * claims, and a future role-aware rule that let a manager open every stage
   * must not thereby mark every stage as theirs.
   */
  isYours?: (stage: ObProjectStage) => boolean
  /** The line beside the caption — "Only stages holding your tasks can be opened". */
  caption?: React.ReactNode
  className?: string
}

const BEAD: Record<StageState, string> = {
  complete: 'border-ribbon-done bg-ribbon-done text-white',
  current: 'border-ribbon-current bg-ribbon-current-bg text-ribbon-current-text',
  pending: 'border-ribbon-pending bg-surface text-ribbon-pending-text',
  empty: 'border-border bg-subtle text-content-muted',
}

const FILL: Record<StageState, string> = {
  complete: 'bg-ribbon-done',
  current: 'bg-ribbon-current',
  pending: 'bg-ribbon-pending',
  empty: 'bg-transparent',
}

/** The visual, shared by both modes so they can never drift apart. */
function StageFace({
  stage,
  index,
  locked,
  yours,
}: {
  stage: ObProjectStage
  index: number
  locked: boolean
  yours: boolean
}) {
  const state = stageState(stage)
  return (
    <>
      <span className="flex items-center gap-1.5">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-[17px] shrink-0 items-center justify-center rounded-chip border-[1.5px] text-[9.5px] font-bold tabular-nums',
            BEAD[state],
          )}
        >
          {state === 'complete' ? '✓' : index + 1}
        </span>
        <span
          className={cn(
            'truncate text-caption font-semibold',
            state === 'empty' ? 'text-content-muted' : 'text-content',
          )}
        >
          {stage.name}
        </span>
        {yours && (
          <span className="shrink-0 rounded-chip border border-primary bg-surface px-1.5 text-[9.5px] font-bold uppercase tracking-wide text-primary">
            Yours
          </span>
        )}
      </span>

      <span className="flex items-center gap-1 pl-[23px] text-[10.5px] tabular-nums text-content-muted">
        {locked && (
          <span aria-hidden="true" className="text-[9.5px]">
            🔒
          </span>
        )}
        <span className="truncate">{stageCountLabel(stage)}</span>
      </span>

      {/* The rail carries progress without spending a number on it — the count
          above is already the number. */}
      <span aria-hidden="true" className="ml-[23px] h-[3px] overflow-hidden rounded-chip bg-subtle">
        <span
          className={cn('block h-full rounded-chip', FILL[state])}
          style={{ width: `${Math.round(stageFraction(stage) * 100)}%` }}
        />
      </span>
    </>
  )
}

const STOP = 'flex min-w-[8.25rem] flex-1 shrink-0 flex-col gap-1 rounded-control px-2.5 py-2 text-left'

export function ObStageRibbon({
  stages,
  selectedKey,
  onSelect,
  canOpen,
  lockedHint,
  isYours,
  caption,
  className,
}: ObStageRibbonProps) {
  const ordered = React.useMemo(() => orderStages(stages), [stages])
  const interactive = onSelect != null
  const mayOpen = React.useCallback(
    (stage: ObProjectStage) => (canOpen ? canOpen(stage) : stage.taskCount > 0),
    [canOpen],
  )

  const markedKey =
    selectedKey !== undefined
      ? selectedKey
      : (ordered.find((s) => s.isCurrent && s.taskCount > 0)?.stageKey ?? null)

  const refs = React.useRef<(HTMLButtonElement | null)[]>([])

  /**
   * Arrow keys move between stops, skipping the ones that cannot be opened.
   *
   * A tablist that traps focus on an inert stop is worse than no arrow support
   * at all, so the search walks past them, and it stops at the ends rather than
   * wrapping — wrapping around a seven-stop sequence loses the sense of
   * position the ribbon exists to give.
   */
  const move = React.useCallback(
    (from: number, delta: number) => {
      for (let i = from + delta; i >= 0 && i < ordered.length; i += delta) {
        if (mayOpen(ordered[i])) {
          onSelect?.(ordered[i].stageKey)
          refs.current[i]?.focus()
          return
        }
      }
    },
    [ordered, mayOpen, onSelect],
  )

  if (ordered.length === 0) return null

  const shell = cn('overflow-hidden rounded-card border border-border bg-surface shadow-rest', className)
  // Scrolls on its own so a long ribbon never widens the page — the body must
  // never scroll sideways.
  const strip = 'flex gap-0.5 overflow-x-auto p-1'

  const captionRow = caption ? (
    <div className="mb-1.5 flex flex-wrap items-center gap-2 text-[10.5px] font-bold uppercase tracking-wide text-content-muted">
      <span>Implementation stage</span>
      <span aria-hidden="true" className="h-px min-w-4 flex-1 bg-border" />
      <span className="text-caption font-medium normal-case tracking-normal">{caption}</span>
    </div>
  ) : null

  if (!interactive) {
    return (
      <div>
        {captionRow}
        <div className={shell}>
        <ol role="list" aria-label="Implementation stages" className={cn(strip, 'm-0 list-none')}>
          {ordered.map((stage, index) => (
            <li
              key={stage.stageKey}
              aria-current={stage.stageKey === markedKey ? 'step' : undefined}
              className={cn(
                STOP,
                stage.stageKey === markedKey && 'bg-primary-soft ring-[1.5px] ring-inset ring-ribbon-current',
              )}
            >
              <StageFace stage={stage} index={index} locked={false} yours={isYours?.(stage) ?? false} />
            </li>
          ))}
        </ol>
        </div>
      </div>
    )
  }

  return (
    <div>
      {captionRow}
      <div className={shell}>
      <div role="tablist" aria-label="Implementation stages" className={strip}>
        {ordered.map((stage, index) => {
          const open = mayOpen(stage)
          const selected = stage.stageKey === markedKey
          const hint = open ? undefined : lockedHint?.(stage)
          const label = hint ? `${stage.name} — ${hint}` : stage.name

          return (
            <button
              key={stage.stageKey}
              ref={(el) => {
                refs.current[index] = el
              }}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-disabled={open ? undefined : true}
              tabIndex={selected || (markedKey === null && index === 0) ? 0 : -1}
              title={label}
              aria-label={`${label}, ${stageCountLabel(stage)}`}
              onClick={() => open && onSelect?.(stage.stageKey)}
              onKeyDown={(event) => {
                if (event.key === 'ArrowRight') {
                  event.preventDefault()
                  move(index, 1)
                } else if (event.key === 'ArrowLeft') {
                  event.preventDefault()
                  move(index, -1)
                }
              }}
              className={cn(
                STOP,
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                open ? 'cursor-pointer hover:bg-subtle' : 'cursor-not-allowed opacity-60',
                selected && 'bg-primary-soft ring-[1.5px] ring-inset ring-ribbon-current hover:bg-primary-soft',
              )}
            >
              <StageFace stage={stage} index={index} locked={!open} yours={isYours?.(stage) ?? false} />
            </button>
          )
        })}
        </div>
      </div>
    </div>
  )
}
