import { describe, expect, it } from 'vitest'
import type { TicketSummary } from '@/api/generated/model/ticketSummary'
import { groupTickets } from './groupTickets'

// Built with the local-time constructor and converted with `.toISOString()`,
// same as `mocks/db.ts`'s own `iso()` helper produces for every seeded
// ticket — a real instant, not a timezone-naive string. `groupTickets`
// compares *local calendar days*, so mixing a naive string in here (no
// offset, parsed as local by `parseISO`) against an explicit-UTC "today"
// would drift a day depending on the machine's own timezone — which is
// exactly the bug this file is guarding against, not just avoiding.
const TODAY = new Date(2026, 7, 10, 9, 0, 0) // 10 Aug 2026, local time

function localIso(year: number, month: number, day: number, hour = 12, minute = 0): string {
  return new Date(year, month, day, hour, minute, 0).toISOString()
}

function ticket(id: string, plannedCloseDate: string | null): TicketSummary {
  return {
    // A list row: GET /tickets returns flat ids, so id and projectId are
    // required and the code is `ticketCode`, not `ticketId`.
    id: 1,
    projectId: 1,
    ticketCode: id,
    title: id,
    level: 'MEDIUM',
    status: 'IN_PROGRESS',
    cycleNo: 1,
    plannedCloseDate: plannedCloseDate ?? undefined,
  }
}

describe('groupTickets — C-018', () => {
  it('buckets by calendar day against "today", most-urgent group first', () => {
    const tickets = [
      ticket('LATER-1', localIso(2026, 7, 20)),
      ticket('OVERDUE-1', localIso(2026, 7, 7)),
      ticket('TODAY-1', localIso(2026, 7, 10, 23)),
      ticket('WEEK-1', localIso(2026, 7, 16)),
    ]

    const groups = groupTickets(tickets, TODAY)

    expect(groups.map((g) => g.key)).toEqual(['overdue', 'dueToday', 'thisWeek', 'later'])
    expect(groups[0].tickets.map((t) => t.ticketCode)).toEqual(['OVERDUE-1'])
    expect(groups[1].tickets.map((t) => t.ticketCode)).toEqual(['TODAY-1'])
    expect(groups[2].tickets.map((t) => t.ticketCode)).toEqual(['WEEK-1'])
    expect(groups[3].tickets.map((t) => t.ticketCode)).toEqual(['LATER-1'])
  })

  it('a due date exactly 7 calendar days out is still This Week, 8 days out is Later', () => {
    const groups = groupTickets(
      [ticket('SEVEN', localIso(2026, 7, 17)), ticket('EIGHT', localIso(2026, 7, 18))],
      TODAY,
    )
    expect(groups.find((g) => g.key === 'thisWeek')?.tickets.map((t) => t.ticketCode)).toEqual(['SEVEN'])
    expect(groups.find((g) => g.key === 'later')?.tickets.map((t) => t.ticketCode)).toEqual(['EIGHT'])
  })

  it('a ticket with no planned close date lands in Later rather than being dropped', () => {
    const groups = groupTickets([ticket('NO-PCD', null)], TODAY)
    expect(groups.find((g) => g.key === 'later')?.tickets.map((t) => t.ticketCode)).toEqual(['NO-PCD'])
  })

  it('a due time later today is Due Today, not Overdue — calendar-day granularity, not exact timestamp', () => {
    const groups = groupTickets(
      [ticket('LATE-TODAY', localIso(2026, 7, 10, 23, 59))],
      new Date(2026, 7, 10, 22, 0, 0),
    )
    expect(groups.find((g) => g.key === 'dueToday')?.tickets.map((t) => t.ticketCode)).toEqual(['LATE-TODAY'])
  })
})
