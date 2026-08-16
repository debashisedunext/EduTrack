import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { useDrillDown } from './useDrillDown'

/**
 * A-057 · widget 14 — the share of finished work that landed on time.
 *
 * <h2>A half-donut, not a RadialBarChart</h2>
 *
 * Recharts' `RadialBarChart` draws a bar on a polar axis, which needs a domain,
 * a background bar and a hand-placed label to read as a gauge, and still leaves
 * the arc scaled by angle rather than by the two quantities. A half `Pie` over
 * `[met, breached]` *is* the gauge: the sweep is the ratio by construction, and
 * the two counts are what the server actually returned.
 *
 * <h2>The number in the middle is the point</h2>
 *
 * A gauge is read as a single figure, so the percentage is drawn large in the
 * arc. But it is computed from the two counts rather than sent as a percentage,
 * and both counts are in the legend and the hidden data table — because 100% off
 * two tickets and 100% off two hundred are the same needle and very different
 * facts, and the sample size has to be reachable.
 *
 * <h2>Colour</h2>
 *
 * Success and danger tokens, not the categorical palette: this is the one chart
 * on the dashboard where the two categories genuinely are "good" and "bad", so
 * the semantic tokens say what the palette would only imply. Contrast that with
 * the KPI delta badge, which deliberately refuses to colour a rise — there,
 * direction is not judgement; here, a breach is.
 */
export function SlaGauge({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown()

  const met = series.find((s) => s.name === 'Met')?.points[0]
  const breached = series.find((s) => s.name === 'Breached')?.points[0]

  const metValue = met?.y ?? 0
  const breachedValue = breached?.y ?? 0
  const total = metValue + breachedValue

  // Nothing closed with a due date in this window. Not 0% — that would read as
  // total failure, when the truth is there is nothing to measure.
  if (total === 0) {
    return (
      <p className="text-xs text-[color:var(--text-secondary)]" role="status">
        No work with a committed date was closed in this range, so there is no
        compliance figure to show.
      </p>
    )
  }

  const pct = Math.round((metValue / total) * 100)

  const data = [
    { name: 'Met', value: metValue, colour: 'var(--success)', drillDown: met?.drillDown ?? null },
    {
      name: 'Breached',
      value: breachedValue,
      colour: 'var(--danger)',
      drillDown: breached?.drillDown ?? null,
    },
  ]

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              // A half circle, opening left and closing right, which is how a
              // gauge is read. cy at 75% keeps the arc centred in the box once
              // the lower half is gone.
              startAngle={180}
              endAngle={0}
              cx="50%"
              cy="75%"
              innerRadius="60%"
              outerRadius="95%"
              isAnimationActive={false}
              onClick={(slice: { payload?: { drillDown?: string | null } }) =>
                drillDown(slice?.payload?.drillDown)
              }
            >
              {data.map((segment) => (
                <Cell
                  key={segment.name}
                  fill={segment.colour}
                  style={{ cursor: segment.drillDown ? 'pointer' : 'default' }}
                />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
      </ChartCanvas>

      {/* The headline figure is real DOM, not `<text>` inside the chart.
          Everything in `ChartCanvas` is aria-hidden — an SVG of paths has no
          text alternative — and this is the one number the whole widget exists
          to state, so burying it in there would have left a screen-reader user
          with the two counts and no ratio. Pulled up over the arc's opening,
          which a half donut leaves empty by construction. */}
      <p className="-mt-16 text-center">
        <span className="block text-3xl font-semibold tabular-nums text-[color:var(--text-primary)]">
          {pct}%
        </span>
        <span className="block text-xs text-[color:var(--text-secondary)]">
          {`${metValue} of ${total} on time`}
        </span>
      </p>

      <ChartLegend
        label="SLA outcomes, with the number of tickets in each"
        entries={data.map((segment) => ({
          label: segment.name,
          colour: segment.colour,
          drillDown: segment.drillDown,
          value: segment.value,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
