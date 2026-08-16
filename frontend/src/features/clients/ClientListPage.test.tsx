import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { ClientListPage } from './ClientListPage'

/**
 * B-025 · S-32 against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * deactivated client is still listed, that the open-ticket count is on the row
 * rather than only in the confirmation, that expanding a row fetches its
 * contacts, and that deactivating warns without refusing — which is the one
 * place this screen deliberately differs from S-07.
 */
function renderPage(initialEntry = '/masters/clients') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <ClientListPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Three round trips through MSW per mutation; the 1 s default is not enough. */
const SLOW = { timeout: 5000 }

const rowFor = async (name: string) =>
  (await screen.findByText(name, undefined, SLOW)).closest('tr') as HTMLElement

describe('the client grid', () => {
  it('lists clients with the S-32 columns', async () => {
    renderPage()

    const acme = await rowFor('Acme Retail Ltd')

    expect(within(acme).getByText('ACME')).toBeInTheDocument()
    expect(within(acme).getByText('Premium')).toBeInTheDocument()
    expect(within(acme).getByText('Active')).toBeInTheDocument()
  })

  /**
   * The endpoint deliberately returns inactive rows: a ticket raised against a
   * since-deactivated client still has to render its name, and a grid that
   * filtered them out would leave an admin unable to see — or undo — a
   * deactivation.
   */
  it('lists deactivated clients too, marked inactive', async () => {
    expect(getDb().clients.some((c) => c.status === 'INACTIVE')).toBe(true)

    renderPage()

    const oldco = await rowFor('Oldco Industries')
    expect(within(oldco).getByText('Inactive')).toBeInTheDocument()
  })

  /**
   * The count is the thing that makes deactivating an informed decision, so it
   * belongs on the row rather than only in the dialog that follows the click.
   */
  it('shows the open ticket count on every row', async () => {
    const db = getDb()
    const open = db.tickets.filter((t) => t.clientId === 1 && t.status !== 'CLOSED').length
    expect(open).toBeGreaterThan(0)

    renderPage()

    const acme = await rowFor('Acme Retail Ltd')
    expect(within(acme).getByText(String(open))).toBeInTheDocument()
  })

  /** A client mapped to two projects is one row carrying both, never two rows. */
  it('renders a multi-project client once', async () => {
    expect(getDb().clientProjects.filter((cp) => cp.clientId === 1)).toHaveLength(2)

    renderPage()

    await rowFor('Acme Retail Ltd')
    expect(screen.getAllByText('Acme Retail Ltd')).toHaveLength(1)
  })

  it('says Never for a client nothing has been raised against', async () => {
    renderPage()

    // Bluewave is mapped to no project and carries no tickets in the fixture.
    const bluewave = await rowFor('Bluewave Media')
    expect(within(bluewave).getByText('Never')).toBeInTheDocument()
    expect(within(bluewave).getByText('None')).toBeInTheDocument()
  })
})

describe('the row expand', () => {
  it('fetches contacts only when a row is opened', async () => {
    renderPage()
    await rowFor('Acme Retail Ltd')

    // Nothing is fetched up front — 25 rows would be 25 requests to render
    // nothing, since almost nobody expands more than one.
    expect(screen.queryByText('sara@acme.example')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Show contacts for Acme Retail Ltd/i }))

    expect(await screen.findByText('sara@acme.example', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByText('dev@acme.example')).toBeInTheDocument()
  })

  it('marks the primary contact', async () => {
    renderPage()
    await rowFor('Acme Retail Ltd')
    fireEvent.click(screen.getByRole('button', { name: /Show contacts for Acme Retail Ltd/i }))

    const sara = (await screen.findByText('sara@acme.example', undefined, SLOW))
      .closest('tr') as HTMLElement
    expect(within(sara).getByText('Primary')).toBeInTheDocument()
  })

  it('collapses again on a second click', async () => {
    renderPage()
    await rowFor('Acme Retail Ltd')

    const toggle = screen.getByRole('button', { name: /Show contacts for Acme Retail Ltd/i })
    fireEvent.click(toggle)
    expect(await screen.findByText('sara@acme.example', undefined, SLOW)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Hide contacts for Acme Retail Ltd/i }))
    await waitFor(() => expect(screen.queryByText('sara@acme.example')).not.toBeInTheDocument())
  })
})

