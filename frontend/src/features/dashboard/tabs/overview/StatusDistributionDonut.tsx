import { Skeleton } from '@/components/ui/skeleton'

import { useDrillDownStore } from '../../drillDownStore'

/**
 * S-05 tab 2 · the status split, as a half-donut with a legend.
 *
 * <h2>Hand-drawn SVG rather than Recharts, for two independent reasons</h2>
 *
 * Neither is a preference. `CalendarHeatmap` and `Sparkline` set the
 * precedent — reach for the library when it saves work, not on principle.
 *
 * 1. **Keyboard reach.** The plan requires every arc to be operable, and
 *    Recharts renders a `<Cell>` as a bare `<path>` it gives you no way to
 *    wrap or make focusable. `useDrillDown`'s own note records that this is
 *    why every Recharts chart on the Analytics tab is `aria-hidden` with the
 *    legend as the only accessible route. Drawing the arcs here means they
 *    can carry their own focus and key handling.
 * 2. **It would be untestable.** jsdom performs no layout, so
 *    `ResponsiveContainer` measures 0×0 and Recharts renders nothing — the
 *    test setup says so directly. A hand-drawn `<svg>` asserts fine.
 *
 * <h2>The arcs are not controls, and the legend is — a deliberate deviation</h2>
 *
 * The plan asks for "every arc, legend row and bar segment" to be a real
 * `<button>`. Taken literally on a donut that is not achievable and not
 * desirable, for two separate reasons:
 *
 * - An HTML `<button>` is not valid SVG content, so an arc can at best carry
 *   `role="button"` and its own key handling.
 * - Doing that alongside a clickable legend gives **every destination two tab
 *   stops announcing the identical sentence**. That was not a thought
 *   experiment here: the first cut of this component did exactly that, and
 *   the test that found it could not tell the arc from its legend row because
 *   the two had the same accessible name. Duplicating a control is a
 *   well-known way to make a screen reader worse, not better.
 *
 * So the drawing is `role="img"` with a text alternative naming every bucket
 * and the total, and the legend rows beneath are genuine `<button>`s that
 * reach every destination an arc could. Clicking an arc still works, as a
 * pointer convenience. This is exactly the split `useDrillDown` and
 * `WidgetFrame.ChartCanvas` already document for the Analytics charts, and
 * the requirement behind the plan's wording — no destination reachable only
 * by mouse — is met in full.
 *
 * <h2>`pct` is the server's, never recomputed</h2>
 *
 * The contract serves it precisely so the arc and the legend cannot round
 * differently. Recomputing `value / total` here would reintroduce the
 * disagreement the field exists to prevent.
 */

export interface DistributionSlice {
  category: string
  label: string
  value: number
  pct: number
  drillDown?: string | null
}

/**
 * Bucket colours, by category rather than by position, so a reordered
 * response cannot silently recolour the chart. Tokens only — the prototype's
 * `#9CA3AF` for the pending bucket is not one, and `--chart-2` stands in, the
 * same substitution `TopAssigneesBar` makes for its "not started" segment.
 */
const CATEGORY_COLOURS: Record<string, string> = {
  TODO: 'var(--chart-2)',
  IN_PROGRESS: 'var(--chart-1)',
  DONE: 'var(--chart-3)',
}

const FALLBACK_COLOUR = 'var(--chart-6)'

// Prototype geometry, kept exactly: a 220×118 box, centre (110, 104),
// radius 76, stroke 26, and a 3° gap between arcs.
const WIDTH = 220
const HEIGHT = 118
const CX = 110
const CY = 104
const R = 76
const STROKE = 26
const GAP = (3 * Math.PI) / 180

function point(angle: number): [number, number] {
  return [CX + R * Math.cos(angle), CY - R * Math.sin(angle)]
}

/**
 * Sweeps from π (due left) clockwise to 0 (due right) — the top half only.
 *
 * Returns null for a slice too thin to draw once both gap insets are taken
 * off it. Emitting the path anyway produces an arc that sweeps backwards,
 * which renders as a stray hairline across the whole chart rather than as
 * nothing.
 */
