import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ReportsHubPage } from './ReportsHubPage'
import { ReportViewerPage } from './ReportViewerPage'

/**
 * A-063 · S-27 against the mock server.
 *
 * <p>The assertions worth making here are the ones about what the screen says
 * when a report <em>cannot</em> be run. Seventeen of eighteen are unbuilt for
 * the next few sprints, so "greyed card carrying its reason" is the state most
 * users will actually meet, and it is the one a screenshot review would skim
 * past.
 */

const CATALOGUE = {
  data: {
    reports: [
      {
        key: 'date-wise',
        title: 'Date-wise Report',
        description: 'Created against closed and reopened per day.',
        category: 'DELIVERY',
        chart: 'line',
        filters: ['DATE_RANGE', 'PROJECT'],
        available: true,
        unavailableReason: null,
      },
      {
        key: 'resource-scorecard',
        title: 'Resource Performance Scorecard',
        description: 'Assigned, closed, on-time %.',
        category: 'PEOPLE',
        chart: 'bar',
        filters: ['DATE_RANGE', 'RESOURCE'],
        available: false,
        unavailableReason: 'This report is not built yet.',
      },
      {
        key: 'email-delivery-log',
        title: 'Email Delivery Log',
        description: 'Every notification mail and its state.',
        category: 'OPERATIONS',
        chart: null,
        filters: ['DATE_RANGE'],
        available: false,
        unavailableReason: 'This report is not built yet.',
      },
    ],
    scopeNote: null,
  },
}

function renderHub() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/reports']}>
        <Routes>
          <Route path="/reports" element={<ReportsHubPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function renderViewer(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/reports/:reportKey" element={<ReportViewerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function catalogue(body: object = CATALOGUE) {
  server.use(http.get('/api/v1/reports', () => HttpResponse.json(body)))
}

describe('S-27 the reports hub', () => {
  it('renders a card per report, grouped by category', async () => {
    catalogue()
    renderHub()

    expect(await screen.findByRole('heading', { name: 'Date-wise Report' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Delivery' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'People' })).toBeInTheDocument()
  })

  it('links an available report to its viewer', async () => {
    catalogue()
    renderHub()

    const link = await screen.findByRole('link', { name: /Date-wise Report/ })
    expect(link).toHaveAttribute('href', '/reports/date-wise')
  })

  /**
   * The decision this suite exists to protect: unbuilt reports are shown, with
   * a reason, rather than hidden. Hiding them would make "not built yet"
   * indistinguishable from "does not exist".
   */
  it('shows an unbuilt report with its reason, and not as a link', async () => {
    catalogue()
    renderHub()

    await screen.findByRole('heading', { name: 'Date-wise Report' })

    expect(
      screen.queryByRole('link', { name: /Resource Performance Scorecard/ }),
    ).not.toBeInTheDocument()

    const card = screen.getByRole('group', { name: /Resource Performance Scorecard/ })
    expect(within(card).getByText('This report is not built yet.')).toBeInTheDocument()
  })

  it('states the scope once, above the grid, when the caller is narrowed', async () => {
    // A Developer would otherwise reasonably expect to pick a colleague on a
    // card titled "Resource Performance Scorecard".
    catalogue({
      data: { ...CATALOGUE.data, scopeNote: 'These reports cover your own work only.' },
    })
    renderHub()

    expect(
      await screen.findByText('These reports cover your own work only.'),
    ).toBeInTheDocument()
  })

  it('says so when the catalogue cannot be loaded, rather than showing an empty hub', async () => {
    server.use(http.get('/api/v1/reports', () => new HttpResponse(null, { status: 500 })))
    renderHub()

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not be loaded/i)
  })
})

describe('S-27 the report viewer', () => {
  const RUN = {
    data: {
      reportKey: 'date-wise',
      columns: [
        { key: 'date', label: 'Date', type: 'date' },
        { key: 'created', label: 'Created', type: 'number' },
        { key: 'openTotal', label: 'Net backlog', type: 'number' },
      ],
      rows: [
        { date: '2026-08-10', created: 2, openTotal: 5 },
        { date: '2026-08-11', created: 3, openTotal: 6 },
      ],
    },
    meta: { appliedScope: 'your projects' },
  }

  function run(body: object = RUN) {
    server.use(http.get('/api/v1/reports/:key', () => HttpResponse.json(body)))
  }

  it('renders the table with a row per result and the declared column labels', async () => {
    catalogue()
    run()
    renderViewer('/reports/date-wise')

    expect(await screen.findByRole('columnheader', { name: 'Net backlog' })).toBeInTheDocument()
    // Two data rows plus the header row.
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3))
  })

  /**
   * A delivery role's `resourceId` is discarded silently, so without this line
   * "the filter did nothing" and "the filter matched nothing" look identical.
   */
  it('states the scope the server actually applied', async () => {
    catalogue()
    run()
    renderViewer('/reports/date-wise')

    expect(await screen.findByText(/Showing your projects\./)).toBeInTheDocument()
  })

  it('draws only the filters the report declares', async () => {
    catalogue()
    run()
    renderViewer('/reports/date-wise')

    // date-wise declares DATE_RANGE and PROJECT — so no Resource control, which
    // its runner would ignore.
    expect(await screen.findByLabelText('From')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Resource/ })).not.toBeInTheDocument()
  })

  it('explains an unbuilt report rather than running it', async () => {
    catalogue()
    renderViewer('/reports/resource-scorecard')

    expect(await screen.findByText('Not built yet')).toBeInTheDocument()
  })

  it('says a key that does not exist does not exist', async () => {
    catalogue()
    renderViewer('/reports/invented-by-a-stale-bookmark')

    expect(await screen.findByText('No such report')).toBeInTheDocument()
  })

  it('shows an empty state rather than a blank chart when nothing matched', async () => {
    catalogue()
    run({ data: { reportKey: 'date-wise', columns: RUN.data.columns, rows: [] }, meta: {} })
    renderViewer('/reports/date-wise')

    expect(await screen.findByText('Nothing to show')).toBeInTheDocument()
  })
})
