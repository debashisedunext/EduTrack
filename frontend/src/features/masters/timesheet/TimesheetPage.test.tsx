import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import type { Me } from '@/api/generated/model/me'
import type { RoleCode } from '@/api/generated/model/roleCode'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'
import { TimesheetPage } from './TimesheetPage'

/**
 * B-063 · the timesheet grid.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that
 * two passes through the same stage are two rows rather than one summed line,
 * that a day the calendar offered nothing is marked as such while the hours
 * somebody logged on it are still displayed, that a reversal is visible and
 * signed, and that a week nobody was expected to work reports no utilisation
 * rather than 0%.
 *
 * The payload is stubbed per test rather than driven from the mock database:
 * every case here is about a shape the grid must render, and seeding a week of
 * effort logs to provoke one would test the fixture generator.
 */
const MONDAY = '2026-08-10'

function week(overrides: Record<string, unknown> = {}) {
  const days = [
    { date: '2026-08-10', capacityHours: 9, loggedHours: 5, isWorkingDay: true },
    { date: '2026-08-11', capacityHours: 9, loggedHours: 0, isWorkingDay: true },
    { date: '2026-08-12', capacityHours: 0, loggedHours: 0, isWorkingDay: false },
    { date: '2026-08-13', capacityHours: 9, loggedHours: 0, isWorkingDay: true },
    { date: '2026-08-14', capacityHours: 9, loggedHours: 0, isWorkingDay: true },
    { date: '2026-08-15', capacityHours: 0, loggedHours: 0, isWorkingDay: false },
    { date: '2026-08-16', capacityHours: 0, loggedHours: 0, isWorkingDay: false },
  ]
  return {
    person: { id: 7, displayName: 'Ravi Kumar', avatarUrl: null, role: 'DEVELOPER' },
    weekStart: '2026-08-10',
    weekEnd: '2026-08-16',
    days,
    rows: [
      {
        ticketId: 'CRM-26-00347',
        ticketTitle: 'Login fails on Safari',
        project: { id: 1, projectCode: 'CRM', name: 'CRM Portal' },
        stage: 'DEV',
        stageName: 'Development',
        iterationNo: 1,
        cycleNo: 1,
        hours: { '2026-08-10': 3 } as Record<string, number>,
        totalHours: 3,
        hasCorrection: false,
      },
      {
        ticketId: 'CRM-26-00347',
        ticketTitle: 'Login fails on Safari',
        project: { id: 1, projectCode: 'CRM', name: 'CRM Portal' },
        stage: 'QA',
        stageName: 'QA / Testing',
        iterationNo: 2,
        cycleNo: 1,
        hours: { '2026-08-10': 2 } as Record<string, number>,
        totalHours: 2,
        hasCorrection: false,
      },
    ],
    totalHours: 5,
    capacityHours: 36,
    utilisationPct: 13.9,
    ...overrides,
  }
}

function stub(payload: unknown, status = 200) {
  server.use(
    http.get('/api/v1/users/:userId/timesheet', () =>
      status === 200
        ? HttpResponse.json({ data: payload })
        : HttpResponse.json({ error: { title: 'Not found' } }, { status }),
    ),
  )
}

/** B-065 · signs the viewer in as `role`, undoing itself after each test. */
function signedInAs(role: RoleCode) {
  useAuthStore.setState({
    ...initialAuthState,
    status: 'authenticated',
    user: { id: 2, displayName: 'Meera Nair', role } as Me,
  })
}

