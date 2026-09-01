import { Skeleton } from '@/components/ui/skeleton'

import { useDrillDownStore } from '../../drillDownStore'
import { figureTone, type FigureTone } from './todayCardTones'

/**
 * S-05 tab 1 · one of the seven `TodaySummaryCard`s (PR 7).
 *
 * <h2>Why not `KpiCard` or `WeeklyCard`</h2>
 *
 * Both are one figure with a delta. Every card here is several independent
 * figures side by side — "Today's Work" is four, "Pending Review" is one —
 * and none of them carries a trend. Widening either existing card for this
 * would put Today's-only props on components the Analytics and Weekly tabs
 * also render.
 *
 * <h2>Real `<button>`s, not `<Link>`s</h2>
 *
 * `KpiCard` and `WeeklyCard` are anchors, so a modified click still navigates
 * and the card is openable in a new tab. The plan is explicit that this one
 * is different: "every sub-figure and every role chip is a real `<button>`"
 * — matching the prototype's own markup, `<button class="mfig drv">`. A
 * multi-figure card has several independent targets in one tile; giving each
 * its own destination URL would make "open in new tab" ambiguous about which
 * figure was meant.
 *
 * <h2>The header total, and why it is sometimes the only figure</h2>
 *
 * `total` is required on every card but the prototype's mock array never
 * rendered it separately — for `not-started` and `wip`, one of the sub-figures
 * already repeats it. `pending-review` is the opposite case: it carries no
 * sub-figures at all, so its total *is* the card, and folding it into the
 * figure row rather than a header nobody can reach keeps every card's total
 * clickable without ever showing the same number twice.
 */
export interface TodaySummaryCardFigure {
  key: string
  label: string
  value: number
  /** Server-built `/tickets?…`. Absent means the figure has no expressible list. */
  drillDown?: string | null
}

export interface TodaySummaryCardProps {
  cardKey: string
  label: string
  total: { value: number; drillDown?: string | null }
  figures: TodaySummaryCardFigure[]
}

export function TodaySummaryCard({ cardKey, label, total, figures }: TodaySummaryCardProps) {
  const openPanel = useDrillDownStore((s) => s.open)
  const hasFigures = figures.length > 0
  const shown: TodaySummaryCardFigure[] = hasFigures
    ? figures
    : [{ key: 'total', label: 'Total', value: total.value, drillDown: total.drillDown }]

  return (
    <div
      role="group"
      aria-label={label}
      className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-3"
    >
      <CardHeader cardKey={cardKey} label={label} total={total} clickable={hasFigures} onOpen={openPanel} />
      <div className="flex">
        {shown.map((figure, i) => (
          <Figure
            key={figure.key}
            figure={figure}
            tone={figureTone(cardKey, figure.key)}
            bordered={i > 0}
            onOpen={openPanel}
          />
        ))}
      </div>
    </div>
  )
}

type OpenDrillDown = (drillDown: string, title: string, count?: number | null) => void

function CardHeader({
  cardKey,
  label,
  total,
  clickable,
  onOpen,
}: {
  cardKey: string
  label: string
  total: { value: number; drillDown?: string | null }
  /** False once the total has moved into the figure row instead (see above). */
  clickable: boolean
  onOpen: OpenDrillDown
}) {
  const caption = (
    <span className="text-[11px] font-semibold uppercase tracking-wide text-[color:var(--text-tertiary)]">
      {label}
    </span>
  )

  if (!clickable || !total.drillDown) return caption

  const drillDown = total.drillDown
  return (
    <button
      type="button"
      className="flex items-center justify-between gap-2 -m-1 rounded-control p-1 text-left
                 transition-colors hover:bg-[color:var(--bg-subtle)]
                 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                 focus-visible:outline-[color:var(--primary)]"
      aria-label={`${label} total: ${total.value}. Open the filtered ticket list.`}
      data-card-key={cardKey}
      onClick={() => onOpen(drillDown, `${label} — total`, total.value)}
    >
      {caption}
      <span className="text-xs font-semibold tabular-nums text-[color:var(--text-secondary)]">
        {total.value}
      </span>
    </button>
  )
}

const TONE_TEXT: Record<FigureTone, string> = {
  neutral: 'var(--text-primary)',
  success: 'var(--success-text)',
  warning: 'var(--warning-text)',
  danger: 'var(--danger-text)',
}

function Figure({
  figure,
  tone,
  bordered,
  onOpen,
}: {
  figure: TodaySummaryCardFigure
  tone: FigureTone
  bordered: boolean
  onOpen: OpenDrillDown
}) {
  const body = (
    <>
      <span
        className="text-lg font-semibold tabular-nums leading-none"
        style={{ color: TONE_TEXT[tone] }}
      >
        {figure.value}
      </span>
      <span className="mt-1 text-[10px] font-semibold uppercase tracking-wide text-[color:var(--text-tertiary)]">
        {figure.label}
      </span>
    </>
  )

  const className = `flex-1 min-w-0 flex flex-col items-start text-left ${
    bordered ? 'border-l border-[color:var(--border)] pl-3 ml-3' : ''
  }`

  // A figure with no drill-down is not a button. Rendering one that opens
  // nothing would put a focus stop in the tab order promising an action it
  // cannot perform.
  if (!figure.drillDown) {
    return (
      <div className={className} aria-label={`${figure.label}: ${figure.value}`}>
        {body}
      </div>
    )
  }

  const drillDown = figure.drillDown
  return (
    <button
      type="button"
      className={`${className} rounded-control transition-colors hover:bg-[color:var(--bg-subtle)]
                  focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                  focus-visible:outline-[color:var(--primary)]`}
      aria-label={`${figure.label}: ${figure.value}. Open the filtered ticket list.`}
      onClick={() => onOpen(drillDown, figure.label, figure.value)}
    >
      {body}
    </button>
  )
}

/** Shown while the first request is in flight — same footprint, so nothing reflows on arrival. */
export function TodaySummaryCardSkeleton() {
  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-3">
      <Skeleton className="h-3 w-24" />
      <div className="flex gap-3">
        {Array.from({ length: 3 }, (_, i) => (
          <div key={i} className="flex-1 flex flex-col gap-1">
            <Skeleton className="h-6 w-10" />
            <Skeleton className="h-2 w-14" />
          </div>
        ))}
      </div>
    </div>
  )
}
