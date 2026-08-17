import { listTickets } from '@/api/generated/tickets/tickets'
import type { ListTicketsParams, Ticket } from '@/api/generated/model'

/**
 * A-061 · the modal's CSV export.
 *
 * <h2>It exports the whole filtered set, not the page on screen</h2>
 *
 * The panel shows one page. Exporting only that while the header says how many
 * matched would be the same class of lie A-060 spent a task removing — a
 * control that appears to act on the figure shown and quietly acts on a subset.
 * So this pages through the cursor until the server says there is no more.
 *
 * <h2>The cap is real and is reported</h2>
 *
 * Paging is bounded. An unbounded loop against a filter matching fifty thousand
 * tickets would hold the browser for minutes and produce a file nobody asked
 * for, so it stops at {@link MAX_ROWS} — and **says so in the result**, which
 * the caller surfaces. A silent truncation reads as "this is everything", and
 * a partial export that claims to be complete is worse than no export.
 *
 * <p>A-064 builds the real export engine, server-side, for the reports hub.
 * This is deliberately not that: it is the grid in front of you, as a file,
 * and it should be replaced rather than grown when A-064 lands.
 */

/** The server's own per-page ceiling (`PageLimit.MAX`). */
const PAGE_SIZE = 200

/** Ten pages. Past this the answer is a report, not a drill-down. */
export const MAX_ROWS = 2000

export interface CsvExport {
  filename: string
  content: string
  rowCount: number
  /** True when the cap stopped the walk before the server ran out of rows. */
  truncated: boolean
}

const COLUMNS: { header: string; value: (t: Ticket) => string }[] = [
  { header: 'Ticket', value: (t) => t.ticketId ?? '' },
  { header: 'Title', value: (t) => t.title ?? '' },
  { header: 'Project', value: (t) => t.project?.name ?? '' },
  { header: 'Client', value: (t) => t.client?.name ?? '' },
  { header: 'Level', value: (t) => t.level ?? '' },
  { header: 'Status', value: (t) => t.status ?? '' },
  { header: 'Assignee', value: (t) => t.assignee?.displayName ?? '' },
  // `createdAt`, not a `dateReported` — the wire model has no such field, and
  // the column it corresponds to (`tickets.date_reported`) is surfaced under
  // this name. Worth the note because the dashboard's own filter *is* called
  // `reportedFrom`, and the two looking unrelated is exactly how somebody
  // "corrects" this back to a field that does not exist.
  { header: 'Reported', value: (t) => t.createdAt ?? '' },
  { header: 'Planned close', value: (t) => t.plannedCloseDate ?? '' },
]

/**
 * Quotes a field for RFC 4180.
 *
 * <p>The leading apostrophe on `=`, `+`, `-` and `@` is not decoration: a cell
 * beginning with one of those is executed as a formula by Excel and Sheets when
 * the file is opened, so a ticket titled `=1+1` becomes a spreadsheet
 * injection. Ticket titles are user input arriving from anyone who can raise a
 * ticket, including — via the client portal — people outside the organisation.
 */
function csvField(raw: string): string {
  const value = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw
  return `"${value.replace(/"/g, '""')}"`
}

function toCsv(rows: Ticket[]): string {
  const header = COLUMNS.map((c) => csvField(c.header)).join(',')
  const body = rows.map((row) => COLUMNS.map((c) => csvField(c.value(row))).join(','))
  // CRLF per RFC 4180, and a BOM so Excel reads the file as UTF-8 rather than
  // as the local codepage — without it a name like "Priya Naïr" arrives mangled.
  //
  // Written as an escape rather than as the character itself: a literal BOM in
  // source is invisible, survives copy-paste into places it should not, and
  // eslint's no-irregular-whitespace rejects it outright — correctly, since a
  // reader cannot tell it apart from nothing at all.
  return `\uFEFF${[header, ...body].join('\r\n')}\r\n`
}

/**
 * Walks the cursor until the filter is exhausted or {@link MAX_ROWS} is reached.
 *
 * @param label a short slug for the filename, e.g. the widget or card key.
 */
export async function buildDrillDownCsv(
  params: ListTicketsParams,
  label: string,
  today: string,
): Promise<CsvExport> {
  const rows: Ticket[] = []
  let cursor: string | undefined
  let truncated = false

  // A `for` bound rather than `while (true)`: a server that kept returning the
  // same cursor would otherwise loop for ever, and a bug in paging should end
  // as a short file rather than as a hung tab.
  for (let page = 0; page < Math.ceil(MAX_ROWS / PAGE_SIZE); page++) {
    const response = await listTickets({ ...params, limit: PAGE_SIZE, cursor })
    rows.push(...(response.data ?? []))

    const next = response.meta?.nextCursor
    if (!response.meta?.hasMore || !next) break
    if (rows.length >= MAX_ROWS) {
      truncated = true
      break
    }
    cursor = next
  }

  return {
    filename: `edutrack-${label}-${today}.csv`,
    content: toCsv(rows.slice(0, MAX_ROWS)),
    rowCount: Math.min(rows.length, MAX_ROWS),
    truncated,
  }
}
