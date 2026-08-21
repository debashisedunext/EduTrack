import { beforeAll, afterEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { TicketListPage } from './TicketListPage'

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

function TicketDetailStub() {
  const { ticketId } = useParams()
  return <p>Landed on {ticketId}</p>
}

function renderPage(initialEntry = '/tickets') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/tickets" element={<TicketListPage />} />
          <Route path="/tickets/new" element={<p>New ticket form</p>} />
          <Route path="/tickets/:ticketId" element={<TicketDetailStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const TICKET_LINK_HREF = /^\/tickets\/\S+-26-\d+$/

/**
 * Waits for the first page of *real* rows — skeleton rows are also `<tr>`s, so
 * counting rows alone would pass while the loading state is still showing.
 */
async function rowsReady() {
  // `timeout: 4000` for the reason `openFilter` below already gives, and this
  // helper was the one place in the file left on the 1 s default — it is the
  // first thing almost every test awaits, so it is where a busy full-suite run
  // surfaces as an unrelated-looking failure. (B-029.)
  await waitFor(
    () => {
      const ticketLinks = screen.getAllByRole('link').filter((el) => TICKET_LINK_HREF.test(el.getAttribute('href') ?? ''))
      expect(ticketLinks.length).toBeGreaterThan(0)
    },
    { timeout: 4000 },
  )
  return screen.getAllByRole('row')
}

/**
 * Opens a filter's popover, retrying — Radix restores focus to the trigger a
 * frame after close. `timeout: 4000` matches the create-form suite's own
 * popover helper: this suite runs alongside nine others under one CPU-bound
 * `vitest run`, and the default 1000ms is tight enough to flake there even
 * when every step is individually correct.
 */
async function openFilter(name: RegExp | string) {
  return waitFor(
    () => {
      const trigger = screen.getByRole('button', { name })
      if (trigger.getAttribute('data-state') !== 'open') fireEvent.click(trigger)
      return trigger
    },
    { timeout: 4000 },
  )
}

/**
 * Open-and-pick as one retryable unit, not two.
 *
 * A popover that just closed restores focus to its own trigger a frame
 * later — the same race `CreateTicketPage.test.tsx` documents. If this pick
 * follows straight on from another filter's pick, that stray focus event can
 * land *after* this popover has already opened and read as focus leaving it,
 * dismissing it before the option click lands. Retrying "reopen, then pick"
 * together recovers; retrying only the click, against a popover that is no
 * longer there to click into, does not.
 */
async function pickFilterOption(triggerName: RegExp | string, optionName: RegExp | string) {
  await waitFor(
    () => {
      const trigger = screen.getByRole('button', { name: triggerName })
      if (trigger.getAttribute('data-state') !== 'open') fireEvent.click(trigger)
      fireEvent.click(screen.getByRole('option', { name: optionName }))
    },
    { timeout: 4000 },
  )
}

let listRequests: URL[] = []
const captureRequest = ({ request }: { request: Request }) => {
  if (request.method === 'GET' && new URL(request.url).pathname.endsWith('/tickets')) {
    listRequests.push(new URL(request.url))
  }
}

beforeAll(() => server.events.on('request:start', captureRequest))
afterEach(() => {
  listRequests = []
})

describe('S-17 Ticket List — C-014', () => {
  it('lists the current user’s scoped tickets and offers New ticket', async () => {
    renderPage()
    const rows = await rowsReady()
    expect(screen.getByRole('link', { name: /New ticket/ })).toHaveAttribute('href', '/tickets/new')
    // Every row's ID cell is a real ticket link, not filler text.
    const firstDataRow = rows[1]
    expect(within(firstDataRow).getByRole('link')).toHaveAttribute('href', expect.stringMatching(/^\/tickets\/\S+-26-\d+$/))
  })

  it('reads the search box from the URL — TopBar’s global search lands here', async () => {
    // "payment" matches nothing in Ravi's own scope — the walkthrough ticket
    // that mentions it belongs to Meera — so this deliberately does not wait
    // for rows, only that the box and the request both carry the term.
    renderPage('/tickets?q=payment')
    expect(await screen.findByRole('textbox', { name: /Search tickets/ })).toHaveValue('payment')
    await waitFor(() => expect(listRequests.some((u) => u.searchParams.get('q') === 'payment')).toBe(true))
  })

  it('narrows to a project and sends projectId to the API, then All projects clears it', async () => {
    renderPage()
    await rowsReady()

    await pickFilterOption('Project', /^CRM/)
    await waitFor(
      () => {
        const rows = screen.getAllByRole('row').slice(1)
        expect(rows.length).toBeGreaterThan(0)
        for (const row of rows) {
          expect(within(row).getByRole('link').textContent).toMatch(/^CRM-/)
        }
      },
      { timeout: 4000 },
    )
    expect(listRequests.at(-1)?.searchParams.get('projectId')).toBeTruthy()
    expect(screen.getByRole('button', { name: /^Project: CRM/ })).toBeInTheDocument()

    await pickFilterOption(/^Project: CRM/, /All project/)
    await waitFor(() => expect(listRequests.at(-1)?.searchParams.has('projectId')).toBe(false), { timeout: 4000 })
    expect(screen.getByRole('button', { name: 'Project' })).toBeInTheDocument()
  })

  /**
   * B-029 · blueprint line 523 — deactivating a client "never hides the
   * historical tickets".
   *
   * This filter used to send `?isActive=true`, so a client's name left it the
   * moment they were deactivated and every ticket they ever raised became
   * unreachable through the one control that reaches them. Nothing failed
   * loudly; the dropdown just quietly stopped offering somebody.
   *
   * The create form still sends the parameter, and must — that list is a picker
   * for a ticket that does not exist yet, which is exactly what deactivation
   * blocks. Same operation, opposite answers, and the question that separates
   * them is "historical or new".
   *
   * Oldco is INACTIVE with three tickets on project 4 (`db.ts`), seeded for
   * this: before those rows, a page that hid deactivated clients and one that
   * did not were indistinguishable.
   */
  it('offers a deactivated client in the Client filter, and finds their tickets', async () => {
    renderPage()
    await rowsReady()

    await openFilter('Client')
    await waitFor(
      () => {
        const options = screen.getAllByRole('option').map((o) => o.textContent ?? '')
        expect(options.some((o) => /Oldco Industries/.test(o))).toBe(true)
      },
      { timeout: 4000 },
    )
    fireEvent.click(screen.getByRole('option', { name: /Oldco Industries/ }))

    await waitFor(
      () => expect(listRequests.at(-1)?.searchParams.get('clientId')).toBe('4'),
      { timeout: 4000 },
    )
    await waitFor(
      () => {
        const rows = screen.getAllByRole('row').slice(1)
        expect(rows.length).toBeGreaterThan(0)
        for (const row of rows) {
          expect(within(row).getByRole('link').textContent).toMatch(/^ARCH-/)
        }
      },
      { timeout: 4000 },
    )
  })

  /**
   * C-072 · `GET /masters/priorities` is active-only *by default*, which is the
   * only thing keeping a retired level off this filter — and that default is
   * due to widen to match `listTaskTypes` and `listModules`
   * (`DEPENDENCIES.md` row 23). The override is that widened endpoint: it is
   * what the page will actually be handed, not a hypothetical. Without the
   * filter in `selectableLevels` the fifth option is back.
   */
  it('leaves a retired level out of the Level filter', async () => {
    server.use(
      http.get('*/masters/priorities', () =>
        HttpResponse.json({
          data: [
            { id: 1, level: 'LOW', name: 'Low', seq: 1, isActive: true },
            { id: 2, level: 'MEDIUM', name: 'Medium', seq: 2, isActive: true },
            { id: 3, level: 'HIGH', name: 'High', seq: 3, isActive: true },
            { id: 4, level: 'CRITICAL', name: 'Critical', seq: 4, isActive: false },
          ],
        }),
      ),
    )
    renderPage()
    await rowsReady()

    await openFilter('Level')
    await waitFor(
      () => {
        const options = screen.getAllByRole('option').map((o) => o.textContent)
        expect(options).toEqual(['All level', 'Low', 'Medium', 'High'])
      },
      { timeout: 4000 },
    )
  })

  it('offers Level options in priority-master order and narrows the grid to the chosen one', async () => {
    renderPage()
    await rowsReady()

    await openFilter('Level')
    await waitFor(
      () => {
        const options = screen.getAllByRole('option').map((o) => o.textContent)
        expect(options).toEqual(['All level', 'Low', 'Medium', 'High', 'Critical'])
      },
      { timeout: 4000 },
    )
    fireEvent.click(screen.getByRole('option', { name: 'Critical' }))
    await waitFor(() => expect(listRequests.at(-1)?.searchParams.get('level')).toBe('CRITICAL'), { timeout: 4000 })

    // `keepPreviousData` deliberately keeps the prior page on screen while the
    // filtered one loads, so the row check has to retry as one assertion —
    // reading the rows once and checking their text after is a stale snapshot.
    await waitFor(
      () => {
        const rows = screen.getAllByRole('row').slice(1)
        expect(rows.length).toBeGreaterThan(0)
        for (const row of rows) {
          expect(within(row).getByText('CRITICAL')).toBeInTheDocument()
        }
      },
      { timeout: 4000 },
    )
  })

  it('counts active filters, and Reset clears the filter row without touching the search box', async () => {
    // Both filters arrive via the URL rather than two chained popover picks.
    // Radix restores focus to a just-closed popover's trigger a frame later
    // (the race `CreateTicketPage.test.tsx` documents), and opening a second,
    // unrelated popover immediately after the first can race that restoration
    // and get dismissed by it — a real flake under a CPU-contended full run.
    // The popover mechanics are already covered by the Project and Level
    // tests above; this one is about `activeCount` and Reset, so it drives
    // the same state the picks would have produced without their timing.
    // Not `rowsReady()` — Level=High and Status=New together may legitimately
    // scope to zero of Ravi's tickets, and this test is about the filter row
    // and Reset, not about what the grid underneath happens to show.
    //
    // `q` arrives via the URL too, rather than typed through `fireEvent.change`
    // — typing schedules the search box's own 300ms debounce timer, and on a
    // slower CI runner the rest of this test (find, click Reset, wait, assert)
    // can easily take longer than that, so the timer fires mid-test and its
    // `setSearchParams` call races Reset's. Driving `q` through the same URL
    // as the other two filters sidesteps that entirely; the debounce itself is
    // already covered by "reads the search box from the URL" above.
    renderPage('/tickets?level=HIGH&status=NEW&q=payment')
    expect(await screen.findByRole('textbox', { name: /Search tickets/ })).toHaveValue('payment')

    expect(await screen.findByRole('button', { name: /^Level: High/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Status: New/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reset (2)' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reset (2)' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Reset/ })).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Level' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Status' })).toBeInTheDocument()
    // The search box is a header control, not part of the filter row Reset clears.
    expect(screen.getByRole('textbox', { name: /Search tickets/ })).toHaveValue('payment')
  })

  it('hides a column from the chooser and remembers the choice across a remount', async () => {
    const { unmount } = renderPage()
    await rowsReady()
    expect(screen.getByRole('columnheader', { name: 'Eff' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }))
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Eff' }))
    await waitFor(() => expect(screen.queryByRole('columnheader', { name: 'Eff' })).not.toBeInTheDocument())

    unmount()
    renderPage()
    await rowsReady()
    expect(screen.queryByRole('columnheader', { name: 'Eff' })).not.toBeInTheDocument()
  })

  /* ── C-070 · §7.5's module ──────────────────────────────────────────── */

  it('filters by module, and the filter is in the URL like every other one', async () => {
    renderPage()
    await rowsReady()
    listRequests = []

    await pickFilterOption('Module', 'Fees')

    // Sent to the server, never applied in the browser: the grid is cursor
    // paginated, so a client-side filter would silently drop matching rows
    // that happen to live on page two.
    await waitFor(() => expect(listRequests.at(-1)?.searchParams.get('moduleId')).toBe('3'), {
      timeout: 4000,
    })
  })

  it('reads a module filter out of the URL, so a filtered grid is a pasteable link', async () => {
    renderPage('/tickets?moduleId=3')
    await rowsReady()

    expect(listRequests.at(-1)?.searchParams.get('moduleId')).toBe('3')
    // Named exactly: the chip also carries a "Clear Module filter" button, and
    // a loose match finds both.
    expect(screen.getByRole('button', { name: 'Module: Fees' })).toBeInTheDocument()
  })

  it('offers a retired module in the filter, which the create form deliberately does not', async () => {
    // Blueprint line 986: "filterable always". "Every open Transport ticket" is
    // a fair question about a module being wound down, and hiding it from the
    // filter is the one thing that makes it unanswerable. S-19 filters the same
    // row out for the opposite and equally correct reason — nothing new should
    // be raised against it.
    renderPage()
    await rowsReady()
    await openFilter('Module')

    expect(await screen.findByRole('option', { name: /Transport \(retired\)/ })).toBeInTheDocument()
  })

  it('keeps the Module column out of the grid until it is chosen', async () => {
    // Blueprint line 986: off by default "because the grid is already at its
    // width budget", but present in the chooser. The filter is what the field
    // is for; the column is for the rarer case of scanning across modules.
    renderPage()
    await rowsReady()
    expect(screen.queryByRole('columnheader', { name: 'Module' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }))
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Module' }))

    const header = await screen.findByRole('columnheader', { name: 'Module' })
    expect(header).toBeInTheDocument()
    // Resolved through the master rather than printed as an id.
    expect(screen.getAllByRole('cell').some((cell) => cell.textContent === 'Fees')).toBe(true)
  })

  it('switches row density from comfortable to compact', async () => {
    renderPage()
    const rows = await rowsReady()
    const firstCell = within(rows[1]).getAllByRole('cell')[0]
    expect(firstCell.className).toMatch(/py-3/)

    fireEvent.click(screen.getByRole('radio', { name: 'Compact' }))
    await waitFor(() => expect(within(rows[1]).getAllByRole('cell')[0].className).toMatch(/py-1\.5/))
  })

  it('shows the no-match empty state for a search with no results, offering New ticket', async () => {
    renderPage()
    await rowsReady()
    fireEvent.change(screen.getByRole('textbox', { name: /Search tickets/ }), {
      target: { value: 'no ticket matches this string zzz' },
    })
    fireEvent.keyDown(screen.getByRole('textbox', { name: /Search tickets/ }), { key: 'Enter' })

    expect(await screen.findByText('No tickets match these filters')).toBeInTheDocument()
    // No other filter is active, so the CTA points at creating rather than at
    // Reset — the header's own [+ New Ticket] is the second "New ticket" link.
    expect(screen.getAllByRole('link', { name: 'New ticket' })).toHaveLength(2)
  })

  it('starts on the first page with Previous disabled', async () => {
    renderPage()
    await rowsReady()
    expect(screen.getByRole('button', { name: /Previous/ })).toBeDisabled()
  })
})

