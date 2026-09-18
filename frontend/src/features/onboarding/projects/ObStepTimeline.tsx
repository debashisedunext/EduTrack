import * as React from 'react'
import { Check, ChevronRight } from 'lucide-react'

import type { UserRef } from '@/api/generated/model/userRef'
import { cn } from '@/lib/utils'

import {
  reviewCounts,
  SEGMENT_LABEL,
  segmentState,
  type ReviewCounts,
  type SegmentState,
} from './moduleStripStats'
import { stageNodeKey, taskAnchorId, type TreeService } from './projectTree'
import {
  hiddenStepCount,
  stepViews,
  TASK_FILTER_LABEL,
  type ObTaskFilter,
  type StepView,
} from './taskFilter'
import { formatDue, overdue } from './taskDates'
import { Avatar } from './taskFacts'
import { TaskStatusDot, ToneCount, ToneDot, type DotTone } from './TaskStatusDot'
import { PILL } from './TaskStatusPill'
import { isMine, type ProjectTask } from './useProjectTasks'
import { isMineOnly, type ObViewerScope } from './viewerScope'

/**
 * One Module Service's Steps as a vertical timeline of accordions, with the
 * tasks under the ones that are open — what an open module shows on the
 * project page.
 *
 * <h2>Step is the product's word; the API still says stage</h2>
 *
 * <p>`ObProjectStage`, `stageKey`, `ob_journey_template_stages` all name the
 * level this calls a <b>Step</b>, and `ob_journey_steps` is the <em>task</em>
 * table. Screen Step = API stage, screen Task = API step.
 *
 * <h2>Every Step is on the page; one of them is open</h2>
 *
 * <p>The Steps run down the page in template order with a bead on a rail — a
 * tick once complete, the number otherwise, coloured by state — and each Step
 * discloses its own tasks behind a chevron. The header row carries the Step's
 * state, its review figures, who is on it, its TAT and its due date, so a reader
 * scanning seven Steps reads all seven without opening any, then opens the one
 * they came for.
 *
 * <p><b>The first Step opens on arrival</b>, so a reader lands on a task rather
 * than on a list of headings. Under <b>Pending</b> the first Step is the first
 * one with work outstanding, which is what somebody opening a project came to
 * see.
 *
 * <p>It replaced a timeline that drew every task of every Step at once. That
 * is the right shape for a two-Step service and the wrong one for the default
 * master's seven, where the Step a reader wanted was four screens down.
 *
 * <h2>Pending, or all of it</h2>
 *
 * <p>Above the Steps, one switch with two positions — see {@link ObTaskFilter}.
 * <b>Pending</b> is the arrival state and draws outstanding work only; <b>Show
 * all</b> puts the finished tasks back. Neither moves a single figure on the
 * page: every count is folded from the unfiltered tree, so the switch changes
 * what is drawn and never what is counted.
 *
 * <p>It replaced <b>Show all Steps</b>, which toggled something else entirely —
 * whose work the page showed, the reader's or everybody's. Two controls in one
 * corner both saying "show all" and meaning different things is one control too
 * many. The page is the reader's own work now, and the switch says which of it.
 *
 * <h2>The row is a selection, and the task opens as a popup</h2>
 *
 * <p>A task row opens the task dialog over the page — check list, transitions,
 * timeline — and closing it leaves the reader exactly where they were. The
 * whole row is the control, and it says it opens a dialog.
 *
 * <h2>The row says what is inside</h2>
 *
 * <p>Status, sign-off, an unassigned owner, an escalation, the answered count,
 * the TAT and the due date ride on the row, so a reader scanning ten tasks
 * does not have to open each one to find the two that need them. The owner is
 * deliberately <b>not</b> on the row: the Step header names who is on the
 * Step, and on a service with one implementor that name repeated on every row
 * was the one thing the row printed that said nothing new. It is in the popup.
 *
 * <h2>Nothing is removed silently</h2>
 *
 * <p>A Step whose work is all finished is not drawn under <b>Pending</b>, and
 * the switch's own line says how many were left out. A Step with only some of
 * its tasks finished says so inside itself. A filter that cannot account for
 * what it removed is indistinguishable from a bug that lost it.
 */
