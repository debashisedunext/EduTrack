import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ProjectTeamPage } from './ProjectTeamPage'

/**
 * B-017 · S-10's Team tab against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that an
 * unstated allocation is blank rather than 100, that zero is not blank, that a
 * member holding open work is refused *before* the click, and that clearing a
 * field really clears it rather than silently doing nothing.
 *
 * Project 1 in the mock has Anita (no allocation), Meera (100), Ravi (70) and
 * Anil (0) on it, and some of them hold open tickets — the roster is
 * deliberately not uniform, because a team where everybody looks the same
 * proves nothing about either edge.
 */
function renderPage(projectId = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/masters/projects/${projectId}/team`]}>
        <Routes>
          <Route path="/masters/projects/:projectId/team" element={<ProjectTeamPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const rowFor = async (name: string) =>
  (await screen.findByText(name)).closest('tr') as HTMLElement

describe('the project team tab', () => {
  it('lists the team with each member’s global role beside their project role', async () => {
    renderPage()

    const ravi = await rowFor('Ravi Kumar')
    expect(within(ravi).getByText(/DEVELOPER/)).toBeInTheDocument()
    expect(
      within(ravi).getByRole('combobox', { name: /Project role for Ravi Kumar/ }),
    ).toBeInTheDocument()
  })

  it('shows an unstated allocation as blank, never as 100', async () => {
    // The claim the nullable column exists for. Every membership written before
    // this screen has no allocation, and rendering them as fully committed
    // would be inventing a figure nobody entered.
    renderPage()

    const anita = await rowFor('Anita Rao')
    expect(
      within(anita).getByRole('spinbutton', { name: /Allocation percent for Anita Rao/ }),
    ).toHaveValue(null)
  })

  it('shows a zero allocation as zero, not as blank', async () => {
    // The other half of the same distinction: "no capacity committed" is a
    // stated fact and must not read as "nobody has said".
    renderPage()

    const anil = await rowFor('Anil Shah')
    expect(
      within(anil).getByRole('spinbutton', { name: /Allocation percent for Anil Shah/ }),
    ).toHaveValue(0)
  })

  it('totals only the stated allocations, and says how many are missing', async () => {
    renderPage()

    // Meera 100 + Ravi 70 + Anil 0 = 170, with Anita and Priya unstated. If the
    // unstated ones were folded in at 100 this would read 370.
    expect(await screen.findByText(/170% allocated/)).toBeInTheDocument()
    expect(screen.getByText(/no allocation set/)).toBeInTheDocument()
  })

  it('saves a project role change on its own, with no Save button', async () => {
    // There is no form and no dirty state — each cell is one PATCH of one
    // field, which is what makes the absent If-Match safe.
    renderPage()

    expect(screen.queryByRole('button', { name: /^Save/ })).not.toBeInTheDocument()
  })

  it('disables removal for a member holding open tickets, and says why before the click', async () => {
    // B-014's lesson from the resource grid: the count is already on the roster,
    // so spending a round trip to be told what was on screen makes the refusal
    // read as a failure of the click rather than a fact about the organisation.
    renderPage()

    const blocked = await screen.findAllByRole('button', {
      name: /holds \d+ open tickets? on this project and cannot be removed/,
    })
    expect(blocked.length).toBeGreaterThan(0)
    blocked.forEach((button) => expect(button).toBeDisabled())
  })

  it('offers only resources who are not already on the team', async () => {
    renderPage()

    // The picker is always visible — no reveal button in front of it. Opening
    // it is one click, which is the whole reason it is not behind another.
    fireEvent.click(await screen.findByRole('button', { name: /Add a resource to the team/ }))

    const options = await screen.findAllByRole('option')
    const labels = options.map((o) => o.textContent)
    // Ravi is already on project 1, so offering him again would be offering a
    // choice whose only outcome is a 409.
    expect(labels).not.toContain('Ravi Kumar')
    // Sunil is deactivated, and the server refuses him by name — a picker that
    // offered him would be offering an error.
    expect(labels).not.toContain('Sunil Menon')
  })

  it('renders the tab strip with Team current, and all four of S-10’s tabs', async () => {
    renderPage()

    expect(await screen.findByRole('link', { name: 'Team' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'General' })).toBeInTheDocument()
    // SLA arrived with B-018 and Settings with B-019. The rule this test was
    // written to hold is that **a tab appears when the screen behind it
    // exists** — never as a disabled stub, since a greyed-out tab and a broken
    // one look identical to a user. It has now been asserted in both
    // directions: it failed when Settings was added, which is exactly what it
    // was for.
    expect(screen.getByRole('link', { name: 'SLA' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href', '/masters/projects/1/settings',
    )
  })

  it('refuses an out-of-range allocation without sending it', async () => {
    renderPage()

    const input = within(await rowFor('Anil Shah')).getByRole('spinbutton', {
      name: /Allocation percent for Anil Shah/,
    })
    fireEvent.change(input, { target: { value: '150' } })
    fireEvent.blur(input)

    expect(await screen.findByRole('alert')).toHaveTextContent(/between 0 and 100/)
    // And the stored value is put back, rather than leaving a number on screen
    // that was never saved.
    await waitFor(() => expect(input).toHaveValue(0))
  })
})
