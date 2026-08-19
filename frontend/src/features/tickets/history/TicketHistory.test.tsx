import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { TicketDetailPage } from '../detail/TicketDetailPage'

/**
 * C-059 · the History tab on S-20, end to end through the mock server.
 *
 * `historyEntryText.test.ts` proves the pure sentence-building; this proves
 * the wiring that file cannot see — that the tab groups by cycle with the
 * latest one open, that `include=comments` actually reaches the request, and
 * that the append-only guarantee holds on screen: no row anywhere in this tab
 * offers an edit or a delete, on any cycle, with or without comments folded
 * in. `TicketComments.test.tsx`'s own note is the reason to check rather than
 * assume — a feature that unit-tests clean has been the one with nothing
 * wired to it before.
 */

const TICKET = 'CRM-26-00347'

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}
})

beforeEach(() => {
  getDb().currentUserId = 1
})

function renderPage(entry = `/tickets/${TICKET}?tab=history`) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/tickets/:ticketId" element={<TicketDetailPage />} />
          <Route path="/tickets" element={<p>Ticket list</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const waitForTicket = () =>
  screen.findByRole('heading', { name: /Checkout fails with 500/ }, { timeout: 4000 })

const cycleButton = (cycleNo: number) => screen.findByRole('button', { name: new RegExp(`Cycle ${cycleNo}\\b`) })

describe('the History tab', () => {
  it('groups entries by cycle, most recent cycle open and earlier ones collapsed', async () => {
    renderPage()
    await waitForTicket()

    expect(await cycleButton(2)).toHaveAttribute('aria-expanded', 'true')
    expect(await cycleButton(1)).toHaveAttribute('aria-expanded', 'false')
  })

  it('shows the SLA auto-escalation attributed to System, in the cycle it landed on', async () => {
    renderPage()
    await waitForTicket()

    const cycle2 = within(await screen.findByRole('list', { name: 'Cycle 2' }))
    expect(cycle2.getByText('Level changed HIGH → CRITICAL')).toBeInTheDocument()
    expect(cycle2.getByText(/System/)).toBeInTheDocument()
  })

  it('expands an earlier cycle on request and reads its rework entry', async () => {
    renderPage()
    await waitForTicket()

    fireEvent.click(await cycleButton(1))

    const cycle1 = within(await screen.findByRole('list', { name: 'Cycle 1' }))
    expect(cycle1.getByText(/Sent back from QA/)).toBeInTheDocument()
    expect((await cycleButton(1))).toHaveAttribute('aria-expanded', 'true')
  })

  it('interleaves comments into the stream only once the toggle is switched on', async () => {
    renderPage()
    await waitForTicket()

    // "Fix is live" is stamped to cycle 2 (SIGNOFF), which is open by default —
    // so its absence here is the toggle's doing, not a collapsed group's.
    expect(screen.queryByText(/Fix is live/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('checkbox', { name: /Show comments/ }))

    expect(await screen.findByText(/Fix is live/)).toBeInTheDocument()
  })

  /**
   * CLAUDE.md's append-only rule, read off the rendered page rather than
   * assumed from the absence of a wired-up button: the History tab must not
   * grow one by accident the way C-028's delete once existed on the client
   * with nothing behind it.
   */
  it('offers no edit or delete affordance anywhere in the stream', async () => {
    renderPage()
    await waitForTicket()

    fireEvent.click(screen.getByRole('checkbox', { name: /Show comments/ }))
    fireEvent.click(await cycleButton(1))
    await screen.findByText(/Sent back from QA/)

    // Scoped to the History tab's own panel — the page has an unrelated
    // attachment delete button ("Remove <file>.png") that a page-wide query
    // would also match, which would fail the assertion for the wrong reason.
    const panel = within(screen.getByRole('tabpanel'))
    expect(panel.queryByRole('button', { name: /^Edit/i })).not.toBeInTheDocument()
    expect(panel.queryByRole('button', { name: /Remove|Delete/i })).not.toBeInTheDocument()
  })
})
