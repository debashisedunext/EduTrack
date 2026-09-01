import { Skeleton } from '@/components/ui/skeleton'

import { useDrillDownStore } from '../../drillDownStore'

/**
 * S-05 tab 2 · open work per assignee, as a horizontal stacked bar.
 *
 * <h2>This is open state now, not what anybody finished in the range</h2>
 *
 * The cards above this chart answer "what happened in the window". This
 * answers "what is on people's plates today", and the two are easy to confuse
 * because they sit on one screen under one date filter. The server's own
 * contract note says so; it is repeated here because the component is where
 * somebody would otherwise "fix" the inconsistency by passing the range
 * through.
 *
 * <h2>Segments partition the person's open total, and overdue wins</h2>
 *
 * A stacked bar makes an arithmetic claim: the three widths sum to the number
 * printed at the end of the row. So an overdue in-progress ticket is counted
 * once, under Overdue — the server subtracts it back out of the other two.
 * Three independent counts would each look plausible and the bar would
 * overstate every person in proportion to how late they are running.
 *
 * <h2>Widths scale to the busiest row, not to each row's own total</h2>
 *
 * The prototype does this and it is the point of the chart: rows are meant to
 * be comparable with each other. Normalising each row to 100% would draw
 * somebody holding two tickets the same width as somebody holding fourteen.
 *
 * <h2>Every segment is a real button</h2>
 *
 * These are HTML elements rather than SVG, so each segment genuinely is a
 * `<button>` — reachable by tab, operable by Enter and Space for free, with a
 * visible focus ring. A zero-value segment renders nothing at all rather than
 * a zero-width control: `ChartLegend`'s rule, that a control which cannot be
 * pressed still takes a tab stop and still announces itself.
 */

export interface AssigneeFigure {
  value: number
  drillDown?: string | null
}

export interface AssigneeRow {
  userId: number
  displayName: string
  inProgress: AssigneeFigure
  overdue: AssigneeFigure
  notStarted: AssigneeFigure
}

/**
 * Segment order matches the contract's field order and the prototype's
 * drawing order: in progress, then overdue, then not started.
 *
 * Colours are tokens only. The prototype's `#9CA3AF` for "not started" is a
 * raw hex that `tokens.css` does not define, so `--chart-2` stands in — the
 * same substitution `ModuleOpenBar` makes for the same segment, which also
 * keeps the two three-segment bars on this dashboard reading alike.
 */
const SEGMENTS = [
  { key: 'inProgress' as const, label: 'In progress', colour: 'var(--chart-1)' },
  { key: 'overdue' as const, label: 'Overdue', colour: 'var(--danger)' },
  { key: 'notStarted' as const, label: 'Not started', colour: 'var(--chart-2)' },
]

export function TopAssigneesBar({ assignees }: { assignees: AssigneeRow[] }) {
  const openPanel = useDrillDownStore((s) => s.open)

  if (assignees.length === 0) {
    return (
      <p className="text-sm text-[color:var(--text-secondary)]">
        Nobody is holding open tickets in this scope.
      </p>
    )
  }

  const totals = assignees.map((a) => a.inProgress.value + a.overdue.value + a.notStarted.value)
  // Guarded against every row being zero, which would divide by nothing and
  // render NaN% widths rather than an empty track.
  const widest = Math.max(...totals, 1)

  return (
    <div>
      <ul className="flex flex-col gap-[7px]">
        {assignees.map((row, index) => {
          const total = totals[index]
          return (
            <li key={row.userId} className="flex items-center gap-2.5">
              <span
                className="w-24 shrink-0 truncate text-xs text-[color:var(--text-secondary)]"
                title={row.displayName}
              >
                {row.displayName}
              </span>

              <div className="flex h-4 flex-1 gap-0.5">
                {SEGMENTS.map((segment) => {
                  const figure = row[segment.key]
                  if (figure.value <= 0) {
                    return null
                  }

                  const label = `${row.displayName} — ${segment.label}`
                  const style = {
                    width: `${(figure.value / widest) * 100}%`,
                    backgroundColor: segment.colour,
                  }

                  // No drill-down means no control, per the codebase rule.
                  if (!figure.drillDown) {
                    return (
                      <div
                        key={segment.key}
                        style={style}
                        role="img"
                        aria-label={`${label}: ${figure.value}`}
                      />
                    )
                  }

                  return (
                    <button
                      key={segment.key}
                      type="button"
                      style={style}
                      className="rounded-[2px] transition-opacity hover:opacity-80 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
                      aria-label={`${label}: ${figure.value}. Open the filtered ticket list.`}
                      onClick={() => openPanel(figure.drillDown as string, label, figure.value)}
                    />
                  )
                })}
              </div>

              <span className="w-6 shrink-0 text-right text-xs font-semibold tabular-nums text-[color:var(--text-primary)]">
                {total}
              </span>
            </li>
          )
        })}
      </ul>

      {/* Static key. The clickable route to each state is the segment itself,
          which is a real button here — unlike the SVG charts on the Analytics
          tab, where the legend has to carry that job. */}
      <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-[color:var(--text-secondary)]">
        {SEGMENTS.map((segment) => (
          <li key={segment.key} className="flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className="inline-block h-2.5 w-2.5 shrink-0 rounded-sm"
              style={{ backgroundColor: segment.colour }}
            />
            {segment.label}
          </li>
        ))}
      </ul>
    </div>
  )
}

export function TopAssigneesBarSkeleton() {
  return (
    <div className="flex flex-col gap-[7px]">
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex items-center gap-2.5">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-4 flex-1" />
          <Skeleton className="h-3 w-6" />
        </div>
      ))}
    </div>
  )
}
