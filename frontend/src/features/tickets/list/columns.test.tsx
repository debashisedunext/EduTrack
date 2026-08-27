import { describe, expect, it } from 'vitest'
import type { Ticket } from '@/api/generated/model/ticket'
import { render, screen } from '@testing-library/react'
import { COLUMNS, DEFAULT_VISIBLE_COLUMNS, rowCueClassName } from './columns'

/** Only `level` and `isDelayed` are exercised — the rest is boilerplate to satisfy the type. */
function ticket(overrides: Partial<Ticket>): Ticket {
  return {
    ticketId: 'CRM-26-00001',
    title: 'Fixture ticket',
    level: 'MEDIUM',
    status: 'NEW',
    cycleNo: 1,
    isDelayed: false,
    ...overrides,
  }
}

describe('C-016 row colour cue', () => {
  it('gives a critical ticket a soft red left border', () => {
    expect(rowCueClassName(ticket({ level: 'CRITICAL' }))).toBe('border-l-4 border-l-level-critical')
  })

  it('gives a delayed, non-critical ticket a soft amber left border', () => {
    expect(rowCueClassName(ticket({ level: 'HIGH', isDelayed: true }))).toBe('border-l-4 border-l-level-high')
  })

  it('prefers the critical cue when a ticket is both delayed and critical', () => {
    expect(rowCueClassName(ticket({ level: 'CRITICAL', isDelayed: true }))).toBe('border-l-4 border-l-level-critical')
  })

  it('gives an on-track, non-critical ticket no cue', () => {
    expect(rowCueClassName(ticket({ level: 'LOW', isDelayed: false }))).toBeUndefined()
  })
})

describe('D-063 the four dates asked for on 18 Aug', () => {
  const column = (key: string) => COLUMNS.find((c) => c.key === key)!

  const context = { taskTypeNames: new Map<number, string>() } as never

  it('offers created, planned close and actual close without opening the chooser', () => {
    // Three of the four are ticket-level fields and are visible by default; a
    // column nobody can find is not a column that was added. The fourth,
    // "actual start", is the cycle's start (`ticket_cycles.start_date`) and has
    // never been a field on the ticket — see D-063 in the backlog for why it
    // was dropped rather than derived.
    expect(DEFAULT_VISIBLE_COLUMNS).toContain('createdAt')
    expect(DEFAULT_VISIBLE_COLUMNS).toContain('plannedCloseDate')
    expect(DEFAULT_VISIBLE_COLUMNS).toContain('actualCloseDate')
  })

  it('renders the close date of a closed ticket', () => {
    render(
      <>{column('actualCloseDate').render(
        ticket({ status: 'CLOSED', actualCloseDate: '2026-08-11T09:30:00Z' }),
        context,
      )}</>,
    )

    expect(screen.getByText('11 Aug 2026')).toBeInTheDocument()
  })

  it('shows a dash for a reopened ticket, which is correct rather than missing data', () => {
    // Reopen nulls the ticket's actual close date because the ticket is open
    // again. Cycle 1's close is preserved on `ticket_cycles`, where C-053's
    // selector reads it. If this ever "gets fixed" to show the old date, the
    // row would claim a ticket is closed while it is being worked on.
    render(
      <>{column('actualCloseDate').render(
        ticket({ status: 'REOPENED', actualCloseDate: undefined }),
        context,
      )}</>,
    )

    expect(screen.getByText('—')).toBeInTheDocument()
  })
})

describe('assignee column shows the role alongside the name', () => {
  const column = COLUMNS.find((c) => c.key === 'assignee')!
  const context = { taskTypeNames: new Map<number, string>() } as never

  it('renders the assignee name with their role in brackets', () => {
    render(
      <>{column.render(
        ticket({ assignee: { id: 7, displayName: 'Farhan Sheikh', role: 'SUPPORT' } }),
        context,
      )}</>,
    )

    expect(screen.getByText('Farhan Sheikh')).toBeInTheDocument()
    expect(screen.getByText('(Support)')).toBeInTheDocument()
  })

  it('falls back to the name alone when the assignee has no role on the record', () => {
    render(
      <>{column.render(ticket({ assignee: { id: 7, displayName: 'Farhan Sheikh' } }), context)}</>,
    )

    expect(screen.getByText('Farhan Sheikh')).toBeInTheDocument()
    expect(screen.queryByText(/\(/)).not.toBeInTheDocument()
  })

  it('shows Unassigned when no assignee is set', () => {
    render(<>{column.render(ticket({ assignee: undefined }), context)}</>)

    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })
})
