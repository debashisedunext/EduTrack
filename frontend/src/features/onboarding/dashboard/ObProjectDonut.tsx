import { useId, useState } from 'react'
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'

import type { ObProjectBoardRow } from '@/api/generated/model'

import { byLatenessDescending, lateLabel, type Slice } from './obProjectBoard'

export interface ObProjectDonutProps {
  title: string
  /** One line under the title, saying what the slices are of. */
  caption: string
  slices: Slice[]
  /** What the number in the hole counts — "ongoing", "projects". */
  centreLabel: string
  /** Names the legend's rows for a screen reader — "Salesperson", "Implementor", "Status". */
  entryNoun: string
  /** Opens the matching projects. A slice with nowhere to go is drawn as plain text, never a dead button. */
  onSelect?: (slice: Slice) => void
}

/**
 * One of OB-02's three donuts, with the legend that makes it usable.
 *
 * <h2>The drawing is decoration; the legend is the control</h2>
 *
 * Recharts renders arcs as `<path>` elements: not focusable, not announced,
 * unreachable without a mouse. `ChartLegend` one module over solved this by
 * putting the interaction in a list of real buttons and hiding the SVG from
 * assistive technology, and this does the same — the chart is
 * `aria-hidden`, every slice has a legend row that is a `<button>`, and the
 * table underneath carries every figure for anyone who reads neither.
 *
 * <h2>Hovering a slice lists the projects behind it</h2>
 *
 * Asked for by name on the salesperson donut ("on hover it should display the
 * list of sales person"), and given to all three because the question is the
 * same each time: a share is not actionable until you know which clients it is
 * made of. The panel is driven by React state rather than Recharts' `Tooltip`,
 * so hovering the arc and focusing the legend row show the identical thing —
 * a tooltip that only a mouse can summon would put the list out of reach of
 * exactly the readers the legend exists for.
 *
 * <h2>A donut, not a pie</h2>
 *
 * The hole is not styling: a pie asks the reader to compare angles at the
 * centre, which is the comparison people are worst at; a donut makes it arc
 * length. Both are weaker than a bar for close values, which is why every
 * figure is also on the legend and in the table — the shape carries the
 * gestalt, the numbers carry the detail.
 */
export function ObProjectDonut({
  title,
  caption,
  slices,
  centreLabel,
  entryNoun,
  onSelect,
}: ObProjectDonutProps) {
  const headingId = useId()
  const [active, setActive] = useState<string | null>(null)

  const total = slices.reduce((sum, slice) => sum + slice.rows.length, 0)
  const activeSlice = slices.find((slice) => slice.key === active) ?? null
  const share = (slice: Slice) => (total === 0 ? 0 : Math.round((slice.rows.length / total) * 100))

  return (
    <section
      aria-labelledby={headingId}
      className="flex min-w-0 flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      <div>
        <h3 id={headingId} className="text-sm font-semibold text-content">
          {title}
        </h3>
        <p className="text-xs text-content-muted">{caption}</p>
      </div>

      {total === 0 ? (
        <p className="py-8 text-center text-xs text-content-muted">
          No running projects to chart yet.
        </p>
      ) : (
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <div className="relative h-[168px] w-[168px] shrink-0 self-center" aria-hidden="true">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={slices}
                  dataKey={(slice: Slice) => slice.rows.length}
                  nameKey="label"
                  innerRadius="62%"
                  outerRadius="92%"
                  paddingAngle={1}
                  /*
                    Off, like every other chart in this codebase: an animation
                    that replays on each refetch is motion nobody asked for, and
                    prefers-reduced-motion cannot reach inside an animation
                    Recharts drives in JavaScript.
                  */
                  isAnimationActive={false}
                  onMouseEnter={(_: unknown, index: number) => setActive(slices[index]?.key ?? null)}
                  onMouseLeave={() => setActive(null)}
                  onClick={(_: unknown, index: number) => {
                    const slice = slices[index]
                    if (slice) onSelect?.(slice)
                  }}
                >
                  {slices.map((slice) => (
                    <Cell
                      key={slice.key}
                      fill={slice.colour}
                      stroke="var(--bg-surface)"
                      strokeWidth={2}
                      opacity={active === null || active === slice.key ? 1 : 0.35}
                      style={{ cursor: onSelect ? 'pointer' : 'default' }}
                    />
                  ))}
                </Pie>
              </PieChart>
            </ResponsiveContainer>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-[28px] font-[650] leading-none text-content">
                {activeSlice ? activeSlice.rows.length : total}
              </span>
              <span className="mt-1 text-[10px] uppercase tracking-[.06em] text-content-muted">
                {activeSlice ? activeSlice.label : centreLabel}
              </span>
            </div>
          </div>

          <ul aria-label={`${entryNoun} shares`} className="flex min-w-0 flex-1 flex-col gap-0.5">
            {slices.map((slice) => (
              <li key={slice.key}>
                <LegendRow
                  slice={slice}
                  share={share(slice)}
                  total={total}
                  entryNoun={entryNoun}
                  isActive={active === slice.key}
                  onActivate={() => setActive(slice.key)}
                  onDeactivate={() => setActive(null)}
                  onSelect={onSelect}
                />
              </li>
            ))}
          </ul>
        </div>
      )}

      {/*
        Hover and focus both land here, so the mouse and the keyboard see the
        same list. Rendered in place rather than as a floating tooltip: this
        panel is up to eight client names long, and a floating layer that size
        covers the chart it is describing.
      */}
      {activeSlice && (
        <ProjectPeek slice={activeSlice} />
      )}

      <details className="text-xs">
        <summary className="cursor-pointer text-content-muted">Show as a table</summary>
        <table className="mt-2 w-full text-left">
          <caption className="sr-only">{title}, as a table</caption>
          <thead className="text-content-muted">
            <tr>
              <th scope="col" className="py-1 font-medium">
                {entryNoun}
              </th>
              <th scope="col" className="py-1 text-right font-medium">
                Projects
              </th>
              <th scope="col" className="py-1 text-right font-medium">
                Share
              </th>
            </tr>
          </thead>
          <tbody>
            {slices.map((slice) => (
              <tr key={slice.key} className="border-t border-border">
                <td className="py-1">{slice.label}</td>
                <td className="py-1 text-right tabular-nums">{slice.rows.length}</td>
                <td className="py-1 text-right tabular-nums">{share(slice)}%</td>
              </tr>
            ))}
            <tr className="border-t border-border font-medium">
              <td className="py-1">Total</td>
              <td className="py-1 text-right tabular-nums">{total}</td>
              <td className="py-1 text-right tabular-nums">100%</td>
            </tr>
          </tbody>
        </table>
      </details>
    </section>
  )
}

