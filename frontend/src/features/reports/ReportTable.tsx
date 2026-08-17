import type { ReportResponseDataColumnsItem } from '@/api/generated/model'

/**
 * A-063 · the table half of the viewer.
 *
 * <p>Generic over eighteen differently-shaped reports, because the response is:
 * columns describe themselves and rows are keyed objects. That is D-001's
 * decision and the reason A-064's exporter will be able to iterate columns
 * rather than special-case a report.
 *
 * <p>Formatting is driven by the column's declared type rather than by
 * inspecting the value. Guessing from the value is what makes a report where
 * every row happens to be zero render its numbers as strings, left-aligned,
 * on the one day that matters.
 */
export function ReportTable({
  columns,
  rows,
}: {
  columns: ReportResponseDataColumnsItem[]
  rows: Record<string, unknown>[]
}) {
  return (
    // Wide reports scroll inside their own container rather than making the
    // page scroll sideways — eighteen reports means some of them will be wide.
    <div className="overflow-x-auto rounded-control border border-border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="bg-subtle">
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={`whitespace-nowrap px-3 py-2 text-caption font-semibold uppercase tracking-wide text-content-muted ${alignFor(column.type)}`}
              >
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            // Index as key: report rows carry no stable identity of their own,
            // and the list is replaced wholesale on every filter change rather
            // than reordered in place, so there is nothing for a stable key to
            // preserve.
            <tr key={index} className="border-t border-border">
              {columns.map((column) => (
                <td
                  key={column.key}
                  className={`whitespace-nowrap px-3 py-2 text-content ${alignFor(column.type)}`}
                >
                  {format(row[column.key], column.type)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Numbers right, everything else left — the convention that makes a column of figures comparable down the page. */
function alignFor(type: ReportResponseDataColumnsItem['type']) {
  return type === 'number' || type === 'percent' || type === 'duration' ? 'text-right' : 'text-left'
}

function format(value: unknown, type: ReportResponseDataColumnsItem['type']): string {
  // Null is rendered as an em dash rather than as "null" or as an empty cell.
  // An empty cell reads as zero in a column of numbers, which is a different
  // claim from "not recorded".
  if (value === null || value === undefined) return '—'

  switch (type) {
    case 'percent':
      return `${value}%`
    case 'duration':
      // Hours, because that is what §4A.4 records effort in and what every
      // other duration on screen already says.
      return `${value}h`
    default:
      return String(value)
  }
}
