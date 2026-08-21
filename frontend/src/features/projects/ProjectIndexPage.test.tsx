import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ProjectIndexPage } from './ProjectIndexPage'

/**
 * A-077 · the index the sidebar leads to.
 *
 * <p>What is worth pinning is that this screen stays a *reader*. The pull to
 * grow it into a second project grid is real — it already fetches the list — and
 * the moment it does, the product has two project lists with different columns
 * and the next person to change one will not know about the other.
 */

const PROJECTS = {
  data: [
    { id: 1, projectCode: 'CRM', name: 'Client CRM Platform', clientName: 'Northwind', status: 'ACTIVE', isActive: true },
    { id: 2, projectCode: 'PAY', name: 'Payments Gateway', clientName: 'Acme Corp', status: 'ACTIVE', isActive: true },
  ],
  meta: { hasMore: false },
}

function renderIndex(body: object = PROJECTS, status = 200) {
  server.use(
    http.get('/api/v1/projects', () =>
      status === 200 ? HttpResponse.json(body) : new HttpResponse(null, { status }),
    ),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ProjectIndexPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ProjectIndexPage', () => {
  it('links each project to its dashboard, not to the master', async () => {
    // The whole reason the screen exists. A link to /masters/projects/1/edit
    // would be a working link to the wrong screen, which is the hardest kind of
    // navigation defect to notice.
    renderIndex()

    const crm = await screen.findByRole('link', { name: /Client CRM Platform/ })
    expect(crm).toHaveAttribute('href', '/projects/1')

    const pay = screen.getByRole('link', { name: /Payments Gateway/ })
    expect(pay).toHaveAttribute('href', '/projects/2')
  })

  it('shows the ticket-ID prefix, which is what people scan for', async () => {
    // Somebody looking for the project CRM-26-13013 belongs to is scanning for
    // "CRM", not for a project name they may never have been told.
    renderIndex()

    expect(await screen.findByText('CRM')).toBeInTheDocument()
    expect(screen.getByText('PAY')).toBeInTheDocument()
  })

  it('offers the master as a separate link rather than duplicating it', async () => {
    // Stream B owns every project write. This screen linking there — instead of
    // growing its own create/edit — is what keeps one owner for those.
    renderIndex()

    const manage = await screen.findByRole('link', { name: /manage projects/i })
    expect(manage).toHaveAttribute('href', '/masters/projects')
  })

  it('lists projects whose figures the caller cannot see', async () => {
    // GET /projects is deliberately not row-scoped, so both appear here. The
    // dashboard is what answers "are these figures yours" — hiding rows here
    // would be the SPA re-deriving scope, which is exactly what A-077 moved to
    // the server.
    renderIndex()

    expect(await screen.findByRole('link', { name: /Client CRM Platform/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Payments Gateway/ })).toBeInTheDocument()
  })

  it('says so when there are no projects, rather than rendering an empty grid', async () => {
    renderIndex({ data: [], meta: { hasMore: false } })

    expect(await screen.findByText(/No projects yet/i)).toBeInTheDocument()
  })

  it('distinguishes a failed fetch from an empty list', async () => {
    // "No projects yet" over a 500 is a claim about the data and is false —
    // the same distinction the dashboard draws between withheld and absent.
    renderIndex({}, 500)

    expect(await screen.findByText(/Could not load projects/i)).toBeInTheDocument()
    expect(screen.queryByText(/No projects yet/i)).not.toBeInTheDocument()
  })
})
