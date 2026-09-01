import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { GroupedTicketAccordion, type WeeklyTicketRow } from './GroupedTicketAccordion'

/**
 * S-05 tab 3, PR 13b · the nested accordion.
 *
 * `groupTickets` already has its own tests for the nesting and the counts.
 * What is asserted here is the thing only the component can get wrong: that
 * the header prints the **true** count rather than the number of rows it drew,
 * and that the gap is stated when the cap bites. A header reading the rendered
 * count is the version nobody reports — they just stop trusting the screen.
 */

const SEVERITY = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const
const MODULES = new Map([
  [10, 'Billing'],
  [20, 'Reports'],
])

function ticket(over: Partial<WeeklyTicketRow> & { ticketId: string }): WeeklyTicketRow {
  return {
    title: `Ticket ${over.ticketId}`,
    level: 'HIGH',
    client: { id: 1, name: 'Acme Retail' },
    moduleId: 10,
    ...over,
  }
}

function renderAccordion(tickets: WeeklyTicketRow[]) {
  return render(
    <MemoryRouter>
      <GroupedTicketAccordion
        tickets={tickets}
        severityOrder={SEVERITY}
        moduleLabel={(id) => MODULES.get(id)}
      />
    </MemoryRouter>,
  )
}

describe('GroupedTicketAccordion', () => {
  it('nests client → module → severity and rolls the count up every level', async () => {
    renderAccordion([
      ticket({ ticketId: 'A-1', level: 'CRITICAL' }),
      ticket({ ticketId: 'A-2', level: 'HIGH' }),
      ticket({ ticketId: 'A-3', level: 'HIGH', moduleId: 20 }),
    ])

    const client = screen.getByRole('button', { name: /Acme Retail/ })
    expect(within(client).getByText('3')).toBeInTheDocument()

    // Billing holds two of the three; Reports the other.
    const billing = screen.getByRole('button', { name: /Billing/ })
    expect(within(billing).getByText('2')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /Reports/ })).getByText('1')).toBeInTheDocument()
  })

  it('opens client and module by default and leaves severity folded', () => {
    renderAccordion([ticket({ ticketId: 'A-1', level: 'CRITICAL' })])

    expect(screen.getByRole('button', { name: /Acme Retail/ })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(screen.getByRole('button', { name: /Billing/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: /CRITICAL/ })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('reveals the ticket rows when a severity header is expanded', async () => {
    renderAccordion([ticket({ ticketId: 'A-1', level: 'CRITICAL', title: 'Coupon dead on mobile' })])

    expect(screen.queryByText('Coupon dead on mobile')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /CRITICAL/ }))

    const row = screen.getByRole('link', { name: /Coupon dead on mobile/ })
    expect(row).toHaveAttribute('href', '/tickets/A-1')
  })

  /**
   * The property the plan calls out by name. Past the cap the rendered rows
   * and the true count diverge, and the header must keep showing the latter.
   */
  it('prints the true count, not the rendered one, once the cap bites', () => {
    const many = Array.from({ length: 230 }, (_, i) =>
      ticket({ ticketId: `A-${i}`, level: 'HIGH' }),
    )
    renderAccordion(many)

    const client = screen.getByRole('button', { name: /Acme Retail/ })
    expect(within(client).getByText('230')).toBeInTheDocument()
    expect(screen.getByText(/Showing 200 of 230 tickets/)).toBeInTheDocument()
  })

  it('says nothing at all when there are no tickets, leaving the empty state to the section', () => {
    const { container } = renderAccordion([])
    expect(container).toBeEmptyDOMElement()
  })

  it('labels the missing-value buckets rather than drawing a blank header', () => {
    renderAccordion([ticket({ ticketId: 'A-1', client: null, moduleId: null })])

    expect(screen.getByRole('button', { name: /No client/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /No module/ })).toBeInTheDocument()
  })

  it('every header is a keyboard-operable control', async () => {
    renderAccordion([ticket({ ticketId: 'A-1', level: 'CRITICAL' })])

    const severity = screen.getByRole('button', { name: /CRITICAL/ })
    severity.focus()
    expect(severity).toHaveFocus()
    await userEvent.keyboard('{Enter}')

    expect(severity).toHaveAttribute('aria-expanded', 'true')
  })
})
