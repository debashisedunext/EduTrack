import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, TOOLTIP_STYLE, categorical } from './chartTokens'
import { useDrillDown } from './useDrillDown'

/**
 * A-056 · widget 12 — how long the open tickets have been open.
 *
 * <h2>The buckets are the schema's, and the labels say so</h2>
 *
 * §S-05 draws 0–2 / 3–5 / 6–10 / >10 days. A-050 stored 0–2 / 3–7 / 8–30 / 31+
 * and fixed those edges in the columns deliberately, so that a bucket boundary
 * cannot move between two loads of the same day. The labels here follow the
 * **columns**, because the one genuinely dishonest option available was to
 * print the blueprint's ranges over the schema's numbers — an axis that
 * disagrees with the bar above it, which nobody would ever catch.
 *
 * <h2>No drill-down, deliberately</h2>
 *
 * §S-05's drill-down column asks for "age range" and the ticket list has no age
 * filter to express one. The server therefore sends `drillDown: null` on all
 * four, and the legend renders them as text rather than as buttons that cannot
 * be pressed. Inventing a parameter the list ignores would open a list
 * contradicting the bar that was clicked — worse than a bar that does not open.
 * Adding `ageFrom`/`ageTo` belongs with A-060 and Divyansh's list.
 */
export function AgingBuckets({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Ticket aging')
  const points = series[0]?.points ?? []

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={points} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="x" stroke={AXIS.stroke} tick={AXIS.tick} />
            <YAxis stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} width={40} />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            <Bar dataKey="y" isAnimationActive={false}>
              {points.map((point, index) => (
                // Sequential rather than categorical would be the better
                // encoding for an ordered scale, and the palette has no
                // sequential ramp; introducing one means new tokens, which is
                // blueprint §12.1's to grant and not this task's to assume.
                <Cell key={point.x} fill={categorical(index)} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Age ranges, with the number of open tickets in each"
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
