import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { TicketDetailPage } from './TicketDetailPage'

const TICKET = 'CRM-26-00347'

/** Radix's dialog/select primitives (the Quick Update trigger) need APIs jsdom lacks. */
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

let detailRequests: URL[] = []
beforeAll(() =>
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url)
    if (url.pathname.endsWith('/full')) detailRequests.push(url)
  }),
)
afterEach(() => {
  detailRequests = []
})

function ShowLocation() {
  const location = useLocation()
  // A plain span, not `<output>` — that maps to role `status`, which is the
  // role the sealed-cycle banner uses, and the probe would then be a second
  // match for the assertion it exists to support.
  return <span data-testid="location">{`${location.pathname}${location.search}`}</span>
}

/**
 * The §14 walkthrough ticket is assigned to Meera, and the mock's default
 * signed-in user is Ravi — a Developer, whose row scope is `assigned_to = me`.
 * Signing in as the Admin is how the fixture becomes visible; the last test in
 * this file deliberately does *not*, because Ravi getting a 404 for it is the
 * scope guard working.
 */
function signInAsAdmin() {
  getDb().currentUserId = 1
}

function renderPage(entry = `/tickets/${TICKET}`) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <ShowLocation />
        <Routes>
          <Route path="/tickets/:ticketId" element={<TicketDetailPage />} />
          <Route path="/tickets" element={<p>Ticket list</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const summary = () => screen.getByRole('complementary', { name: 'Summary' })

const waitForTicket = () =>
  screen.findByRole('heading', { name: /Checkout fails with 500/ }, { timeout: 4000 })

describe('S-20 Ticket detail shell — C-019', () => {
  it('renders the header from one aggregated call: id, level, status, delay and cycle', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const header = screen.getByRole('banner')
    expect(within(header).getByText(TICKET, { selector: 'span' })).toBeInTheDocument()
    expect(within(header).getByText('CRITICAL')).toBeInTheDocument()
    expect(within(header).getByText('Closed')).toBeInTheDocument()
    expect(within(header).getByText(/Delayed/)).toBeInTheDocument()
    expect(within(header).getByText('Cycle 2')).toBeInTheDocument()

    // One request for the whole page — the waterfall is what makes a detail
    // page feel slow, and `/full` exists so there is not one.
    expect(detailRequests).toHaveLength(1)
  })

  it('renders header buttons only for the actions the server said this caller may take', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const header = screen.getByRole('banner')
    // `availableActions` is resolved server-side; the client never re-derives it.
    expect(within(header).getByRole('button', { name: 'Close' })).toBeInTheDocument()
    expect(within(header).getByRole('button', { name: /Quick update/ })).toBeEnabled()

    // C-039 · unlike Close (still C-040's placeholder), Reopen is wired —
    // real button, opening the real dialog rather than the honest-refusal one.
    const reopenButton = within(header).getByRole('button', { name: 'Reopen' })
    expect(reopenButton).toBeEnabled()
    await userEvent.setup().click(reopenButton)
    expect(await screen.findByRole('dialog', { name: /reopen crm-26-00347/i })).toBeInTheDocument()
  })

  /**
   * C-040 · proves the header wires the real dialog rather than the
   * honestly-disabled placeholder — clicking it must open a dialog, not do
   * nothing, and a successful close must refetch the aggregated payload
   * rather than patch the cache, on `onLevelChanged`'s own precedent.
   */
  it('opens the real close dialog and refetches on a successful close', async () => {
    server.use(
      http.post('*/tickets/:ticketId/close', () =>
        HttpResponse.json({ data: { ticketId: TICKET, status: 'CLOSED' } }),
      ),
    )
    signInAsAdmin()
    renderPage()
    await waitForTicket()
    const user = userEvent.setup()

    await user.click(within(screen.getByRole('banner')).getByRole('button', { name: 'Close' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/seals cycle/i)).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText(/resolution summary/i), 'Fixed and deployed.')
    await user.click(within(dialog).getByRole('button', { name: 'Close ticket' }))

    await waitFor(() => expect(detailRequests.length).toBeGreaterThan(1))
  })

  it('makes every entity in the summary panel a link to its own screen', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const panel = summary()
    expect(within(panel).getByRole('link', { name: /Client CRM Platform/ })).toHaveAttribute('href', '/projects/1')
    expect(within(panel).getByRole('link', { name: 'Acme Retail Ltd' })).toHaveAttribute('href', '/clients/1')
    // Meera is both the assignee and a watcher, so there is more than one.
    for (const link of within(panel).getAllByRole('link', { name: 'Meera Iyer' })) {
      expect(link).toHaveAttribute('href', '/resources/2')
    }
    expect(within(panel).getByRole('link', { name: 'Priya Nair' })).toHaveAttribute('href', '/resources/6')
    expect(within(panel).getByRole('link', { name: 'Anil Shah' })).toHaveAttribute('href', '/resources/4')
  })

  it('resolves the ids the payload does not embed — task type and client contact', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const panel = summary()
    expect(await within(panel).findByText('Production Bug')).toBeInTheDocument()
    expect(await within(panel).findByText('Sara Kapoor')).toBeInTheDocument()
  })

  it('shows the escalated level beside the original, which is never overwritten', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const panel = summary()
    expect(within(panel).getByText('CRITICAL')).toBeInTheDocument()
    expect(within(panel).getByText('was HIGH')).toBeInTheDocument()
  })

  it('totals effort across every cycle and compares it to the estimate', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const panel = summary()
    // Walkthrough A: 24.5 h in cycle 1 plus 13.5 h in cycle 2, against a 16 h estimate.
    expect(within(panel).getAllByText('38.0 h').length).toBeGreaterThan(0)
    expect(within(panel).getByText('+138% vs estimate')).toBeInTheDocument()
    expect(within(panel).getByText('24.5 h')).toBeInTheDocument()
    expect(within(panel).getByText('13.5 h')).toBeInTheDocument()
  })

  it('links each cycle to its own effort logs, carrying the cycle as well as the tab', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const panel = summary()
    expect(within(panel).getByRole('link', { name: /Cycle 1/ })).toHaveAttribute(
      'href',
      `/tickets/${TICKET}?cycle=1&tab=effort`,
    )
    expect(within(panel).getByRole('link', { name: /Cycle 2/ })).toHaveAttribute(
      'href',
      `/tickets/${TICKET}?cycle=2&tab=effort`,
    )
  })

  it('refetches for an earlier cycle and says plainly that it is sealed', async () => {
    signInAsAdmin()
    renderPage(`/tickets/${TICKET}?cycle=1`)
    await waitForTicket()

    await waitFor(() => expect(detailRequests.at(-1)?.searchParams.get('cycle')).toBe('1'))
    expect(screen.getByRole('status')).toHaveTextContent(/cycle 1, which is sealed and read-only/)
  })

  it('does not claim a cycle is sealed when the current one is selected', async () => {
    signInAsAdmin()
    renderPage(`/tickets/${TICKET}?cycle=2`)
    await waitForTicket()

    expect(screen.queryByText(/sealed and read-only/)).not.toBeInTheDocument()
  })

  it('drives the tab strip from the URL, so a tab is a link that can be pasted', async () => {
    signInAsAdmin()
    renderPage(`/tickets/${TICKET}?tab=history`)
    await waitForTicket()

    const tabs = screen.getByRole('tablist', { name: 'Ticket detail' })
    expect(within(tabs).getByRole('tab', { name: 'History' })).toHaveAttribute('aria-selected', 'true')
    // C-059 · the real History tab, not a PendingSection placeholder — the
    // toggle renders synchronously, before the history fetch resolves.
    expect(screen.getByRole('tabpanel')).toHaveTextContent(/show comments in this stream/i)

    fireEvent.click(within(tabs).getByRole('tab', { name: 'Effort' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('tab=effort'))
    expect(await within(screen.getByRole('tabpanel')).findByText(/Grand total/i)).toBeInTheDocument()
  })

  /**
   * C-061 · proves the Effort tab renders the real cycle-grouped log rather
   * than the `PendingSection` placeholder every other still-open tab shows.
   * The §14 walkthrough fixture's hops reconcile to 38.0 h total — 24.5 h in
   * cycle 1 (including the QA rework's extra `DEVELOPMENT`/`QA` pass) and
   * 13.5 h in cycle 2 — the same figure `STREAM-C-TICKETS.md`'s exit
   * criterion names.
   */
  it('renders the real Effort tab, grouped by cycle with per-cycle and grand totals, not the pending placeholder', async () => {
    signInAsAdmin()
    renderPage(`/tickets/${TICKET}?tab=effort`)
    await waitForTicket()

    const panel = screen.getByRole('tabpanel')
    expect(panel).not.toHaveTextContent(/C-061/)
    expect(await within(panel).findByText(/Grand total/i)).toHaveTextContent('38.0 h')

    // Cycle 2 is the latest and starts open, its own total visible on the group header.
    const cycle2 = within(panel).getByRole('button', { name: /Cycle 2/ })
    expect(cycle2).toHaveAttribute('aria-expanded', 'true')
    expect(cycle2).toHaveTextContent('13.5 h')
    expect(within(panel).getByRole('columnheader', { name: 'Resource' })).toBeInTheDocument()

    // Cycle 1 starts collapsed; expanding it reaches its own total and rows.
    const cycle1 = within(panel).getByRole('button', { name: /Cycle 1/ })
    expect(cycle1).toHaveAttribute('aria-expanded', 'false')
    expect(cycle1).toHaveTextContent('24.5 h')
    fireEvent.click(cycle1)
    expect(within(panel).getAllByText('Ravi Kumar').length).toBeGreaterThan(0)
  })

  /**
   * C-060 · proves the Attachments tab renders the real gallery rather than
   * the `PendingSection` placeholder every other still-open tab shows.
   * `db.ts`'s own fixture note explains the split: `gateway-500.png` is the
   * one hand-seeded row (cycle 1, Intake), and `attachmentsFor` generates five
   * more onto the ticket's current cycle and stage (cycle 2, Closed) so the
   * gallery, the lightbox and the "Scanning" placeholder all have something to
   * render under `npm run dev` too.
   */
  it('renders the real Attachments gallery, grouped by cycle and stage, not the pending placeholder', async () => {
    signInAsAdmin()
    renderPage(`/tickets/${TICKET}?tab=attachments`)
    await waitForTicket()

    const panel = screen.getByRole('tabpanel')
    expect(panel).not.toHaveTextContent(/C-060/)

    // Cycle 2 is the latest and starts open, grouped under the stage the
    // ticket closed in.
    expect(await within(panel).findByRole('button', { name: /Cycle 2/ })).toHaveAttribute('aria-expanded', 'true')
    expect(within(panel).getByText('Closed')).toBeInTheDocument()

    // Cycle 1 starts collapsed; expanding it reaches the hand-seeded row.
    const cycle1 = within(panel).getByRole('button', { name: /Cycle 1/ })
    expect(cycle1).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(cycle1)
    expect(within(panel).getByText('Intake')).toBeInTheDocument()
  })

  it('moves between tabs with the arrow keys, one tab stop for the whole strip', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const tabs = screen.getByRole('tablist', { name: 'Ticket detail' })
    const journey = within(tabs).getByRole('tab', { name: 'Journey' })
    expect(journey).toHaveAttribute('aria-selected', 'true')
    expect(journey).toHaveAttribute('tabindex', '0')
    expect(within(tabs).getByRole('tab', { name: 'History' })).toHaveAttribute('tabindex', '-1')

    fireEvent.keyDown(tabs, { key: 'ArrowRight' })
    await waitFor(() =>
      expect(within(tabs).getByRole('tab', { name: 'History' })).toHaveAttribute('aria-selected', 'true'),
    )

    fireEvent.keyDown(tabs, { key: 'End' })
    await waitFor(() => expect(within(tabs).getByRole('tab', { name: 'Chat' })).toHaveAttribute('aria-selected', 'true'))

    // Wrapping past the last tab lands on the first, per the APG tabs pattern.
    fireEvent.keyDown(tabs, { key: 'ArrowRight' })
    await waitFor(() =>
      expect(within(tabs).getByRole('tab', { name: 'Journey' })).toHaveAttribute('aria-selected', 'true'),
    )
  })

  it('names the tasks that fill the ribbon and the tabs rather than rendering an empty shell', async () => {
    signInAsAdmin()
    renderPage()
    await waitForTicket()

    const ribbon = screen.getByRole('region', { name: 'Workflow ribbon' })
    expect(within(ribbon).getByText(/C-051/)).toBeInTheDocument()
  })

  describe('description — rich text since C-066', () => {
    const setDescription = (description: string) => {
      const ticket = getDb().tickets.find((t) => t.ticketId === TICKET)!
      ticket.description = description
    }

    it('renders stored markup as markup, not as visible tags', async () => {
      signInAsAdmin()
      setDescription('<p>Card payments <strong>hang</strong>.</p><ol><li>Open Fees</li></ol>')
      renderPage()
      await waitForTicket()

      // Scoped: the ribbon and the tab strip have lists of their own.
      const section = screen.getByRole('region', { name: 'Description' })
      expect(within(section).getByText('hang').tagName).toBe('STRONG')
      expect(within(section).getByRole('listitem')).toHaveTextContent('Open Fees')
    })

    it('sanitises on render, so tightening the allow-list reaches rows already stored', async () => {
      // §3.9's retroactivity rule, and the reason this goes through
      // `RichTextView` rather than a bare `dangerouslySetInnerHTML`. A row
      // written by an older, looser sanitiser is still in the table.
      signInAsAdmin()
      setDescription('<p>legit</p><script>alert(1)</script><img src="x" onerror="alert(2)">')
      renderPage()
      await waitForTicket()

      const section = screen.getByRole('region', { name: 'Description' })
      expect(within(section).getByText('legit')).toBeInTheDocument()
      expect(section.querySelector('script')).toBeNull()
      expect(section.querySelector('img')).toBeNull()
      expect(section.innerHTML).not.toContain('onerror')
    })

    it('keeps the line breaks of a description written before the field was rich text', async () => {
      // Every row in the database is plain text until C-067 backfills. Handed
      // straight to the view, HTML would collapse these two paragraphs into
      // one — which is a silent edit to somebody's bug report.
      signInAsAdmin()
      setDescription('Reproduced on production.\n\nGuest checkout is fine.')
      renderPage()
      await waitForTicket()

      const section = screen.getByRole('region', { name: 'Description' })
      expect(within(section).getByText('Reproduced on production.').tagName).toBe('P')
      expect(within(section).getByText('Guest checkout is fine.').tagName).toBe('P')
    })
  })

  /**
   * C-020 · the wiring, against the real mock handler rather than an override.
   *
   * `TicketLevelControl.test.tsx` proves the control; these prove the three
   * props the page has to pass it, each of which fails silently if it is
   * dropped: `canChangeLevel` (no editor at all), `clockStart` (a preview
   * measured from the wrong instant) and `onLevelChanged` (a stale panel behind
   * a saved change).
   */
  describe('level — C-020, §4B.1', () => {
    it('offers the editor to an Admin, and the saved change reaches the panel', async () => {
      signInAsAdmin()
      renderPage()
      await waitForTicket()

      const panel = summary()
      const before = within(panel).getByText('CRITICAL')
      expect(before).toBeInTheDocument()

      const user = userEvent.setup()
      await user.click(within(panel).getByRole('button', { name: /change the level/i }))

      const dialog = await screen.findByRole('dialog')
      await user.click(await within(dialog).findByRole('radio', { name: 'LOW' }))
      await user.type(
        within(dialog).getByLabelText(/reason/i),
        'Downgraded after the client confirmed the workaround holds.',
      )
      await user.click(within(dialog).getByRole('button', { name: 'Save' }))

      // The refetch is the assertion. Without `onLevelChanged` the request
      // succeeds and the panel goes on showing CRITICAL, which is the failure
      // mode a mutation test that only checked the response would miss.
      await waitFor(() => expect(within(summary()).getByText('LOW')).toBeInTheDocument(), { timeout: 4000 })
      // `originalLevel` is untouched, so the escalation note survives the
      // de-escalation — the one direction somebody might expect it to reset.
      expect(within(summary()).getByText('was HIGH')).toBeInTheDocument()
    })

    it('offers no editor on a sealed earlier cycle', async () => {
      signInAsAdmin()
      renderPage(`/tickets/${TICKET}?cycle=1`)
      await waitForTicket()

      // The write would land on the *current* cycle — the server has no notion
      // of "change the level as it was in cycle 1" — so offering it here would
      // be offering the wrong act under the right label.
      expect(screen.getByRole('status')).toHaveTextContent(/sealed and read-only/)
      expect(within(summary()).queryByRole('button', { name: /change the level/i })).not.toBeInTheDocument()
    })
  })

  it('answers an out-of-scope ticket with "not found" and never hints that it exists', async () => {
    // No signInAsAdmin: Ravi is a Developer and this ticket is assigned to
    // Meera, so the mock's scope guard answers 404 — the same response a
    // ticket that does not exist gets. A 403 here would confirm it exists.
    renderPage()

    expect(await screen.findByText('Ticket not found', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to tickets' })).toHaveAttribute('href', '/tickets')
    expect(screen.queryByText(/permission|access|not allowed|forbidden/i)).not.toBeInTheDocument()
  })
})
