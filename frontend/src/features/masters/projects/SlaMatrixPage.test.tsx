import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { SlaMatrixPage } from './SlaMatrixPage'

/**
 * B-018 · S-10's SLA tab against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that an
 * inherited cell says where its figure came from, that saving an untouched grid
 * does not turn every inherited cell into an override, and that emptying a box
 * really removes an override rather than silently doing nothing.
 *
 * The mock's project 1 (CRM) is deliberately not uniform — the seed gives it
 * two rung-1 overrides on Production Bug and inherits everything else, so both
 * kinds of cell are on screen at once. Project 2 (PAY) has a rung-2 row, which
 * is the case the grid must render as inherited and must not delete.
 */
function renderPage(projectId = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/masters/projects/${projectId}/sla`]}>
        <Routes>
          <Route path="/masters/projects/:projectId/sla" element={<SlaMatrixPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The row for one task type × level, found by the input's accessible name. */
async function rowFor(taskType: string, level: string) {
  const input = await screen.findByRole('spinbutton', {
    name: new RegExp(`Resolution hours for ${taskType} at ${level}`),
  })
  return input.closest('tr') as HTMLElement
}

const resolutionInput = (row: HTMLElement) =>
  within(row).getByRole('spinbutton', { name: /Resolution hours/ })

const saveButton = () => screen.getByRole('button', { name: /^Save|^Saved|^Saving/ })

/**
 * The three save assertions get a wider window than the 5s default.
 *
 * This screen renders eleven sections of four rows with seven cells each, and a
 * save remounts the whole thing on the new `ETag` — around two seconds on an
 * idle machine, which are the slowest tests in the repository. A 2.5× margin is
 * thin on a loaded CI runner, and a test that fails only when the box is busy
 * is worse than a slow one: it teaches everybody to re-run rather than read.
 * Found the honest way, by running the backend suite alongside this one.
 */
const SAVE_TIMEOUT = { timeout: 15_000 }

describe('the project SLA tab', () => {
  it('renders a row for every task type × level, not only the configured ones', async () => {
    // A grid of this project's own rows would be almost entirely blank for a
    // project whose tickets all get perfectly good planned close dates.
    renderPage()

    await screen.findByText('Change Request')
    expect(await screen.findAllByRole('spinbutton', { name: /Resolution hours/ })).toHaveLength(11 * 4)
  })

  it('shows an inherited figure and says where it came from', async () => {
    // The distinction the whole screen exists to make. Blanking inherited cells
    // would tell an administrator nothing is configured when a ticket raised
    // there is measured against a real target.
    renderPage()

    const row = await rowFor('Change Request', 'LOW')
    expect(resolutionInput(row)).not.toHaveValue(null)
    expect(within(row).getByText(/Organisation default|Priority master|Project default/)).toBeInTheDocument()
  })

  it('marks a cell this project set as an override', async () => {
    // CRM's Production Bug × HIGH is a rung-1 row in the seed.
    renderPage()

    const row = await rowFor('Production Bug', 'HIGH')
    expect(within(row).getByText('Set here')).toBeInTheDocument()
  })

  it('starts with the Save button disabled and no changes to make', async () => {
    renderPage()
    await rowFor('Change Request', 'LOW')

    expect(saveButton()).toBeDisabled()
    expect(saveButton()).toHaveTextContent('Saved')
  })

  it('enables Save once a cell is edited, and counts the edits', async () => {
    renderPage()

    fireEvent.change(resolutionInput(await rowFor('Change Request', 'LOW')), {
      target: { value: '99' },
    })

    await waitFor(() => expect(saveButton()).toHaveTextContent('Save 1 change'))
    expect(saveButton()).toBeEnabled()
  })

  it('editing an inherited cell turns it into an override once saved', async () => {
    renderPage()

    const before = await rowFor('Change Request', 'LOW')
    expect(within(before).queryByText('Set here')).not.toBeInTheDocument()

    fireEvent.change(resolutionInput(before), { target: { value: '99' } })
    fireEvent.click(saveButton())

    // Re-queried inside the wait, not captured before it. The form remounts on
    // the new `ETag` so the row is a different node afterwards, and holding the
    // old one asserts against something detached from the document.
    await waitFor(async () =>
      expect(within(await rowFor('Change Request', 'LOW')).getByText('Set here')).toBeInTheDocument(),
      SAVE_TIMEOUT)
    expect(resolutionInput(await rowFor('Change Request', 'LOW'))).toHaveValue(99)
  })

  it('the cells nobody touched stay inherited after a save', async () => {
    // The defect the override/inherit split exists to prevent, end to end: if
    // the body carried the resolved grid, saving one cell would materialise the
    // other forty-three as project rows and the project would silently stop
    // following the defaults it was shown as following.
    renderPage()

    fireEvent.change(resolutionInput(await rowFor('Change Request', 'LOW')), {
      target: { value: '99' },
    })
    fireEvent.click(saveButton())

    await waitFor(() => expect(saveButton()).toHaveTextContent('Saved'), SAVE_TIMEOUT)

    const untouched = await rowFor('Client Request', 'MEDIUM')
    expect(within(untouched).queryByText('Set here')).not.toBeInTheDocument()
  })

  it('emptying the resolution box removes the override and the cell inherits again', async () => {
    renderPage()

    const row = await rowFor('Production Bug', 'HIGH')
    expect(within(row).getByText('Set here')).toBeInTheDocument()

    fireEvent.change(resolutionInput(row), { target: { value: '' } })
    fireEvent.click(saveButton())

    await waitFor(() => expect(saveButton()).toHaveTextContent('Saved'), SAVE_TIMEOUT)

    const after = await rowFor('Production Bug', 'HIGH')
    expect(within(after).queryByText('Set here')).not.toBeInTheDocument()
    // Inherits rather than going blank — is_active = 0 is what the resolution
    // ladder already reads, so a cleared cell falls through to the next rung.
    expect(resolutionInput(after)).not.toHaveValue(null)
  })

  it('refuses a response target longer than the resolution target, on the row', async () => {
    // On the row rather than in a banner: a banner naming one cell out of
    // forty-four is a hunt.
    renderPage()

    const row = await rowFor('Change Request', 'LOW')
    fireEvent.change(
      within(row).getByRole('spinbutton', { name: /Response hours/ }),
      { target: { value: '9999' } },
    )

    await waitFor(() =>
      expect(within(row).getByRole('alert'))
        .toHaveTextContent(/response target cannot be longer/i))
    expect(saveButton()).toBeDisabled()
  })

  it('the escalation controls are flags, and say who each level means', async () => {
    // §6 fixes the recipients — L1 the reporting manager, L2 that manager's
    // manager — so there is nobody to pick. A checkbox labelled "L1" and
    // nothing else is one nobody can answer.
    renderPage()

    const row = await rowFor('Production Bug', 'CRITICAL')
    expect(within(row).getByRole('checkbox', { name: /reporting manager on breach/ })).toBeInTheDocument()
    expect(within(row).getByRole('checkbox', { name: /manager.s manager after 48 working hours/ }))
      .toBeInTheDocument()
  })

  it('reverting an unsaved change puts the stored figures back', async () => {
    renderPage()

    const row = await rowFor('Change Request', 'LOW')
    const stored = (resolutionInput(row) as HTMLInputElement).value

    fireEvent.change(resolutionInput(row), { target: { value: '77' } })
    await waitFor(() => expect(saveButton()).toBeEnabled())

    fireEvent.click(within(row).getByRole('button', { name: /Discard the unsaved change/ }))

    await waitFor(() => expect(saveButton()).toBeDisabled())
    expect(resolutionInput(row)).toHaveValue(Number(stored))
  })

  it('a project-level default renders as inherited rather than as this project’s own', async () => {
    // PAY has a rung-2 row: one policy covering every task type at CRITICAL.
    // The grid has no cell for it, so it must show as the *source* of the cells
    // it answers — and the save must not offer to edit or delete it.
    renderPage(2)

    const row = await rowFor('Change Request', 'CRITICAL')
    expect(within(row).getByText('Project default')).toBeInTheDocument()
    expect(within(row).queryByText('Set here')).not.toBeInTheDocument()
  })

  it('summarises how much of the grid this project has taken over', async () => {
    // The one sentence that tells an administrator whether the project is
    // mostly following the defaults or has quietly drifted onto its own.
    renderPage()

    expect(await screen.findByText(/of 44 combinations are set on this project|follow the organisation/))
      .toBeInTheDocument()
  })
})
