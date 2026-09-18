import { useId, useState } from 'react'
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'

import type { ObProjectBoardRow } from '@/api/generated/model'

import { type Slice } from './obProjectBoard'

export interface ObProjectDonutProps<R = ObProjectBoardRow> {
  title: string
  /** One line under the title, saying what the slices are of. */
  caption: string
  slices: Slice<R>[]
  /** What the number in the hole counts — "ongoing", "projects". */
  centreLabel: string
  /** Names the legend's rows for a screen reader — "Salesperson", "Implementor", "Status". */
  entryNoun: string
  /**
   * What a slice holds, for the figures a screen reader is read — "projects"
   * unless the donut is a cut of something else, as the implementor's task
   * donut is.
   */
  unitNoun?: string
  /** Opens the matching rows. A slice with nowhere to go is drawn as plain text, never a dead button. */
  onSelect?: (slice: Slice<R>) => void
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
export function ObProjectDonut<R = ObProjectBoardRow>({
  title,
  caption,
  slices,
  centreLabel,
  entryNoun,
  unitNoun = 'projects',
  onSelect,
}: ObProjectDonutProps<R>) {
  const headingId = useId()
  const [view, setView] = useState<'chart' | 'table'>('chart')
  /*
    Which arc is lit. It drove the hover panel too; that panel is gone, so
    this now only dims the other arcs and swaps the centre figure — both of
    which happen inside the chart's own box and move nothing on the page.
  */
  const [active, setActive] = useState<string | null>(null)

  const total = slices.reduce((sum, slice) => sum + slice.rows.length, 0)
  const activeSlice = slices.find((slice) => slice.key === active) ?? null
  const share = (slice: Slice<R>) => (total === 0 ? 0 : Math.round((slice.rows.length / total) * 100))

  return (
    <section
      aria-labelledby={headingId}
      className="flex min-w-0 flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      {/* Deliberately not `flex-wrap`: the toggle is `shrink-0`, so wrapping
          would drop it onto its own line the moment a caption grew — which is
          what a long one did. The caption wraps inside its own column
          instead, and the control stays on the title's line. */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 id={headingId} className="text-sm font-semibold text-content">
            {title}
          </h3>
          <p className="text-xs text-content-muted">{caption}</p>
        </div>
        {/*
          Chart or table, chosen rather than disclosed. The table used to be
          behind a `<details>` and the figures behind a hover, so reading one
          off the chart meant either opening a second copy of it or holding
          the pointer still — and the hover panel opened *under* the chart,
          which pushed the rest of the board down every time the mouse
          crossed an arc. Two views, one at a time, and the card keeps its
          height whichever is up.
        */}
        <div
          role="group"
          aria-label={`${title} view`}
          className="flex shrink-0 rounded-control bg-subtle p-0.5"
        >
          {(['chart', 'table'] as const).map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={view === option}
              onClick={() => setView(option)}
              className={
                'rounded-[6px] px-2.5 py-1 text-[11px] font-semibold capitalize ' +
                (view === option
                  ? 'bg-surface text-primary shadow-rest'
                  : 'text-content-muted hover:text-content')
              }
            >
              {option}
            </button>
          ))}
        </div>
      </div>

      {total === 0 ? (
        <p className="py-8 text-center text-xs text-content-muted">
          No running projects to chart yet.
        </p>
      ) : view === 'chart' ? (
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <div className="relative h-[168px] w-[168px] shrink-0 self-center" aria-hidden="true">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={slices}
                  dataKey={(slice: Slice<R>) => slice.rows.length}
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
                  unitNoun={unitNoun}
                  isActive={active === slice.key}
                  onActivate={() => setActive(slice.key)}
                  onDeactivate={() => setActive(null)}
                  onSelect={onSelect}
                />
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <table className="w-full text-left text-xs">
          <caption className="sr-only">{title}, as a table</caption>
          <thead className="text-content-muted">
            <tr>
              <th scope="col" className="py-1 font-medium">
                {entryNoun}
              </th>
              <th scope="col" className="py-1 text-right font-medium capitalize">
                {unitNoun}
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
      )}
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
function LegendRow<R>({
  slice,
  share,
  total,
  entryNoun,
  unitNoun,
  isActive,
  onActivate,
  onDeactivate,
  onSelect,
}: {
  slice: Slice<R>
  share: number
  total: number
  entryNoun: string
  unitNoun: string
  isActive: boolean
  onActivate: () => void
  onDeactivate: () => void
  onSelect?: (slice: Slice<R>) => void
}) {
  const name =
    `${entryNoun} ${slice.label}: ${slice.rows.length} of ${total} ${unitNoun}, ${share} percent.` +
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
