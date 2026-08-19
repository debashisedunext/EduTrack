import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ScheduleReportDialog } from './ScheduleReportDialog'
import { ScheduledReportsPage } from './ScheduledReportsPage'

/**
 * A-065 · §7.8's scheduled report emails, on the two screens that carry them.
 *
 * <p>What is worth asserting here is what the user is told rather than what the
 * server does — the server's own rules have their own suite. Three things in
 * particular: that the dialog does not quietly send a date range it will not
 * honour, that a cancelled schedule stays visible with a reason, and that a
 * failed run says why rather than showing an absent download button and
 * nothing else.
 */

function renderWithProviders(ui: React.ReactNode, url = '/reports/schedules') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/reports/schedules" element={<>{ui}</>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('the schedule dialog', () => {
  /**
   * 🔴 The filters travel and the dates deliberately do not. Asserted on the
   * request body, because this is the one place the omission is observable —
   * on screen it looks identical either way.
   */
  it('posts the viewer’s filters without the date range', async () => {
    const posted = vi.fn()
    server.use(
      http.post('/api/v1/reports/schedule', async ({ request }) => {
        posted(await request.json())
        return HttpResponse.json({ data: schedule() }, { status: 201 })
      }),
    )

    renderWithProviders(
      <ScheduleReportDialog
        reportKey="date-wise"
        reportTitle="Date-wise Report"
        params={new URLSearchParams({ from: '2026-08-01', to: '2026-08-07', projectId: '4' })}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /schedule by email/i }))
    await userEvent.type(screen.getByLabelText(/send to/i), 'lead@example.test')
    await userEvent.click(screen.getByRole('button', { name: /^schedule$/i }))

    await waitFor(() => expect(posted).toHaveBeenCalled())
    expect(posted.mock.calls[0][0]).toMatchObject({
      reportKey: 'date-wise',
      cadence: 'WEEKLY',
      recipients: ['lead@example.test'],
      parameters: { projectId: '4' },
    })
    expect(posted.mock.calls[0][0].parameters).not.toHaveProperty('from')
  })

  /**
   * The dialog says what it will do with the dates rather than leaving it to be
   * discovered from the first email. Silently dropping a filter somebody set is
   * the failure this sentence exists to prevent.
   */
  it('says out loud that the date range is not saved', async () => {
    renderWithProviders(
      <ScheduleReportDialog
        reportKey="date-wise"
        reportTitle="Date-wise Report"
        params={new URLSearchParams()}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /schedule by email/i }))

    expect(screen.getByText(/date range is not/i)).toBeInTheDocument()
  })

  /**
   * A 400 here is always actionable — an address with no account, a report the
   * role cannot run — so the server's sentence is shown rather than replaced
   * with "something went wrong".
   */
  it('shows the server’s reason for a refusal', async () => {
    server.use(
      http.post('/api/v1/reports/schedule', () =>
        HttpResponse.json(
          // A full RFC 9457 document, because `readProblem` keeps `detail` only
          // when `title` is present — a fixture with `detail` alone falls back
          // to "HTTP 400" and this assertion fails against working code. Spring
          // always sends both, so the shorter fixture was the unrealistic half.
          {
            type: 'about:blank',
            title: 'Bad Request',
            status: 400,
            detail:
              'These addresses do not belong to an active EduTrack user: ghost@example.test',
          },
          { status: 400 },
        ),
      ),
    )

    renderWithProviders(
      <ScheduleReportDialog
        reportKey="date-wise"
        reportTitle="Date-wise Report"
        params={new URLSearchParams()}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /schedule by email/i }))
    await userEvent.type(screen.getByLabelText(/send to/i), 'ghost@example.test')
    await userEvent.click(screen.getByRole('button', { name: /^schedule$/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/ghost@example.test/)
  })
})

