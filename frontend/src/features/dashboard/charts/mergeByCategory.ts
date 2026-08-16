import type { WidgetSeries } from '../WidgetFrame'

/**
 * A-056 · the server's shape turned into the one recharts wants.
 *
 * The API returns series-of-points — `[{name: 'Created', points: [...]}, …]` —
 * because that is what describes a chart. Recharts wants the transpose: one row
 * per category with a column per series, `[{category: '2026-08-10', Created: 2,
 * Closed: 1}, …]`. Four charts need that transpose and doing it inline in each
 * would be four chances to get the missing-value case wrong.
 *
 * <h2>Absent stays absent</h2>
 *
 * A category a series has no point for is left **undefined**, not zero. This is
 * the whole reason the function is worth its own file. Recharts treats
 * undefined as a break in the line and zero as a value, so filling gaps with
 * zero would draw every unsummarised day as a plunge to the axis and back — on
 * every weekend and after every outage — and the shape is the entire content of
 * a trend chart. A-055's sparklines and `WidgetService`'s series both make the
 * same distinction, and this is where it survives the transpose.
 *
 * Category order follows first appearance across the series in order, so a
 * resource present in one series and absent from another still gets a row
 * rather than being dropped because the first series did not mention it.
 */
export type MergedRow = { category: string } & Record<string, number | string | undefined>

export function mergeByCategory(series: WidgetSeries[]): MergedRow[] {
  const rows = new Map<string, MergedRow>()

  for (const s of series) {
    for (const point of s.points) {
      let row = rows.get(point.x)
      if (!row) {
        row = { category: point.x }
        rows.set(point.x, row)
      }
      row[s.name] = point.y
    }
  }

  return [...rows.values()]
}
