import * as React from 'react'
import { ChevronRight } from 'lucide-react'

import { Chip } from '@/components/ui/chip'
import { cn } from '@/lib/utils'

import { ObImplementorPopover } from './ObImplementorPopover'
import {
  DELIVERY_LABEL,
  SEGMENT_LABEL,
  STUCK_AFTER_DAYS,
  deliveryHealth,
  serviceDates,
  stageDots,
  statsByImplementor,
  stripStats,
  type DeliveryHealth,
  type DeliveryStatus,
  type ImplementorStats,
  type SegmentState,
  type StripStats,
} from './moduleStripStats'
import type { TreeService } from './projectTree'
import { formatDay, formatFullDay } from './taskDates'
import { isMineOnly, type ObViewerScope } from './viewerScope'

/**
 * One Module Service — the header row of its accordion on the project page,
 * and the row that answers "how is this service going, for me?"
 *
 * <h2>One row, and it opens onto the timeline</h2>
 *
 * <p>The chevron and the name disclose the service's Steps underneath, as a
 * vertical timeline with every task on it. The figures ride along the row —
 * who is on it, Steps done, TAT and the service's two dates under the name; the
 * three counts, the meter and the percentage, and the delivery status on the
 * right — so "where is everyone" is answered without opening anything.
 *
 * <h2>The schedule under the name, the verdict on the right</h2>
 *
 * <p>Start date, expected end date and TAT are the service's <b>plan</b>, and
 * they sit together because they are read together: a reader checking whether
 * **Delayed** is fair wants the date it is measured against on the same line.
 * The status chip is the plan's one-word verdict and sits at the end of the row
 * where a scanning eye lands last — see {@link DeliveryStatus}.
 *
 * <p>It replaced a chip reading Running / Blocked / Waiting / Complete, which
 * described what the service was <em>doing</em> rather than whether it was
 * being delivered on time. Nothing was lost: completion still wears its own
 * chip beside the name, and blocked and waiting Steps still colour their dots.
 *
 * <h2>A dot per Step</h2>
 *
 * <p>Between the name and the counts, one small dot for every Step with work
 * in it, coloured by that Step's state: green done, indigo running, amber
 * waiting, red blocked, an outline for not started. They are the whole
 * service, not the reader's share of it — an implementor's timeline hides a
 * colleague's Steps, but the dots still show them, because "how far along is
 * SIS" has one answer. Each dot names its Step on hover, and the row of them
 * is one label for a screen reader; see {@link stageDots}.
 *
 * <h2>The row counts the reader, not the project</h2>
 *
 * <p>An implementor gets their own four figures over their own denominator; an
 * admin gets every implementor's, summed, over the service's. Both are honest
 * and they are different numbers, so the row <b>says which</b> — "of your 6"
 * against "of 14". An unlabelled 50% beside an unlabelled 36% on two people's
 * screens is the most misleading thing this page could do.
 *
 * <h2>A name is a link only when it is not already the answer</h2>
 *
 * <p>Where a service has one implementor the row's figures already <em>are</em>
 * that person's, and a popover repeating them teaches a reader the control is
 * not worth pressing. Same in the filtered view, where the only name is the
 * reader's own. So two or more people make every name a control; one person is
 * plain text. See {@link linkNames}.
 *
 * <h2>Why the row is not one big button</h2>
 *
 * <p>A name is a control, and a control inside a control is invalid markup that
 * browsers resolve by dropping one of them. So the disclosure is the chevron
 * and the service name together — an ordinary accordion affordance — and the
 * names and figures are siblings beside it rather than children of it.
 */
export interface ObModuleStripProps {
  node: TreeService
  scope: ObViewerScope
  /** Resolved display name, or null where the directory has no such user. */
  nameOf: (userId: number) => string | null
  isOpen: boolean
  onToggle: () => void
  /** The timeline this row discloses, for `aria-controls`. */
  panelId: string
}

/**
 * How many names the row prints before it folds the rest into `+N more`.
 *
 * <p>Four fits beside the Steps and TAT figures at laptop width. The overflow
 * popover holds every name it did not print, so nothing is lost to the fold.
 */
const NAMES_SHOWN = 4

/** Whether pressing a name should open anything. See the class docblock.
    Not exported — see `initials` in `ObImplementorPopover` for why. */
