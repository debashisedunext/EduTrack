import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, STAGE_TIME_COLOURS, TOOLTIP_STYLE } from './chartTokens'
import { mergeByCategory } from './mergeByCategory'
import { useDrillDown } from './useDrillDown'

/**
 * A-058 · widget 18 — average hours per stage, split into work and waiting.
 *
 * <h2>Stacked, because the split is the point</h2>
 *
 * §4A.8: "where the calendar time actually goes, split into active vs idle". Two
 * bars side by side would show the same two numbers and lose the comparison the
 * widget exists for — that a stage averaging four days contained three hours of
 * work. Stacked, the total is the bar's length and the amber share is how much
 * of it nobody was working, which is one glance rather than two.
 *
 * <h2>Server order, like the funnel</h2>
 *
 * `mergeByCategory` preserves first appearance across the series, and the server
 * emits both series in ribbon order. So the stages line up with widget 16 above
 * and a reader comparing "where they sit" against "how long they stay" is
 * reading the same axis twice.
 *
 * <h2>Both segments open the same list, and that is not a bug</h2>
 *
 * Active and idle are two measurements of one stage; there is no list of "idle
 * tickets" distinct from "tickets in this stage". The link opens the tickets
 * standing in that stage now, which is a different population from the visits
 * that ended in the window — permissible here, and refused on widget 17, for a
 * reason worth keeping straight: **this bar's value is a duration**, so the
 * opened list has no count to contradict. Widget 17's value is a ticket count,
 * and a list of a different size would be read as the card being wrong.
 */
export function StageDurationBar({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Time per stage')
  const rows = mergeByCategory(series)

  // Every point of a given stage carries the same target — the server builds it
  // from the stage code alone — so the first series is as good as any.
  const targetFor = (category: string) =>
    series[0]?.points.find((point) => point.x === category)?.drillDown ?? null

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" horizontal={false} />
            <XAxis
              type="number"
              stroke={AXIS.stroke}
              tick={AXIS.tick}
              // Hours, and they are fractional — a stage averaging 40 minutes is
              // 0.67 and rounding it away would draw it as zero. The server
              // already rounds to two places.
              allowDecimals
            />
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
                stackId="stage-time"
                fill={STAGE_TIME_COLOURS[s.name] ?? 'var(--chart-1)'}
                isAnimationActive={false}
                onClick={(bar: { category?: string }) =>
                  drillDown(targetFor(bar?.category ?? ''), bar?.category)
                }
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Average hours per visit, split into time worked and time waiting"
        entries={series.map((s) => ({
          label: s.name,
          colour: STAGE_TIME_COLOURS[s.name] ?? 'var(--chart-1)',
          // The legend names the two measures, not two lists — clicking
          // "Idle" cannot open "idle tickets", which do not exist as a set.
          drillDown: null,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