describe('the scheduled reports screen', () => {
  it('lists a schedule with its cadence and next run', async () => {
    schedules([schedule()])
    renderWithProviders(<ScheduledReportsPage />)

    expect(await screen.findByText('Date-wise Report')).toBeInTheDocument()
    expect(screen.getByText(/Weekly/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument()
  })

  /**
   * "Why did this stop arriving" is the question this screen exists to answer,
   * and a row that vanishes on cancel answers it with silence. Stated in words
   * as well as by greying, because opacity is not available to a screen reader.
   */
  it('keeps a cancelled schedule visible and says it will not run again', async () => {
    schedules([{ ...schedule(), active: false }])
    renderWithProviders(<ScheduledReportsPage />)

    expect(await screen.findByText('Date-wise Report')).toBeInTheDocument()
    expect(screen.getByText(/no further runs/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument()
  })

  /**
   * 🔴 A schedule somebody else sent you.
   *
   * The list carries these because the emailed link points at this screen — a
   * list of only what you created sent every recipient to an empty page, which
   * was the defect. What it must not offer is Cancel: stopping somebody else's
   * standing instruction is not the recipient's decision, and the screen says
   * whose it is instead of leaving the missing button to be puzzled over.
   */
  it('shows a received schedule without a cancel button, and names the owner', async () => {
    schedules([{ ...schedule(), ownedByMe: false, createdByName: 'Priya N.' }])
    renderWithProviders(<ScheduledReportsPage />)

    expect(await screen.findByText('Date-wise Report')).toBeInTheDocument()
    expect(screen.getByText(/sent to you by priya n\./i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument()
  })

  it('offers a download only for a run that has a file', async () => {
    schedules([
      {
        ...schedule(),
        recentRuns: [
          run({ id: 1, status: 'SUCCEEDED', downloadable: true }),
          run({ id: 2, status: 'FAILED', downloadable: false, errorText: 'The owner is no longer active.' }),
        ],
      },
    ])
    renderWithProviders(<ScheduledReportsPage />)

    await screen.findByText('Date-wise Report')
    expect(screen.getAllByRole('button', { name: /download/i })).toHaveLength(1)
  })

  /**
   * A failed run says why. A schedule that silently produces nothing is
   * indistinguishable from one nobody looks at, which is the state this whole
   * row exists to make visible.
   */
  it('shows why a run failed', async () => {
    schedules([
      {
        ...schedule(),
        recentRuns: [
          run({ id: 2, status: 'FAILED', downloadable: false, errorText: 'The owner is no longer active.' }),
        ],
      },
    ])
    renderWithProviders(<ScheduledReportsPage />)

    expect(await screen.findByText(/owner is no longer active/i)).toBeInTheDocument()
  })

  /**
   * The scope is per run, not per schedule — the owner's role can change
   * between two of them, and this line is where that becomes visible.
   */
  it('shows each run’s own applied scope', async () => {
    schedules([
      {
        ...schedule(),
        recentRuns: [
          run({ id: 1, appliedScope: 'projects 4 and 9' }),
          run({ id: 2, appliedScope: 'your own work' }),
        ],
      },
    ])
    renderWithProviders(<ScheduledReportsPage />)

    await screen.findByText('Date-wise Report')
    const list = screen.getAllByRole('list')[0]
    expect(within(list).getByText(/projects 4 and 9/)).toBeInTheDocument()
    expect(within(list).getByText(/your own work/)).toBeInTheDocument()
  })

  it('says what to do when nothing is scheduled', async () => {
    schedules([])
    renderWithProviders(<ScheduledReportsPage />)

    expect(await screen.findByText(/nothing scheduled/i)).toBeInTheDocument()
  })
})

// ── fixtures ────────────────────────────────────────────────────────────────

function schedules(data: unknown[]) {
  server.use(http.get('/api/v1/reports/schedules', () => HttpResponse.json({ data })))
}

function schedule() {
  return {
    id: 7,
    reportKey: 'date-wise',
    reportTitle: 'Date-wise Report',
    cadence: 'WEEKLY',
    format: 'xlsx',
    recipients: ['lead@example.test'],
    parameters: {},
    active: true,
    ownedByMe: true,
    createdBy: 3,
    createdByName: 'Priya N.',
    nextRunAt: '2026-08-24T00:30:00Z',
    lastRunAt: null,
    recentRuns: [],
  }
}

function run(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    runAt: '2026-08-17T00:30:00Z',
    periodFrom: '2026-08-10',
    periodTo: '2026-08-16',
    status: 'SUCCEEDED',
    rowCount: 12,
    appliedScope: 'projects 4',
    errorText: null,
    downloadable: true,
    ...overrides,
  }
}
