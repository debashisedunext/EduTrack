import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, LOAD_COLOURS, TOOLTIP_STYLE } from './chartTokens'
import { mergeByCategory } from './mergeByCategory'
import { useDrillDown } from './useDrillDown'

/**
 * A-056 · widget 10 — each resource's open load, split three ways.
 *
 * Horizontal, per §S-05, which is the right call for once: the categories are
 * people's names, and names read far better along the y axis than rotated
 * forty-five degrees under a vertical bar.
 *
 * <h2>The stack is an arithmetic claim, and the schema makes it true</h2>
 *
 * Open, In progress and Delayed **partition** the person's assigned load —
 * every open ticket is in exactly one segment. That is not a rendering
 * convention, it is enforced where the numbers are computed:
 * `assigned_in_progress` is defined disjointly from `assigned_delayed` in the
 * migration precisely so this bar's length is the person's real load. Segments
 * that overlapped would draw a bar showing eleven tickets for somebody holding
 * nine, and a stacked bar's length is exactly what people read off it.
 *
 * Delayed is drawn last so it ends the bar, where its extent is read against
 * the axis rather than against a neighbouring segment.
 */
export function ResourceLoadBar({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Resource load')
  const rows = mergeByCategory(series)

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" horizontal={false} />
            <XAxis type="number" stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} />
            <YAxis
              type="category"
              dataKey="category"
              stroke={AXIS.stroke}
              tick={AXIS.tick}
              width={110}
            />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            {series.map((s) => (
              <Bar
                key={s.name}
                dataKey={s.name}
                stackId="load"
                fill={LOAD_COLOURS[s.name] ?? 'var(--chart-1)'}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Load states, linking to the tickets in each"
        entries={series.map((s) => ({
          label: s.name,
          colour: LOAD_COLOURS[s.name] ?? 'var(--chart-1)',
          drillDown: s.points[0]?.drillDown ?? null,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
