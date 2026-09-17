import * as PopoverPrimitive from '@radix-ui/react-popover'

import { cn } from '@/lib/utils'

import type { ImplementorStats } from './moduleStripStats'

/**
 * One implementor's figures on one Module Service, opened from their name.
 *
 * <h2>Why a popover and not the rows it replaced</h2>
 *
 * <p>These four figures were a `By implementor (3)` accordion under the Module
 * strip — two permanent rows per service (the head line and the stat line above
 * it) before a reader reached the first Step. On a project boarded through
 * three services that is nine rows of chrome, two thirds of it carrying numbers
 * nobody had asked for yet.
 *
 * <p>The names were already being printed on the strip. Making each one the
 * control costs no rows at all, and asks the reader to point at the person they
 * are actually wondering about rather than unfolding all of them.
 *
 * <h2>Radix, imported here rather than promoted to `components/ui`</h2>
 *
 * <p>`@radix-ui/react-popover` is already a dependency — it brings the dismiss,
 * focus-return and escape handling that a hand-rolled anchored `div` gets wrong
 * on the second try. There is no `components/ui/popover.tsx`, and that
 * directory is <b>Stream C's</b>: adding a shared primitive to it needs their
 * sign-off (CLAUDE.md, code ownership). So the primitive is used directly in
 * this feature. If a second screen wants one, promoting it is the conversation
 * to have then, with a caller to design it against.
 */
export interface ObImplementorPopoverProps {
  /** The trigger's text — a person's name, or `+3 more`. */
  label: string
  /** Which Module Service these figures are for. The popover says so, because
      the same person carries different numbers on each of a project's services. */
  serviceName: string
  /** One person for the detail card; several for the overflow list. */
  people: readonly ImplementorStats[]
  /**
   * Draw each person's counts.
   *
   * <p>False for Sales and Viewer, who get the names but not the workload
   * split — that is a management reading with a screen of its own in the
   * dashboard's implementor workload grid.
   */
  showFigures: boolean
  className?: string
}

export function ObImplementorPopover({
  label,
  serviceName,
  people,
  showFigures,
  className,
}: ObImplementorPopoverProps) {
  const single = people.length === 1 && showFigures ? people[0] : null

  return (
    <PopoverPrimitive.Root>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          data-testid="ob-implementor-trigger"
          className={cn(
            'rounded-[3px] font-semibold text-content underline decoration-dotted decoration-content-muted underline-offset-[3px]',
            'hover:text-primary hover:decoration-solid hover:decoration-primary',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
            className,
          )}
        >
          {label}
        </button>
      </PopoverPrimitive.Trigger>

      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          side="bottom"
          align="start"
          sideOffset={6}
          collisionPadding={12}
          aria-label={single ? `${single.name} on ${serviceName}` : `Implementors on ${serviceName}`}
          data-testid="ob-implementor-popover"
          className={cn(
            'z-50 w-[17rem] max-w-[calc(100vw-1.5rem)] rounded-card border border-border bg-surface p-3 shadow-modal',
            'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'motion-reduce:animate-none',
          )}
        >
          <PopoverPrimitive.Arrow className="fill-surface" />

          {single ? <Detail person={single} serviceName={serviceName} /> : (
            <List people={people} serviceName={serviceName} showFigures={showFigures} />
          )}
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

/** One person: exactly what the accordion row held, with room to breathe. */
function Detail({ person, serviceName }: { person: ImplementorStats; serviceName: string }) {
  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-2 border-b border-border pb-2">
        <Avatar name={person.name} unassigned={person.userId == null} />
        <span className="min-w-0">
          <span
            className={cn(
              'block truncate text-sm font-semibold',
              person.userId == null ? 'text-danger-text' : 'text-content',
            )}
          >
            {person.name}
          </span>
          <span className="block truncate text-caption text-content-muted">{serviceName}</span>
        </span>
      </div>

      <dl className="m-0 flex flex-col gap-1 py-2">
        <Row label="Pending" value={person.pending} />
        <Row label="Partially completed" value={person.partial} tone="warning" />
        <Row label="Completed" value={person.completed} tone="success" />
      </dl>

      <p className="m-0 flex items-center gap-2 border-t border-border pt-2 text-caption text-content-muted">
        <Meter person={person} />
        <span className="text-sm font-semibold tabular-nums text-content">{person.percent}%</span>
        <span>{`of ${person.total} ${person.total === 1 ? 'task' : 'tasks'}`}</span>
      </p>
    </div>
  )
}

/**
 * Several people — what `+3 more` opens, and the whole of the old accordion.
 *
 * <p>Figures ride on the row here rather than getting a card each: this is the
 * list a reader opened to scan, and four stacked cards would be a scroll.
 */
function List({
  people,
  serviceName,
  showFigures,
}: {
  people: readonly ImplementorStats[]
  serviceName: string
  showFigures: boolean
}) {
  return (
    <div className="flex flex-col">
      <p className="m-0 truncate border-b border-border pb-2 text-caption font-semibold text-content">
        {serviceName}
      </p>
      <dl className="m-0 flex flex-col gap-1.5 pt-2">
        {people.map((person) => (
          <div key={person.userId ?? 'unassigned'} data-testid="ob-implementor-popover-row">
            <dt
              className={cn(
                'truncate text-caption font-semibold',
                person.userId == null ? 'text-danger-text' : 'text-content',
              )}
            >
              {person.name}
            </dt>
            {showFigures && (
              <dd className="m-0 text-caption tabular-nums text-content-muted">
                <b className="font-semibold text-content">{person.pending}</b> pending{' · '}
                <b className="font-semibold text-warning-text">{person.partial}</b> partial{' · '}
                <b className="font-semibold text-success-text">{person.completed}</b> completed{' · '}
                <b className="font-semibold text-content">{person.percent}%</b>
              </dd>
            )}
          </div>
        ))}
      </dl>
    </div>
  )
}

function Row({ label, value, tone }: { label: string; value: number; tone?: 'warning' | 'success' }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-caption text-content-muted">{label}</dt>
      <dd
        className={cn(
          'm-0 text-sm font-semibold tabular-nums',
          tone === 'warning' && 'text-warning-text',
          tone === 'success' && 'text-success-text',
          !tone && 'text-content',
        )}
      >
        {value}
      </dd>
    </div>
  )
}

/** Completed solid, partial beside it. Decorative — the figures are above it. */
function Meter({ person }: { person: ImplementorStats }) {
  const share = (part: number) => (person.total === 0 ? 0 : (part / person.total) * 100)
  return (
    <span aria-hidden="true" className="flex h-[7px] w-[5.5rem] overflow-hidden rounded-chip bg-subtle">
      <span className="block h-full bg-success" style={{ width: `${share(person.completed)}%` }} />
      <span className="block h-full bg-warning" style={{ width: `${share(person.partial)}%` }} />
    </span>
  )
}

function Avatar({ name, unassigned }: { name: string; unassigned: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'grid size-7 shrink-0 place-items-center rounded-chip text-[10px] font-bold',
        unassigned ? 'bg-level-critical-soft text-danger-text' : 'bg-primary-soft text-primary',
      )}
    >
      {unassigned ? '?' : initials(name)}
    </span>
  )
}

/** "Priya Nair" to "PN". One letter where there is only one word.
    Deliberately not exported: a file that exports both components and plain
    functions loses fast refresh — `taskDates.ts` carries the same note. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}
