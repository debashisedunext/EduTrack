import { Chip } from '@/components/ui/chip'
import type { ObReportResponseDataColumnsItem } from '@/api/generated/model'

/**
 * B-122 · the table half of OB-10's viewer.
 *
 * <h2>Generic over every report, because the response is</h2>
 *
 * Columns describe themselves and rows are keyed objects, so one table renders
 * a funnel, a scorecard and a pending list — and the export engine iterates the
 * same columns rather than special-casing a report.
 *
 * <h2>Formatting is driven by the declared type, never by the value</h2>
 *
 * Guessing from the value is what makes a report whose every row happens to be
 * zero render its numbers as left-aligned strings on the one day that matters.
 *
 * <h2>`duration` is working hours and is not re-derived here</h2>
 *
 * A-118 states it on the contract: every duration on this surface "has already
 * been through the calendar, so a client formatting it must not re-derive it
 * from two timestamps". So this appends `h` and does nothing else. Converting to
 * days would need the length of a working day, which is `WorkingCalendar`'s and
 * varies by org — a hardcoded eight here would be a second calendar constant
 * that drifts from the real one silently.
 */
export function ObReportTable({
  columns,
  rows,
}: {
  columns: ObReportResponseDataColumnsItem[]
  rows: Record<string, unknown>[]
}) {
  return (
    // Wide reports scroll inside their own container rather than making the
    // page scroll sideways — stuck-and-aging is ten columns.
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
            // Index as key: report rows carry no stable identity, and the list
            // is replaced wholesale on every filter change rather than
            // reordered in place, so there is nothing for a stable key to keep.
            <tr key={index} className="border-t border-border">
              {columns.map((column) => (
                <td
                  key={column.key}
                  className={`whitespace-nowrap px-3 py-2 text-content ${alignFor(column.type)}`}
                >
                  <Cell type={column.type} value={row[column.key]} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Cell({
  type,
  value,
}: {
  type: ObReportResponseDataColumnsItem['type']
  value: unknown
}) {
  if (type === 'rag') return <Rag value={value} />
  return <>{format(value, type)}</>
}

/**
 * A health chip.
 *
 * <p>Null is a real answer rather than a missing one — `ObRag` is "null where
 * there is nothing to colour", which today is any step whose `due_at` C-105's
 * clock has not yet set. It renders as an em dash, because a grey "unknown"
 * chip would look like a fourth health state.
 *
 * <p>The colour comes from the chip's own §12.1 variants, so a RED here is the
 * same red as a critical ticket. The word is inside the chip and not only in
 * the colour: red/green is the single most common colour-vision confusion, and
 * a chart legend elsewhere does not help somebody reading one table cell.
 */
function Rag({ value }: { value: unknown }) {
  if (value === null || value === undefined || value === '') return <>—</>

  const rag = String(value)
  const variant =
    rag === 'RED' ? 'danger' : rag === 'AMBER' ? 'warning' : rag === 'GREEN' ? 'success' : 'neutral'

  return <Chip variant={variant}>{rag.charAt(0) + rag.slice(1).toLowerCase()}</Chip>
}

/** Numbers right, everything else left — what makes a column of figures comparable down the page. */
function alignFor(type: ObReportResponseDataColumnsItem['type']) {
  return type === 'number' || type === 'percent' || type === 'duration' ? 'text-right' : 'text-left'
}

function format(value: unknown, type: ObReportResponseDataColumnsItem['type']): string {
  // An em dash, not "null" and not an empty cell. An empty cell reads as zero
  // in a column of numbers, which is a different claim from "not recorded" —
  // and on this screen the difference is "nobody was late" against "nothing
  // could be measured".
  if (value === null || value === undefined) return '—'

  switch (type) {
    case 'percent':
      return `${value}%`
    case 'duration':
      return `${value}h`
    default:
      return String(value)
  }
}
