import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, TOOLTIP_STYLE, categorical } from './chartTokens'
import { mergeByCategory } from './mergeByCategory'
import { useDrillDown } from './useDrillDown'

/**
 * A-056 · widget 9 — tickets closed per resource per week.
 *
 * Weeks, not days, because §S-05 specifies "tickets/week" and because fifteen
 * daily lines over a month is thirty points of noise each. The server groups by
 * ISO week (Monday-start) and labels each point with that Monday's date.
 *
 * <h2>A resource with a quiet week is absent from it, not zero in it</h2>
 *
 * The server omits weeks where somebody closed nothing, and `connectNulls` is
 * off, so their line breaks rather than dropping to the axis. The distinction
 * is not cosmetic: a line at zero asserts "closed nothing that week", and the
 * data cannot tell that from leave, a reassignment, or a week spent on one
 * large ticket. A break says only that there is nothing to plot.
 *
 * <h2>Everyone's line, in one chart</h2>
 *
 * The number of series is the number of resources in scope, which for an Admin
 * is the organisation. No cap is applied — a silent top-N would make a
 * dashboard that quietly stops mentioning people, which is worse than a busy
 * chart — but the legend and the hidden table both carry every name, so the
 * chart being crowded never means somebody is unreachable.
 */
export function VelocityLines({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown()
  const rows = mergeByCategory(series)

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="category" stroke={AXIS.stroke} tick={AXIS.tick} minTickGap={16} />
            <YAxis stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} width={40} />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            {series.map((s, index) => (
              <Line
                key={s.name}
                type="monotone"
                dataKey={s.name}
                stroke={categorical(index)}
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
        label="Resources, linking to the tickets each closed"
        entries={series.map((s, index) => ({
          label: s.name,
          colour: categorical(index),
          // The most recent week's link — the one somebody clicking a
          // resource's name almost always wants, and the only per-resource
          // target that is not ambiguous across a multi-week window.
          drillDown: s.points[s.points.length - 1]?.drillDown ?? null,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
