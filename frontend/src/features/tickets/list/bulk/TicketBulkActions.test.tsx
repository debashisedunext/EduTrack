import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { TicketListPage } from '../TicketListPage'

/** Radix's popover primitives need measurement and pointer-capture APIs jsdom does not implement. */
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

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/tickets']}>
        <Routes>
          <Route path="/tickets" element={<TicketListPage />} />
          <Route path="/tickets/:ticketId" element={<p>detail</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * Sign in as Anita, the Admin (`currentUserId = 1`).
 *
 * The mock's default is Ravi, a Developer, which is deliberate elsewhere —
 * `scopedTickets` narrows to his own rows and the scoping is visible by
 * default. Here it is the wrong seat: a Developer sees no selection column at
 * all, which is what one of these tests asserts and what the rest need to get
 * past. `timeout: 4000` on the waits for the reason the sibling list suite
 * documents — this file runs alongside nine others under one CPU-bound run.
 */
function signInAsAdmin() {
  getDb().currentUserId = 1
}

async function selectAllOnPage() {
  const header = await waitFor(
    () => screen.getByRole('checkbox', { name: /select all tickets on this page/i }),
    { timeout: 4000 },
  )
  await waitFor(() => expect(header).toBeEnabled(), { timeout: 4000 })
  fireEvent.click(header)
  return header
}

function bulkBar() {
  return screen.getByRole('region', { name: /bulk ticket actions/i })
}

/**
 * The rows page 1 will actually show, ordered the way the handler orders them.
 *
 * Deliberately mirrors the mock's own sort (`-createdAt`, string-compared)
 * rather than reaching for `db.tickets[0]`. Insertion order is not display
 * order — the seed builds three separate batches — so a test that pre-closed
 * `tickets[0]` was closing a ticket on page 2 and then asserting about a bar
 * that had never seen it.
 */
function firstPageTickets(limit = 25) {
  return [...getDb().tickets]
    .sort((a, b) => String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? '')))
    .slice(0, limit)
}

