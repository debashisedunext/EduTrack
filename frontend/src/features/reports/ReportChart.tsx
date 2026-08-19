import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import { AXIS, GRID_STROKE, TOOLTIP_STYLE, categorical } from '../dashboard/charts/chartTokens'
import type { ReportResponseDataColumnsItem } from '@/api/generated/model'

/**
 * A-063 · the chart half of the viewer.
 *
 * <h2>One component for four chart types, driven by the descriptor</h2>
 *
 * <p>The server says `line`, `bar`, `stacked-bar` or `donut` and this draws it.
 * Eighteen bespoke chart components would be eighteen places for the palette,
 * the axis styling and the accessibility treatment to drift — which is exactly
 * what `chartTokens` was extracted to prevent one screen over.
 *
 * <h2>The palette is the dashboard's, imported rather than restated</h2>
 *
 * <p>CLAUDE.md: never introduce a colour that isn't a token. `chartTokens` is
 * Stream A's own file from A-056 and it already resolves §12.1's eight
 * colour-blind-safe series, so a report and a dashboard widget showing the same
 * data show it in the same colours.
 *
 * <h2>What the chart is not allowed to be</h2>
 *
 * <p>The only source of a number. The viewer always renders the table beneath
 * it, because a chart cannot be read for an exact value and this is the screen
 * people open to get a figure they intend to quote. That also carries the
 * accessibility case: the table is the text alternative, so the chart is marked
 * `aria-hidden` rather than being given a label that would make a screen reader
 * announce an unreadable SVG.
 */
export function ReportChart({
  chart,
  columns,
  rows,
  title,
}: {
  chart: string
  columns: ReportResponseDataColumnsItem[]
  rows: Record<string, unknown>[]
  title: string
}) {
  // The first non-numeric column is the axis; every numeric one is a series.
  // Derived from the declared types rather than from position, so a report that
  // puts its label second still plots correctly.
  //
  // B-061 · `trend` is numeric and is deliberately absent from the series list.
  // A signed change against the previous window shares no axis with the
  // quantities beside it — on the scorecard it was being plotted against an SLA
  // percentage and a cycle time in hours — and on a stacked chart a negative
  // value subtracts from the bar it is stacked into. This is an allow-list
  // rather than a deny-list precisely so a new type has to be admitted on
  // purpose; a `!== 'trend'` would have let the next one in by default.
  const categoryColumn = columns.find((c) => c.type === 'string' || c.type === 'date')
  const valueColumns = columns.filter((c) => c.type === 'number' || c.type === 'percent' || c.type === 'duration')

  if (!categoryColumn || valueColumns.length === 0) return null

  const data = rows.map((row) => {
    const point: Record<string, unknown> = { category: String(row[categoryColumn.key] ?? '') }
    for (const column of valueColumns) {
      point[column.key] = Number(row[column.key] ?? 0)
    }
    return point
  })

  return (
    <figure className="mb-4 rounded-control border border-border bg-surface p-4">
      <figcaption className="sr-only">
        {title} — the same figures are in the table below.
      </figcaption>
      {/*
        aria-hidden because the table beneath carries every value this plots.
        Labelling the SVG instead would have a screen reader announce a chart it
        cannot read, in place of a table it can.
      */}
      <div className="h-72" aria-hidden>
        <ResponsiveContainer width="100%" height="100%">
          {chart === 'donut' ? (
            <PieChart>
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Legend />
              <Pie data={data} dataKey={valueColumns[0].key} nameKey="category" innerRadius="55%" outerRadius="80%">
                {data.map((_, index) => (
                  <Cell key={index} fill={categorical(index)} />
                ))}
              </Pie>
            </PieChart>
          ) : chart === 'line' ? (
            <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
              <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="category" stroke={AXIS.stroke} tick={AXIS.tick} minTickGap={16} />
              <YAxis stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} width={40} />
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Legend />
              {valueColumns.map((column, index) => (
                <Line
                  key={column.key}
                  type="monotone"
                  dataKey={column.key}
                  name={column.label}
                  stroke={categorical(index)}
                  strokeWidth={2}
                  dot={false}
                  // Off, for VelocityLines' reason: a line dropping to the axis
                  // asserts a zero, and an absent day is not a zero.
                  connectNulls={false}
                />
              ))}
            </LineChart>
          ) : (
            <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
              <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="category" stroke={AXIS.stroke} tick={AXIS.tick} minTickGap={16} />
              <YAxis stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} width={40} />
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Legend />
              {valueColumns.map((column, index) => (
                <Bar
                  key={column.key}
                  dataKey={column.key}
                  name={column.label}
                  fill={categorical(index)}
                  // The one difference between bar and stacked-bar. Stacking
                  // requires the series to partition a total — a report whose
                  // columns overlap must declare `bar`, or the bar's height
                  // double-counts.
                  stackId={chart === 'stacked-bar' ? 'a' : undefined}
                />
              ))}
            </BarChart>
          )}
        </ResponsiveContainer>
      </div>
    </figure>
  )
}
