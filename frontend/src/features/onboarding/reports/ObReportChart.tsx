import { categorical } from '@/features/dashboard/charts/chartTokens'
import type { ObReportDescriptorChart, ObReportResponseDataColumnsItem } from '@/api/generated/model'

/**
 * B-122 · the chart half of OB-10's viewer, drawn the way the mockup draws it
 * (`vReports()` in `docs/prototype/onboarding.html`).
 *
 * <h2>Bars are the mockup's hbar rows, not a charting library's</h2>
 *
 * Every bar-shaped report — the funnel included — is a column of horizontal
 * rows: a 190px label, a track, the value printed at the end. That is what
 * "a healthy funnel drains left to right" needs a reader to see, it keeps
 * long step names legible, and the value label answers the number question a
 * floating tooltip only answers on hover.
 *
 * <h2>Threshold colouring is the mockup's, for percent columns only</h2>
 *
 * Below 70% draws in the danger token, 70–85% in warning, the rest in
 * `--chart-1` — with a swatch legend underneath, because colour is never the
 * only signal. Counts stay `--chart-1`: a small funnel stage is not a
 * failure.
 *
 * <h2>The palette is the token set, never a literal</h2>
 *
 * CLAUDE.md: never introduce a colour that isn't a token. Series colours come
 * through `chartTokens.categorical` and the thresholds through the semantic
 * `--danger`/`--warning` custom properties — all live CSS variables, so
 * blueprint §12.1 stays the single place the palette changes.
 *
 * <h2>What the chart is not allowed to be</h2>
 *
 * The only source of a number. The viewer always renders the table beneath
 * it, which is also the accessibility position: the figure carries a
 * `role="img"` label saying what it shows (the mockup labels its SVG the same
 * way), and the table below is the readable version of every figure.
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

  const labels = rows.map((row) => String(row[categoryColumn.key] ?? ''))
  const series = valueColumns.map((column) => ({
    column,
    // Null stays null, not zero. TAT compliance sends null for a service
    // nothing could be measured on, and plotting that as a bar of height zero
    // would draw "everybody was late" where the answer is "we cannot say".
    values: rows.map((row) => {
      const value = row[column.key]
      return value === null || value === undefined ? null : Number(value)
    }),
  }))

  return (
    <figure
      className="mb-4 rounded-control border border-border bg-surface p-4"
      role="img"
      aria-label={`${chart === 'line' ? 'Line chart' : 'Bar chart'}: ${title}. The same figures are in the table below.`}
    >
      <div aria-hidden>
        {chart === 'line' ? (
          <LineChart labels={labels} series={series} />
        ) : (
          <HBarRows labels={labels} series={series} />
        )}
      </div>
    </figure>
  )
}

interface Series {
  column: ObReportResponseDataColumnsItem
  values: (number | null)[]
}

function formatValue(value: number | null, type: ObReportResponseDataColumnsItem['type']): string {
  if (value === null) return '—'
  if (type === 'percent') return `${value}%`
  if (type === 'duration') return `${value}h`
  return String(value)
}

/** The mockup's threshold rule for a percent bar: red below 70, amber below 85. */
function percentColour(value: number): string {
  if (value < 70) return 'var(--danger)'
  if (value < 85) return 'var(--warning)'
  return 'var(--chart-1)'
}

/**
 * The mockup's `.hbar-row`: `grid-template-columns: 190px 1fr 34px` — label,
 * track, value. The first series draws the bars; every series is in the
 * table, which is the reference for exact values.
 */
function HBarRows({ labels, series }: { labels: string[]; series: Series[] }) {
  const [first] = series
  const isPercent = first.column.type === 'percent'
  const max = isPercent
    ? 100
    : Math.max(1, ...first.values.filter((v): v is number => v !== null))

  return (
    <div>
      {labels.map((label, index) => {
        const value = first.values[index]
        return (
          <div
            key={index}
            className="grid grid-cols-[190px_1fr_44px] items-center gap-2.5 py-1"
          >
            <span className="truncate text-[13px] text-content-muted" title={label}>
              {label}
            </span>
            <span className="flex h-[18px] items-center">
              {value !== null && (
                <span
                  className="h-3.5 min-w-[2px] rounded-r"
                  style={{
                    width: `${Math.max(0, Math.min(100, (value / max) * 100))}%`,
                    background: isPercent ? percentColour(value) : 'var(--chart-1)',
                  }}
                />
              )}
            </span>
            <span className="text-caption font-semibold tabular-nums text-content">
              {formatValue(value, first.column.type)}
            </span>
          </div>
        )
      })}

      {isPercent && (
        // The mockup's swatch legend — the words carry the rule, the swatches
        // only echo it, so a colour-blind reader loses nothing.
        <p className="mt-3 flex items-center gap-4 text-caption text-content-muted">
          <span className="flex items-center gap-1.5">
            <span
              className="inline-block h-2.5 w-2.5 rounded-sm"
              style={{ background: 'var(--danger)' }}
            />
            below 70%
          </span>
          <span className="flex items-center gap-1.5">
            <span
              className="inline-block h-2.5 w-2.5 rounded-sm"
              style={{ background: 'var(--warning)' }}
            />
            70–85%
          </span>
        </p>
      )}
    </div>
  )
}