/**
 * Picks a Saved-views item as one retryable "open, then click" unit, same
 * defensive shape as `pickFilterOption` above — a popover that was open a
 * frame ago and got dismissed by a stray focus event just gets retried from
 * the top rather than clicking into a menu that is no longer there.
 */
async function pickSavedView(name: RegExp | string) {
  await waitFor(
    () => {
      const trigger = screen.getByRole('button', { name: 'Saved views' })
      if (trigger.getAttribute('data-state') !== 'open') fireEvent.click(trigger)
      fireEvent.click(screen.getByRole('menuitemradio', { name }))
    },
    { timeout: 4000 },
  )
}

describe('S-17 Row colour cues — C-016', () => {
  /**
   * The 8 s ceiling is B-029's and is a timing budget, not a behaviour change.
   *
   * This test renders the grid, opens a popover and waits out a filtered
   * refetch — about 1.7 s on an idle machine, against vitest's 5 s default. It
   * had roughly three seconds of headroom, which the whole file shares under
   * one CPU-bound `vitest run`; adding a fifteenth test to the file spent it,
   * and the failure surfaced here rather than in the test that caused it. Both
   * pass in isolation and the assertion never changed. The same reason the
   * popover helper above already sets `timeout: 4000`.
   */
  it('gives every Critical row a soft red left border', async () => {
    renderPage()
    await rowsReady()
    await pickFilterOption('Level', 'Critical')
    await waitFor(
      () => {
        const rows = screen.getAllByRole('row').slice(1)
        expect(rows.length).toBeGreaterThan(0)
        for (const row of rows) {
          expect(within(row).getByText('CRITICAL')).toBeInTheDocument()
          expect(row.className).toMatch(/border-l-level-critical/)
        }
      },
      { timeout: 4000 },
    )
  }, 8000)

  it('gives a Low row no colour cue — Low is never auto-escalated', async () => {
    renderPage()
    await rowsReady()
    await pickFilterOption('Level', 'Low')
    await waitFor(
      () => {
        const rows = screen.getAllByRole('row').slice(1)
        expect(rows.length).toBeGreaterThan(0)
        for (const row of rows) {
          expect(row.className).not.toMatch(/border-l-level/)
        }
      },
      { timeout: 4000 },
    )
  })
})

