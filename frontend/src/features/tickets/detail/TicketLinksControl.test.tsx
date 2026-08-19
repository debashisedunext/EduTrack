import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import type { LinkedTicket } from '@/api/generated/model/linkedTicket'

import { TicketLinksControl } from './TicketLinksControl'

/**
 * C-064 · the "Linked" row of S-20's summary panel.
 *
 * Against the real mock handlers rather than a per-test stub — unlike
 * `TicketLevelControl.test.tsx`, `POST`/`DELETE .../links` are this stream's
 * own addition to `frontend/src/mocks/`, so there is no owner boundary
 * stopping the test from exercising the actual canonicalisation and scoping
 * logic the handler carries.
 */

/** Radix's dialog needs APIs jsdom lacks. */
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

let pathId: string
let targetId: string
let targetTitle: string

beforeEach(() => {
  // Admin, so scope never narrows what the search combobox can find — the
  // point under test is the linking logic, not `scopedTickets`, which
  // `TicketList` and the mocks' own suite already cover.
  getDb().currentUserId = 1

  const [path, target] = getDb().tickets
  pathId = path.ticketId
  targetId = target.ticketId
  targetTitle = target.title
})

function renderControl(props: Partial<Parameters<typeof TicketLinksControl>[0]> = {}) {
  const onChanged = () => {
    calls.push(true)
  }
  const calls: boolean[] = []
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TicketLinksControl ticketId={pathId} onChanged={onChanged} {...props} />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { user: userEvent.setup(), changedCount: () => calls.length }
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /add link/i }))
  return screen.findByRole('dialog')
}

/** Search for the target ticket and click its result row. */
async function pickTarget(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, query: string) {
  await user.type(within(dialog).getByLabelText(/ticket/i), query)
  const option = await within(dialog).findByRole('button', { name: new RegExp(targetId) }, { timeout: 4000 })
  await user.click(option)
}

describe('TicketLinksControl — C-064, blueprint §16 item 17', () => {
  it('renders "None" with no links yet', () => {
    renderControl({ links: [] })

    expect(screen.getByText('None')).toBeInTheDocument()
  })

  it('renders each link as a label plus a link to the other ticket', () => {
    const links: LinkedTicket[] = [
      { id: 1, linkType: 'BLOCKED_BY', ticket: { ticketId: targetId, title: targetTitle, level: 'HIGH', status: 'IN_PROGRESS' } },
    ]
    renderControl({ links })

    expect(screen.getByText('Is blocked by')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: targetId })
    expect(link).toHaveAttribute('href', `/tickets/${targetId}`)
  })

  it('excludes the path ticket itself from the search results', async () => {
    const { user } = renderControl({ links: [] })
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByLabelText(/ticket/i), pathId)

    await waitFor(() => expect(within(dialog).getByText(/no matching tickets/i)).toBeInTheDocument(), {
      timeout: 4000,
    })
    expect(within(dialog).queryByRole('button', { name: new RegExp(pathId) })).not.toBeInTheDocument()
  })

  it('creates a link and asks the page to refetch', async () => {
    const { user, changedCount } = renderControl({ links: [] })
    const dialog = await openDialog(user)

    await user.selectOptions(within(dialog).getByLabelText(/relationship/i), 'BLOCKS')
    await pickTarget(user, dialog, targetId)
    await user.click(within(dialog).getByRole('button', { name: 'Link' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(changedCount()).toBe(1))
  })

  it('removes a link and asks the page to refetch', async () => {
    // Created for real, through the same handler `createTicketLink` calls —
    // a link id fabricated only in the prop would not exist in the mock's
    // own store, and the delete would 404 against a row that was never there.
    const created = await fetch(`/api/v1/tickets/${pathId}/links`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ targetTicketId: targetId, linkType: 'RELATES_TO' }),
    })
    const { data } = (await created.json()) as { data: LinkedTicket }

    const { user, changedCount } = renderControl({ links: [data] })

    await user.click(screen.getByRole('button', { name: new RegExp(`remove link.*${targetId}`, 'i') }))

    await waitFor(() => expect(changedCount()).toBe(1))
  })

  it('reports a duplicate relationship without closing the dialog', async () => {
    // Created for real, through the same canonicalisation `createTicketLink`
    // applies — hand-inserting a row into `getDb().ticketLinks` would have to
    // reimplement that ordering to collide reliably, and getting it wrong
    // would make the second submission succeed instead of conflicting.
    await fetch(`/api/v1/tickets/${pathId}/links`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ targetTicketId: targetId, linkType: 'RELATES_TO' }),
    })
    const { user } = renderControl({ links: [] })
    const dialog = await openDialog(user)

    await user.selectOptions(within(dialog).getByLabelText(/relationship/i), 'RELATES_TO')
    await pickTarget(user, dialog, targetId)
    await user.click(within(dialog).getByRole('button', { name: 'Link' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
