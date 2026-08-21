import { CHART_HEIGHT, type WidgetSeries } from '../WidgetFrame'
import { REWORK_COLOURS } from './chartTokens'

/**
 * A-058 · widget 17 — open tickets that have been sent back, and the ones
 * bouncing.
 *
 * <h2>Figures rather than a chart, because §7.9 asks for a KPI card</h2>
 *
 * The other nine widgets on this screen are drawings; this one is two numbers
 * and a proportion. Recharts is not reached for, because a two-segment bar it
 * would draw as an SVG full of unreadable `<path>`s is here a `<div>` with a
 * width — real text, real contrast, and legible to a screen reader without the
 * hidden-table workaround the charts need.
 *
 * <h2>🔴 Ping-pong is nested inside rework, never stacked beside it</h2>
 *
 * The server sends three points and the temptation is to stack all three. It
 * would be wrong: `Reworked` counts `iteration_no >= 2` and `Ping-pong` counts
 * `iteration_no >= 3`, so every ping-pong ticket is *already* inside the rework
 * figure. Stacked, the bar would be longer than the backlog it describes and
 * every ping-pong ticket would be counted twice.
 *
 * So the bar has two segments — `First pass` and `Reworked`, which partition the
 * open backlog — and ping-pong is stated as a share *of* rework, in words.
 *
 * <h2>Both numbers, because one is unreadable</h2>
 *
 * Twelve tickets in rework is a crisis in a team holding twenty and a rounding
 * error in two thousand, so the denominator is on screen. And a team with ten
 * one-off corrections is not the team with ten tickets in a loop, though both
 * report ten — §4A.7 raises an alert at the second and nothing at the first.
 *
 * <h2>No drill-down</h2>
 *
 * `GET /tickets` has no iteration filter. The nearest, `reopenedOnly`, is
 * `cycle_no` — a different counter, and the baseline migration calls confusing
 * the two "the single most misread concept in the spec". Nothing is rendered as
 * a control, so nothing invites a click that would open the wrong list.
 */

function valueOf(series: WidgetSeries[], name: string): number {
  return series[0]?.points.find((point) => point.x === name)?.y ?? 0
}

export function ReworkPanel({ series }: { series: WidgetSeries[] }) {
  const reworked = valueOf(series, 'Reworked (2 or more passes)')
  const pingPong = valueOf(series, 'Ping-pong (3 or more passes)')
  const firstPass = valueOf(series, 'First pass')

  const open = firstPass + reworked
  // Guarded rather than assumed: a day whose summary row is all zeroes would
  // divide by nothing, and NaN reaches the DOM as a width of "NaN%" that
  // silently renders as zero — a clean-looking chart built on a broken sum.
  const reworkShare = open > 0 ? (reworked / open) * 100 : 0

  return (
    <div className="flex flex-col justify-center gap-4" style={{ minHeight: CHART_HEIGHT }}>
      <div className="flex flex-wrap gap-x-10 gap-y-4">
        <div>
          <p className="text-3xl font-semibold text-[var(--text-primary)]">{reworked}</p>
          <p className="text-xs text-[var(--text-secondary)]">
            of {open} open {open === 1 ? 'ticket' : 'tickets'} sent back at least once
          </p>
        </div>

        <div>
          <p className="text-3xl font-semibold text-[var(--ribbon-breached-text)]">{pingPong}</p>
          <p className="text-xs text-[var(--text-secondary)]">
            {/* Stated as a share of rework rather than of the backlog, because
                that is the relationship — and because "3 of 12 reworked" and
                "3 of 400 open" are different sentences and only one of them is
                about how corrections are going. */}
            of those are on a third pass or beyond
          </p>
        </div>
      </div>

      {/* aria-hidden because the two figures above already say everything this
          draws; announcing a decorative bar as well would read the same numbers
          twice in a row. WidgetFrame's hidden table carries the exact values. */}
      <div
        aria-hidden
        className="flex h-2.5 overflow-hidden rounded-full bg-[var(--bg-subtle)]"
      >
        <div
          style={{
            width: `${100 - reworkShare}%`,
            backgroundColor: REWORK_COLOURS['First pass'],
          }}
        />
        <div
          style={{
            width: `${reworkShare}%`,
            backgroundColor: REWORK_COLOURS['Reworked (2 or more passes)'],
          }}
        />
      </div>

      <p className="text-xs text-[var(--text-secondary)]">
        {/* Named in words rather than left to a legend swatch: this is the one
            widget where colour is the only thing distinguishing two segments,
            and CLAUDE.md's accessibility rule means colour is never the only
            signal. */}
        {reworkShare.toFixed(0)}% of open work has been reworked at least once.
      </p>
    </div>
  )
}
