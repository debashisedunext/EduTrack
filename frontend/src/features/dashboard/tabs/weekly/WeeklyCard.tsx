import { Link } from 'react-router-dom'

import { Skeleton } from '@/components/ui/skeleton'

import { useDrillDownStore } from '../../drillDownStore'
import { HIGHER_IS_BETTER, formatValue, type WeeklyCardUnit } from './weeklyCardFormat'

/**
 * S-05 tab 3 · one of the four weekly figures.
 *
 * <h2>Why not `KpiCard`</h2>
 *
 * `KpiCard` is a count with a sparkline. These four are not all counts —
 * average progress is a percentage and average delay is days — and two of them
 * carry a second figure beside the first. Widening `KpiCard` to cover both
 * would put four optional props on the card the whole Analytics tab renders,
 * for the benefit of one tab. Same call the plan makes for `TodaySummaryCard`.
 *
 * <h2>`deltaPct` null renders nothing, and that is the point</h2>
 *
 * The server sends null when the prior week has no data, rather than zero: a
 * first week has nothing to improve on, and 0% claims it held steady. So null
 * has to render as an absence here too — "no prior week" — and never as 0%.
 *
 * <h2>Whether a rise is good is decided here, not on the wire</h2>
 *
 * A rise in average progress is good; a rise in delayed tickets is not. The
 * contract carries no sentiment field and should not: it is a purely visual
 * concern, and adding one would make every future consumer inherit this
 * screen's opinion. So the direction is coloured from a small map keyed by
 * card, and anything unrecognised stays neutral rather than guessing.
 */

export interface WeeklyCardProps {
  cardKey: string
  label: string
  value: number
  unit: WeeklyCardUnit | string
  secondaryValue?: number | null
  secondaryLabel?: string | null
  /** Null when the prior week holds no data. Never rendered as 0%. */
  deltaPct?: number | null
  /** Server-built `/tickets?…`. Absent means the figure has no expressible list. */
  drillDown?: string | null
}

export function WeeklyCard({
  cardKey,
  label,
  value,
  unit,
  secondaryValue,
  secondaryLabel,
  deltaPct,
  drillDown,
}: WeeklyCardProps) {
  const openPanel = useDrillDownStore((s) => s.open)
  const shown = formatValue(value, unit)

  const body = (
    <>
      <span className="text-sm text-[color:var(--text-secondary)]">{label}</span>
      <span className="text-2xl font-semibold tabular-nums text-[color:var(--text-primary)]">
        {shown}
      </span>
      {secondaryValue != null && secondaryLabel ? (
        <span className="text-xs text-[color:var(--text-secondary)] tabular-nums">
          {formatValue(secondaryValue, 'COUNT')} {secondaryLabel}
        </span>
      ) : null}
      <DeltaChip cardKey={cardKey} deltaPct={deltaPct} />
    </>
  )

  const className =
    'rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 ' +
    'flex flex-col gap-1 text-left transition-shadow hover:shadow-sm ' +
    'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 ' +
    'focus-visible:outline-[color:var(--primary)]'

  // A figure with no drill-down is not a link. Rendering an anchor to nowhere
  // would put it in the tab order promising something it cannot do.
  if (!drillDown) {
    return (
      <div className={className} aria-label={`${label}: ${shown}`}>
        {body}
      </div>
    )
  }

  return (
    <Link
      to={drillDown}
      aria-label={`${label}: ${shown}`}
      /**
       * Unmodified primary click opens the S-06 panel; every other click still
       * navigates. Same contract as `KpiCard` — ctrl/cmd/shift/middle-click are
       * how people open a ticket list in a second tab, and intercepting them
       * would take away a browser affordance nobody expects an app to remove.
       */
      onClick={(event) => {
        if (event.defaultPrevented) return
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        if (event.button !== 0) return
        event.preventDefault()
        openPanel(drillDown, label, unit === 'COUNT' ? value : null)
      }}
      className={className}
    >
      {body}
    </Link>
  )
}

function DeltaChip({ cardKey, deltaPct }: { cardKey: string; deltaPct?: number | null }) {
  if (deltaPct == null) {
    return (
      <span className="text-xs text-[color:var(--text-tertiary)]">No prior week to compare</span>
    )
  }

  const rounded = Math.round(deltaPct * 10) / 10
  if (rounded === 0) {
    return <span className="text-xs text-[color:var(--text-secondary)]">Unchanged on last week</span>
  }

  const rose = rounded > 0
  const better: boolean | undefined = HIGHER_IS_BETTER[cardKey]
  const tone =
    better === undefined
      ? 'var(--text-secondary)'
      : rose === better
        ? 'var(--success)'
        : 'var(--danger)'

  return (
    <span className="text-xs tabular-nums" style={{ color: `color-mix(in srgb, ${tone} 100%, transparent)` }}>
      {/* The arrow is decorative; the sign is already in the number, and a
          screen reader announcing "up arrow up 12 percent" reads as a stutter. */}
      <span aria-hidden="true">{rose ? '▲' : '▼'}</span> {rose ? '+' : ''}
      {rounded}% on last week
    </span>
  )
}

export function WeeklyCardSkeleton() {
  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-2">
      <Skeleton className="h-4 w-24" />
      <Skeleton className="h-7 w-16" />
      <Skeleton className="h-3 w-32" />
    </div>
  )
}