/**
 * The mockup's SVG line chart: horizontal gridlines with axis figures, a
 * 2px `--chart-1` polyline, a filled dot on the latest point, and the first
 * and last values printed above their points so the trend's two ends can be
 * quoted without hovering anything.
 */
function LineChart({ labels, series }: { labels: string[]; series: Series[] }) {
  const W = 560
  const H = 200
  const P = 34

  const allValues = series.flatMap((s) => s.values).filter((v): v is number => v !== null)
  if (allValues.length === 0 || labels.length === 0) return null

  // A rounded-up "nice" ceiling so the top gridline is a readable figure.
  const rawMax = Math.max(...allValues)
  const step = Math.max(1, Math.ceil(rawMax / 4))
  const maxY = step * 4

  const x = (i: number) => (labels.length === 1 ? W / 2 : P + (i * (W - 2 * P)) / (labels.length - 1))
  const y = (v: number) => H - P - (v / maxY) * (H - 2 * P)

  return (
    <div className="overflow-x-auto">
      <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full max-w-[640px]">
        {[1, 2, 3, 4].map((g) => (
          <g key={g}>
            <line
              x1={P}
              x2={W - P}
              y1={y(g * step)}
              y2={y(g * step)}
              stroke="var(--border)"
              strokeWidth={1}
            />
            <text
              x={P - 8}
              y={y(g * step) + 4}
              textAnchor="end"
              fontSize={11}
              fill="var(--text-secondary)"
            >
              {g * step}
            </text>
          </g>
        ))}

        {series.map((s, seriesIndex) => {
          const colour = categorical(seriesIndex)
          /*
            Null breaks the line rather than being bridged: a month with no
            go-lives is not a boarding that took no time, and a line drawn
            through it would assert one.
          */
          const segments: string[][] = []
          s.values.forEach((v, i) => {
            if (v === null) {
              segments.push([])
              return
            }
            if (segments.length === 0) segments.push([])
            segments[segments.length - 1].push(`${x(i)},${y(v)}`)
          })
          const firstIdx = s.values.findIndex((v) => v !== null)
          const lastIdx = s.values.length - 1 - [...s.values].reverse().findIndex((v) => v !== null)

          return (
            <g key={s.column.key}>
              {segments
                .filter((points) => points.length > 1)
                .map((points, i) => (
                  <polyline
                    key={i}
                    points={points.join(' ')}
                    fill="none"
                    stroke={colour}
                    strokeWidth={2}
                  />
                ))}
              {s.values.map((v, i) =>
                v === null ? null : (
                  <circle
                    key={i}
                    cx={x(i)}
                    cy={y(v)}
                    r={i === lastIdx ? 5 : 4}
                    fill={i === lastIdx ? colour : 'var(--bg-surface)'}
                    stroke={colour}
                    strokeWidth={2}
                  />
                ),
              )}
              {s.values.map((v, i) =>
                v !== null && (i === firstIdx || i === lastIdx) ? (
                  <text
                    key={`label-${i}`}
                    x={x(i)}
                    y={y(v) - 10}
                    textAnchor="middle"
                    fontSize={11}
                    fontWeight={600}
                    fill="var(--text-primary)"
                  >
                    {formatValue(v, s.column.type)}
                  </text>
                ) : null,
              )}
            </g>
          )
        })}

        {labels.map((label, i) => (
          <text
            key={i}
            x={x(i)}
            y={H - P + 16}
            textAnchor="middle"
            fontSize={11}
            fill="var(--text-secondary)"
          >
            {label}
          </text>
        ))}
      </svg>

      {series.length > 1 && (
        <p className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-caption text-content-muted">
          {series.map((s, i) => (
            <span key={s.column.key} className="flex items-center gap-1.5">
              <span
                className="inline-block h-2.5 w-2.5 rounded-sm"
                style={{ background: categorical(i) }}
              />
              {s.column.label}
            </span>
          ))}
        </p>
      )}
    </div>
  )
}