describe('C-017 · bulk select on S-17', () => {
  beforeEach(signInAsAdmin)

  it('offers no selection column at all to a Developer', async () => {
    // Ravi, the mock's default — one of the four roles §7.5 leaves out. The
    // server refuses him too (the handlers return 403), but the point of this
    // assertion is that he is never offered the button in the first place.
    getDb().currentUserId = 3
    renderPage()

    await waitFor(() => expect(screen.getAllByRole('row').length).toBeGreaterThan(1), {
      timeout: 4000,
    })
    expect(screen.queryByRole('checkbox', { name: /select all tickets/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /bulk ticket actions/i })).not.toBeInTheDocument()
  })

  it('shows the bar with a count once rows are ticked, and clears it again', async () => {
    renderPage()
    const header = await selectAllOnPage()

    const bar = await waitFor(() => bulkBar(), { timeout: 4000 })
    expect(within(bar).getByText(/\d+ selected/)).toBeInTheDocument()
    expect(within(bar).getByRole('button', { name: /^Reassign$/ })).toBeEnabled()
    expect(within(bar).getByRole('button', { name: /^Change level$/ })).toBeEnabled()

    fireEvent.click(within(bar).getByRole('button', { name: /clear selection/i }))
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: /bulk ticket actions/i })).not.toBeInTheDocument(),
    )
    expect(header).not.toBeChecked()
  })

  it('refuses to submit a level change without a reason, then applies one', async () => {
    const originalLevels = new Map(getDb().tickets.map((t) => [t.ticketId, t.originalLevel]))

    renderPage()
    await selectAllOnPage()

    fireEvent.click(within(await waitFor(bulkBar)).getByRole('button', { name: /^Change level$/ }))

    const dialog = await screen.findByRole('dialog')
    const confirm = within(dialog).getByRole('button', { name: /^Set level$/ })
    // Nothing chosen and nothing typed: the confirm is the rule, restated.
    expect(confirm).toBeDisabled()

    fireEvent.click(within(dialog).getByRole('radio', { name: 'Critical' }))
    // A level with no reason is still refused — the reason is the only record
    // of why a batch moved, and the endpoint requires it unconditionally.
    expect(within(dialog).getByRole('button', { name: /^Set Critical$/ })).toBeDisabled()

    fireEvent.change(within(dialog).getByLabelText(/^Reason$/), {
      target: { value: 'Client escalation ahead of go-live' },
    })
    const ready = within(dialog).getByRole('button', { name: /^Set Critical$/ })
    await waitFor(() => expect(ready).toBeEnabled())
    fireEvent.click(ready)

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), {
      timeout: 4000,
    })

    // Every affected ticket carries its own LEVEL_CHANGED entry — one summary
    // row for the batch would destroy the per-ticket audit trail.
    //
    // Matched on the note, not on the action alone: the seed already carries
    // LEVEL_CHANGED rows from the SLA scanner, so filtering by action swept
    // those in and asserted this batch's reason against theirs.
    const db = getDb()
    const entries = db.history.filter(
      (h) => h.action === 'LEVEL_CHANGED' && h.note === 'Client escalation ahead of go-live',
    )
    expect(entries.length).toBeGreaterThan(0)
    expect(new Set(entries.map((h) => h.ticketId)).size).toBe(entries.length)
    for (const entry of entries) {
      expect(entry.newValue).toBe('CRITICAL')
    }

    // `originalLevel` survives — it is what makes "born critical" reportable,
    // and a bulk path that overwrote it would erase that fifty rows at a time.
    for (const entry of entries) {
      const ticket = db.tickets.find((t) => t.ticketId === entry.ticketId)!
      expect(ticket.level).toBe('CRITICAL')
      expect(ticket.originalLevel).toBe(originalLevels.get(ticket.ticketId))
    }
  })

  it('excludes tickets that are already closed from a bulk close, and says so', async () => {
    const db = getDb()
    // One *more* row on the first page pre-closed, so the selection genuinely
    // spans both states. Counted rather than assumed to be the only one: the
    // seed already closes some tickets, and a hard-coded 1 was asserting about
    // a fixture rather than about the feature.
    const page = firstPageTickets()
    const alreadyClosed = page.filter((t) => t.status === 'CLOSED').length
    const closed = page.find((t) => t.status !== 'CLOSED')!
    closed.status = 'CLOSED'
    closed.actualCloseDate = new Date().toISOString()
    const expectedClosed = alreadyClosed + 1

    renderPage()
    await selectAllOnPage()

    const bar = await waitFor(bulkBar)
    expect(within(bar).getByText(`${expectedClosed} already closed`)).toBeInTheDocument()

    fireEvent.click(within(bar).getByRole('button', { name: /^Close$/ }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/already closed and will be left alone/)).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText(/resolution summary/i), {
      target: { value: 'Batch signed off by the client' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: /^Close \d+$/ }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), {
      timeout: 4000,
    })

    // The pre-closed ticket was never sent, so it gained no second CLOSED entry
    // and its close date was not re-stamped — re-sealing a sealed transition is
    // exactly the mutation the append-only rule forbids.
    const closedEntries = db.history.filter(
      (h) => h.action === 'CLOSED' && h.ticketId === closed.ticketId,
    )
    expect(closedEntries).toHaveLength(0)
  })

  it('keeps refused tickets selected and names them', async () => {
    // A ticket the server refuses but the client cannot have filtered out:
    // closed *after* the grid rendered, which is the real race the per-ticket
    // result exists to report.
    renderPage()
    await selectAllOnPage()

    const bar = await waitFor(bulkBar)
    const page = firstPageTickets()
    // The rows the client already excludes — never sent, so they survive the
    // action untouched and stay ticked alongside the refusal.
    const excludedByClient = page.filter((t) => t.status === 'CLOSED').length
    const target = page.find((t) => t.status !== 'CLOSED')!
    target.status = 'CLOSED'

    fireEvent.click(within(bar).getByRole('button', { name: /^Close$/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/resolution summary/i), {
      target: { value: 'Batch signed off by the client' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: /^Close \d+$/ }))

    expect(await screen.findByText(/1 refused/, undefined, { timeout: 4000 })).toBeInTheDocument()
    // Scoped to the dialog: the id is also on its own row in the grid behind it.
    const resultDialog = screen.getByRole('dialog')
    expect(within(resultDialog).getByText('Already closed')).toBeInTheDocument()
    expect(within(resultDialog).getByText(target.ticketId)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^Done$/ }))

    // Still ticked, because there is something left to do about it — the one
    // the server refused, plus whatever the client had already held back.
    const stillSelected = excludedByClient + 1
    await waitFor(
      () => expect(within(bulkBar()).getByText(`${stillSelected} selected`)).toBeInTheDocument(),
      { timeout: 4000 },
    )
  })
})
