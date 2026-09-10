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

async function clientNames() {
  await screen.findByText('GreenValley International School', undefined, SLOW)
  return bodyRows().map((row) => within(row).getAllByRole('cell')[0].textContent?.trim() ?? '')
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

    const link = await screen.findByRole('link', {
      name: 'GreenValley International School',
    }, SLOW)
    expect(link).toHaveAttribute('href', '/onboarding/clients/1')
  })

  /**
   * Products Bought links straight to that product's own ribbon page
   * (`/onboarding/clients/:id/products/:productId`), not to the client page's
   * card chooser — the reader already named the product by clicking it.
   */
  it('links a bought product straight to its own ribbon, skipping the card chooser', async () => {
    renderList()

    await screen.findByText('GreenValley International School', undefined, SLOW)
    const horizon = bodyRows().find((row) => row.textContent?.includes('Horizon Academy'))!

    expect(within(horizon).getByRole('link', { name: 'ERP' })).toHaveAttribute(
      'href',
      '/onboarding/clients/3/products/1',
    )
    expect(within(horizon).getByRole('link', { name: 'BIOMETRIC' })).toHaveAttribute(
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

    await screen.findByText('GreenValley International School', undefined, SLOW)
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

    await screen.findByText('GreenValley International School', undefined, SLOW)
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

    await screen.findByText('GreenValley International School', undefined, SLOW)
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

    await screen.findByText('GreenValley International School', undefined, SLOW)
    const horizon = bodyRows().find((row) => row.textContent?.includes('Horizon Academy'))!

    expect(within(horizon).getByRole('link', { name: 'ERP' })).toBeInTheDocument()
    expect(within(horizon).getByRole('link', { name: 'BIOMETRIC' })).toBeInTheDocument()
    expect(within(horizon).getByText(asDate('2026-08-03T09:00:00.000Z'))).toBeInTheDocument()
    expect(within(horizon).getByText(asDate('2026-08-20T18:30:00.000Z'))).toBeInTheDocument()
  })

  /**
   * Little Scholars is gate-locked: bought ERP, but nothing has started, so
   * neither Start Date nor Expected Completion has anything to show.
   */
  it('shows a dash for start date and expected completion on a gate-locked client', async () => {
    renderList()

    await screen.findByText('GreenValley International School', undefined, SLOW)
    const scholars = bodyRows().find((row) =>
      row.textContent?.includes('Little Scholars Preschool'),
    )!

    expect(within(scholars).getByText('ERP')).toBeInTheDocument()
    const dashes = within(scholars).getAllByText('—')
    // Health's own "Prerequisites pending" lock glyph is a separate cell —
    // this counts only the plain em-dash cells, Start Date and Expected
    // Completion.
    expect(dashes).toHaveLength(2)
  })

  /**
   * GreenValley's two journeys are both finished: Start Date still names
   * when the primary one began, but Expected Completion has no current step
   * left to name.
   */
  it('keeps the start date once a client finishes, but drops expected completion', async () => {
    renderList()

    const greenValley = (await screen.findByText('GreenValley International School', undefined, SLOW))
      .closest('tr')!

    expect(within(greenValley).getByText(asDate('2026-06-15T09:00:00.000Z'))).toBeInTheDocument()
    expect(within(greenValley).getAllByText('—')).toHaveLength(1)
  })

  it('captions the page head with boarded and live counts', async () => {
    renderList()

    // 8 fixture clients, of which GreenValley is LIVE.
    await screen.findByText('8 boarded · 1 live', undefined, SLOW)
  })

  it('applies a status filter read straight off the URL', async () => {
    renderList('/onboarding/clients?status=LIVE')

    await screen.findByText('GreenValley International School', undefined, SLOW)
    expect(bodyRows()).toHaveLength(1)
    expect(screen.queryByText('Sunrise EdTech Pvt Ltd')).not.toBeInTheDocument()
  })

  it('applies the gate filter, which is the only way to ask for a locked client', async () => {
    renderList('/onboarding/clients?gateStatus=LOCKED')

    await screen.findByText('Little Scholars Preschool', undefined, SLOW)
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

    await screen.findByText('Bluebell Public School', undefined, SLOW)
    expect(screen.getByText('Trinity College of Commerce')).toBeInTheDocument()
    expect(bodyRows()).toHaveLength(2)
    expect(screen.queryByText('GreenValley International School')).not.toBeInTheDocument()
  })

  /**
   * The half of the owner rule that is easiest to lose. User 4 owns nothing
   * and backs up one step on GreenValley — a step long since DONE, on a LIVE
   * client — and covering a step is what a backup is for, finished or not.
   */
  it('counts a backup owner, because covering a step is what a backup is for', async () => {
    renderList('/onboarding/clients?ownerId=4')

    await screen.findByText('GreenValley International School', undefined, SLOW)
    expect(bodyRows()).toHaveLength(1)
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

    await screen.findByText('Sunrise EdTech Pvt Ltd', undefined, SLOW)
    expect(screen.getByLabelText('Search onboarding clients by name')).toHaveValue('Sunrise')
    expect(bodyRows()).toHaveLength(1)
  })

  it('drives the filter from the Status select and writes it into the URL', async () => {
    renderList()

    await screen.findByText('GreenValley International School', undefined, SLOW)

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'LIVE' } })

    await waitFor(() => expect(bodyRows()).toHaveLength(1), SLOW)
    expect(screen.getByText('GreenValley International School')).toBeInTheDocument()
  })

  /**
   * The mockup's Health select offers "Live", which is a status in the
   * contract — `toQueryParams` translates it, so the same one-row answer
   * comes back as for `status=LIVE`.
   */
  it('answers Health "Live" by asking the server for status LIVE', async () => {
    renderList()

    await screen.findByText('Little Scholars Preschool', undefined, SLOW)

    fireEvent.change(screen.getByLabelText('Health'), { target: { value: 'LIVE' } })

    await waitFor(() => expect(bodyRows()).toHaveLength(1), SLOW)
    expect(screen.getByText('GreenValley International School')).toBeInTheDocument()
  })

  it('refuses to translate Health "Live" over an explicit non-Live status, and explains', async () => {
    renderList('/onboarding/clients?status=ONBOARDING&rag=LIVE')

    await screen.findByText('No clients match these filters', undefined, SLOW)
    expect(
      screen.getByText(/cannot both be true of one client/),
    ).toBeInTheDocument()
  })
})
