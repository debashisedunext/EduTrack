import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, FUNNEL_COLOUR, GRID_STROKE, TOOLTIP_STYLE } from './chartTokens'
import { useDrillDown } from './useDrillDown'

/**
 * A-058 · widget 16 — how many tickets sit at each ribbon stage right now.
 *
 * <h2>Server order, never sorted here</h2>
 *
 * This is the one thing that would break the widget while leaving it looking
 * correct. §4A.8 describes the funnel as "spot the bottleneck instantly", and a
 * bottleneck is a bulge at a *known point in a known sequence* — INTAKE, TRIAGE,
 * DEV, QA and so on. The server returns the stages in ribbon order and they are
 * drawn in that order.
 *
 * `ClientVolumeBar` sorts by value and says why: clients have no inherent order,
 * so the ranking is the information. Stages have nothing but order, and sorting
 * by size would produce exactly the same bars arranged so the one thing the
 * chart exists to show cannot be seen.
 *
 * <h2>An empty stage is still drawn</h2>
 *
 * Unlike the donut, which omits a task type with nothing open. A gap in the
 * middle of a funnel is information — work is arriving after that stage and not
 * sitting in it — whereas an omitted band silently shortens the sequence and
 * makes two non-adjacent stages look adjacent.
 *
 * <h2>One colour</h2>
 *
 * See `FUNNEL_COLOUR`. The y axis already names every band, so hue would be
 * decoration that the reader is nonetheless obliged to interpret.
 */
export function StageFunnel({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Stage funnel')
  const points = series[0]?.points ?? []

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={points}
            layout="vertical"
            margin={{ top: 4, right: 8, bottom: 0, left: 8 }}
          >
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" horizontal={false} />
            <XAxis type="number" stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} />
            <YAxis
              type="category"
              dataKey="x"
              stroke={AXIS.stroke}
              tick={AXIS.tick}
              // Stage display names are short by construction — workflow_stages
              // caps display_name at 50 and the seeded ribbon uses one or two
              // words — so this needs less room than the client bar's 140.
              width={110}
            />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            <Bar
              dataKey="y"
              isAnimationActive={false}
              onClick={(bar: { drillDown?: string | null; x?: string; y?: number }) =>
                drillDown(bar?.drillDown, bar?.x, bar?.y)
              }
            >
              {points.map((point) => (
                <Cell key={point.x} fill={FUNNEL_COLOUR} style={{ cursor: 'pointer' }} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Stages, with the number of open tickets standing in each"
        entries={points.map((point) => ({
          label: point.x,
          colour: FUNNEL_COLOUR,
          drillDown: point.drillDown,
          value: point.y,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