function renderPage(entry = `/timesheet/7?weekOf=${MONDAY}`) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/timesheet/:userId" element={<TimesheetPage />} />
          <Route path="/timesheet" element={<TimesheetPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const rowFor = async (stage: string) =>
  (await screen.findByText(stage)).closest('tr') as HTMLElement

/**
 * A row's cells by position: ticket, stage, then Monday through Sunday, then
 * the row total.
 *
 * <p>Queried by index rather than by text because a grid of numbers is full of
 * repeats — a row logging three hours on Monday and nothing else shows "3"
 * twice, in Monday's cell and in its total, and `getByText('3')` cannot say
 * which one it found. Position is the thing under test anyway: the point of a
 * timesheet is that an hour lands on the right day.
 */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell')
const dayCell = (row: HTMLElement, index: number) => cells(row)[2 + index]
const totalCell = (row: HTMLElement) => cells(row)[cells(row).length - 1]

/**
 * The same, for the two summary rows at the foot of the grid.
 *
 * <p>Their label spans the ticket and stage columns as one cell, so a day sits
 * one position earlier than it does on a ticket row. Sharing `dayCell` between
 * the two would read every footer day one column late — and, because a week
 * ends in two non-working days that look alike, would still pass.
 */
const footerDayCell = (row: HTMLElement, index: number) => cells(row)[1 + index]

afterEach(() => {
  useAuthStore.setState(initialAuthState)
})

describe('the timesheet grid', () => {
  it('splits one ticket into a row per stage and pass', async () => {
    // The whole point of "stage-aware". Both rows are the same ticket and the
    // same day; a grid keyed on ticket alone would show five undifferentiated
    // hours and lose the fact that two of them were rework.
    stub(week())
    renderPage()

    const dev = await rowFor('Development')
    const qa = await rowFor('QA / Testing')

    expect(dayCell(dev, 0)).toHaveTextContent('3')
    expect(totalCell(dev)).toHaveTextContent('3')
    expect(dayCell(qa, 0)).toHaveTextContent('2')
    expect(within(qa).getByText('pass 2')).toBeInTheDocument()
    // The first pass is not labelled — "pass 1" on every line is noise that
    // hides the line that matters.
    expect(within(dev).queryByText('pass 1')).not.toBeInTheDocument()
  })

  it('names the week and the person it belongs to', async () => {
    stub(week())
    renderPage()

    expect(await screen.findByText(/Ravi Kumar/)).toBeInTheDocument()
    expect(screen.getByText(/10 Aug – 16 Aug 2026/)).toBeInTheDocument()
  })

  it('shows hours logged on a day the calendar offered nothing', async () => {
    // Somebody worked a Saturday. The capacity row says the day offered nothing
    // and the hours are still real — hiding either would be a different lie.
    const saturday = week()
    saturday.days[5] = {
      date: '2026-08-15',
      capacityHours: 0,
      loggedHours: 4,
      isWorkingDay: false,
    }
    saturday.rows[0].hours = { '2026-08-10': 3, '2026-08-15': 4 }
    saturday.rows[0].totalHours = 7
    stub(saturday)
    renderPage()

    const dev = await rowFor('Development')
    expect(dayCell(dev, 5)).toHaveTextContent('4')

    // The available row reports a dash for that day rather than a zero, which
    // would read as a figure somebody entered. Scoped to the table, because
    // "Available" is also one of the three headline figures above it.
    const table = screen.getByRole('table')
    const available = within(table).getByText('Available').closest('tr') as HTMLElement
    expect(footerDayCell(available, 5)).toHaveTextContent('—')
    expect(footerDayCell(available, 0)).toHaveTextContent('9')
  })

  it('marks a row carrying a correction and renders the reversal signed', async () => {
    const corrected = week()
    corrected.rows[0].hasCorrection = true
    corrected.rows[0].hours = { '2026-08-10': -1.5 }
    corrected.rows[0].totalHours = -1.5
    stub(corrected)
    renderPage()

    const dev = await rowFor('Development')
    expect(within(dev).getByText('includes a correction')).toBeInTheDocument()
    expect(dayCell(dev, 0)).toHaveTextContent('-1.5')
  })

  it('keeps a quarter of an hour rather than rounding it to a tenth', async () => {
    // 0.25 must not render as 0.3 in a grid somebody is checking against their
    // own week — the effort log stores two decimal places for a reason.
    const quarter = week()
    quarter.rows[0].hours = { '2026-08-10': 0.25 }
    quarter.rows[0].totalHours = 0.25
    stub(quarter)
    renderPage()

    expect(dayCell(await rowFor('Development'), 0)).toHaveTextContent('0.25')
  })

  it('reports no utilisation, rather than 0%, for a week that offered no working hours', async () => {
    stub(week({ utilisationPct: null, capacityHours: 0, totalHours: 0, rows: [] }))
    renderPage()

    const utilisation = (await screen.findByText('Utilisation')).closest('div') as HTMLElement
    expect(within(utilisation).getByText('—')).toBeInTheDocument()
    expect(within(utilisation).queryByText('0%')).not.toBeInTheDocument()
  })

  it('says a week with nothing in it is empty rather than showing an empty grid', async () => {
    stub(week({ rows: [], totalHours: 0 }))
    renderPage()

    expect(await screen.findByText('Nothing logged this week')).toBeInTheDocument()
  })

  it('treats a refusal and a missing person as the same answer', async () => {
    // The server does not distinguish "no such user" from "not yours to see",
    // so that ids cannot be walked to learn who exists. The screen must not
    // invent a distinction either.
    stub(null, 404)
    renderPage()

    expect(await screen.findByText('Timesheet not available')).toBeInTheDocument()
    expect(
      screen.getByText(/does not exist, or is not one you can view/),
    ).toBeInTheDocument()
  })

  it('asks for the previous week by date, letting the server resolve the boundary', async () => {
    // The client never computes a Monday. It names a date inside the week it
    // wants and the server answers the week containing it — one boundary rule,
    // on the side that also owns the totals.
    const seen: string[] = []
    server.use(
      http.get('/api/v1/users/:userId/timesheet', ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('weekOf') ?? '')
        return HttpResponse.json({ data: week() })
      }),
    )
    renderPage()

    await screen.findByText(/Ravi Kumar/)
    await userEvent.click(screen.getByRole('button', { name: 'Previous week' }))

    expect(seen[0]).toBe(MONDAY)
    expect(seen).toContain('2026-08-03')
  })
})