export interface ObStepTimelineProps {
  node: TreeService
  scope: ObViewerScope
  /** Resolved display name, or null where the directory has no such user. */
  nameOf: (userId: number) => string | null
  users: readonly UserRef[]
  /** This client's open escalations, keyed by the task they were raised against. */
  escalations?: ReadonlyMap<number, { id: number; raisedBy: string; raisedAt: string; note: string }>
  /** The task open in the dialog, if any. Its Step is opened to hold it. */
  selectedTaskId: number | null
  onSelectTask: (taskId: number) => void
  /** Outstanding work, or all of it — see {@link ObTaskFilter}. */
  filter: ObTaskFilter
  /**
   * The switch.
   *
   * <p>Held by the workspace rather than here, so a reader who asked for
   * everything on one module is not asked the same question again on the next.
   */
  onFilterChange: (filter: ObTaskFilter) => void
}

/** The switch's positions, in the order they are drawn. */
const FILTERS: readonly ObTaskFilter[] = ['PENDING', 'ALL']

/**
 * The bead on the rail, and the hue of the ring beside the Step's name.
 *
 * <p>The ring replaced a chip that said <i>Complete</i> or <i>Running</i> in
 * words — the same trade the task rows under it made, and for the same reason:
 * a column of Steps is scanned, and five state words in five widths push every
 * figure beside them to a different place on every row. The word is not lost,
 * it is the ring's name (see {@link ObStepTimeline}'s header below).
 *
 * <p>`dot` is a {@link DotTone} rather than a class string so the ring cannot
 * drift from the circles on the rest of the page — one palette, in one file.
 */
const TONE: Record<SegmentState, { bead: string; dot: DotTone }> = {
  complete: { bead: 'border-ribbon-done bg-ribbon-done text-white', dot: 'green' },
  current: { bead: 'border-primary bg-surface text-primary', dot: 'blue' },
  waiting: { bead: 'border-ribbon-waiting bg-ribbon-waiting-bg text-ribbon-waiting-text', dot: 'amber' },
  blocked: { bead: 'border-ribbon-blocked bg-ribbon-blocked-bg text-ribbon-blocked-text', dot: 'red' },
  pending: { bead: 'border-border bg-surface text-content-muted', dot: 'grey' },
}

function settled(task: ProjectTask): boolean {
  return task.status === 'DONE' || task.status === 'SKIPPED'
}