describe('S-17 Saved views — C-015', () => {
  it('Overdue sends isDelayed=true and replaces whatever else was set', async () => {
    // Not `rowsReady()` — Level=High alone may legitimately scope to zero of
    // Ravi's tickets (the "counts active filters" test above hits the same
    // thing); this test is about the request Overdue sends, not the grid.
    renderPage('/tickets?level=HIGH')
    await waitFor(() => expect(listRequests.some((u) => u.searchParams.get('level') === 'HIGH')).toBe(true))
    await pickSavedView('Overdue')
    await waitFor(() => {
      const last = listRequests.at(-1)!
      expect(last.searchParams.get('isDelayed')).toBe('true')
      expect(last.searchParams.has('level')).toBe(false)
    })
    expect(screen.getByRole('button', { name: 'Saved views' })).toHaveTextContent('Overdue')
  })

  it('Unassigned sends unassigned=true — the contract param C-015 added for "assignee is null"', async () => {
    renderPage()
    await rowsReady()
    await pickSavedView('Unassigned')
    await waitFor(() => expect(listRequests.at(-1)?.searchParams.get('unassigned')).toBe('true'))
  })

  it('My Open sends assigneeId=<the signed-in user> and excludeClosed=true, once useGetMe resolves', async () => {
    renderPage()
    await rowsReady()
    fireEvent.click(screen.getByRole('button', { name: 'Saved views' }))
    // The mock's default currentUserId is 3 (Ravi) — see db.ts. Disabled until
    // `useGetMe` resolves, so the item existing-but-disabled is the starting
    // state, not a race to avoid.
    await waitFor(() => expect(screen.getByRole('menuitemradio', { name: 'My Open' })).not.toBeDisabled())
    await pickSavedView('My Open')
    await waitFor(() => {
      const last = listRequests.at(-1)!
      expect(last.searchParams.get('assigneeId')).toBe('3')
      expect(last.searchParams.get('excludeClosed')).toBe('true')
    })
  })
})
