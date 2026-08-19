import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ReportViewerPage } from './ReportViewerPage'

/**
 * B-061 · S-27's viewer running §7.8's two people-reports.
 *
 * The figures are `ReportRunnersIT`'s and `StageAndHealthReportsIT`'s. What is
 * only checkable from the screen is the half B-061 exists for: that the Resource
 * control reaches the request rather than being drawn and dropped, that a trend
 * renders as a direction a person can read and a screen reader can speak, and
 * that the allocation total stays distinguishable from a stated zero.
 */

const SCORECARD_DESCRIPTOR = {
  key: 'resource-scorecard',
  title: 'Resource Performance Scorecard',
  description:
    'Closed, on-time %, cycle time, effort, variance, reopen rate and utilisation per person.',
  category: 'PEOPLE',
  chart: 'bar',
  filters: ['DATE_RANGE', 'PROJECT', 'RESOURCE'],
  available: true,
  unavailableReason: null,
}

const WORKLOAD_DESCRIPTOR = {
  key: 'workload-capacity',
  title: 'Workload & Capacity',
  description:
    'What each person is carrying against the working hours they actually have, and what their ' +
    'projects add up to committing them to. Allocation counts only projects where one was stated.',
  category: 'DELIVERY',
  chart: 'bar',
  filters: ['DATE_RANGE', 'RESOURCE'],
  available: true,
  unavailableReason: null,
}

const CATALOGUE = {
  data: { reports: [SCORECARD_DESCRIPTOR, WORKLOAD_DESCRIPTOR], scopeNote: null },
}

const USERS = {
  data: [
    { id: 4, displayName: 'Ravi Menon' },
    { id: 6, displayName: 'Sana Iqbal' },
  ],
}

const PROJECTS = { data: [{ id: 1, name: 'Atlas' }] }

const SCORECARD = {
  data: {
    reportKey: 'resource-scorecard',
    columns: [
      { key: 'resource', label: 'Resource', type: 'string' },
      { key: 'closed', label: 'Closed', type: 'number' },
      { key: 'slaPct', label: 'SLA %', type: 'percent' },
      { key: 'utilisation', label: 'Utilisation', type: 'percent' },
      { key: 'trend', label: 'Closed vs previous', type: 'trend' },
    ],
    rows: [
      { resource: 'Ravi Menon', closed: 12, slaPct: 75, utilisation: 88.5, trend: 3 },
      { resource: 'Sana Iqbal', closed: 5, slaPct: 60, utilisation: 41.2, trend: -2 },
      { resource: 'Devi Rao', closed: 7, slaPct: 100, utilisation: 55, trend: 0 },
      // Nobody was here in the previous window, so there is no change to state.
      { resource: 'Noor Bakshi', closed: 2, slaPct: null, utilisation: null, trend: null },
    ],
  },
  meta: { appliedScope: 'the whole organisation' },
}

const WORKLOAD = {
  data: {
    reportKey: 'workload-capacity',
    columns: [
      { key: 'resource', label: 'Resource', type: 'string' },
      { key: 'assignedOpen', label: 'Open', type: 'number' },
      { key: 'capacityHours', label: 'Capacity', type: 'duration' },
      { key: 'projects', label: 'Projects', type: 'number' },
      { key: 'allocationPct', label: 'Allocated', type: 'percent' },
      { key: 'allocationStated', label: 'Allocation stated on', type: 'number' },
    ],
    rows: [
      // Over-allocated, and every project said so.
      {
        resource: 'Ravi Menon',
        assignedOpen: 11,
        capacityHours: 160,
        projects: 3,
        allocationPct: 150,
        allocationStated: 3,
      },
      // On four projects and nobody wrote down what any of them committed him
      // to. Not 0% — that is a different fact.
      {
        resource: 'Sana Iqbal',
        assignedOpen: 4,
        capacityHours: 160,
        projects: 4,
        allocationPct: null,
        allocationStated: 0,
      },
    ],
  },
  meta: { appliedScope: 'the whole organisation' },
}

/** The query string the viewer actually sent, captured off the request. */
let lastQuery: URLSearchParams