export function ObStepTimeline({
  node,
  scope,
  nameOf,
  users,
  escalations,
  selectedTaskId,
  onSelectTask,
  filter,
  onFilterChange,
}: ObStepTimelineProps) {
  const views = React.useMemo(() => stepViews(node.stages, filter), [node.stages, filter])
  const hidden = hiddenStepCount(node.stages, filter)
  const firstKey = views.length > 0 ? views[0].step.stage.stageKey : null

  const ownerName = React.useCallback(
    (userId: number | null | undefined) =>
      userId == null ? null : (users.find((u) => u.id === userId)?.displayName ?? `User ${userId}`),
    [users],
  )

  /** Which Steps the reader has open. The first one is, on arrival. */
  const [openKeys, setOpenKeys] = React.useState<ReadonlySet<number>>(
    () => new Set(firstKey == null ? [] : [firstKey]),
  )

  /*
    Re-seeded when the switch moves, or when the Step at the top of the list
    changes — finishing the last outstanding task of the open Step drops it out
    from under the reader, and leaving nothing open would land them back on a
    list of headings.

    Keyed on a signature rather than on the list itself, because the tree is a
    fresh object on every refetch and depending on it would slam every Step the
    reader had opened shut each time the journey read came back.
  */
  const seed = `${filter}:${firstKey ?? ''}`
  const seenRef = React.useRef(seed)
  React.useEffect(() => {
    if (seenRef.current === seed) return
    seenRef.current = seed
    setOpenKeys(new Set(firstKey == null ? [] : [firstKey]))
  }, [seed, firstKey])

  /*
    The open task's Step is open, whatever else is. A `?task=` link names one
    task and the workspace scrolls to its row a frame later — a row inside a
    closed accordion is not there to be scrolled to.
  */
  React.useEffect(() => {
    if (selectedTaskId == null) return
    const holder = node.stages.find((s) => s.tasks.some((t) => t.id === selectedTaskId))
    if (!holder) return
    setOpenKeys((current) => {
      if (current.has(holder.stage.stageKey)) return current
      return new Set(current).add(holder.stage.stageKey)
    })
  }, [selectedTaskId, node.stages])

  const toggle = (stageKey: number) =>
    setOpenKeys((current) => {
      const next = new Set(current)
      if (next.has(stageKey)) next.delete(stageKey)
      else next.add(stageKey)
      return next
    })

  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 py-1">
        <div
          role="group"
          aria-label="Which tasks to show"
          className="inline-flex gap-0.5 rounded-chip border border-border bg-surface p-0.5"
        >
          {FILTERS.map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={filter === value}
              onClick={() => onFilterChange(value)}
              className={cn(
                'rounded-chip px-3 py-1 text-[11px] font-semibold transition-colors motion-reduce:transition-none',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                filter === value
                  ? 'bg-primary text-white'
                  : 'text-content-muted hover:bg-subtle hover:text-content',
              )}
            >
              {TASK_FILTER_LABEL[value]}
            </button>
          ))}
        </div>

        {/* What the switch is leaving out — never nothing, and never a number
            the reader has to work out for themselves. */}
        <p className="m-0 text-caption text-content-muted">
          {filter === 'ALL'
            ? 'Every task of this service, finished and outstanding.'
            : hidden === 0
              ? 'Outstanding tasks only.'
              : hidden === 1
                ? 'Outstanding tasks only — 1 finished Step is hidden.'
                : `Outstanding tasks only — ${hidden} finished Steps are hidden.`}
        </p>

        {/*
          The Steps that are somebody else's — stated, never offered. The page
          is scoped to the reader's own work and there is no longer a control
          that widens it, so this is a fact about the service rather than a
          filter with a way out: "my Step is waiting on Step 2" at least has a
          Step 2 to ask about. See `TreeService.hiddenStageCount`.
        */}
        {isMineOnly(scope) && node.hiddenStageCount > 0 && (
          <p className="m-0 text-caption text-content-muted">
            {node.hiddenStageCount === 1
              ? '1 more Step of this service is somebody else’s.'
              : `${node.hiddenStageCount} more Steps of this service are somebody else’s.`}
          </p>
        )}
      </div>

      {views.length === 0 ? (
        <p className="m-0 py-3 text-caption text-content-muted">
          {node.stages.length > 0
            ? 'Nothing outstanding here — every task of this service is finished.'
            : isMineOnly(scope)
              ? 'No Step of this service holds a task of yours.'
              : 'No tasks scheduled yet.'}
        </p>
      ) : (
        <ol className="m-0 flex list-none flex-col p-0" aria-label={`${node.service.serviceName} steps`}>
          {views.map((view, index) => (
            <StepItem
              key={view.step.stage.stageKey}
              view={view}
              last={index === views.length - 1}
              isOpen={openKeys.has(view.step.stage.stageKey)}
              onToggle={() => toggle(view.step.stage.stageKey)}
              panelId={`ob-step-${stageNodeKey(node.service.journeyId, view.step.stage.stageKey)}`}
              nameOf={nameOf}
              ownerName={ownerName}
              meId={scope.meId}
              escalations={escalations}
              selectedTaskId={selectedTaskId}
              onSelectTask={onSelectTask}
            />
          ))}
        </ol>
      )}
    </div>
  )
}

