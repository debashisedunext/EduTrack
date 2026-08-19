import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, TOOLTIP_STYLE, categorical } from './chartTokens'
import { useDrillDown } from './useDrillDown'

/**
 * A-059 · widget 20 — tickets raised per client over the window.
 *
 * <h2>Horizontal, and for widget 10's reason rather than by preference</h2>
 *
 * §S-05 asks for a horizontal bar and the categories say why: client names are
 * long, and long names read along the y axis but stack up rotated and clipped
 * under a vertical one. `ResourceLoadBar` made the same call for people's
 * names.
 *
 * <h2>Value order, unlike the priority bar</h2>
 *
 * The server returns clients largest first and they are drawn in that order.
 * `PriorityBar` deliberately refuses to sort by value because severity has an
 * inherent order that position carries; clients have none, so the ranking *is*
 * the information — "who is raising the most" is the question the widget
 * answers, and an alphabetical axis would hide it in plain sight.
 *
 * <h2>The pooled bar is drawn differently because it behaves differently</h2>
 *
 * Beyond the server's cap the remainder arrives as one "Other (N clients)"
 * point with no `drillDown`. It is drawn in a muted token rather than the next
 * palette colour, because every other bar opens a filtered list and this one
 * cannot — there is no filter for "any of thirty-one clients". Giving it a
 * palette colour would make it look like the thirteenth client, which is both
 * a category that does not exist and a bar whose silence on click reads as a
 * broken link rather than as a deliberate absence.
 */

/** Matches the server's `Other (N clients)` label — see `WidgetService.clientVolume`. */
function isPooled(label: string): boolean {
  return /^Other \(\d+ clients?\)$/.test(label)
}

function colourFor(label: string, index: number): string {
  return isPooled(label) ? 'var(--text-secondary)' : categorical(index)
}

export function ClientVolumeBar({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Client volume')
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
              // Wider than the resource bar's 110: a client name is a company
              // name, and truncating it to an abbreviation shared by two
              // clients would give one bar a label belonging to another.
              width={140}
            />
            <Tooltip contentStyle={TOOLTIP_STYLE} />
            <Bar
              dataKey="y"
              isAnimationActive={false}
              // D-064 · the bar's own figure travels into the panel header, so
              // the number in the panel is the number that was clicked.
              onClick={(bar: { drillDown?: string | null; x?: string; y?: number }) =>
                drillDown(bar?.drillDown, bar?.x, bar?.y)
              }
            >
              {points.map((point, index) => (
                <Cell
                  key={point.x}
                  fill={colourFor(point.x, index)}
                  style={{ cursor: point.drillDown ? 'pointer' : 'default' }}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Clients, with the number of tickets each raised"
        entries={points.map((point, index) => ({
          label: point.x,
          colour: colourFor(point.x, index),
          drillDown: point.drillDown,
          value: point.y,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