function linkNames(scope: ObViewerScope, people: readonly ImplementorStats[]): boolean {
  return scope.showsBreakdown && people.length > 1
}

/**
 * The delivery-status chip, as a four-step colour ramp: green, yellow, orange,
 * red.
 *
 * <p>Classes rather than `Chip`'s `variant`, and all four in one table rather
 * than three variants and one exception. Only three of the four hues are levels
 * — the ramp needs a step between amber and red that the priority palette has
 * no reason to own, so `--status-delayed` was added beside it. Reading the ramp
 * off four sibling class pairs is what makes it checkable at a glance; splitting
 * it across two mechanisms would hide the one that is different.
 *
 * <p>The label always rides with the colour — {@link DELIVERY_LABEL}, never
 * colour alone (blueprint §12.1).
 */
const DELIVERY_CHIP: Record<DeliveryStatus, string> = {
  ON_TIME: 'bg-level-low-soft text-success-text',
  AT_RISK: 'bg-level-high-soft text-warning-text',
  DELAYED: 'bg-status-delayed-soft text-status-delayed-text',
  STUCK: 'bg-level-critical-soft text-danger-text',
}

/** A dot's fill per state. Never colour alone — the dot names its state on hover. */
const DOT: Record<SegmentState, string> = {
  complete: 'border-ribbon-done bg-ribbon-done',
  current: 'border-primary bg-primary',
  waiting: 'border-ribbon-waiting bg-ribbon-waiting',
  blocked: 'border-ribbon-blocked bg-ribbon-blocked',
  pending: 'border-ribbon-pending bg-surface',
}

