import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { ResourceProfilePage } from './ResourceProfilePage'

/**
 * A-069 · S-28.
 *
 * <p>The cases worth pinning are the ones about what the screen <em>says</em>
 * rather than what it fetches: that a refused profile is indistinguishable from
 * a missing one, that a null rate renders as an em dash rather than 0%, and
 * that the window the figures cover is printed rather than assumed.
 */

const PROFILE = {
  data: {
    person: {
      id: 8,
      fullName: 'Nikhil Bansal',
      username: 'nikhil.bansal',
      email: 'nikhil.bansal@edunext.example',
      role: 'DEVELOPER',
      department: 'Engineering',
      designation: 'Senior Developer',
      active: true,
      joinedOn: '2024-04-01',
      managerName: 'Priya Nair',
    },
    from: '2026-07-19',
    to: '2026-08-18',
    openNow: 4,
    closedInWindow: 6,
    effortHours: 22.5,
    slaCompliancePct: 66.7,
    reworkRatePct: 16.7,
    currentStages: [
      { stage: 'DEV', openCount: 3 },
      { stage: 'QA', openCount: 1 },
    ],
  },
}

function renderProfile(body: object | null = PROFILE, status = 200) {
  server.use(
    http.get('/api/v1/users/:id/profile-360', () =>
      body === null ? new HttpResponse(null, { status }) : HttpResponse.json(body),
    ),
    http.get('/api/v1/tickets', () => HttpResponse.json({ data: [], meta: { hasMore: false } })),
    http.get('/api/v1/reports/:key', () =>
      HttpResponse.json({ data: { reportKey: 'resource-velocity', columns: [], rows: [] }, meta: {} }),
    ),
  )

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/resources/8']}>
        <Routes>
          <Route path="/resources/:userId" element={<ResourceProfilePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('S-28 the resource 360 profile', () => {
  it('names the person, their role and who they report to', async () => {
    renderProfile()

    expect(await screen.findByRole('heading', { name: 'Nikhil Bansal' })).toBeInTheDocument()
    expect(screen.getByText(/reports to Priya Nair/)).toBeInTheDocument()
  })

  it('prints the window the figures cover, so nobody quotes a guessed period', async () => {
    renderProfile()

    expect(await screen.findByText(/Figures cover 2026-07-19 to 2026-08-18/)).toBeInTheDocument()
  })

  it('shows the scorecard, with open-now separated from the windowed figures', async () => {
    renderProfile()

    await screen.findByRole('heading', { name: 'Nikhil Bansal' })
    expect(screen.getByText('Open now')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('66.7%')).toBeInTheDocument()
  })

  /**
   * 0% is a measurement — "nothing was on time". Null is the truth when nothing
   * closed, and the two must not look the same on a screen somebody quotes from.
   */
  it('renders a null rate as an em dash, never as 0%', async () => {
    renderProfile({
      data: { ...PROFILE.data, closedInWindow: 0, slaCompliancePct: null, reworkRatePct: null },
    })

    await screen.findByRole('heading', { name: 'Nikhil Bansal' })
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })

  it('breaks the open work down by the stage it is sitting at', async () => {
    renderProfile()

    await screen.findByRole('heading', { name: 'Nikhil Bansal' })

    // Scoped to the section: /DEV/ alone also matches the role DEVELOPER in
    // the header, which is the sort of match that passes for the wrong reason.
    const section = screen.getByRole('region', { name: /Where their open work is sitting/ })
    expect(within(section).getByText(/DEV/)).toBeInTheDocument()
    expect(within(section).getByText('3')).toBeInTheDocument()
  })

  /**
   * The server answers 404 for both "no such person" and "not yours to see", so
   * that ids cannot be walked to learn who exists. The screen must not invent a
   * distinction the API deliberately refuses to make.
   */
  it('says the same thing for a missing profile and a forbidden one', async () => {
    renderProfile(null, 404)

    expect(await screen.findByText('Profile not available')).toBeInTheDocument()
    expect(screen.getByText(/does not exist, or is not one you can view/)).toBeInTheDocument()
  })

  it('links to the full ticket list rather than embedding a second one', async () => {
    renderProfile()

    await screen.findByRole('heading', { name: 'Nikhil Bansal' })
    await waitFor(() =>
      expect(screen.getByRole('link', { name: /Complete history/ })).toHaveAttribute(
        'href',
        '/tickets?assigneeId=8',
      ),
    )
  })
})
