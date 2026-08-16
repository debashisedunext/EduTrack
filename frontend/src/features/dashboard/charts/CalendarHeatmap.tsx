import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { useDrillDown } from './useDrillDown'

/**
 * A-057 · widget 13 — activity per day, as a calendar grid.
 *
 * <h2>Hand-drawn SVG, like the sparkline and unlike the other charts</h2>
 *
 * Recharts has no heatmap primitive, and the shapes that could be bent into one
 * — a scatter with square symbols on two categorical axes — cost more in
 * configuration than a `<rect>` per day costs outright. This is the same call
 * A-055 made for `Sparkline`: reach for the library when it saves work, not on
 * principle.
 *
 * <h2>Intensity by opacity, not by a new colour</h2>
 *
 * A heatmap wants a sequential ramp and blueprint §12.1 has none — the chart
 * palette is categorical, chosen for *separation* between series, which is the
 * opposite property. Generating intermediate colours would introduce values
 * nobody checked for contrast, and CLAUDE.md is absolute that a colour must be
 * a token.
 *
 * So intensity is opacity over a single token. It reads as a ramp, it cannot
 * drift from the palette, and it stays correct if §12.1 ever changes the token.
 * Five steps rather than a continuous scale: a continuous one makes neighbouring
 * days indistinguishable and invites the reader to compare shades they cannot
 * actually tell apart.
 *
 * <h2>A day with no data is a gap, not a quiet day</h2>
 *
 * The server omits days it has not summarised, and those render as no square at
 * all rather than as the lightest step. On a heatmap an unsummarised day and a
 * zero-activity day are otherwise pixel-identical, and one of them is a claim
 * about the team.
 */

const CELL = 13
const GAP = 3
const WEEKDAY_LABELS = ['Mon', '', 'Wed', '', 'Fri', '', 'Sun']
const TOP = 16

/** Five steps. `0` is a drawn-but-empty day; absent days never reach here. */
function intensity(value: number, max: number): number {
  if (value <= 0) return 0.08
  if (max <= 0) return 0.08
  const step = Math.ceil((value / max) * 4)
  return [0.08, 0.25, 0.45, 0.7, 1][Math.min(step, 4)]
}

/** Monday = 0, matching the ISO week the server groups velocity by. */
function isoWeekday(date: Date): number {
  return (date.getUTCDay() + 6) % 7
}

export function CalendarHeatmap({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown()
  const points = series[0]?.points ?? []

  if (points.length === 0) {
    return null
  }

  const max = Math.max(...points.map((p) => p.y))

  // Column 0 is the week containing the earliest day, so the grid reads
  // left-to-right in time regardless of which weekday the range opens on.
  const first = new Date(`${points[0].x}T00:00:00Z`)
  const firstMonday = new Date(first)
  firstMonday.setUTCDate(first.getUTCDate() - isoWeekday(first))

  const cells = points.map((point) => {
    const date = new Date(`${point.x}T00:00:00Z`)
    const week = Math.floor((date.getTime() - firstMonday.getTime()) / (7 * 86_400_000))
    return { point, week, weekday: isoWeekday(date) }
  })

  const weeks = Math.max(...cells.map((c) => c.week)) + 1
  const width = 28 + weeks * (CELL + GAP)
  const height = TOP + 7 * (CELL + GAP)

  return (
    <ChartCanvas>
      <svg
        width="100%"
        height="100%"
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="xMinYMid meet"
        focusable="false"
      >
        {WEEKDAY_LABELS.map((label, row) =>
          label ? (
            <text
              key={row}
              x={0}
              y={TOP + row * (CELL + GAP) + CELL - 3}
              fontSize={9}
              fill="var(--text-secondary)"
            >
              {label}
            </text>
          ) : null,
        )}

        {cells.map(({ point, week, weekday }) => (
          <rect
            key={point.x}
            x={28 + week * (CELL + GAP)}
            y={TOP + weekday * (CELL + GAP)}
            width={CELL}
            height={CELL}
            rx={2}
            fill="var(--primary)"
            fillOpacity={intensity(point.y, max)}
            style={{ cursor: point.drillDown ? 'pointer' : 'default' }}
            onClick={() => drillDown(point.drillDown)}
          >
            {/* Native SVG tooltip. The canvas is aria-hidden and the hidden
                table carries every figure, so this is a pointer affordance
                only — no library, no portal, nothing to keep in sync. */}
            <title>{`${point.x}: ${point.y}`}</title>
          </rect>
        ))}
      </svg>
    </ChartCanvas>
  )
}
