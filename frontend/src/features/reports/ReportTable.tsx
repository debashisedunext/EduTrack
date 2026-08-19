import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUp, Minus } from 'lucide-react'
import type { ReportEntityKind, ReportResponseDataColumnsItem } from '@/api/generated/model'
import {
  clientPath,
  projectPath,
  resourcePath,
  ticketPath,
} from '@/features/tickets/detail/entityLinks'

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
 *
 * <p>B-061 · a `trend` column renders an arrow rather than a signed integer,
 * which is what §7.8's scorecard line asks for. Driven by the type for the same
 * reason every other format is: the alternative is a `column.key === 'trend'`
 * branch, and the next report with a trend column would silently render `-3`.
 *
 * <p>B-060 · a cell links when its **column** says so. §7.8 gives the client
 * report a drill-in and gives four other reports one too eventually, and a
 * `reportKey === 'client-report'` branch here would be the second copy of the
 * server's vocabulary this feature already refuses to keep — the same argument
 * the catalogue makes for being served rather than hardcoded. The server names
 * an entity; the route comes from `entityLinks.ts`, which owns every path in
 * the product.
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
                  <Cell column={column} row={row} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * One cell: a link when the column declares a destination and the row carries
 * the id, plain text otherwise.
 *
 * <p>Both halves are checked. A column declaring `linkTo` whose row is missing
 * the id renders as text rather than as an anchor to `/clients/undefined` —
 * a dead link is harder to notice than a missing one, and it is the state a
 * runner produces by declaring the link and forgetting to put the id in the
 * row.
 */
function Cell({
  column,
  row,
}: {
  column: ReportResponseDataColumnsItem
  row: Record<string, unknown>
}) {
  // Before `format`, because a trend is a rendered thing rather than a string.
  // It never links: a delta names no entity, and `linkTo` is absent on it.
  if (column.type === 'trend') return <Trend value={row[column.key]} />

  const text = format(row[column.key], column.type)
  const href = hrefFor(column, row)

  if (!href) return <>{text}</>

  return (
    <Link
      to={href}
      className="text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      {text}
    </Link>
  )
}

/**
 * A change against the comparable preceding window, as a direction and a
 * magnitude.
 *
 * <p>**The direction is not coloured.** Up is not green. This same type will
 * carry a reopen-rate trend, where up is the bad one, and a renderer that
 * decided good from the sign would be wrong on half its uses — a report that
 * quietly congratulates somebody for a rising reopen rate is worse than one
 * that leaves the judgement to the reader. The arrow says which way; the column
 * heading says of what.
 *
 * <p>The magnitude is printed unsigned, because the arrow already carries the
 * sign — `↓ -3` reads as a double negative. The screen-reader text spells both
 * out, since the arrow is `aria-hidden` and a bare "3" would lose the half of
 * the value that matters.
 */
function Trend({ value }: { value: unknown }) {
  // Same em dash as every other type: nothing recorded is not a flat trend.
  // "No change" is a measurement — it says the previous window was measured and
  // matched — and a person with no previous window has not made one.
  if (value === null || value === undefined || value === '') return <>—</>

  const delta = Number(value)
  // A trend column carrying something non-numeric is a runner bug. Shown as-is
  // rather than swallowed into an em dash, which would hide it.
  if (!Number.isFinite(delta)) return <>{String(value)}</>

  const Icon = delta > 0 ? ArrowUp : delta < 0 ? ArrowDown : Minus
  const spoken =
    delta === 0
      ? 'unchanged from the previous period'
      : `${delta > 0 ? 'up' : 'down'} ${Math.abs(delta)} on the previous period`

  return (
    <span className="inline-flex items-center justify-end gap-1">
      <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden />
      <span aria-hidden>{Math.abs(delta)}</span>
      <span className="sr-only">{spoken}</span>
    </span>
  )
}

function hrefFor(
  column: ReportResponseDataColumnsItem,
  row: Record<string, unknown>,
): string | undefined {
  if (!column.linkTo || !column.linkIdKey) return undefined

  const id = row[column.linkIdKey]
  if (id === null || id === undefined || id === '') return undefined

  /*
    The mapping is exhaustive over ReportEntityKind, and deliberately a record
    rather than a switch with a default: a fifth kind added to the contract
    becomes a TypeScript error here rather than a cell that silently stops
    linking after the client regenerates.
  */
  const builders: Record<ReportEntityKind, (id: never) => string> = {
    CLIENT: clientPath as (id: never) => string,
    PROJECT: projectPath as (id: never) => string,
    RESOURCE: resourcePath as (id: never) => string,
    TICKET: ticketPath as (id: never) => string,
  }

  // Ticket ids are codes (`PRJ-000123`) and the other three are numbers, which
  // is why the id is read from the row untyped and handed to the builder as-is.
  return builders[column.linkTo](id as never)
}

/** Numbers right, everything else left — the convention that makes a column of figures comparable down the page. */
function alignFor(type: ReportResponseDataColumnsItem['type']) {
  return type === 'number' || type === 'percent' || type === 'duration' || type === 'trend'
    ? 'text-right'
    : 'text-left'
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
