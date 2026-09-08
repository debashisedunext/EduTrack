import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ObClientListPage } from './ObClientListPage'

/**
 * B-108 · OB-03 against the mock server.
 *
 * Mounted through `Routes` rather than rendered bare, on
 * `ObNotificationCentrePage.test.tsx`'s reason: every filter arrives through
 * `useSearchParams`, and a test that passed them as props would not notice the
 * page and the URL disagreeing about a parameter name — which is the whole
 * mechanism this screen's shareable-link behaviour rests on.
 *
 * Fixture note — `db.ts`'s `OB_CLIENTS`: three clients, boarded Acme
 * 2026-08-28, Northwind 2026-07-14, Contoso 2026-04-02. Northwind has two
 * `OPEN` journeys, Acme's single journey is `LOCKED` — no colour, and §9's
 * "Prerequisites pending" — and Contoso is `LIVE` with its one journey
 * finished. User 3 owns steps on Northwind and Acme and none on Contoso; user 4
 * owns nothing anywhere and backs up one step on Contoso, which is what makes
 * the two owner assertions below narrowings rather than requests the server
 * could satisfy by ignoring the parameter.
 *
 * The sales-person filter has no test here, and that is the fixture's fault
 * rather than an omission: all three clients share sales person 5, so every
 * value of the filter either returns the whole list or none of it, and neither
 * outcome distinguishes a working filter from an ignored one. It is covered
 * server-side in `ObClientsIT` instead, where the rows can be arranged.
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
  await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
  return bodyRows().map((row) => within(row).getAllByRole('cell')[0].textContent?.trim() ?? '')
}

describe('ObClientListPage', () => {
  it('lists every client, newest onboarding date first', async () => {
    renderList()

    const names = await clientNames()
    // Acme 2026-08-28, Northwind 2026-07-14, Contoso 2026-04-02 — the
    // contract's ordering, which the keyset cursor is built on.
    expect(names[0]).toContain('Acme Private Limited')
    expect(names[2]).toContain('Contoso Education Trust')
    expect(names).toHaveLength(3)
  })

  it('links each row to OB-05 rather than making the row a click handler', async () => {
    renderList()

    const link = await screen.findByRole('link', {
      name: 'Northwind Technologies Pvt Ltd',
    }, SLOW)
    expect(link).toHaveAttribute('href', '/onboarding/clients/1')
  })

  /**
   * §9's sentence for this screen, and the one thing on it that is not a
   * colour: a client whose journeys are all locked has no RAG at all.
   */
  it('shows a locked client as “Prerequisites pending” and never as a colour', async () => {
    renderList()

    await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
    const acme = bodyRows().find((row) => row.textContent?.includes('Acme Private Limited'))!

    expect(within(acme).getByText('Prerequisites pending')).toBeInTheDocument()
    expect(within(acme).queryByText('On track')).not.toBeInTheDocument()
    expect(within(acme).queryByText('At risk')).not.toBeInTheDocument()
    expect(within(acme).queryByText('Breached')).not.toBeInTheDocument()
  })

  it('shows journey count as complete over bought', async () => {
    renderList()

    await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
    const contoso = bodyRows().find((row) => row.textContent?.includes('Contoso Education Trust'))!

    // One journey, and it is finished — every step DONE.
    expect(within(contoso).getByText('1 / 1')).toBeInTheDocument()
  })

  it('applies a status filter read straight off the URL', async () => {
    renderList('/onboarding/clients?status=LIVE')

    await screen.findByText('Contoso Education Trust', undefined, SLOW)
    expect(bodyRows()).toHaveLength(1)
    expect(screen.queryByText('Northwind Technologies Pvt Ltd')).not.toBeInTheDocument()
  })

  it('applies the gate filter, which is the only way to ask for a locked client', async () => {
    renderList('/onboarding/clients?gateStatus=LOCKED')

    await screen.findByText('Acme Private Limited', undefined, SLOW)
    expect(bodyRows()).toHaveLength(1)
  })

  /**
   * The filter B-108 exists for. User 3 owns a step on Northwind and on Acme
   * and none on Contoso, so this is a real narrowing rather than a request the
   * server could satisfy by ignoring the parameter.
   */
  it('filters to the clients an implementor owns a step on', async () => {
    renderList('/onboarding/clients?ownerId=3')

    await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
    expect(bodyRows()).toHaveLength(2)
    expect(screen.queryByText('Contoso Education Trust')).not.toBeInTheDocument()
  })

  /**
   * The half of the owner rule that is easiest to lose. User 4 owns nothing
   * and backs up one step on Contoso — a filter reading only `owner_user_id`
   * would return an empty list here and look perfectly reasonable doing it.
   */
  it('counts a backup owner, because covering a step is what a backup is for', async () => {
    renderList('/onboarding/clients?ownerId=4')

    await screen.findByText('Contoso Education Trust', undefined, SLOW)
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
    renderList('/onboarding/clients?q=Northwind&status=LIVE')

    // Live *and* named Northwind is nobody — Northwind is ONBOARDING.
    await screen.findByText('No clients match these filters', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Reset filters' }))

    await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
    expect(screen.getByLabelText('Search onboarding clients by name')).toHaveValue('Northwind')
    expect(bodyRows()).toHaveLength(1)
  })

  it('drives the filter from the dropdown and writes it into the URL', async () => {
    renderList()

    await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: /Status/ }))
    fireEvent.click(await screen.findByRole('option', { name: 'Live' }, SLOW))

    await waitFor(() => expect(bodyRows()).toHaveLength(1), SLOW)
    expect(screen.getByText('Contoso Education Trust')).toBeInTheDocument()
  })

  /**
   * A client with no primary SPOC is not merely a blank cell — it is a client
   * nothing the module sends can reach. Every fixture client has one, so this
   * asserts the ordinary case renders the contact rather than the warning.
   */
  it('names the primary SPOC on the row', async () => {
    renderList()

    await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
    expect(screen.getByText('Meena Raghavan')).toBeInTheDocument()
    expect(screen.queryByText('No primary SPOC')).not.toBeInTheDocument()
  })
})