/**
 * B-065 · "Mark reviewed" and the badge behind it.
 *
 * The row question — Admin, or this resource's own direct manager, nobody
 * else — lives server-side and is exactly what a 404 on submit exercises
 * below; the button's own visibility is the coarser, rowless half:
 * `canApproveTimesheet` cannot know in advance whether this particular PM
 * manages this particular resource, so it draws for any PM or Admin and lets
 * the server refuse a submission that turns out not to be theirs to make.
 */
describe('B-065 · marking a week reviewed', () => {
  function stubApproval(
    response: { weekStart: string; approvedBy: unknown; approvedAt: string; note: string | null } | null,
    status = 201,
  ) {
    server.use(
      http.post('/api/v1/users/:userId/timesheet/approval', () =>
        status === 201
          ? HttpResponse.json({ data: response }, { status: 201 })
          : HttpResponse.json(
              { type: 'https://edutrack/errors/already-approved', title: 'Already reviewed', status },
              { status, headers: { 'Content-Type': 'application/problem+json' } },
            ),
      ),
    )
  }

  it('offers the control to a PM, and not to a Developer', async () => {
    signedInAs('PM')
    stub(week())
    renderPage()

    expect(await screen.findByRole('button', { name: /Mark reviewed/ })).toBeInTheDocument()

    signedInAs('DEVELOPER')
    stub(week())
    renderPage()

    await screen.findByText(/Ravi Kumar/)
    expect(screen.queryByRole('button', { name: /Mark reviewed/ })).not.toBeInTheDocument()
  })

  it('shows who reviewed it, and offers no control once a week is already reviewed', async () => {
    signedInAs('ADMIN')
    stub(
      week({
        approval: {
          weekStart: MONDAY,
          approvedBy: { id: 2, displayName: 'Meera Nair', role: 'PM' },
          approvedAt: '2026-08-17T10:15:00Z',
          note: 'Looks right',
        },
      }),
    )
    renderPage()

    expect(await screen.findByText(/Reviewed by Meera Nair on 17 Aug 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Looks right/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Mark reviewed/ })).not.toBeInTheDocument()
  })

  it('marks the week reviewed and shows the reviewer once the request succeeds', async () => {
    signedInAs('PM')
    stub(week())
    stubApproval({
      weekStart: MONDAY,
      approvedBy: { id: 2, displayName: 'Meera Nair', role: 'PM' },
      approvedAt: '2026-08-17T10:15:00Z',
      note: 'All good',
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Mark reviewed/ }))
    await userEvent.type(screen.getByLabelText(/Note/), 'All good')

    // The GET is re-stubbed to answer with the approval now attached, mirroring
    // what the real server would return on the refetch this submission triggers.
    stub(
      week({
        approval: {
          weekStart: MONDAY,
          approvedBy: { id: 2, displayName: 'Meera Nair', role: 'PM' },
          approvedAt: '2026-08-17T10:15:00Z',
          note: 'All good',
        },
      }),
    )
    await userEvent.click(screen.getByRole('button', { name: 'Mark reviewed' }))

    expect(await screen.findByText(/Reviewed by Meera Nair on 17 Aug 2026/)).toBeInTheDocument()
  })

  it('shows the server refusal when this caller turns out not to be the manager', async () => {
    // canApproveTimesheet cannot see the reporting line from here — the button
    // shows for any PM, and a 404 is exactly what a PM who is not this
    // resource's own direct manager gets back.
    signedInAs('PM')
    stub(week())
    server.use(
      http.post('/api/v1/users/:userId/timesheet/approval', () =>
        HttpResponse.json(
          { type: 'https://edutrack/errors/not-found', title: 'Not found', status: 404 },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Mark reviewed/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Mark reviewed' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('names the first reviewer when the week was already reviewed underneath this request', async () => {
    signedInAs('ADMIN')
    stub(week())
    stubApproval(null, 409)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Mark reviewed/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Mark reviewed' }))

    expect(await screen.findByText('Already reviewed')).toBeInTheDocument()
  })
})
