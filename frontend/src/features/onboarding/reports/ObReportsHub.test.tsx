import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ObReportsHubPage } from './ObReportsHubPage'
import { ObReportViewerPage } from './ObReportViewerPage'

/**
 * B-122 · OB-10 against the mock server.
 *
 * <p>The assertions worth making are the ones about what the screen says when a
 * report cannot be run, and about the two values a reader is most likely to
 * misread: a health chip and a duration. Six of twelve reports are unbuilt for
 * the next few sprints, so "greyed card carrying its reason" is the state most
 * users will actually meet — and it is the one a screenshot review skims past.
 */

const CATALOGUE = {
  data: {
    reports: [
      {
        key: 'journey-funnel',
        title: 'Journey funnel by product',
        description: 'Where journeys sit, per product.',
        category: 'DELIVERY',
        chart: 'funnel',
        filters: ['dateRange', 'product'],
        available: true,
      },
      {
        key: 'stuck-and-aging',
        title: 'Stuck & aging',
        description: 'Block reasons and client-attributed waits.',
        category: 'CLIENT',
        chart: 'bar',
        filters: ['dateRange', 'product', 'rag'],
        available: true,
      },
      {
        key: 'signoff-pending',
        title: 'Sign-offs pending',
        description: 'Requested and unanswered, oldest first.',
        category: 'CLIENT',
        chart: null,
        filters: ['dateRange', 'client'],
        available: true,
      },
      {
        key: 'prereq-aging',
        title: 'Prerequisite aging',
        description: 'Client-attributed time before the gate.',
        category: 'CLIENT',
        chart: 'bar',
        filters: ['dateRange', 'client'],
        available: false,
        unavailableReason:
          'The prerequisites master and its per-client tasks are not built yet (B-124, B-125), so there is nothing to age.',
      },
      {
        key: 'csat-summary',
        title: 'CSAT summary',
        description: 'Go-live survey scores by product.',
        category: 'QUALITY',
        chart: 'donut',
        filters: ['dateRange', 'product'],
        available: false,
        unavailableReason: 'Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).',
      },
    ],
    scopeNote: null,
  },
}

function catalogue(body: object = CATALOGUE) {
  server.use(http.get('/api/v1/onboarding/reports', () => HttpResponse.json(body)))
}

function run(body: object) {
  server.use(http.get('/api/v1/onboarding/reports/:reportKey', () => HttpResponse.json(body)))
}

