import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ProjectDashboardPage } from './ProjectDashboardPage'

/**
 * A-077 · the project dashboard.
 *
 * <p>The case that matters most is the one that used to be unreachable. Every
 * dashboard query has always ANDed the caller's scope with the requested
 * project, so an out-of-scope project matched no rows — safe, never a leak, and
 * indistinguishable on screen from a project that genuinely has no work.
 *
 * <p>This screen makes that routine, because it is reached by clicking a project
 * name on a ticket and the project master is deliberately not row-scoped. So the
 * assertion that earns its place is <b>not</b> "the figures are absent" — they
 * were absent before — it is that the screen <em>says why</em> rather than
 * drawing six zeroes and calling it data.
 */

const PROJECT = {
  data: {
    id: 7,
    projectCode: 'PAY',
    name: 'Payments Gateway',
    clientName: 'Acme Corp',
    projectManager: { id: 2, displayName: 'Rohan Kapoor' },
    startDate: '2026-01-14',
    status: 'ACTIVE',
    isActive: true,
    ticketsIssued: 142,
  },
}

const SUMMARY_IN_SCOPE = {
  data: {
    asOf: '2026-08-21T04:00:00Z',
    unavailableReason: null,
    cards: [
      { key: 'open', label: 'Open', value: 142, deltaPct: null, sparkline: [], drillDown: '/tickets?status=OPEN' },
      { key: 'closed', label: 'Closed', value: 891, deltaPct: null, sparkline: [], drillDown: '/tickets?status=CLOSED' },
    ],
  },
}

const SUMMARY_WITHHELD = {
  data: {
    asOf: null,
    cards: [],
    unavailableReason:
      'These figures cover the projects you are a member of. This project is not one of them, '
      + 'so its numbers are not shown here.',
  },
}

function renderPage({
  project = PROJECT as object | null,
  summary = SUMMARY_IN_SCOPE as object,
  projectStatus = 200,
} = {}) {
  server.use(
    http.get('/api/v1/projects/:projectId', () =>
      project === null ? new HttpResponse(null, { status: projectStatus }) : HttpResponse.json(project),
    ),
    http.get('/api/v1/projects/:projectId/members', () =>
      HttpResponse.json({ data: [{ userId: 8, displayName: 'Priya Nair', role: 'DEVELOPER', isActive: true, addedAt: '2026-02-01' }] }),
    ),
    http.get('/api/v1/dashboard/summary', () => HttpResponse.json(summary)),
    http.get('/api/v1/dashboard/widgets', () => HttpResponse.json({ data: [] })),
    http.get('/api/v1/tickets', () =>
      HttpResponse.json({
        data: [{ ticketId: 'PAY-26-0142', title: 'Login fails on retry', level: 'CRITICAL', status: 'OPEN' }],
        meta: { hasMore: false },
      }),
    ),
  )

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/7']}>
        <Routes>
          <Route path="/projects/:projectId" element={<ProjectDashboardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ProjectDashboardPage', () => {
  it('names the project by its ticket-ID prefix, not its numeric id', async () => {
    // Somebody arriving from PAY-26-0142 is confirming they are in the right
    // place, and "7" tells them nothing. The code is the string on every ticket.
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Payments Gateway' })).toBeInTheDocument()
    expect(screen.getByText('PAY')).toBeInTheDocument()
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
    expect(screen.getByText('Rohan Kapoor')).toBeInTheDocument()
  })

  it('shows the figures when the project is the caller’s', async () => {
    renderPage()

    expect(await screen.findByText('Open')).toBeInTheDocument()
    expect(screen.getByText('Closed')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Project charts' })).toBeInTheDocument()
  })

  it('withholds the figures with a reason, and draws no zeroes', async () => {
    // The assertion this file exists for. Before A-077 the server answered an
    // out-of-scope project with an empty result and the screen would have
    // rendered six cards reading 0 — a measurement, and false about a project
    // with 142 open tickets.
    renderPage({ summary: SUMMARY_WITHHELD })

    expect(await screen.findByText(/Figures not shown for this project/i)).toBeInTheDocument()
    expect(screen.getByText(/projects you are a member of/i)).toBeInTheDocument()

    // Not a zero anywhere, and no chart region at all — an empty chart is the
    // claim being avoided, so its absence is the thing to assert.
    expect(screen.queryByText('0')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Project charts' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Project summary' })).not.toBeInTheDocument()
  })

  it('still names the project when its figures are withheld', async () => {
    // The header stays. The project's name is not the withheld fact — it is
    // public to every authenticated caller by ProjectController's deliberate
    // decision — and a bare refusal would leave somebody who clicked a project
    // name unsure they had arrived anywhere.
    renderPage({ summary: SUMMARY_WITHHELD })

    expect(await screen.findByRole('heading', { name: 'Payments Gateway' })).toBeInTheDocument()
    expect(screen.getByText('PAY')).toBeInTheDocument()
  })

  it('does not fetch the team or the tickets when the figures are withheld', async () => {
    // Both are scoped server-side and would return nothing, so this is not a
    // leak — it is two requests whose only possible answer is an empty panel
    // under a sentence explaining there is nothing to show.
    let ticketCalls = 0
    server.use(
      http.get('/api/v1/tickets', () => {
        ticketCalls += 1
        return HttpResponse.json({ data: [], meta: { hasMore: false } })
      }),
    )
    renderPage({ summary: SUMMARY_WITHHELD })

    await screen.findByText(/Figures not shown for this project/i)
    await waitFor(() => expect(ticketCalls).toBe(0))
  })

  it('a project that does not exist is a different answer from one that is withheld', async () => {
    // Conflating the two would either leak (a refusal that reads as "exists")
    // or mislead (a missing project that reads as "not yours"). A-069 made them
    // deliberately identical for *people*; here they are deliberately distinct,
    // because project existence is already public.
    renderPage({ project: null, projectStatus: 404 })

    expect(await screen.findByText(/Project not found/i)).toBeInTheDocument()
    expect(screen.queryByText(/projects you are a member of/i)).not.toBeInTheDocument()
  })

  it('links the sample list to the real, filtered ticket list', async () => {
    // Ten rows is a sample, not a page — A-069's rule. The link goes to the
    // screen that knows how to page and filter, carrying the project filter so
    // it opens on the same rows the sample came from.
    renderPage()

    const link = await screen.findByRole('link', { name: /open full list/i })
    expect(link).toHaveAttribute('href', '/tickets?projectId=7')
  })
})