/**
 * One legend row — a real button, so it takes a tab stop, announces its role
 * and carries the figure in its accessible name.
 *
 * A row with no `onSelect` renders as text rather than a disabled button:
 * `ChartLegend`'s rule, and for its reason — a control that cannot be pressed
 * still takes a tab stop and still announces itself as a control, which is
 * worse than a label that was never one.
 */
function LegendRow({
  slice,
  share,
  total,
  entryNoun,
  isActive,
  onActivate,
  onDeactivate,
  onSelect,
}: {
  slice: Slice
  share: number
  total: number
  entryNoun: string
  isActive: boolean
  onActivate: () => void
  onDeactivate: () => void
  onSelect?: (slice: Slice) => void
}) {
  const name =
    `${entryNoun} ${slice.label}: ${slice.rows.length} of ${total} projects, ${share} percent.` +
    (onSelect ? ' Open them.' : '')

  const body = (
    <>
      <span
        aria-hidden="true"
        className="h-2.5 w-2.5 shrink-0 rounded-sm"
        style={{ backgroundColor: slice.colour }}
      />
      <span className="min-w-0 flex-1 truncate">{slice.label}</span>
      <span className="tabular-nums font-medium text-content">{slice.rows.length}</span>
      <span className="w-9 text-right tabular-nums text-content-muted">{share}%</span>
    </>
  )

  const shell =
    'flex w-full items-center gap-2 rounded-control px-2 py-1 text-left text-xs text-content-muted'

  if (!onSelect) {
    return (
      <span className={shell} aria-label={name} role="group">
        {body}
      </span>
    )
  }

  return (
    <button
      type="button"
      className={`${shell} hover:bg-subtle focus-visible:outline focus-visible:outline-2
                  focus-visible:-outline-offset-2 focus-visible:outline-primary
                  ${isActive ? 'bg-subtle' : ''}`}
      aria-label={name}
      onMouseEnter={onActivate}
      onMouseLeave={onDeactivate}
      onFocus={onActivate}
      onBlur={onDeactivate}
      onClick={() => onSelect(slice)}
    >
      {body}
    </button>
  )
}

/** At most this many names before the panel says how many more there are. */
const PEEK_LIMIT = 6

/**
 * The projects behind the slice under the pointer.
 *
 * Worst first, because a reader hovering a person's share is asking what to do
 * about it, and the answer starts at the top. Capped, with the remainder
 * counted rather than silently dropped — a panel that grew to forty rows would
 * push the rest of the board off the screen on hover.
 */
function ProjectPeek({ slice }: { slice: Slice }) {
  const rows: ObProjectBoardRow[] = [...slice.rows].sort(byLatenessDescending)
  const shown = rows.slice(0, PEEK_LIMIT)

  return (
    <div className="rounded-control border border-border bg-subtle p-2 text-xs">
      <p className="mb-1 font-medium text-content">
        {slice.label} · {slice.rows.length} {slice.rows.length === 1 ? 'project' : 'projects'}
      </p>
      <ul className="flex flex-col gap-0.5">
        {shown.map((row) => (
          <li key={row.id} className="flex items-baseline justify-between gap-3">
            <span className="min-w-0 truncate text-content">{row.client.name}</span>
            <span className="shrink-0 text-content-muted">
              {lateLabel(row) ?? row.currentStage ?? 'on track'}
            </span>
          </li>
        ))}
      </ul>
      {rows.length > shown.length && (
        <p className="mt-1 text-content-muted">and {rows.length - shown.length} more</p>
      )}
    </div>
  )
}
