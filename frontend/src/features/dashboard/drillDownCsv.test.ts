import { beforeEach, describe, expect, it, vi } from 'vitest'

import { buildDrillDownCsv, MAX_ROWS } from './drillDownCsv'

const listTickets = vi.fn()
vi.mock('@/api/generated/tickets/tickets', () => ({
  listTickets: (...args: unknown[]) => listTickets(...args),
}))

function ticket(id: number, overrides: Record<string, unknown> = {}) {
  return {
    ticketId: `CRM-26-${String(id).padStart(5, '0')}`,
    title: `Ticket ${id}`,
    level: 'HIGH',
    status: 'NEW',
    project: { name: 'Client CRM' },
    assignee: { displayName: 'Priya Nair' },
    createdAt: '2026-08-10T09:00:00Z',
    ...overrides,
  }
}

/** One page of `n` rows, with `hasMore` and a cursor when more follow. */
function page(rows: unknown[], hasMore = false) {
  return { data: rows, meta: { hasMore, nextCursor: hasMore ? 'next' : null } }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('buildDrillDownCsv', () => {
  it('exports the whole filtered set, not just the page the panel shows', async () => {
    listTickets
      .mockResolvedValueOnce(page(Array.from({ length: 200 }, (_, i) => ticket(i)), true))
      .mockResolvedValueOnce(page([ticket(200), ticket(201)], false))

    const csv = await buildDrillDownCsv({ level: 'HIGH' }, 'critical', '2026-08-17')

    expect(csv.rowCount).toBe(202)
    expect(csv.truncated).toBe(false)
    expect(listTickets).toHaveBeenCalledTimes(2)
    // The filter travels with every page, or page two is a different query.
    expect(listTickets).toHaveBeenLastCalledWith(
      expect.objectContaining({ level: 'HIGH', cursor: 'next' }),
    )
  })

  /**
   * The cap is real, so it has to be reported. A file short of the filter it
   * claims to represent is worse than no file — nobody re-checks a download.
   */
  it('reports truncation rather than silently returning a short file', async () => {
    listTickets.mockResolvedValue(page(Array.from({ length: 200 }, (_, i) => ticket(i)), true))

    const csv = await buildDrillDownCsv({}, 'everything', '2026-08-17')

    expect(csv.truncated).toBe(true)
    expect(csv.rowCount).toBe(MAX_ROWS)
  })

  it('stops when the server says there is no more, without asking again', async () => {
    listTickets.mockResolvedValueOnce(page([ticket(1)], false))

    const csv = await buildDrillDownCsv({}, 'one', '2026-08-17')

    expect(listTickets).toHaveBeenCalledTimes(1)
    expect(csv.rowCount).toBe(1)
  })

  /**
   * A ticket titled `=1+1` is executed as a formula when the file opens in
   * Excel or Sheets. Titles are user input, and via the client portal they
   * arrive from outside the organisation — so the export is an injection path
   * unless every field that could start a formula is neutralised.
   */
  it('neutralises spreadsheet formula injection in user-supplied text', async () => {
    listTickets.mockResolvedValueOnce(
      page([ticket(1, { title: '=1+1' }), ticket(2, { title: '@SUM(A1)' })], false),
    )

    const csv = await buildDrillDownCsv({}, 'x', '2026-08-17')

    expect(csv.content).toContain(`"'=1+1"`)
    expect(csv.content).toContain(`"'@SUM(A1)"`)
  })

  it('escapes quotes and commas per RFC 4180', async () => {
    listTickets.mockResolvedValueOnce(
      page([ticket(1, { title: 'Broken "login", again' })], false),
    )

    const csv = await buildDrillDownCsv({}, 'x', '2026-08-17')

    expect(csv.content).toContain('"Broken ""login"", again"')
  })

  it('leads with a BOM so Excel reads it as UTF-8', async () => {
    listTickets.mockResolvedValueOnce(page([ticket(1, { title: 'Priya Naïr' })], false))

    const csv = await buildDrillDownCsv({}, 'x', '2026-08-17')

    expect(csv.content.charCodeAt(0)).toBe(0xfeff)
  })

  it('names the file for what was exported and when', async () => {
    listTickets.mockResolvedValueOnce(page([ticket(1)], false))

    const csv = await buildDrillDownCsv({}, 'critical', '2026-08-17')

    expect(csv.filename).toBe('edutrack-critical-2026-08-17.csv')
  })

  /**
   * A server that kept returning the same cursor would loop for ever. The walk
   * is bounded by a `for`, so a paging bug ends as a short file rather than a
   * hung tab.
   */
  it('cannot loop for ever on a server that always claims more', async () => {
    listTickets.mockResolvedValue(page([ticket(1)], true))

    const csv = await buildDrillDownCsv({}, 'x', '2026-08-17')

    expect(listTickets.mock.calls.length).toBeLessThanOrEqual(MAX_ROWS / 200)
    expect(csv.rowCount).toBeGreaterThan(0)
  })
})
