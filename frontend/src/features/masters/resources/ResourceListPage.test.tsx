import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { ResourceListPage } from './ResourceListPage'

/** Radix's popover and dialog primitives need APIs jsdom does not implement. */
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

let requests: URL[] = []
beforeAll(() =>
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url)
    if (url.pathname.endsWith('/users') || url.pathname.endsWith('/users/bulk-status')) {
      requests.push(url)
    }
  }),
)

// The bulk-status handler mutates `isActive` on the shared fixture db, which
// `src/test/setup.ts` already resets after every test. Only the request log is
// this file's to clear.
afterEach(() => {
  requests = []
})

function renderPage(initialEntry = '/masters/resources') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/masters/resources" element={<ResourceListPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const waitForGrid = () =>
  waitFor(() => expect(screen.getByText('Ravi Kumar')).toBeInTheDocument())

/**
 * Finds a row by its select checkbox rather than by the name in it.
 *
 * A name is not unique on this grid — Anita Rao is a row *and* the Reporting
 * Manager of four other rows, and the results dialog prints the same names
 * again. The checkbox's accessible name is one per row by construction.
 *
 * Note this cannot see the grid while the results dialog is open: Radix marks
 * the rest of the page `aria-hidden`, correctly, so row assertions come after
 * the dialog is dismissed.
 */
const row = (name: string) =>
  screen.getByRole('checkbox', { name: `Select ${name}` }).closest('tr')!

const closeDialog = async () => {
  fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Done' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
}

/**
 * An active resource holding no open tickets, and one holding several, read
 * out of the fixture rather than named.
 *
 * The mock assigns tickets by stage owner role, so which person ends up clean
 * is a consequence of the stage seed. Hardcoding a name here would make this
 * file fail the next time somebody adds a stage — a fixture change wearing a
 * product bug's clothes.
 */
function pickResources() {
  const db = getDb()
  const openCount = (id: number) =>
    db.tickets.filter((t) => t.assigneeId === id && t.status !== 'CLOSED').length
  const active = db.users.filter((u) => u.isActive)

  const clean = active.find((u) => openCount(u.id) === 0)!
  const busy = active.find((u) => openCount(u.id) > 0)!
  return { clean, busy }
}

describe('the grid', () => {
  it('renders every S-07 column header', async () => {
    renderPage()
    await waitForGrid()

    for (const header of [
      'Emp Code', 'Name', 'Email', 'Role', 'Department',
      'Reporting Manager', 'Projects', 'Status', 'Last login',
    ]) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
  })

  it('renders the row data the columns promise', async () => {
    renderPage()
    await waitForGrid()

    const ravi = row('Ravi Kumar')
    expect(within(ravi).getByText('EMP-003')).toBeInTheDocument()
    expect(within(ravi).getByText('ravi@edunext.example')).toBeInTheDocument()
    expect(within(ravi).getByText('Developer')).toBeInTheDocument()
    expect(within(ravi).getByText('Engineering')).toBeInTheDocument()
    expect(within(ravi).getByText('Meera Iyer')).toBeInTheDocument()
    expect(within(ravi).getByText('Active')).toBeInTheDocument()
  })

  it('shows project codes, not ids', async () => {
    renderPage()
    await waitForGrid()

    // Ravi is on CRM and PAY but not WEB — a row that printed all three, or
    // printed "1, 2", would pass a laxer assertion than this one.
    const ravi = row('Ravi Kumar')
    expect(within(ravi).getByText('CRM')).toBeInTheDocument()
    expect(within(ravi).getByText('PAY')).toBeInTheDocument()
    expect(within(ravi).queryByText('WEB')).not.toBeInTheDocument()
  })

  it('says "Never" rather than blank for someone who has not logged in', async () => {
    renderPage()
    await waitForGrid()

    expect(within(row('Karan Bose')).getByText('Never')).toBeInTheDocument()
  })

  it('shows deactivated resources by default — this is the screen that reactivates them', async () => {
    renderPage()
    await waitForGrid()

    expect(within(row('Sunil Menon')).getByText('Inactive')).toBeInTheDocument()
  })
})