export function ObModuleStrip({ node, scope, nameOf, isOpen, onToggle, panelId }: ObModuleStripProps) {
  const { service } = node
  const mineOnly = isMineOnly(scope)

  const stats = React.useMemo(() => stripStats(node.tasks), [node.tasks])
  /*
    Computed whatever the role, because the *names* are not the breakdown — they
    are who a reader is looking at, and Sales needs them as much as an admin.
    `scope.showsBreakdown` decides only whether the figures behind them open.
  */
  const people = React.useMemo(() => statsByImplementor(node.tasks, nameOf), [node.tasks, nameOf])
  const dots = React.useMemo(() => stageDots(node), [node])
  /*
    The whole service, never the reader's share of it — `allTasks`, the same
    list `totalTatDays` is summed from. See the `TreeService.allTasks` field.
  */
  const dates = React.useMemo(() => serviceDates(node.allTasks), [node.allTasks])
  const health = React.useMemo(() => deliveryHealth(node.allTasks), [node.allTasks])

  const myName = scope.meId == null ? null : nameOf(scope.meId)

  return (
    <div
      data-testid="ob-module-strip"
      className={cn(
        'flex flex-wrap items-center gap-x-5 gap-y-2 px-4 py-3',
        isOpen && 'bg-primary-soft',
      )}
    >
      <div className="flex min-w-[16rem] flex-1 flex-col gap-0.5">
        <button
          type="button"
          aria-expanded={isOpen}
          aria-controls={panelId}
          onClick={onToggle}
          data-testid="ob-tree-service"
          className={cn(
            '-ml-1 inline-flex min-w-0 items-center gap-2 self-start rounded-control px-1 py-0.5 text-left',
            'hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
            isOpen && 'hover:bg-surface',
          )}
        >
          <ChevronRight
            aria-hidden="true"
            className={cn(
              'size-4 shrink-0 text-content-muted transition-transform motion-reduce:transition-none',
              isOpen && 'rotate-90',
            )}
          />
          <span className="truncate text-h3 text-content">{service.serviceName}</span>
          {/*
            No prerequisite chip here. The gate is the client's, not the
            service's — the header already wears it once for the project.
          */}
          {service.isComplete ? <Chip variant="success">Complete</Chip> : null}
          {/* Only worth saying where the page shows everybody's work. In the
              filtered view every row is yours, and the pill would be on all of them. */}
          {!mineOnly && node.hasMine ? <Chip variant="info">Yours</Chip> : null}
        </button>

        <p className="m-0 flex flex-wrap items-baseline gap-x-1.5 pl-6 text-caption text-content-muted">
          <Names
            people={people}
            scope={scope}
            mineOnly={mineOnly}
            myName={myName}
            serviceName={service.serviceName}
          />
          {(mineOnly || people.length > 0) && <Sep />}
          <span className="tabular-nums">
            {node.stagesComplete}/{node.stageCount} steps
          </span>
          <Sep />
          {/*
            The service's whole TAT, whoever is reading. The counts to the right
            shrink to the reader's share in the filtered view; this does not,
            because it is the schedule rather than a share of it — and because
            the rows are meant to add up to the header's Total TAT, which they
            only do unscoped. `TreeService.totalTatDays` carries the argument.
          */}
          <span
            data-testid="ob-strip-tat"
            className="tabular-nums"
            title={`${node.totalTatDays} working days of TAT across every task in ${service.serviceName}`}
          >
            TAT {node.totalTatDays}d
          </span>
          <Sep />
          {/*
            The service's own two dates, unscoped for the same reason the TAT
            beside them is: a schedule is the service's, not a share of it.
            Neither is re-derived here — the start is the earliest task start
            the server sent and the expected end the latest task due date, and
            `dueAt` has already been walked through the working calendar.
          */}
          <span
            data-testid="ob-strip-start"
            className="tabular-nums"
            title={
              dates.startedAt
                ? `${service.serviceName} started on ${formatFullDay(dates.startedAt)} — the earliest task start on it`
                : `Nothing on ${service.serviceName} has started yet`
            }
          >
            Start {formatDay(dates.startedAt)}
          </span>
          <Sep />
          <span
            data-testid="ob-strip-end"
            className="tabular-nums"
            title={
              dates.expectedEndAt
                ? `${service.serviceName} is expected to finish by ${formatFullDay(dates.expectedEndAt)} — the latest task due date on it`
                : `No task on ${service.serviceName} carries a due date, so there is no expected end`
            }
          >
            Expected end {formatDay(dates.expectedEndAt)}
          </span>
        </p>
      </div>

      {dots.length > 0 && (
        <span
          role="img"
          data-testid="ob-strip-dots"
          aria-label={dots.map((d) => `${d.name} ${SEGMENT_LABEL[d.state]}`).join(', ')}
          className="inline-flex items-center gap-1.5"
        >
          {dots.map((d) => (
            <span
              key={d.stageKey}
              data-state={d.state}
              title={`${d.sequence >= 9999 ? '' : `${d.sequence}. `}${d.name} — ${SEGMENT_LABEL[d.state]}`}
              className={cn('size-[11px] rounded-chip border-[1.5px]', DOT[d.state])}
            />
          ))}
        </span>
      )}

      {stats.total === 0 ? (
        <p className="m-0 text-caption text-content-muted">
          {mineOnly ? 'No tasks assigned to you in this service.' : 'No tasks scheduled yet.'}
        </p>
      ) : (
        <>
          <p
            data-testid="ob-strip-counts"
            className="m-0 flex flex-wrap items-baseline gap-x-3 gap-y-0.5"
          >
            <Count label="pending" value={stats.pending} />
            <Count label="partial" value={stats.partial} tone="warning" />
            <Count label="completed" value={stats.completed} tone="success" />
          </p>

          <p className="m-0 flex items-center gap-2 text-caption text-content-muted">
            <Meter stats={stats} />
            <span className="text-sm font-semibold tabular-nums text-content">
              {stats.percent}%
            </span>
            <span>{mineOnly ? `of your ${stats.total}` : `of ${stats.total}`}</span>
          </p>
        </>
      )}

      <Chip
        data-testid="ob-strip-status"
        className={DELIVERY_CHIP[health.status]}
        title={statusHint(health, service.serviceName)}
      >
        {DELIVERY_LABEL[health.status]}
      </Chip>
    </div>
  )
}

/**
 * Why the chip says what it says, on hover.
 *
 * <p>A four-word ramp is only as trustworthy as the reason behind it, and
 * "Delayed" with no reason is a thing a reader either believes or argues with.
 * This names the task that decided it and by how much, which is checkable
 * against the expected end date printed on the same row.
 */
