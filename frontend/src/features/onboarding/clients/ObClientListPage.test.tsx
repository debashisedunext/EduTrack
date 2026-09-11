import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { format, parseISO } from 'date-fns'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ObClientListPage } from './ObClientListPage'

/** The table's own rendering, so the assertion tracks the environment's timezone rather than a hardcoded one. */
const asDate = (iso: string) => format(parseISO(iso), 'd MMM yyyy')

/**
 * B-108 · OB-03 against the mock server.
 *
 * Mounted through `Routes` rather than rendered bare, on
 * `ObNotificationCentrePage.test.tsx`'s reason: every filter arrives through
 * `useSearchParams`, and a test that passed them as props would not notice the
 * page and the URL disagreeing about a parameter name — which is the whole
 * mechanism this screen's shareable-link behaviour rests on.
 *
 * Fixture note — `db.ts`'s eight `obClients`, ordered by onboarding date:
 * Little Scholars 2026-08-19, Nalanda 08-12, Bluebell 08-10, Horizon 08-03,
 * Sunrise 07-28, Trinity 07-25, Cambridge 07-20, GreenValley 06-12.
 * Little Scholars' single journey is `LOCKED` — no colour, and §9's
 * "Prerequisites pending" — and GreenValley (client 1) is `LIVE` with both
 * its journeys finished. User 3 owns no step directly and backs up one step
 * each on Bluebell and Trinity; user 4 owns nothing anywhere and backs up one
 * (long-done) step on GreenValley, which is what makes the two owner
 * assertions below narrowings rather than requests the server could satisfy
 * by ignoring the parameter.
 */
