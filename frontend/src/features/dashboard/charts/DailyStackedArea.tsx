import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, FLOW_COLOURS, GRID_STROKE, TOOLTIP_STYLE } from './chartTokens'
import { useDrillDown } from './useDrillDown'
import { mergeByCategory } from './mergeByCategory'

/**
 * A-056 · widget 8 — created, closed and reopened per day.
 *
 * All three are **flow**, which is what makes stacking them legitimate: each
 * counts events that happened on that day, so the stack's height is "things
 * that happened", a quantity that means something. Stacking a stock series
 * (how many are open) on top of these would produce a total that is not a
 * number at all, which is the mistake A-050's migration header exists to warn
 * about.
 *
 * <h2>Gaps are gaps</h2>
 *
 * A day A-051 never summarised is absent from the server's series, and it stays
 * absent here — `connectNulls` is off, so the area breaks rather than drawing a
 * straight line across a weekend as though the data were continuous. A-055's
 * sparklines made the same choice for the same reason.
 */
export function DailyStackedArea({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Daily task status')
  const rows = mergeByCategory(series)

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={rows} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="category"
              stroke={AXIS.stroke}
              tick={AXIS.tick}
              // Thirty ISO dates will not fit; recharts thins them itself, and
              // the hidden table carries every one for anybody who needs them all.
              minTickGap={24}
            />
            <YAxis stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} width={40} />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            {series.map((s) => (
              <Area
                key={s.name}
                type="monotone"
                dataKey={s.name}
                stackId="flow"
                stroke={FLOW_COLOURS[s.name] ?? 'var(--chart-1)'}
                fill={FLOW_COLOURS[s.name] ?? 'var(--chart-1)'}
                fillOpacity={0.25}
                isAnimationActive={false}
                connectNulls={false}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </ChartCanvas>

      {/* One entry per series, not per day. Thirty dated buttons would be a
          tab-stop wall; the series-level links open the same window filtered by
          what the series counts, which is the useful granularity. */}
      <ChartLegend
        label="Daily ticket flow"
        entries={series.map((s) => ({
          label: s.name,
          colour: FLOW_COLOURS[s.name] ?? 'var(--chart-1)',
          drillDown: s.points[s.points.length - 1]?.drillDown ?? null,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
