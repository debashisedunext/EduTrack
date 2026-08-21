import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, HANDOFF_COLOUR, TOOLTIP_STYLE } from './chartTokens'
import { mergeByCategory } from './mergeByCategory'
import { useDrillDown } from './useDrillDown'

/**
 * A-058 · widget 19 — the average wait between one team finishing and the next
 * picking up, per day.
 *
 * <h2>Working hours, computed by the worker</h2>
 *
 * §7.6 defines handoff latency as the gap between one stage's `exited_at` and
 * the next stage's `entered_at`, and CLAUDE.md requires the working calendar for
 * every duration. So a Friday-evening handoff picked up at nine on Monday is a
 * few minutes here and not two days — which matters most on exactly this chart,
 * since a wall-clock version would show a spike every Monday and the reader
 * would learn to ignore the one signal it carries.
 *
 * <h2>A day with no handoff is absent, not zero</h2>
 *
 * `connectNulls` is off and the server omits days where nothing was handed
 * over, so the line breaks. `VelocityLines` makes the same argument and it is
 * sharper here: a point at zero on this axis says "handoffs were instantaneous
 * that day", which is a flattering claim about a day on which nothing moved.
 *
 * <h2>No drill-down, and the legend says so by having nothing to press</h2>
 *
 * §7.9 gives this widget's drill-down as "slowest handoffs" — a list of *hops*.
 * `GET /tickets` lists tickets and has no filter for "had a handoff on this
 * date"; the nearest is `reportedFrom`/`reportedTo`, which is when a ticket was
 * raised and would open a plausible, different set. A-060's whole defect was a
 * link whose filter did not mean what the segment meant, so this emits nothing.
 */
export function HandoffLatencyLine({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Handoff latency')
  const rows = mergeByCategory(series)

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="category" stroke={AXIS.stroke} tick={AXIS.tick} minTickGap={16} />
            {/* Hours, fractional — a twenty-minute average is 0.33 and rounding
                it to a whole number would draw a real wait as no wait. */}
            <YAxis stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals width={40} />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            {series.map((s) => (
              <Line
                key={s.name}
                type="monotone"
                dataKey={s.name}
                stroke={HANDOFF_COLOUR}
                strokeWidth={1.5}
                dot={{ r: 2 }}
                isAnimationActive={false}
                connectNulls={false}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Average working hours a ticket waited between stages"
        entries={series.map((s) => ({ label: s.name, colour: HANDOFF_COLOUR, drillDown: null }))}
        onSelect={drillDown}
      />
    </>
  )
}