function renderList(initialPath = '/onboarding/clients') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/onboarding/clients" element={<ObClientListPage />} />
          <Route path="/onboarding/clients/:obClientId" element={<p>client detail</p>} />
          <Route path="/onboarding/dashboard" element={<p>dashboard</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * MSW adds latency to every request and a full-suite run is heavily parallel,
 * so the 1 s default is not enough — `ClientListPage.test.tsx`'s convention.
 */
const SLOW = { timeout: 5000 }

/** The data rows, without the header row the table also reports. */
function bodyRows() {
  return screen.getAllByRole('row').slice(1)
}

/**
 * A row is a (client, product) pair, and the Client column — cell index 1,
 * after Serial Number — names the client on *every* one of its rows, the
 * continuation rows included. A client with three products says its own name
 * three times, which is what makes a row readable on its own.
 */
function clientCellText(row: HTMLElement) {
  return within(row).getAllByRole('cell')[1].textContent?.trim() ?? ''
}

/**
 * Every row belonging to the client named `name` — one per product bought, in
 * bought order (primary journey first), each naming the client.
 */
function rowsForClient(name: string) {
  const group = bodyRows().filter((row) => clientCellText(row) === name)
  if (group.length === 0) throw new Error(`No row found for ${name}`)
  return group
}

/** The list has rendered once this resolves. A multi-product client is several nodes. */
function listed(name: string) {
  return screen.findAllByText(name, undefined, SLOW)
}

async function clientNames() {
  await listed('GreenValley International School')
  // One entry per client, in row order: a client occupies as many rows as it
  // bought products and is named on each, so consecutive repeats collapse.
  return bodyRows()
    .map(clientCellText)
    .filter((name, i, all) => name !== all[i - 1])
}

describe('ObClientListPage', () => {
  it('lists every client, newest onboarding date first', async () => {
    renderList()

    const names = await clientNames()
    // Onboarding date DESC — the contract's ordering, which the keyset cursor
    // is built on. Little Scholars 2026-08-19 … GreenValley 2026-06-12.
    expect(names).toEqual([
      'Little Scholars Preschool',
      'Nalanda Group of Institutions',
      'Bluebell Public School',
      'Horizon Academy',
      'Sunrise EdTech Pvt Ltd',
      'Trinity College of Commerce',
      'Cambridge Heights School',
      'GreenValley International School',
    ])
  })

  it('links each row to OB-05 rather than making the row a click handler', async () => {
    renderList()

    // GreenValley bought two products, so it is two rows and two client links.
    // They share an href — both open the client — and the accessible name
    // carries the product, so a screen reader reading them out of context can
    // tell which row it is on.
    const links = await screen.findAllByRole('link', {
      name: /^GreenValley International School — /,
    }, SLOW)
    expect(links.map((a) => a.getAttribute('aria-label'))).toEqual([
      'GreenValley International School — ERP',
      'GreenValley International School — BIOMETRIC',
    ])
    for (const link of links) {
      expect(link).toHaveAttribute('href', '/onboarding/clients/1')
    }
  })

  it('repeats the client name onto every product row, not just the first', async () => {
    renderList()

    await listed('GreenValley International School')
    // Horizon bought ERP and Biometric: two rows, and row 2 says whose
    // biometric journey it is rather than leaving the Client cell blank.
    const rows = rowsForClient('Horizon Academy')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText('ERP')).toBeInTheDocument()
    expect(within(rows[1]).getByText('BIOMETRIC')).toBeInTheDocument()
    expect(rows.map(clientCellText)).toEqual(['Horizon Academy', 'Horizon Academy'])
  })

  /**
   * Products Bought links straight to that product's own ribbon page
   * (`/onboarding/clients/:id/products/:productId`), not to the client page's
   * card chooser — the reader already named the product by clicking it.
   */
  it('links a bought product straight to its own ribbon, skipping the card chooser', async () => {
    renderList()

    await listed('GreenValley International School')
    // Horizon bought two products — one row each, ERP (the primary journey's
    // product) first.
    const [erpRow, biometricRow] = rowsForClient('Horizon Academy')

    expect(within(erpRow).getByRole('link', { name: 'ERP' })).toHaveAttribute(
      'href',
      '/onboarding/clients/3/products/1',
    )
    expect(within(biometricRow).getByRole('link', { name: 'BIOMETRIC' })).toHaveAttribute(
      'href',
      '/onboarding/clients/3/products/2',
    )
  })

  /**
   * §9's sentence for this screen, and the one thing on it that is not a
   * colour: a client whose journeys are all locked has no RAG at all.
   */
  it('shows a locked client as “Prerequisites pending” and never as a colour', async () => {
    renderList()

    await listed('GreenValley International School')
    const scholars = bodyRows().find((row) =>
      row.textContent?.includes('Little Scholars Preschool'),
    )!

    expect(within(scholars).getByText('Prerequisites pending')).toBeInTheDocument()
    expect(within(scholars).queryByText('On track')).not.toBeInTheDocument()
    expect(within(scholars).queryByText('At risk')).not.toBeInTheDocument()
    expect(within(scholars).queryByText('Breached')).not.toBeInTheDocument()
  })

  it('shows a finished client as “Journeys complete”, with the Live chip', async () => {
    renderList()

    await listed('GreenValley International School')
    const greenValley = bodyRows().find((row) =>
      row.textContent?.includes('GreenValley International School'),
    )!

    // Two journeys, and both are finished — every step DONE. The mockup's
    // Current step column says so in words, and the health chip is "Live"
    // because the list has no Status column to say it in.
    expect(within(greenValley).getByText('Journeys complete')).toBeInTheDocument()
    expect(within(greenValley).getByText('Live')).toBeInTheDocument()
  })

  it('shows the mockup’s small journey-count chip only when a client has more than one', async () => {
    renderList()

    await listed('GreenValley International School')
    // Horizon has a running ERP journey and a held biometric one; Sunrise has
    // exactly one. (GreenValley and Nalanda also carry two, but GreenValley's
    // row reads "Journeys complete" instead of a progress strip.)
    const horizon = bodyRows().find((row) => row.textContent?.includes('Horizon Academy'))!
    const sunrise = bodyRows().find((row) => row.textContent?.includes('Sunrise EdTech Pvt Ltd'))!

    expect(within(horizon).getByText('2 journeys')).toBeInTheDocument()
    expect(within(sunrise).queryByText(/journeys$/)).not.toBeInTheDocument()
  })

  /**
   * Horizon's ERP journey (id 31) is running: bought ERP and Biometric,
   * started 2026-08-03, current step "Admin & user training" due
   * 2026-08-20T18:30Z.
   */
  it('shows products bought, start date and expected completion for a running client', async () => {
    renderList()

    await listed('GreenValley International School')
    const [erpRow, biometricRow] = rowsForClient('Horizon Academy')

    expect(within(erpRow).getByRole('link', { name: 'ERP' })).toBeInTheDocument()
    expect(within(biometricRow).getByRole('link', { name: 'BIOMETRIC' })).toBeInTheDocument()
    // Client-level facts — repeated onto every one of the client's rows.
    expect(within(erpRow).getByText(asDate('2026-08-03T09:00:00.000Z'))).toBeInTheDocument()
    expect(within(erpRow).getByText(asDate('2026-08-20T18:30:00.000Z'))).toBeInTheDocument()
    expect(within(biometricRow).getByText(asDate('2026-08-03T09:00:00.000Z'))).toBeInTheDocument()
    expect(within(biometricRow).getByText(asDate('2026-08-20T18:30:00.000Z'))).toBeInTheDocument()
  })

  /**
   * Little Scholars is gate-locked: bought ERP, but nothing has started, so
   * neither Start Date nor Expected Completion has anything to show — and the
   * grid says *why* instead of printing an em-dash. Four cells read "Not
   * started": the two dates, Delayed, and Responsible person.
   */
  it('names the reason a gate-locked client has no dates, rather than dashing them', async () => {
    renderList()

    await listed('GreenValley International School')
    const scholars = bodyRows().find((row) =>
      row.textContent?.includes('Little Scholars Preschool'),
    )!

    expect(within(scholars).getByText('ERP')).toBeInTheDocument()
    expect(within(scholars).queryByText('—')).not.toBeInTheDocument()
    expect(within(scholars).getAllByText('Not started')).toHaveLength(4)
  })

  /**
   * GreenValley's two journeys are both finished: Start Date still names
   * when the primary one began, but Expected Completion has no current step
   * left to name.
   */
  it('keeps the start date once a client finishes, but drops expected completion', async () => {
    renderList()

    await listed('GreenValley International School')
    // Its ERP row — the primary journey's, which is the one both dates
    // describe.
    const [greenValley] = rowsForClient('GreenValley International School')

    expect(within(greenValley).getByText(asDate('2026-06-15T09:00:00.000Z'))).toBeInTheDocument()
    // Expected Completion, Delayed and Responsible person all read "Complete"
    // — a finished journey is not late, and nobody is holding it.
    expect(within(greenValley).queryByText('—')).not.toBeInTheDocument()
    expect(within(greenValley).getAllByText('Complete')).toHaveLength(3)
  })

  /**
   * B-128's read, joined onto this grid by (client, product). Sunrise's ERP
   * journey is the fixture's worst: two working days past its expected
   * completion, held by the owner of "Data migration".
   *
   * The count lives **in the chip**, not in a column of its own — a "Delayed
   * by" column on a grid where most rows are not late is "On schedule"
   * repeated down the page.
   */
  it('names who holds a delayed journey and how many working days it is late', async () => {
    renderList()

    await listed('Sunrise EdTech Pvt Ltd')
    const [sunrise] = rowsForClient('Sunrise EdTech Pvt Ltd')

    expect(within(sunrise).getByText('Delayed · 2 working days')).toBeInTheDocument()
    expect(within(sunrise).getByText('Kavya Sharma')).toBeInTheDocument()
  })

  /**
   * The half of the delay join that is easy to get wrong: the read covers
   * delayed journeys only, so a row that is *not* on it must not silently
   * inherit another row's delay — and must not claim an owner it has no
   * source for. Bluebell is running and not late.
   */
  it('says a journey is on time, and admits it cannot name who holds it', async () => {
    renderList()

    await listed('Bluebell Public School')
    const [bluebell] = rowsForClient('Bluebell Public School')

    expect(within(bluebell).getByText('On time')).toBeInTheDocument()
    // The clients read carries no step owner, and `GET /onboarding/journeys`
    // — the one that would — is still unimplemented. Said, not dashed.
    expect(within(bluebell).getByText('Unknown')).toBeInTheDocument()
  })

  /** The grid's own rule, asserted across every row rather than per column. */
  it('leaves no cell in the grid blank', async () => {
    renderList()

    await listed('GreenValley International School')

    for (const row of bodyRows()) {
      for (const cell of within(row).getAllByRole('cell')) {
        expect(cell.textContent?.trim()).not.toBe('')
        expect(cell.textContent?.trim()).not.toBe('—')
      }
    }
  })

  it('captions the page head with boarded and live counts', async () => {
    renderList()

    // 8 fixture clients, of which GreenValley is LIVE.
    await screen.findByText('8 boarded · 1 live', undefined, SLOW)
  })

  it('applies a status filter read straight off the URL', async () => {
    renderList('/onboarding/clients?status=LIVE')

    await listed('GreenValley International School')
    // GreenValley is the only LIVE client, and it bought two products — one
    // row each.
    expect(bodyRows()).toHaveLength(2)
    expect(screen.queryAllByText('Sunrise EdTech Pvt Ltd')).toHaveLength(0)
  })

  it('applies the gate filter, which is the only way to ask for a locked client', async () => {
    renderList('/onboarding/clients?gateStatus=LOCKED')

    await listed('Little Scholars Preschool')
    expect(bodyRows()).toHaveLength(1)
  })

  /**
   * The filter B-108 exists for. User 3 backs up a step on Bluebell and on
   * Trinity and touches nobody else, so this is a real narrowing rather than
   * a request the server could satisfy by ignoring the parameter — and both
   * matches are *backup* assignments, so a filter reading only
   * `owner_user_id` would return an empty list here.
   */
  it('filters to the clients an implementor owns a step on', async () => {
    renderList('/onboarding/clients?ownerId=3')

    await listed('Bluebell Public School')
    expect(screen.getAllByText('Trinity College of Commerce').length).toBeGreaterThan(0)
    expect(bodyRows()).toHaveLength(2)
    expect(screen.queryAllByText('GreenValley International School')).toHaveLength(0)
  })

  /**
   * The half of the owner rule that is easiest to lose. User 4 owns nothing
   * and backs up one step on GreenValley — a step long since DONE, on a LIVE
   * client — and covering a step is what a backup is for, finished or not.
   */
  it('counts a backup owner, because covering a step is what a backup is for', async () => {
    renderList('/onboarding/clients?ownerId=4')

    await listed('GreenValley International School')
    // One client, two products bought — two rows.
    expect(bodyRows()).toHaveLength(2)
  })

  it('explains the one filter pair that can never match, instead of looking broken', async () => {
    renderList('/onboarding/clients?gateStatus=LOCKED&rag=RED')

    await screen.findByText('No clients match these filters', undefined, SLOW)
    expect(
      screen.getByText(/has no health colour yet — nothing is running to colour/),
    ).toBeInTheDocument()
  })

  it('resets the filter row and keeps the search term', async () => {
    renderList('/onboarding/clients?q=Sunrise&status=LIVE')

    // Live *and* named Sunrise is nobody — Sunrise is ONBOARDING.
    await screen.findByText('No clients match these filters', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Reset filters' }))

    await listed('Sunrise EdTech Pvt Ltd')
    expect(screen.getByLabelText('Search onboarding clients by name')).toHaveValue('Sunrise')
    expect(bodyRows()).toHaveLength(1)
  })

  it('drives the filter from the Status select and writes it into the URL', async () => {
    renderList()

    await listed('GreenValley International School')

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'LIVE' } })

    // GreenValley, the only LIVE client, bought two products — two rows.
    await waitFor(() => expect(bodyRows()).toHaveLength(2), SLOW)
    expect(screen.getAllByText('GreenValley International School')).toHaveLength(2)
  })

  /**
   * The mockup's Health select offers "Live", which is a status in the
   * contract — `toQueryParams` translates it, so the same one-row answer
   * comes back as for `status=LIVE`.
   */
  it('answers Health "Live" by asking the server for status LIVE', async () => {
    renderList()

    await listed('Little Scholars Preschool')

    fireEvent.change(screen.getByLabelText('Health'), { target: { value: 'LIVE' } })

    // GreenValley, the only LIVE client, bought two products — two rows.
    await waitFor(() => expect(bodyRows()).toHaveLength(2), SLOW)
    expect(screen.getAllByText('GreenValley International School')).toHaveLength(2)
  })

  it('refuses to translate Health "Live" over an explicit non-Live status, and explains', async () => {
    renderList('/onboarding/clients?status=ONBOARDING&rag=LIVE')

    await screen.findByText('No clients match these filters', undefined, SLOW)
    expect(
      screen.getByText(/cannot both be true of one client/),
    ).toBeInTheDocument()
  })
})
