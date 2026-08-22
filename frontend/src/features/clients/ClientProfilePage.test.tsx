import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ClientProfilePage } from './ClientProfilePage'

/**
 * B-066 · S-32's Client 360 view.
 *
 * The cases worth pinning: that a missing client reads as "not found" rather
 * than an error page, that a null SLA/resolution figure renders as an em
 * dash rather than 0, and that the ticket sample links out to the real,
 * filterable list instead of trying to be one.
 */

const VIEW = {
  data: {
    client: {
      id: 42,
      clientCode: 'ACME',
      name: 'Acme Corp',
      status: 'ACTIVE',
      accountManager: { id: 3, displayName: 'Priya Nair' },
      supportPlan: 'GOLD',
    },
    tickets: [
      { ticketId: 'ACME-26-00001', title: 'Login fails on SSO', level: 'HIGH', status: 'IN_PROGRESS', cycleNo: 1 },
      { ticketId: 'ACME-26-00002', title: 'Invoice export missing rows', level: 'MEDIUM', status: 'CLOSED', cycleNo: 1 },
    ],
    openCount: 4,
    closedCount: 6,
    slaCompliancePct: 66.7,
    avgResolutionHrs: 18.2,
  },
  meta: { hasMore: false },
}

function renderProfile(body: object | null = VIEW, status = 200) {
  server.use(
    http.get('/api/v1/clients/:clientId/tickets', () =>
      body === null ? new HttpResponse(null, { status }) : HttpResponse.json(body),
    ),
  )

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/clients/42']}>
        <Routes>
          <Route path="/clients/:clientId" element={<ClientProfilePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('S-32 the client 360 view', () => {
  it('renders the header, scorecard and ticket sample', async () => {
    renderProfile()

    expect(await screen.findByRole('heading', { name: 'Acme Corp' })).toBeInTheDocument()
    expect(screen.getByText('ACME')).toBeInTheDocument()
    expect(screen.getByText('Priya Nair')).toBeInTheDocument()

    expect(screen.getByText('4')).toBeInTheDocument() // openCount
    expect(screen.getByText('6')).toBeInTheDocument() // closedCount
    expect(screen.getByText('66.7%')).toBeInTheDocument()
    expect(screen.getByText('18.2h')).toBeInTheDocument()

    expect(screen.getByText('ACME-26-00001')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open full list' })).toHaveAttribute(
      'href',
      '/tickets?clientId=42',
    )
  })

  it('renders an em dash rather than 0 when nothing has closed', async () => {
    renderProfile({
      data: {
        client: VIEW.data.client,
        tickets: [],
        openCount: 1,
        closedCount: 0,
        slaCompliancePct: null,
        avgResolutionHrs: null,
      },
      meta: { hasMore: false },
    })

    await waitFor(() => expect(screen.getAllByText('—')).toHaveLength(2))
    expect(screen.getByText('No tickets have been raised against this client yet.')).toBeInTheDocument()
  })

  it('a missing client reads as not found, not an error page', async () => {
    renderProfile(null, 404)

    expect(await screen.findByText('Client not found')).toBeInTheDocument()
  })
})
