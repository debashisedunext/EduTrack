import { beforeAll, afterEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { server } from '@/mocks/server'
import { MyTasksPage } from './MyTasksPage'

/** Radix's popover/dialog/select primitives need APIs jsdom does not implement. */
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
      <MemoryRouter initialEntries={['/my-tasks']}>
        <Routes>
          <Route path="/my-tasks" element={<MyTasksPage />} />
          <Route path="/tickets/:ticketId" element={<p>Ticket detail</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

let quickUpdateRequests: { method: string; url: URL }[] = []
beforeAll(() =>
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url)
    if (request.method === 'POST' && url.pathname.includes('/quick-update')) {
      quickUpdateRequests.push({ method: request.method, url })
    }
  }),
)
afterEach(() => {
  quickUpdateRequests = []
  // The List/Kanban toggle persists to localStorage, which jsdom keeps alive
  // across every test in this file — without this, whichever test switches
  // to Kanban leaks that choice into every test that runs after it.
  window.localStorage.clear()
})

async function waitForContent() {
  // The header's summary line goes from "…" nothing to a real count once the
  // list settles — a stand-in for "loading is done" that does not care
  // whether the fixture landed anyone in Overdue specifically.
  return waitFor(
    () => {
      expect(screen.getByRole('status')).not.toHaveTextContent('Nothing open right now.')
    },
    { timeout: 4000 },
  )
}

describe('S-18 My Tasks — C-018', () => {
  it('hard-scopes to the signed-in user and groups by due date, most urgent first', async () => {
    renderPage()
    await waitForContent()

    expect(screen.getByRole('heading', { name: /Your work, Ravi/ })).toBeInTheDocument()

    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    // Only groups that actually have tickets render, but whichever do must
    // stay in urgency order — Overdue and Due Today (if present) never come
    // after This Week or Later.
    const order = ['Overdue', 'Due Today', 'This Week', 'Later']
    const present = order.filter((label) => headings.includes(label))
    expect(headings.filter((h) => order.includes(h ?? ''))).toEqual(present)
    expect(present.length).toBeGreaterThan(0)
  })

  it('offers a Quick Update button on every row that opens the S-21 slide-over', async () => {
    renderPage()
    await waitForContent()

    const triggers = screen.getAllByRole('button', { name: /Quick update/ })
    expect(triggers.length).toBeGreaterThan(0)
    fireEvent.click(triggers[0])

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: /^[A-Z]+-\d{2}-\d{5,}$/ })).toBeInTheDocument()
    // The fields the contract explicitly forbids editing here.
    expect(
      within(dialog).getByText(/Not editable here: ticket ID, reported by, assigned by/),
    ).toBeInTheDocument()
  })

  it('Quick Update changes status with a single POST, closes on success, and the new status sticks', async () => {
    renderPage()
    await waitForContent()

    fireEvent.click(screen.getAllByRole('button', { name: /Quick update/ })[0])
    const dialog = await screen.findByRole('dialog')
    const ticketId = within(dialog)
      .getByRole('heading', { name: /^[A-Z]+-\d{2}-\d{5,}$/ })
      .textContent

    // Radix Select portals its listbox to `document.body`, not into the
    // dialog's own subtree — the option has to be queried globally.
    fireEvent.click(within(dialog).getByRole('combobox'))
    await waitFor(() => fireEvent.click(screen.getByRole('option', { name: 'On Hold' })), { timeout: 4000 })
    fireEvent.click(within(dialog).getByRole('button', { name: /Update/ }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 4000 })
    expect(quickUpdateRequests).toHaveLength(1)
    expect(quickUpdateRequests[0].method).toBe('POST')
    expect(quickUpdateRequests[0].url.pathname).toBe(`/api/v1/tickets/${ticketId}/quick-update`)

    // Round-trips through the real mock handler and back through the list
    // refetch — the Kanban board is where per-ticket status is visible.
    fireEvent.click(screen.getByRole('button', { name: /^Kanban$/ }))
    const onHoldColumn = await screen.findByRole('group', { name: 'On Hold' })
    await waitFor(() => expect(within(onHoldColumn).getByText(ticketId!)).toBeInTheDocument(), { timeout: 4000 })
  })

  it('requires a reason once a revised ETA is entered', async () => {
    renderPage()
    await waitForContent()

    fireEvent.click(screen.getAllByRole('button', { name: /Quick update/ })[0])
    const dialog = await screen.findByRole('dialog')

    fireEvent.change(within(dialog).getByLabelText('Revised ETA'), { target: { value: '2026-09-01' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /Update/ }))

    expect(await within(dialog).findByText(/required once you change it/)).toBeInTheDocument()
    expect(quickUpdateRequests).toHaveLength(0)
  })

  it('switches to the Kanban board, grouped by status instead of due date', async () => {
    renderPage()
    await waitForContent()

    fireEvent.click(screen.getByRole('button', { name: /^Kanban$/ }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Overdue' })).not.toBeInTheDocument()
    })
    // Every status column renders even if empty — New is always first.
    expect(screen.getByRole('group', { name: 'New' })).toBeInTheDocument()
  })
})