function StepItem({
  view,
  last,
  isOpen,
  onToggle,
  panelId,
  nameOf,
  ownerName,
  meId,
  escalations,
  selectedTaskId,
  onSelectTask,
}: {
  view: StepView
  last: boolean
  isOpen: boolean
  onToggle: () => void
  /** The task list this header discloses, for `aria-controls`. */
  panelId: string
  nameOf: (userId: number) => string | null
  ownerName: (userId: number | null | undefined) => string | null
  meId: number | null
  escalations: ObStepTimelineProps['escalations']
  selectedTaskId: number | null
  onSelectTask: (taskId: number) => void
}) {
  const { step, tasks: drawn, hiddenCount } = view
  const { stage, tasks } = step
  const state = segmentState(step)
  const tone = TONE[state]
  /*
    The header's figures are the Step's own, folded from every task in it and
    never from what the filter drew. "1 verified" above a list of one
    outstanding task is the truth; recomputing it over the drawn rows would
    print zero and quietly disagree with the Module strip above it.
  */
  const counts = reviewCounts(tasks)
  const totalTat = tasks.reduce((sum, t) => sum + t.tatDays, 0)

  /*
    Who is on this Step: every distinct owner, in task order, so the first name
    is the person the Step opens on. Nobody at all is said in red — an
    unassigned task is the one thing on this header somebody has to fix.
  */
  const owners: string[] = []
  let unassigned = false
  tasks.forEach((task) => {
    if (task.ownerUserId == null) {
      unassigned = true
      return
    }
    const name = nameOf(task.ownerUserId) ?? `User ${task.ownerUserId}`
    if (!owners.includes(name)) owners.push(name)
  })

  /*
    The earliest deadline still outstanding — the one that decides whether this
    Step is late. A settled task's due date is history and would have every
    finished Step wearing a red date for ever.
  */
  const due = tasks
    .filter((t) => t.dueAt && !settled(t))
    .map((t) => t.dueAt as string)
    .sort()[0]
  const late = overdue(due)

  return (
    <li data-testid="ob-timeline-step" className="relative flex gap-3.5 py-2.5">
      {/* The rail, from this bead down to the next. */}
      {!last && (
        <span aria-hidden="true" className="absolute bottom-[-6px] left-[11px] top-[34px] w-0.5 bg-border" />
      )}
      <span
        data-state={state}
        data-testid="ob-step-bead"
        className={cn(
          'relative z-[1] grid size-6 shrink-0 place-items-center rounded-chip border-[1.5px] text-[11px] font-semibold tabular-nums',
          tone.bead,
        )}
      >
        {/* The bead is the Step's number on a rail, coloured. The ring beside
            the name is the one that carries the state as a word, so this is
            left out of the accessibility tree rather than announcing the same
            thing twice — and it no longer takes a `title`, which would put the
            same tooltip on two elements a hand's width apart. */}
        {state === 'complete' ? (
          <Check aria-hidden="true" className="size-3.5" strokeWidth={3} />
        ) : (
          <span aria-hidden="true">{stage.sequence >= 9999 ? '·' : stage.sequence}</span>
        )}
      </span>

      <div className="flex min-w-0 flex-1 flex-col gap-2">
        {/*
          The disclosure is the chevron and the Step's name together, exactly
          as the Module strip above does it: the row carries figures and names
          beside the name, and a control inside a control is markup a browser
          resolves by dropping one of them.
        */}
        <div className="flex min-h-6 flex-wrap items-center gap-x-2.5 gap-y-1">
          <h3 className="m-0 min-w-0 text-[15px] font-semibold leading-5 text-content">
            <button
              type="button"
              aria-expanded={isOpen}
              aria-controls={panelId}
              onClick={onToggle}
              data-testid="ob-step-disclosure"
              className={cn(
                '-ml-1 inline-flex min-w-0 items-center gap-1.5 rounded-control px-1 py-0.5 text-left',
                'hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
              )}
            >
              <ChevronRight
                aria-hidden="true"
                className={cn(
                  'size-4 shrink-0 text-content-muted transition-transform motion-reduce:transition-none',
                  isOpen && 'rotate-90',
                )}
              />
              <span className="truncate">{stage.name}</span>
            </button>
          </h3>
          {/*
            The state as a ring rather than as the word it used to print. Its
            name is still the word — hover, and for a screen reader — which is
            what keeps the three figures beside it from being the only thing
            a reader who cannot see the hue has to go on.
          */}
          <ToneDot
            tone={tone.dot}
            label={SEGMENT_LABEL[state]}
            hollow
            dataState={state}
            testId="ob-step-dot"
          />
          <ReviewTally counts={counts} />

          {tasks.length > 0 && (
            <span className="ml-auto flex flex-wrap items-center gap-x-2 gap-y-0.5 text-caption tabular-nums text-content-muted">
              <span className="inline-flex items-center gap-1.5">
                {owners.length > 0 && <Avatar name={owners[0]} />}
                <span className="font-semibold text-content">{owners.join(', ')}</span>
                {unassigned && (
                  <span className="font-semibold text-danger-text">
                    {owners.length > 0 ? '+ nobody responsible' : 'Nobody responsible'}
                  </span>
                )}
              </span>
              <Sep />
              <span>
                TAT <b className="font-semibold text-content">{totalTat}d</b>
              </span>
              {due && (
                <>
                  <Sep />
                  <span>
                    Due{' '}
                    <b className={cn('font-semibold', late ? 'text-danger-text' : 'text-content')}>
                      {shortDay(due)}
                      {late ? ' (missed)' : ''}
                    </b>
                  </span>
                </>
              )}
            </span>
          )}
        </div>

        {isOpen && (
          <div id={panelId} className="flex flex-col gap-1">
            {drawn.length === 0 ? (
              <p className="m-0 text-caption text-content-muted">
                This stage carries no tasks for the services on this project. Add one on the workflow
                template.
              </p>
            ) : (
              <ol className="m-0 flex list-none flex-col divide-y divide-border overflow-hidden rounded-control border border-border bg-surface p-0">
                {drawn.map((task, index) => {
                  const yours = isMine(task, meId)
                  const isSelected = task.id === selectedTaskId
                  const owner = ownerName(task.ownerUserId)
                  const answered = task.items.filter((i) => i.answer != null).length
                  const missed = overdue(task.dueAt)

                  return (
                    <li key={task.id} id={taskAnchorId(task.id)} data-testid="ob-project-task">
                      <button
                        type="button"
                        aria-pressed={isSelected}
                        aria-haspopup="dialog"
                        onClick={() => onSelectTask(task.id)}
                        className={cn(
                          'flex w-full flex-wrap items-center gap-2 px-3 py-2 text-left',
                          'hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
                          isSelected && 'bg-primary-soft',
                          /* A task you own gets the indigo edge; nothing else is dimmed. */
                          yours && !settled(task) && 'shadow-[inset_3px_0_0_var(--primary)]',
                        )}
                      >
                        {/* a, b, c — the task's place under its Step. The Steps are
                            numbered, so the level below them takes letters, and the
                            check list inside a task takes i, ii, iii. */}
                        <span
                          aria-hidden="true"
                          className="w-4 shrink-0 text-caption font-semibold tabular-nums text-content-muted"
                        >
                          {letter(index)}.
                        </span>
                        <TaskStatusDot task={task} />
                        <span
                          className={cn(
                            'text-sm',
                            yours ? 'font-semibold' : 'font-medium',
                            settled(task) ? 'text-content-muted' : 'text-content',
                          )}
                        >
                          {task.name}
                        </span>
                        {task.requiresSignoff && (
                          <span className={cn(PILL, 'bg-level-high-soft text-warning-text')}>Sign-off</span>
                        )}
                        {owner == null && (
                          <span className={cn(PILL, 'bg-danger-soft text-danger-text')}>Unassigned</span>
                        )}
                        {escalations?.has(task.id) && (
                          <span className={cn(PILL, 'bg-danger-soft text-danger-text')}>🔔 Escalated</span>
                        )}

                        <span className="ml-auto flex flex-wrap items-center gap-x-2.5 gap-y-0.5 text-caption tabular-nums text-content-muted">
                          {task.items.length > 0 && (
                            <span className={cn(PILL, 'bg-subtle tabular-nums text-content-muted')}>
                              ☑ {answered}/{task.items.length}
                            </span>
                          )}
                          <span>
                            TAT{' '}
                            <b className="font-semibold text-content">{`${task.tatDays} ${task.tatDays === 1 ? 'wd' : 'wds'}`}</b>
                          </span>
                          {task.dueAt && (
                            <>
                              <span aria-hidden="true">/</span>
                              <span>
                                Due{' '}
                                <b className={cn('font-semibold', missed ? 'text-danger-text' : 'text-content')}>
                                  {formatDue(task.dueAt)}
                                  {missed ? ' (missed)' : ''}
                                </b>
                              </span>
                            </>
                          )}
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ol>
            )}

            {/* Said inside the Step it applies to rather than up on the switch:
                which Step the finished work sits in is the part a reader needs. */}
            {hiddenCount > 0 && (
              <p className="m-0 px-1 text-caption text-content-muted">
                {hiddenCount === 1
                  ? '1 finished task is hidden — Show all has it back.'
                  : `${hiddenCount} finished tasks are hidden — Show all has them back.`}
              </p>
            )}
          </div>
        )}
      </div>
    </li>
  )
}

/**
 * The Step's three review figures — verified, rejected, in progress.
 *
 * <h2>Numbers, and the words are on hover</h2>
 *
 * <p>It replaced "1 of 2 tasks done", which said one thing where a Step with a
 * manager gate in it has three to say: what came back approved, what came back
 * refused, and what somebody is still on. Three labelled counts in that space
 * would be longer than the Step's own name, so each is a hue with its figure
 * <em>inside</em> it — the header is scanned, and the fastest thing to compare
 * down a column of seven Steps is a number in a fixed position. The figure sits
 * in the circle rather than beside it because a ring and a digit side by side
 * are two marks the reader has to pair up, and three of those is six.
 *
 * <h2>Never colour alone</h2>
 *
 * <p>Blueprint §12.1. The group is one image whose name spells all three out —
 * "2 verified, 1 rejected, 3 in progress" — so a reader who cannot see the hues
 * gets the same three facts, and each figure carries its own word as a tooltip
 * for anybody who can see them but does not yet know which is which.
 *
 * <h2>All three, including the zeroes</h2>
 *
 * <p>Hiding an empty bucket would move the other two along the row and make
 * comparing the same figure across Steps a matter of reading each one first.
 * "0 rejected" is also the answer somebody scanning for red came for.
 */
function ReviewTally({ counts }: { counts: ReviewCounts }) {
  const shown: readonly { key: keyof ReviewCounts; tone: DotTone; word: string }[] = [
    { key: 'verified', tone: 'green', word: 'verified' },
    { key: 'rejected', tone: 'red', word: 'rejected' },
    { key: 'inProgress', tone: 'blue', word: 'in progress' },
  ]

  return (
    <span
      role="img"
      data-testid="ob-step-review-counts"
      aria-label={shown.map((s) => `${counts[s.key]} ${s.word}`).join(', ')}
      className="inline-flex items-center gap-x-1.5 text-caption tabular-nums text-content-muted"
    >
      {shown.map((s) => (
        <ToneCount
          key={s.key}
          tone={s.tone}
          count={counts[s.key]}
          bucket={s.key}
          label={`${counts[s.key]} ${s.word}`}
        />
      ))}
    </span>
  )
}

function Sep() {
  return (
    <span aria-hidden="true" className="text-border">
      ·
    </span>
  )
}

/** 0 → "a", 25 → "z", 26 → "aa" — a Step never has that many, but a label must not run out. */
function letter(index: number): string {
  let n = index
  let out = ''
  do {
    out = String.fromCharCode(97 + (n % 26)) + out
    n = Math.floor(n / 26) - 1
  } while (n >= 0)
  return out
}

/** `2026-09-18T09:42:02Z` and `2026-09-18` both → "18 Sep". */
function shortDay(value: string): string {
  const parsed = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })
}