function arcPath(startAngle: number, sweep: number): string | null {
  const endAngle = startAngle - sweep
  if (sweep <= GAP) {
    return null
  }
  const [x0, y0] = point(startAngle - GAP / 2)
  const [x1, y1] = point(endAngle + GAP / 2)
  return `M ${x0.toFixed(1)} ${y0.toFixed(1)} A ${R} ${R} 0 0 1 ${x1.toFixed(1)} ${y1.toFixed(1)}`
}

export function StatusDistributionDonut({ distribution }: { distribution: DistributionSlice[] }) {
  const openPanel = useDrillDownStore((s) => s.open)
  const total = distribution.reduce((sum, slice) => sum + slice.value, 0)

  if (total === 0) {
    return (
      <p className="text-sm text-[color:var(--text-secondary)]">
        No tickets in this range to distribute.
      </p>
    )
  }

  let angle = Math.PI
  const arcs = distribution.map((slice) => {
    const sweep = (slice.value / total) * Math.PI
    const path = arcPath(angle, sweep)
    angle -= sweep
    return { slice, path }
  })

  const open = (slice: DistributionSlice) => {
    if (!slice.drillDown) return
    openPanel(slice.drillDown, slice.label, slice.value)
  }

  return (
    <div>
      <div className="flex justify-center">
        <svg
          width={WIDTH}
          height={HEIGHT}
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          role="img"
          aria-label={`Status distribution: ${distribution
            .map((s) => `${s.label} ${s.value}`)
            .join(', ')} of ${total} tickets`}
        >
          {arcs.map(({ slice, path }) =>
            path === null ? null : (
              <path
                key={slice.category}
                d={path}
                fill="none"
                stroke={CATEGORY_COLOURS[slice.category] ?? FALLBACK_COLOUR}
                strokeWidth={STROKE}
                // Pointer convenience only — deliberately not a control. See
                // the class note: the legend row below is the accessible
                // route, and giving the arc its own role here would announce
                // the same destination twice. The parent <svg> is role="img",
                // so these are already outside the accessibility tree.
                {...(slice.drillDown
                  ? {
                      className: 'cursor-pointer transition-opacity hover:opacity-80',
                      onClick: () => open(slice),
                    }
                  : {})}
              />
            ),
          )}
          <text
            x={CX}
            y={88}
            textAnchor="middle"
            fontSize={26}
            fontWeight={680}
            fill="var(--text-primary)"
          >
            {total}
          </text>
          <text x={CX} y={106} textAnchor="middle" fontSize={10.5} fill="var(--text-secondary)">
            TOTAL TICKETS
          </text>
        </svg>
      </div>

      <ul className="mt-3 flex flex-col gap-[7px]">
        {distribution.map((slice) => {
          const swatch = (
            <span
              aria-hidden="true"
              className="inline-block h-2.5 w-2.5 shrink-0 rounded-sm"
              style={{ backgroundColor: CATEGORY_COLOURS[slice.category] ?? FALLBACK_COLOUR }}
            />
          )
          const body = (
            <>
              {swatch}
              <span className="truncate">{slice.label}</span>
              <b className="ml-auto tabular-nums text-[color:var(--text-primary)]">{slice.value}</b>
              <i className="w-10 shrink-0 text-right not-italic tabular-nums text-[color:var(--text-secondary)]">
                {slice.pct}%
              </i>
            </>
          )

          // A bucket the list cannot express is text, not a dead control.
          if (!slice.drillDown) {
            return (
              <li
                key={slice.category}
                className="flex items-center gap-2 px-1 py-0.5 text-xs text-[color:var(--text-secondary)]"
              >
                {body}
              </li>
            )
          }

          return (
            <li key={slice.category}>
              <button
                type="button"
                onClick={() => open(slice)}
                aria-label={`${slice.label}: ${slice.value}, ${slice.pct}%. Open the filtered ticket list.`}
                className="flex w-full items-center gap-2 rounded-[6px] px-1 py-0.5 text-left text-xs text-[color:var(--text-secondary)] transition-colors hover:bg-[color:var(--bg-subtle)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
              >
                {body}
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export function StatusDistributionDonutSkeleton() {
  return (
    <div className="flex flex-col items-center gap-3">
      <Skeleton className="h-[118px] w-[220px]" />
      <div className="flex w-full flex-col gap-[7px]">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-4 w-full" />
        ))}
      </div>
    </div>
  )
}