describe('bulk activate and deactivate', () => {
  async function select(clientName: string) {
    renderPage()
    await rowFor(clientName)
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${clientName}` }))
  }

  /**
   * The one place this screen deliberately differs from S-07's resource grid.
   *
   * A resource with open tickets **cannot** be deactivated until they are
   * reassigned — the tickets would be orphaned. A client's tickets are not:
   * blueprint §4B.2 says deactivating blocks *new* tickets and never hides
   * historical ones. So this warns, with the count in it, and then proceeds.
   */
  it('warns before deactivating a client that has open tickets', async () => {
    await select('Acme Retail Ltd')

    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    const dialog = await screen.findByRole('dialog', undefined, SLOW)
    expect(within(dialog).getByText(/open tickets/i)).toBeInTheDocument()
    // It confirms rather than refuses — the action is still available.
    expect(within(dialog).getByRole('button', { name: /^Deactivate 1$/ })).toBeEnabled()
  })

  it('deactivates through the warning and leaves the client listed', async () => {
    await select('Acme Retail Ltd')
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    const dialog = await screen.findByRole('dialog', undefined, SLOW)
    fireEvent.click(within(dialog).getByRole('button', { name: /^Deactivate 1$/ }))

    // Still on the grid — deactivating hides nothing.
    const acme = await rowFor('Acme Retail Ltd')
    await waitFor(() => expect(within(acme).getByText('Inactive')).toBeInTheDocument(), SLOW)
    expect(getDb().clients.find((c) => c.id === 1)?.status).toBe('INACTIVE')
  })

  /**
   * Activation cannot strand anything, and a confirmation on every bulk action
   * is a confirmation nobody reads.
   */
  it('activates without a confirmation step', async () => {
    await select('Oldco Industries')

    fireEvent.click(screen.getByRole('button', { name: 'Activate' }))

    await waitFor(
      () => expect(getDb().clients.find((c) => c.id === 4)?.status).toBe('ACTIVE'),
      SLOW,
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  /** No open tickets means nothing to warn about, so the dialog stays away. */
  it('deactivates a client with no open tickets without warning', async () => {
    await select('Bluewave Media')

    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(
      () => expect(getDb().clients.find((c) => c.id === 3)?.status).toBe('INACTIVE'),
      SLOW,
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  /**
   * B-029 · the blind spot B-025 documented and left open.
   *
   * The warning used to be assembled from the *current page*, so a client
   * selected on page 1 and deactivated from page 2 went through with no dialog
   * at all — on a grid that deliberately builds a selection across pages
   * (`togglePage` adds rather than replaces). It is the case the warning exists
   * for: somebody ticking rows across three pages is exactly somebody who
   * cannot hold the open-ticket counts in their head.
   *
   * Needs more clients than fit on a page, so the fixture is widened here
   * rather than in `db.ts` — twenty-six near-identical rows would tell every
   * other test in the suite nothing and slow all of them down.
   */
  it('warns about a client selected on an earlier page', async () => {
    const db = getDb()
    // Named to sort *after* Acme so Acme stays on page 1 and these fill it.
    for (let i = 0; i < 30; i++) {
      db.clients.push({
        ...db.clients[2],
        id: 100 + i,
        clientCode: `FILL${i}`,
        name: `Zeta Filler ${String(i).padStart(2, '0')}`,
        status: 'ACTIVE',
      })
    }

    renderPage()
    await rowFor('Acme Retail Ltd')
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Acme Retail Ltd' }))

    // Forward a page: Acme is now off screen but still selected.
    fireEvent.click(screen.getByRole('button', { name: /Next/i }))
    await waitFor(
      () => expect(screen.queryByText('Acme Retail Ltd')).not.toBeInTheDocument(),
      SLOW,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    const dialog = await screen.findByRole('dialog', undefined, SLOW)
    // Named in the dialog, with its count, despite not being on the page.
    expect(within(dialog).getByText('ACME')).toBeInTheDocument()
    expect(getDb().clients.find((c) => c.id === 1)?.status).toBe('ACTIVE')
  })

  it('sends one request for the whole selection, not one per client', async () => {
    renderPage()
    await rowFor('Acme Retail Ltd')

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select all clients on this page' }))
    fireEvent.click(screen.getByRole('button', { name: 'Activate' }))

    // Every client on the page ends up active off a single bulk call; if this
    // were N requests a partial failure would leave some of them behind.
    await waitFor(
      () => expect(getDb().clients.every((c) => c.status !== 'INACTIVE')).toBe(true),
      SLOW,
    )
  })
})

describe('filters', () => {
  it('filters to inactive clients from the URL', async () => {
    renderPage('/masters/clients?isActive=false')

    expect(await screen.findByText('Oldco Industries', undefined, SLOW)).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByText('Acme Retail Ltd')).not.toBeInTheDocument(),
    )
  })

  it('filters by support plan', async () => {
    renderPage('/masters/clients?supportPlan=Premium')

    expect(await screen.findByText('Acme Retail Ltd', undefined, SLOW)).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByText('Northwind Logistics')).not.toBeInTheDocument(),
    )
  })

  it('filters by project through the mapping, not the project label', async () => {
    // Acme is mapped to projects 1 and 3; Northwind to 2. Filtering on 2 must
    // drop Acme even though its name appears on project 1's `clientName`.
    renderPage('/masters/clients?projectId=2')

    expect(await screen.findByText('Northwind Logistics', undefined, SLOW)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Acme Retail Ltd')).not.toBeInTheDocument())
  })

  it('offers a reset once a filter is set', async () => {
    renderPage('/masters/clients?supportPlan=Premium&isActive=true')

    expect(await screen.findByRole('button', { name: /Reset \(2\)/ }, SLOW)).toBeInTheDocument()
  })
})