function renderHub() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding/reports']}>
        <Routes>
          <Route path="/onboarding/reports" element={<ObReportsHubPage />} />
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
          <Route path="/onboarding/reports/:reportKey" element={<ObReportViewerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('OB-10 the onboarding reports hub', () => {
  it('renders a card per report, grouped by category', async () => {
    catalogue()
    renderHub()

    expect(
      await screen.findByRole('heading', { name: 'Journey funnel by product' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Delivery' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Clients' })).toBeInTheDocument()
  })

  it('links an available report to its viewer', async () => {
    catalogue()
    renderHub()

    const link = await screen.findByRole('link', { name: /Journey funnel by product/ })
    expect(link).toHaveAttribute('href', '/onboarding/reports/journey-funnel')
  })

  /**
   * The decision this suite exists to protect. Hiding an unbuilt report would
   * make "not built yet" indistinguishable from "does not exist", which for the
   * OB4b five is exactly the question somebody is trying to decide.
   */
  it('shows an unbuilt report with its reason, and not as a link', async () => {
    catalogue()
    renderHub()

    await screen.findByRole('heading', { name: 'Journey funnel by product' })

    expect(screen.queryByRole('link', { name: /CSAT summary/ })).not.toBeInTheDocument()
    const card = screen.getByRole('group', { name: 'CSAT summary — not available' })
    expect(within(card).getByText(/Held as OB4b/)).toBeInTheDocument()
  })

  /**
   * The two unbuilt groups have different reasons and the cards say which.
   * Prerequisite aging is waiting on tables, not on a decision — telling a user
   * it is "held pending the reports decision" would send them to ask a question
   * nobody can answer.
   */
  it('distinguishes a report waiting on tables from one waiting on a decision', async () => {
    catalogue()
    renderHub()

    await screen.findByRole('heading', { name: 'Journey funnel by product' })

    const card = screen.getByRole('group', { name: 'Prerequisite aging — not available' })
    expect(within(card).getByText(/B-124, B-125/)).toBeInTheDocument()
    expect(within(card).queryByText(/OB4b/)).not.toBeInTheDocument()
  })

  /**
   * A narrowed caller is told before they open a report rather than after —
   * plan §3 gives a Step Owner their own services, and "your figures look low"
   * is the wrong way to find that out.
   */
  it('shows the scope note when the caller is narrowed', async () => {
    catalogue({
      data: {
        ...CATALOGUE.data,
        scopeNote: 'You see journeys containing your services.',
      },
    })
    renderHub()

    expect(
      await screen.findByText('You see journeys containing your services.'),
    ).toBeInTheDocument()
  })

  it('shows no scope note for a caller who sees everything', async () => {
    catalogue()
    renderHub()

    await screen.findByRole('heading', { name: 'Journey funnel by product' })
    expect(screen.queryByText(/You see/)).not.toBeInTheDocument()
  })
})

describe('OB-10 the viewer', () => {
  it('renders the table for a report that has rows', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'signoff-pending',
        columns: [
          { key: 'client', label: 'Client', type: 'string' },
          { key: 'waitingFor', label: 'Waiting', type: 'duration' },
        ],
        rows: [{ client: 'Horizon Group', waitingFor: '48.00' }],
      },
      meta: { appliedScope: 'all clients', computedAt: '2026-09-06T12:00:00Z' },
    })
    renderViewer('/onboarding/reports/signoff-pending')

    expect(await screen.findByRole('columnheader', { name: 'Client' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Horizon Group' })).toBeInTheDocument()
  })

  /**
   * A duration on this surface is working hours, already through the calendar.
   * The client appends the unit and does nothing else — converting to days
   * would need the length of a working day, which is the calendar's and varies
   * by org.
   */
  it('renders a duration as working hours without converting it', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'signoff-pending',
        columns: [{ key: 'waitingFor', label: 'Waiting', type: 'duration' }],
        rows: [{ waitingFor: '48.00' }],
      },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/signoff-pending')

    expect(await screen.findByRole('cell', { name: '48.00h' })).toBeInTheDocument()
  })

  /**
   * Null health is "nothing to colour", not a fourth state — today that is any
   * step whose deadline C-105's clock has not set. An em dash, because a grey
   * "unknown" chip would read as a health verdict.
   */
  it('renders a health chip, and an em dash where there is nothing to colour', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'stuck-and-aging',
        columns: [
          { key: 'client', label: 'Client', type: 'string' },
          { key: 'rag', label: 'Health', type: 'rag' },
        ],
        rows: [
          { client: 'Horizon Group', rag: 'RED' },
          { client: 'Crestwood', rag: null },
        ],
      },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/stuck-and-aging')

    expect(await screen.findByRole('cell', { name: 'Red' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '—' })).toBeInTheDocument()
  })

  /**
   * Without this line, "the filter did nothing" and "the filter matched
   * nothing" look identical — and only one of them is a statement about the
   * data. It is the only thing that tells a Step Owner their `ownerUserId` was
   * overruled.
   */
  it('says what the server actually narrowed the rows to', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'stuck-and-aging',
        columns: [{ key: 'client', label: 'Client', type: 'string' }],
        rows: [{ client: 'Horizon Group' }],
      },
      meta: { appliedScope: 'journeys containing your services, and your own figures only' },
    })
    renderViewer('/onboarding/reports/stuck-and-aging?ownerUserId=999')

    expect(
      await screen.findByText(
        /Showing journeys containing your services, and your own figures only\./,
      ),
    ).toBeInTheDocument()
  })

  it('offers XLSX and CSV exports, and never PDF', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'signoff-pending',
        columns: [{ key: 'client', label: 'Client', type: 'string' }],
        rows: [{ client: 'Horizon Group' }],
      },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/signoff-pending')

    expect(await screen.findByRole('button', { name: /Export XLSX/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Export CSV/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /PDF/ })).not.toBeInTheDocument()
  })

  /**
   * The mockup's tab row, drawn only over reports the catalogue names as
   * available — this catalogue has three of the six, so three tabs, with the
   * current one marked selected. Each tab is a link, because the viewer's
   * routing structure (one URL per report) is kept.
   */
  it('draws the mockup’s tab row from the catalogue and marks the current report selected', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'signoff-pending',
        columns: [{ key: 'client', label: 'Client', type: 'string' }],
        rows: [{ client: 'Horizon Group' }],
      },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/signoff-pending')

    const selected = await screen.findByRole('tab', { name: 'Sign-offs pending' })
    expect(selected).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Funnel' })).toHaveAttribute(
      'href',
      '/onboarding/reports/journey-funnel',
    )
    expect(screen.getByRole('tab', { name: 'Stuck & aging' })).toHaveAttribute(
      'aria-selected',
      'false',
    )
    // TAT compliance is not in this catalogue, so no tab claims it exists.
    expect(screen.queryByRole('tab', { name: 'TAT compliance' })).not.toBeInTheDocument()
  })

  /**
   * The mockup's hbar drawing: a labelled row per category with the value
   * printed at the end, and — for percent columns — the threshold colouring
   * with its swatch legend. The figure is a labelled image; the table below
   * stays the readable copy of every number.
   */
  it('draws a percent report as hbar rows with the mockup’s threshold legend', async () => {
    catalogue({
      data: {
        reports: [
          {
            key: 'journey-funnel',
            title: 'Journey funnel by product',
            category: 'DELIVERY',
            chart: 'bar',
            filters: [],
            available: true,
          },
        ],
        scopeNote: null,
      },
    })
    run({
      data: {
        reportKey: 'journey-funnel',
        columns: [
          { key: 'step', label: 'Step', type: 'string' },
          { key: 'onTime', label: 'On time', type: 'percent' },
        ],
        rows: [
          { step: 'Kickoff', onTime: 92 },
          { step: 'Data migration', onTime: 58 },
        ],
      },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/journey-funnel')

    const figure = await screen.findByRole('img', { name: /Bar chart: Journey funnel by product/ })
    expect(figure).toBeInTheDocument()
    expect(screen.getByText('below 70%')).toBeInTheDocument()
    expect(screen.getByText('70–85%')).toBeInTheDocument()
  })

  /**
   * Only the controls the report declares. A Health control on the sign-off
   * list would be set, change nothing, and teach the user the screen is broken.
   */
  it('draws only the filters the descriptor declares', async () => {
    catalogue()
    run({
      data: {
        reportKey: 'signoff-pending',
        columns: [{ key: 'client', label: 'Client', type: 'string' }],
        rows: [{ client: 'Horizon Group' }],
      },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/signoff-pending')

    await screen.findByRole('cell', { name: 'Horizon Group' })
    expect(screen.getByLabelText('From')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Health/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Product/ })).not.toBeInTheDocument()
  })

  it('says a report is not built rather than showing an empty table', async () => {
    catalogue()
    renderViewer('/onboarding/reports/csat-summary')

    expect(await screen.findByText('Not built yet')).toBeInTheDocument()
    expect(screen.getByText(/Held as OB4b/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('says a key the catalogue does not name does not exist', async () => {
    catalogue()
    renderViewer('/onboarding/reports/invented')

    expect(await screen.findByText('No such report')).toBeInTheDocument()
  })

  it('shows an empty state rather than a bare table when there are no rows', async () => {
    catalogue()
    run({
      data: { reportKey: 'signoff-pending', columns: [], rows: [] },
      meta: { appliedScope: 'all clients' },
    })
    renderViewer('/onboarding/reports/signoff-pending')

    expect(await screen.findByText('Nothing to show')).toBeInTheDocument()
  })
})