function statusHint(health: DeliveryHealth, serviceName: string): string {
  const days = (n: number) => `${n} day${n === 1 ? '' : 's'}`
  switch (health.status) {
    case 'STUCK':
      return `${health.worstTask} is ${days(health.lateByDays)} past its due date — more than ${STUCK_AFTER_DAYS}, so ${serviceName} reads as stuck rather than merely late.`
    case 'DELAYED':
      return `${health.worstTask} is ${days(health.lateByDays)} past its due date. Past ${STUCK_AFTER_DAYS} this becomes Stuck.`
    case 'AT_RISK':
      return `Nothing on ${serviceName} is overdue yet, but ${health.worstTask} has used most of its TAT.`
    default:
      return `Nothing on ${serviceName} is overdue or close to running out of TAT.`
  }
}

/**
 * Who is on this service, inline.
 *
 * <p>In the filtered view that is the reader and nobody else — deliberately not
 * folded from the tasks, because a task the reader only <em>backs up</em> is
 * owned by somebody else, and grouping by owner would print a colleague's name
 * on a row counting the reader's own work.
 */
function Names({
  people,
  scope,
  mineOnly,
  myName,
  serviceName,
}: {
  people: readonly ImplementorStats[]
  scope: ObViewerScope
  mineOnly: boolean
  myName: string | null
  serviceName: string
}) {
  if (mineOnly) {
    return <span className="font-semibold text-content">{myName ?? 'You'}</span>
  }

  if (people.length === 0) return null

  const linked = linkNames(scope, people)
  const shown = people.slice(0, NAMES_SHOWN)
  const rest = people.slice(NAMES_SHOWN)

  return (
    <span
      data-testid="ob-strip-names"
      className="inline-flex min-w-0 flex-wrap items-baseline gap-x-1 gap-y-0.5"
    >
      {shown.map((person, index) => (
        <React.Fragment key={person.userId ?? 'unassigned'}>
          {linked ? (
            <ObImplementorPopover
              label={person.name}
              serviceName={serviceName}
              people={[person]}
              showFigures
              className={person.userId == null ? 'text-danger-text' : undefined}
            />
          ) : (
            <span
              className={cn(
                'font-semibold',
                person.userId == null ? 'text-danger-text' : 'text-content',
              )}
            >
              {person.name}
            </span>
          )}
          {index < shown.length - 1 || rest.length > 0 ? (
            <span aria-hidden="true" className="text-content-muted">
              ,
            </span>
          ) : null}
        </React.Fragment>
      ))}

      {/* Always a control, whatever the role: without it the names it folded
          would have nowhere to be read. What it shows behind them is what
          `showFigures` decides. */}
      {rest.length > 0 && (
        <ObImplementorPopover
          label={`+${rest.length} more`}
          serviceName={serviceName}
          people={rest}
          showFigures={scope.showsBreakdown}
          className="font-medium text-content-muted"
        />
      )}
    </span>
  )
}

/**
 * One figure and its word.
 *
 * <p>Number first, in the DOM as well as on the screen. It was a `dl` with the
 * label flipped into place by `order`, which reads back to a screen reader as
 * "pending 2" — the right words in the wrong order, and a definition list is
 * the wrong structure for three figures about one thing anyway.
 */
function Count({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone?: 'warning' | 'success'
}) {
  return (
    <span className="whitespace-nowrap text-caption text-content-muted">
      <b
        className={cn(
          'text-sm font-semibold tabular-nums',
          tone === 'warning' && 'text-warning-text',
          tone === 'success' && 'text-success-text',
          !tone && 'text-content',
        )}
      >
        {value}
      </b>{' '}
      {label}
    </span>
  )
}

/**
 * Completed solid, partial beside it in amber.
 *
 * <p>Partial is never folded into the percentage — see {@link StripStats} — so
 * the bar is where a reader sees that a third of the rest is under way. The
 * figures are all in the text beside it, which is why this is decorative.
 */
function Meter({ stats }: { stats: StripStats }) {
  const share = (part: number) => (stats.total === 0 ? 0 : (part / stats.total) * 100)
  return (
    <span aria-hidden="true" className="flex h-1.5 w-36 overflow-hidden rounded-chip bg-subtle">
      <span className="block h-full bg-success" style={{ width: `${share(stats.completed)}%` }} />
      <span className="block h-full bg-warning" style={{ width: `${share(stats.partial)}%` }} />
    </span>
  )
}

/** The faint interpunct between facts. Hidden from the accessibility tree. */
function Sep() {
  return (
    <span aria-hidden="true" className="text-border">
      ·
    </span>
  )
}
