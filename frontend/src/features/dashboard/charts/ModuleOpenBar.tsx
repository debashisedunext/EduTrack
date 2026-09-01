import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { AXIS, GRID_STROKE, TOOLTIP_STYLE } from './chartTokens'
import { mergeByCategory } from './mergeByCategory'
import { useDrillDown } from './useDrillDown'

/**
 * Dashboard Rework Dev 2, PR 14 · widget 15 — module-wise total open tickets.
 *
 * <h2>The stack is an arithmetic claim, and the schema makes it true</h2>
 *
 * Not started, WIP and Overdue **partition** the module's open work: every
 * outstanding ticket is in exactly one segment. That is not a rendering
 * convention — it is enforced where the numbers are computed, by a single
 * `CASE` per ticket in `DailyStatsRepository.refreshModuleStats` with overdue
 * tested first. `ResourceLoadBar` makes the identical argument for its own
 * triple, and it matters for the identical reason: a stacked bar's *length* is
 * what people read off it, so segments that overlapped would draw eleven
 * tickets for a module holding nine.
 *
 * Overdue is drawn last so it ends the bar, where its extent is read against
 * the axis rather than against a neighbouring segment.
 *
 * <h2>Horizontal, for widget 10 and 20's reason</h2>
 *
 * Module names are words — "Examination", "Attendance", "Parent App" — and
 * words read along the y axis but clip and rotate under a vertical one.
 *
 * <h2>Colour is borrowed from the load bar, not from the palette</h2>
 *
 * These three states are the same three states `ResourceLoadBar` draws, one
 * axis over: not-yet-started, in-flight, late. Reaching for `categorical()`
 * here would give one fact two colours on one screen, and somebody who has
 * learnt that amber means late on the resource bar would have to learn it
 * again two panels down.
 */

/**
 * Deliberately keyed by the server's own series names.
 *
 * A positional map would silently recolour every segment the day the server
 * reorders them, and the order is load-bearing here (overdue last), so it is
 * the kind of change somebody will make for a good reason.
 */
const MODULE_COLOURS: Record<string, string> = {
  'Not started': 'var(--chart-2)',
  WIP: 'var(--chart-1)',
  Overdue: 'var(--warning)',
}

const colourFor = (name: string) => MODULE_COLOURS[name] ?? 'var(--chart-1)'

export function ModuleOpenBar({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Open tickets by module')
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
                stackId="open"
                fill={colourFor(s.name)}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </ChartCanvas>

      {/*
        The legend is the keyboard path to the same three destinations. The bars
        are `<path>` elements — not focusable, not announced — so the canvas is
        aria-hidden by `WidgetFrame` and these buttons are how a keyboard user
        reaches the drill-downs. Click-through on the drawing is a convenience
        for pointer users and never the only way in.
      */}
      <ChartLegend
        label="Open states, linking to the tickets in each"
        entries={series.map((s) => ({
          label: s.name,
          colour: colourFor(s.name),
          drillDown: s.points[0]?.drillDown ?? null,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