function mocks() {
  lastQuery = new URLSearchParams()
  server.use(
    http.get('/api/v1/reports', () => HttpResponse.json(CATALOGUE)),
    http.get('/api/v1/users', () => HttpResponse.json(USERS)),
    http.get('/api/v1/projects', () => HttpResponse.json(PROJECTS)),
    http.get('/api/v1/reports/resource-scorecard', ({ request }) => {
      lastQuery = new URL(request.url).searchParams
      return HttpResponse.json(SCORECARD)
    }),
    http.get('/api/v1/reports/workload-capacity', ({ request }) => {
      lastQuery = new URL(request.url).searchParams
      return HttpResponse.json(WORKLOAD)
    }),
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

/** The `<tr>` whose first cell names this person. */
function rowFor(name: string) {
  const row = screen.getByText(name).closest('tr')
  expect(row).not.toBeNull()
  return row!
}

describe('B-061 the resource scorecard', () => {
  /**
   * The defect B-061 exists for. `?resourceId=` was declared in the contract,
   * accepted by the controller, resolved by `ReportService` — and handed to the
   * ETag and the scope note rather than to the runner, which re-derived it as
   * `scope.resourceSubject(null)`. So an Admin or a PM picked a person and the
   * report did not move.
   *
   * The viewer's half was always correct, which is why this asserts the request
   * rather than the rows: the control's job is to put the id in the URL and the
   * URL in the query, and that is the seam a screen test can reach.
   */
  it('sends the chosen resource to the server', async () => {
    mocks()
    renderViewer('/reports/resource-scorecard')

    await userEvent.click(await screen.findByRole('button', { name: /Resource/ }))
    await userEvent.click(await screen.findByRole('option', { name: /Ravi Menon/ }))

    await waitFor(() => expect(lastQuery.get('resourceId')).toBe('4'))
  })

  /**
   * §7.8's scorecard line ends with "Trend arrows". The column carried a signed
   * integer and rendered as one.
   */
  it('renders a rise and a fall as directions rather than signed numbers', async () => {
    mocks()
    renderViewer('/reports/resource-scorecard')

    await screen.findByText('Ravi Menon')

    // The arrow carries the sign, so the figure beside it is unsigned — "↓ -2"
    // reads as a double negative.
    expect(within(rowFor('Sana Iqbal')).getByText('2')).toBeInTheDocument()
    expect(within(rowFor('Sana Iqbal')).queryByText('-2')).not.toBeInTheDocument()
  })

  /**
   * The arrow is `aria-hidden`, so the direction has to be spoken somewhere or
   * a screen reader hears a bare "2" and loses the half of the value that
   * matters. CLAUDE.md: accessibility is not optional.
   */
  it('spells the direction out for a screen reader', async () => {
    mocks()
    renderViewer('/reports/resource-scorecard')

    await screen.findByText('Ravi Menon')

    expect(within(rowFor('Ravi Menon')).getByText('up 3 on the previous period')).toBeInTheDocument()
    expect(
      within(rowFor('Sana Iqbal')).getByText('down 2 on the previous period'),
    ).toBeInTheDocument()
    expect(
      within(rowFor('Devi Rao')).getByText('unchanged from the previous period'),
    ).toBeInTheDocument()
  })

  /**
   * "No change" is a measurement — it says the previous window was measured and
   * matched. Somebody who was not there has not made one, and rendering their
   * absence as a level arrow would assert a comparison nobody performed.
   */
  it('renders an absent trend as an em dash, not as no change', async () => {
    mocks()
    renderViewer('/reports/resource-scorecard')

    await screen.findByText('Ravi Menon')

    const cells = within(rowFor('Noor Bakshi')).getAllByRole('cell')
    expect(cells[4]).toHaveTextContent('—')
    expect(cells[4]).not.toHaveTextContent('unchanged')
  })
})

describe('B-061 workload and capacity', () => {
  /**
   * B-017 built the Team tab's per-project allocation and flagged the total for
   * this report, because one project's rows cannot answer what a person is
   * committed to altogether.
   */
  it('shows the allocation total across all of a resource’s projects', async () => {
    mocks()
    renderViewer('/reports/workload-capacity')

    await screen.findByText('Ravi Menon')

    const cells = within(rowFor('Ravi Menon')).getAllByRole('cell')
    expect(cells[3]).toHaveTextContent('3')
    expect(cells[4]).toHaveTextContent('150%')
    expect(cells[5]).toHaveTextContent('3')
  })

  /**
   * `allocation_pct` is nullable and means "not stated" — B-017 refused the
   * contract's `default: 100` for exactly this reason. A 0% here would be a
   * decision somebody made appearing under the name of an absence, and it would
   * read as a person with nothing committed and therefore room to spare.
   */
  it('renders an unstated allocation as an em dash, not as 0%', async () => {
    mocks()
    renderViewer('/reports/workload-capacity')

    await screen.findByText('Sana Iqbal')

    const cells = within(rowFor('Sana Iqbal')).getAllByRole('cell')
    expect(cells[4]).toHaveTextContent('—')
    expect(cells[4]).not.toHaveTextContent('0%')
    // On four projects, none of which stated one — which is what makes the em
    // dash above readable as a gap rather than as a missing person.
    expect(cells[3]).toHaveTextContent('4')
    expect(cells[5]).toHaveTextContent('0')
  })

  /**
   * The card says the total counts only stated figures, so somebody reading
   * "150%" knows it is a floor. Same call the client report made about the
   * figure it does not have: name the limit before it is quoted, not after.
   */
  it('says on the page that allocation counts only projects that stated one', async () => {
    mocks()
    renderViewer('/reports/workload-capacity')

    expect(
      await screen.findByText(/Allocation counts only projects where one was stated/),
    ).toBeInTheDocument()
  })
})
