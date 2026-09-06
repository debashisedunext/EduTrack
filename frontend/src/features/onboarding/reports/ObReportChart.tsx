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

import { AXIS, GRID_STROKE, TOOLTIP_STYLE, categorical } from '@/features/dashboard/charts/chartTokens'
import type { ObReportDescriptorChart, ObReportResponseDataColumnsItem } from '@/api/generated/model'

/**
 * B-122 · the chart half of OB-10's viewer.
 *
 * <h2>The palette is the dashboard's, imported rather than restated</h2>
 *
 * CLAUDE.md: never introduce a colour that isn't a token. `chartTokens` already
 * resolves blueprint §12.1's colour-blind-safe series, so an onboarding report
 * and a ticketing widget showing the same shape of data show it in the same
 * colours. Importing across a feature boundary is deliberate and is read-only —
 * the alternative is a second palette that agrees today.
 *
 * <h2>A funnel is drawn as a horizontal bar chart</h2>
 *
 * Recharts has a `Funnel`, and the prototype does not use one: its funnel tab is
 * a column of horizontal bars with the step name on the left and the count on
 * the right. That is the better drawing here and not only for fidelity — a
 * recharts funnel narrows each band to the *shape* of the value, which makes two
 * adjacent stages with similar counts indistinguishable, and journey step names
 * ("Data migration", "Branding & configuration") do not fit on a funnel band.
 * The bar keeps the labels legible and the lengths comparable, which is what
 * "a healthy funnel drains left to right" needs a reader to be able to see.
 *
 * <h2>What the chart is not allowed to be</h2>
 *
 * The only source of a number. The viewer always renders the table beneath it,
 * because a chart cannot be read for an exact value and this is the screen
 * people open to get a figure they intend to quote. That is also the
 * accessibility position: the table is the text alternative, so the chart is
 * `aria-hidden` rather than given a label that would have a screen reader
 * announce an unreadable SVG.
 */
export function ObReportChart({
  chart,
  columns,
  rows,
  title,
}: {
  chart: NonNullable<ObReportDescriptorChart>
  columns: ObReportResponseDataColumnsItem[]
  rows: Record<string, unknown>[]
  title: string
}) {
  /*
    The first non-numeric column is the axis; every numeric one is a series.
    Derived from the declared types rather than from position, so a report that
    puts its label second still plots correctly.

    `rag` is a string and is deliberately excluded from the category candidates
    as well as from the series: it has three values across every row, so
    choosing it as the axis would collapse a fifty-row table into three bars
    labelled by colour. The allow-lists are explicit for that reason — a
    `!== 'rag'` would let the next non-plottable type in by default.
  */
  const categoryColumn = columns.find((c) => c.type === 'string' || c.type === 'date')
  const valueColumns = columns.filter(
    (c) => c.type === 'number' || c.type === 'percent' || c.type === 'duration',
  )

  if (!categoryColumn || valueColumns.length === 0) return null

  const data = rows.map((row) => {
    const point: Record<string, unknown> = { category: String(row[categoryColumn.key] ?? '') }
    for (const column of valueColumns) {
      // Null becomes null, not zero. TAT compliance sends null for a service
      // nothing could be measured on, and plotting that as a bar of height zero
      // would draw "everybody was late" where the answer is "we cannot say".
      const value = row[column.key]
      point[column.key] = value === null || value === undefined ? null : Number(value)
    }
    return point
  })

  return (
    <figure className="mb-4 rounded-control border border-border bg-surface p-4">
      <figcaption className="sr-only">
        {title} — the same figures are in the table below.
      </figcaption>
      <div className="h-72" aria-hidden>
        <ResponsiveContainer width="100%" height="100%">
          {chart === 'donut' ? (
            <PieChart>
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Legend />
              <Pie
                data={data}
                dataKey={valueColumns[0].key}
                nameKey="category"
                innerRadius="55%"
                outerRadius="80%"
              >
                {data.map((_, index) => (
                  <Cell key={index} fill={categorical(index)} />
                ))}
              </Pie>
            </PieChart>
          ) : chart === 'line' ? (
            <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
              <CartesianGrid stroke={GRID_STROKE} strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="category" stroke={AXIS.stroke} tick={AXIS.tick} minTickGap={16} />
              <YAxis stroke={AXIS.stroke} tick={AXIS.tick} width={48} />
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
                  // asserts a zero, and a month with no go-lives is not a
                  // boarding that took no time.
                  connectNulls={false}
                />
              ))}
            </LineChart>
          ) : (
            <BarChart
              data={data}
              // The funnel's one difference: bars run left to right with the
              // step name as the row label, which is the prototype's drawing.
              layout={chart === 'funnel' ? 'vertical' : 'horizontal'}
              margin={
                chart === 'funnel'
                  ? { top: 4, right: 16, bottom: 0, left: 8 }
                  : { top: 4, right: 8, bottom: 0, left: -12 }
              }
            >
              <CartesianGrid
                stroke={GRID_STROKE}
                strokeDasharray="3 3"
                vertical={chart === 'funnel'}
                horizontal={chart !== 'funnel'}
              />
              {chart === 'funnel' ? (
                <>
                  <XAxis type="number" stroke={AXIS.stroke} tick={AXIS.tick} allowDecimals={false} />
                  <YAxis
                    type="category"
                    dataKey="category"
                    stroke={AXIS.stroke}
                    tick={AXIS.tick}
                    width={160}
                  />
                </>
              ) : (
                <>
                  <XAxis
                    dataKey="category"
                    stroke={AXIS.stroke}
                    tick={AXIS.tick}
                    minTickGap={16}
                  />
                  <YAxis stroke={AXIS.stroke} tick={AXIS.tick} width={48} />
                </>
              )}
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Legend />
              {valueColumns.map((column, index) => (
                <Bar
                  key={column.key}
                  dataKey={column.key}
                  name={column.label}
                  fill={categorical(index)}
                  // Stacking requires the series to partition a total. A report
                  // whose columns overlap must declare `bar`, or the bar's
                  // height double-counts — the funnel's "journeys here" and "of
                  // which awaiting prerequisites" are exactly that pair, which
                  // is why it is not a stacked chart.
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
