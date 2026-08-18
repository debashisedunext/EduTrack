import { beforeAll, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { BulkReassignWizardPage } from './BulkReassignWizardPage'

/** Radix's popover primitives need measurement APIs jsdom does not implement — `TicketBulkActions.test.tsx`'s own setup. */
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

function renderWizard(initialPath = '/tickets/bulk-reassign') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/tickets/bulk-reassign" element={<BulkReassignWizardPage />} />
          <Route path="/tickets" element={<p>tickets list</p>} />
          <Route path="/masters/resources" element={<p>resource master</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Anita, the Admin (id 1) — one of the two roles this route accepts. */
function signInAsAdmin() {
  getDb().currentUserId = 1
}

/**
 * C-063 · S-24, the wizard blueprint §7.5 describes as "pick source resource
 * → select tickets → target resource → reason → confirm. Each move writes
 * its own history entry."
 */
describe('BulkReassignWizardPage', () => {
  it('hides the wizard from anyone who is not PM or Admin', async () => {
    getDb().currentUserId = 3 // Ravi, a Developer
    renderWizard()

    await waitFor(() =>
      expect(screen.getByText(/restricted to admin and pm/i)).toBeInTheDocument(),
    )
    expect(screen.queryByText(/who is leaving/i)).not.toBeInTheDocument()
  })

  it('opens on step 1 when nobody is preselected', async () => {
    signInAsAdmin()
    renderWizard()

    await waitFor(() => expect(screen.getByRole('heading', { name: /who is leaving\?/i })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /continue/i })).toBeDisabled()
  })

  /**
   * The heart of the B-014 handoff: `?fromUserId=` does not just fill in step
   * 1's dropdown, it skips the step entirely — `reassignHandoff.ts`'s own
   * words are "asking again is how a handoff becomes a fresh task".
   */
  it('skips straight to step 2 when the handoff names a source resource', async () => {
    signInAsAdmin()
    renderWizard('/tickets/bulk-reassign?fromUserId=3&returnTo=%2Fmasters%2Fresources')

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /ravi kumar.?s open tickets/i })).toBeInTheDocument(),
    )
    expect(screen.queryByRole('heading', { name: /who is leaving\?/i })).not.toBeInTheDocument()
  });

  /** The full round trip: source is preselected, every open ticket starts checked, and confirming reassigns them. */
  it('reassigns every preselected ticket to the chosen target for a stated reason', async () => {
    signInAsAdmin()
    renderWizard('/tickets/bulk-reassign?fromUserId=3&returnTo=%2Fmasters%2Fresources')

    // Step 2 — Ravi's open tickets, pre-checked.
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /ravi kumar.?s open tickets/i })).toBeInTheDocument(),
    )
    const continueStep2 = await waitFor(() => {
      const button = screen.getByRole('button', { name: /^continue with \d+ tickets?$/i })
      expect(button).toBeEnabled()
      return button
    });
    const ticketCountMatch = /^continue with (\d+) tickets?$/i.exec(continueStep2.textContent ?? '')
    const ticketCount = Number(ticketCountMatch?.[1]);
    expect(ticketCount).toBeGreaterThan(0);
    fireEvent.click(continueStep2)

    // Step 3 — target and reason.
    await waitFor(() => expect(screen.getByRole('heading', { name: /who receives/i })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /reassign to/i }))
    const listbox = await screen.findByRole('listbox')
    // Meera Iyer (PM, id 2) — not Ravi, who is excluded from his own target list.
    fireEvent.click(within(listbox).getByText('Meera Iyer'))
    fireEvent.change(screen.getByLabelText(/^reason$/i), {
      target: { value: 'Ravi is moving to another team next sprint.' },
    })
    const continueStep3 = screen.getByRole('button', { name: /^continue$/i })
    await waitFor(() => expect(continueStep3).toBeEnabled())
    fireEvent.click(continueStep3)

    // Step 4 — confirm.
    await waitFor(() => expect(screen.getByRole('heading', { name: /^confirm$/i })).toBeInTheDocument())
    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByText('Meera Iyer')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`^reassign ${ticketCount}$`, 'i') }))

    // The result.
    await waitFor(
      () => expect(screen.getByText(new RegExp(`${ticketCount} tickets? reassigned`, 'i'))).toBeInTheDocument(),
      { timeout: 4000 },
    )

    // And the tickets actually moved, in the mock's own store.
    const db = getDb()
    const stillRavis = db.tickets.filter((t) => t.assigneeId === 3 && t.status !== 'CLOSED')
    expect(stillRavis).toHaveLength(0)
  }, 10_000)
})