describe('filters', () => {
  it('reads its initial state from the URL, so a filtered list is a link', async () => {
    renderPage('/masters/resources?role=DEVELOPER')
    await waitForGrid()

    expect(screen.getByText('Sunil Menon')).toBeInTheDocument()
    expect(screen.queryByText('Anita Rao')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Role: Developer/ })).toBeInTheDocument()
  })

  it('sends the status filter as a real boolean, both ways', async () => {
    renderPage('/masters/resources?isActive=false')
    await waitFor(() => expect(screen.getByText('Sunil Menon')).toBeInTheDocument())

    // `isActive=false` must reach the server. Treated as "unset" — the ticket
    // list's convention for its boolean filters — this screen could never show
    // only the deactivated resources.
    expect(requests.at(-1)?.searchParams.get('isActive')).toBe('false')
    expect(screen.queryByText('Ravi Kumar')).not.toBeInTheDocument()
  })

  it('filters by manager', async () => {
    renderPage('/masters/resources?managerId=1')
    await waitFor(() => expect(screen.getByText('Meera Iyer')).toBeInTheDocument())

    expect(screen.queryByText('Ravi Kumar')).not.toBeInTheDocument()
  })

  it('offers Reset once a filter is set, and clearing it restores everyone', async () => {
    renderPage('/masters/resources?role=QA')
    await waitFor(() => expect(screen.getByText('Anil Shah')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Reset \(1\)/ }))

    await waitFor(() => expect(screen.getByText('Ravi Kumar')).toBeInTheDocument())
  })

  it('reports an empty result rather than an empty table', async () => {
    renderPage('/masters/resources?q=nobody-by-that-name')

    await waitFor(() =>
      expect(screen.getByText('No resources match these filters')).toBeInTheDocument(),
    )
  })
})

describe('export', () => {
  it('links to the export route carrying the current filters', async () => {
    renderPage('/masters/resources?role=DEVELOPER&isActive=true')
    await waitForGrid()

    const href = screen.getByRole('link', { name: /Export/ }).getAttribute('href')!
    const url = new URL(href, 'http://localhost')

    expect(url.pathname).toBe('/api/v1/users/export')
    expect(url.searchParams.get('format')).toBe('xlsx')
    expect(url.searchParams.get('role')).toBe('DEVELOPER')
    expect(url.searchParams.get('isActive')).toBe('true')
    // The export is every matching row. Carrying the page's cursor would give
    // the user a file that looks complete and is not.
    expect(url.searchParams.has('cursor')).toBe(false)
    expect(url.searchParams.has('limit')).toBe(false)
  })
})

describe('bulk activate and deactivate', () => {
  it('stays hidden until something is selected', async () => {
    renderPage()
    await waitForGrid()

    expect(screen.queryByRole('region', { name: 'Bulk actions' })).not.toBeInTheDocument()
  })

  it('deactivates a clean selection without stopping to ask', async () => {
    const { clean } = pickResources()
    renderPage()
    await waitForGrid()

    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${clean.displayName}` }))
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(within(row(clean.displayName)).getByText('Inactive')).toBeInTheDocument())
    // Nothing needed attention, so nothing interrupts: a dialog for a clean run
    // is a click somebody has to make for no reason.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(requests.some((u) => u.pathname.endsWith('/users/bulk-status'))).toBe(true)
  })

  it('refuses to deactivate someone holding open tickets, and names them', async () => {
    const { busy } = pickResources()
    renderPage()
    await waitForGrid()

    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${busy.displayName}` }))
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(busy.displayName)).toBeInTheDocument()
    expect(within(dialog).getByText(/open tickets? to reassign first/)).toBeInTheDocument()

    // And they are still active — deactivating anyway would orphan live work.
    await closeDialog()
    expect(within(row(busy.displayName)).getByText('Active')).toBeInTheDocument()
  })

  it('a mixed selection deactivates who it can and reports who it could not', async () => {
    const { clean, busy } = pickResources()
    renderPage()
    await waitForGrid()

    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${clean.displayName}` }))
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${busy.displayName}` }))
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    // Partial success is the normal case: failing the batch would punish the
    // clean resource for the busy one's open tickets.
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading')).toHaveTextContent('1 deactivated')
    expect(within(dialog).getByText(/blocked by open tickets/)).toBeInTheDocument()

    await closeDialog()
    await waitFor(() =>
      expect(within(row(clean.displayName)).getByText('Inactive')).toBeInTheDocument(),
    )
    expect(within(row(busy.displayName)).getByText('Active')).toBeInTheDocument()
  })

  it('select-all on the page ticks every row', async () => {
    renderPage()
    await waitForGrid()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select all resources on this page' }))

    expect(screen.getByText(/7 selected/)).toBeInTheDocument()
  })

  it('clears the selection when a filter changes', async () => {
    renderPage()
    await waitForGrid()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Ravi Kumar' }))
    expect(screen.getByText(/1 selected/)).toBeInTheDocument()

    // Acting on people you can no longer see is how a bulk deactivation
    // surprises somebody.
    fireEvent.click(screen.getByRole('button', { name: /^Status/ }))
    fireEvent.click(await screen.findByRole('option', { name: 'Active' }))

    await waitFor(() => expect(screen.queryByText(/1 selected/)).not.toBeInTheDocument())
  })
})
