import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ReportViewerPage } from './ReportViewerPage'

/**
 * B-060 · S-27's viewer running the client report.
 *
 * The three things worth asserting from the screen's side are the three the
 * server cannot check for itself: that the Client control is drawn and reaches
 * the request, that the client cell is a link to the 360 view, and that the
 * internal id the link is built from never appears as a column. Everything
 * about the figures is `ReportRunnersIT`'s.
 */

const DESCRIPTOR = {
  key: 'client-report',
  title: 'Client Report',
  description:
    'Raised, closed and still open per client, with SLA compliance and average resolution time. ' +
    'Satisfaction is not included — no rating is captured on closure yet.',
  category: 'OPERATIONS',
  chart: 'bar',
  filters: ['DATE_RANGE', 'CLIENT'],
  available: true,
  unavailableReason: null,
}

const CATALOGUE = { data: { reports: [DESCRIPTOR], scopeNote: null } }

const CLIENTS = {
  data: [
    { id: 7, clientCode: 'ARI', name: 'Ariadne Systems' },
    { id: 9, clientCode: 'BOR', name: 'Borealis Freight' },
  ],
}

const REPORT = {
  data: {
    reportKey: 'client-report',
    columns: [
      { key: 'client', label: 'Client', type: 'string', linkTo: 'CLIENT', linkIdKey: 'clientId' },
      { key: 'clientCode', label: 'Code', type: 'string' },
      { key: 'raised', label: 'Raised', type: 'number' },
      { key: 'closed', label: 'Closed', type: 'number' },
      { key: 'openNow', label: 'Open now', type: 'number' },
      { key: 'slaPct', label: 'SLA %', type: 'percent' },
      { key: 'avgResolutionHours', label: 'Avg resolution', type: 'duration' },
    ],
    rows: [
      {
        client: 'Ariadne Systems',
        clientId: 7,
        clientCode: 'ARI',
        raised: 12,
        closed: 9,
        openNow: 4,
        slaPct: 66.7,
        avgResolutionHours: 31.5,
      },
      {
        client: 'Borealis Freight',
        clientId: 9,
        clientCode: 'BOR',
        raised: 3,
        closed: 0,
        openNow: 3,
        // No closed ticket carried a planned date, so there is no denominator.
        slaPct: null,
        avgResolutionHours: null,
      },
    ],
  },
  meta: { appliedScope: 'your projects' },
}

/** The query string the viewer actually sent, captured off the request. */
let lastQuery: URLSearchParams

function mocks(report: object = REPORT) {
  lastQuery = new URLSearchParams()
  server.use(
    http.get('/api/v1/reports', () => HttpResponse.json(CATALOGUE)),
    http.get('/api/v1/clients', () => HttpResponse.json(CLIENTS)),
    http.get('/api/v1/reports/client-report', ({ request }) => {
      lastQuery = new URL(request.url).searchParams
      return HttpResponse.json(report)
    }),
  )
}

function renderViewer(url = '/reports/client-report') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/reports/:reportKey" element={<ReportViewerPage />} />
          <Route path="/clients/:clientId" element={<div>Client 360</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('B-060 the client report', () => {
  it('draws the Client control the descriptor declares', async () => {
    mocks()
    renderViewer()

    expect(await screen.findByRole('button', { name: /Client/ })).toBeInTheDocument()
  })

  /**
   * The failure this prevents is the one the whole per-report filter list
   * exists for: a control that is drawn, is set, and changes nothing. Before
   * B-060 the viewer had no `clientId` passthrough and the controller did not
   * accept the parameter, so the request would have gone out unchanged.
   */
  it('sends the chosen client to the server', async () => {
    mocks()
    renderViewer()

    await userEvent.click(await screen.findByRole('button', { name: /Client/ }))
    await userEvent.click(await screen.findByRole('option', { name: /Ariadne Systems/ }))

    await waitFor(() => expect(lastQuery.get('clientId')).toBe('7'))
  })

  it('names the client by code as well as name in the picker', async () => {
    mocks()
    renderViewer()

    await userEvent.click(await screen.findByRole('button', { name: /Client/ }))

    // "Acme Retail" and "Acme Logistics" share a leading word, and the code is
    // what a support desk says out loud. Picking the wrong one off a name-only
    // list is a report sent to the wrong client.
    expect(await screen.findByRole('option', { name: 'Ariadne Systems (ARI)' })).toBeInTheDocument()
  })

  it('drills the client cell into the client 360 view', async () => {
    mocks()
    renderViewer()

    const link = await screen.findByRole('link', { name: 'Ariadne Systems' })
    expect(link).toHaveAttribute('href', '/clients/7')
  })

  /**
   * The id the link is built from is carried in the row and declared by no
   * column, so it must not reach the table — and by the same construction it
   * never reaches `?export=`, which iterates columns. An internal id is not a
   * figure to put in a spreadsheet sent to a client.
   */
  it('never shows the internal client id as a column', async () => {
    mocks()
    renderViewer()

    await screen.findByRole('link', { name: 'Ariadne Systems' })

    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).not.toContain('clientId')
    expect(headers).toEqual([
      'Client',
      'Code',
      'Raised',
      'Closed',
      'Open now',
      'SLA %',
      'Avg resolution',
    ])
  })

  /**
   * §7.8 names five figures per client and four are recorded. There is no CSAT
   * column in the schema — blueprint §17 item 19 puts the closure rating in
   * phase 2–3 — so the report omits it and the description says so. A column of
   * em dashes would read as "we asked and they did not answer", which is a
   * claim about the clients rather than about the schema.
   */
  it('says on the page that satisfaction is not in the report', async () => {
    mocks()
    renderViewer()

    expect(await screen.findByText(/Satisfaction is not included/)).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: /Satisfaction/ })).not.toBeInTheDocument()
  })

  /**
   * A client whose closed work carried no planned close date has no SLA
   * denominator. Null renders as an em dash; 0% would say every commitment was
   * missed, and 100% that every one was met.
   */
  it('renders a client with no SLA commitments as an em dash, not 0%', async () => {
    mocks()
    renderViewer()

    const row = (await screen.findByText('Borealis Freight')).closest('tr')
    expect(row).not.toBeNull()

    // By cell position, not by text: this row has two nulls — no SLA
    // denominator and no closed ticket to average — and a bare search for an em
    // dash would pass while asserting nothing about which column held it.
    const cells = within(row!).getAllByRole('cell')
    expect(cells[5]).toHaveTextContent('—')
    expect(cells[5]).not.toHaveTextContent('0%')
  })
})
