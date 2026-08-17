import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, LEVEL_COLOURS, TOOLTIP_STYLE } from './chartTokens'
import { useDrillDown } from './useDrillDown'

/**
 * A-056 · widget 11 — open tickets by priority.
 *
 * <h2>The level tokens, not the chart palette</h2>
 *
 * Low/Medium/High/Critical have their own tokens (blueprint §12.1) and they are
 * used everywhere else in EduTrack that severity appears — the level chips on
 * the ticket list, the KPI card for widget 4. Drawing this chart from the
 * generic categorical palette would give the same four levels one set of
 * colours on the list and a different set on the dashboard, on the same screen,
 * for the same tickets.
 *
 * <h2>Severity order, never value order</h2>
 *
 * The server returns the four ascending by severity and they are drawn in that
 * order. Sorting by value instead would rearrange the axis whenever the
 * underlying numbers crossed, so the same chart would be laid out differently
 * on two consecutive loads — and colour is not the only signal by design, which
 * means the position genuinely carries meaning here.
 */
export function PriorityBar({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Priority')
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
            <Bar
              dataKey="y"
              isAnimationActive={false}
              onClick={(bar: { drillDown?: string | null }) => drillDown(bar?.drillDown)}
            >
              {points.map((point) => (
                <Cell
                  key={point.x}
                  fill={LEVEL_COLOURS[point.x] ?? 'var(--chart-1)'}
                  style={{ cursor: point.drillDown ? 'pointer' : 'default' }}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Priority levels, with the number of open tickets at each"
        entries={points.map((point) => ({
          label: point.x,
          colour: LEVEL_COLOURS[point.x] ?? 'var(--chart-1)',
          drillDown: point.drillDown,
          value: point.y,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
