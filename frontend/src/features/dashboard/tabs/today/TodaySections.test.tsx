import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { server } from '@/mocks/server'

import { TodaySections } from './TodaySections'

/**
 * Dashboard Rework Dev 1 · tab 1, PR 8 — the six collapsible sections.
 *
 * Every test overrides the `/tickets` handler explicitly rather than relying
 * on the seeded db, the same reason `TodayTab.test.tsx`'s sub-figure tests
 * do: a section can be empty, full, or erroring on any given seed, and the
 * assertions here are about the section's own behaviour — lazy fetch,
 * "View all", the empty and error states — not about what the fixture
 * happens to contain today.
 */

function renderSections() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TodaySections scope={{ today: '2026-09-01' }} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const ticket = (overrides: Partial<Record<string, unknown>> = {}) => ({
  ticketId: 'CRM-26-00951',
  title: 'Client portal shows stale ticket count',
  level: 'MEDIUM',
  status: 'NEW',
  cycleNo: 1,
  assignee: { id: 5, displayName: 'Neha Singh', role: 'DEVELOPER' },
  plannedCloseDate: '2026-08-28',
  ...overrides,
})

describe('TodaySections', () => {
  it('renders all six sections, collapsed, and fetches nothing until expanded', () => {
    const handler = vi.fn(() => HttpResponse.json({ data: [ticket()], meta: {} }))
    server.use(http.get('/api/v1/tickets', handler))

    renderSections()

    for (const title of [
      'Not started — overdue / due today',
      'Started today',
      'Finished today',
      'WIP — updated today',
      'WIP — not updated today',
      'Blocked / on hold',
    ]) {
      expect(screen.getByRole('button', { name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })).toBeInTheDocument()
    }
    expect(handler).not.toHaveBeenCalled()
  })

  it('fetches and shows rows once a section is expanded', async () => {
    server.use(
      http.get('/api/v1/tickets', () => HttpResponse.json({ data: [ticket()], meta: { hasMore: false } })),
    )
    renderSections()

    await userEvent.click(screen.getByRole('button', { name: /Started today/ }))

    expect(await screen.findByText('CRM-26-00951')).toBeInTheDocument()
    expect(screen.getByText('Neha Singh')).toBeInTheDocument()
  })

  it('collapses again on a second click without re-fetching', async () => {
    const handler = vi.fn(() => HttpResponse.json({ data: [ticket()], meta: {} }))
    server.use(http.get('/api/v1/tickets', handler))
    renderSections()

    const button = screen.getByRole('button', { name: /Finished today/ })
    await userEvent.click(button)
    await screen.findByText('CRM-26-00951')
    await userEvent.click(button)

    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('CRM-26-00951')).not.toBeInTheDocument()
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('shows a "View all" link only when the section has more than the page fetched', async () => {
    server.use(
      http.get('/api/v1/tickets', () => HttpResponse.json({ data: [ticket()], meta: { hasMore: true } })),
    )
    renderSections()

    await userEvent.click(screen.getByRole('button', { name: /Blocked \/ on hold/ }))

    const link = await screen.findByRole('link', { name: 'View all in ticket list →' })
    expect(link).toHaveAttribute('href', '/tickets?statuses=ON_HOLD%2CAWAITING_INFO')
  })

  it('shows an empty state when a section has nothing in it', async () => {
    server.use(http.get('/api/v1/tickets', () => HttpResponse.json({ data: [], meta: {} })))
    renderSections()

    await userEvent.click(screen.getByRole('button', { name: /WIP — updated today/ }))

    expect(await screen.findByText('Nothing here')).toBeInTheDocument()
  })

  it('shows an error state without blaming the figures above', async () => {
    server.use(http.get('/api/v1/tickets', () => HttpResponse.error()))
    renderSections()

    await userEvent.click(screen.getByRole('button', { name: /WIP — not updated today/ }))

    expect(await screen.findByText('These tickets could not be loaded')).toBeInTheDocument()
  })

  it('scopes every section to the dashboard\'s project filter', async () => {
    const handler = vi.fn(() => HttpResponse.json({ data: [], meta: {} }))
    server.use(http.get('/api/v1/tickets', handler))

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <TodaySections scope={{ today: '2026-09-01', projectId: 4 }} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await userEvent.click(screen.getByRole('button', { name: /Started today/ }))
    await screen.findByText('Nothing here')

    const [info] = handler.mock.calls[0] as unknown as [{ request: Request }]
    const url = new URL(info.request.url)
    expect(url.searchParams.get('projectId')).toBe('4')
  })
})
