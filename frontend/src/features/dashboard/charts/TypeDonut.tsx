import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { TOOLTIP_STYLE, categorical } from './chartTokens'
import { useDrillDown } from './useDrillDown'

/**
 * A-056 · widget 7 — open tickets by task type.
 *
 * A donut rather than a pie, per §S-05's layout. The hole is not decoration: a
 * pie asks the reader to compare angles at the centre, which is the comparison
 * people are worst at, and a donut at least makes it arc length. Both are
 * weaker than a bar chart, and §S-05 asks for a donut — noted rather than
 * quietly substituted, because the eleven-category comparison here is the one
 * the shape handles least well.
 *
 * The server returns slices largest-first and omits types with nothing open, so
 * there are no zero-width arcs with legend entries against them.
 */
export function TypeDonut({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Task type')
  const points = series[0]?.points ?? []

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={points}
              dataKey="y"
              nameKey="x"
              innerRadius="55%"
              outerRadius="80%"
              // Off by default and left off. An animation that redraws on every
              // filter change is motion nobody asked for, and prefers-reduced-
              // motion cannot reach inside an SVG animation recharts drives in
              // JavaScript — the tokens' media query only governs CSS.
              isAnimationActive={false}
              onClick={(slice: { payload?: { drillDown?: string | null } }) =>
                drillDown(slice?.payload?.drillDown)
              }
            >
              {points.map((point, index) => (
                <Cell
                  key={point.x}
                  fill={categorical(index)}
                  style={{ cursor: point.drillDown ? 'pointer' : 'default' }}
                />
              ))}
            </Pie>
            <Tooltip contentStyle={TOOLTIP_STYLE} />
          </PieChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Task types, with the number of open tickets in each"
        entries={points.map((point, index) => ({
          label: point.x,
          colour: categorical(index),
          drillDown: point.drillDown,
          value: point.y,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
