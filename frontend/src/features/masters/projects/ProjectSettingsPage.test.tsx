import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'

import { ProjectSettingsPage } from './ProjectSettingsPage'

/**
 * B-019 · S-10's Settings tab against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that an
 * unrestricted project starts with nothing ticked *and says why*, that saving an
 * untouched screen does not invent an allow-list, and that a retired task type
 * the project still allows is rendered rather than quietly dropped.
 *
 * The mock's project 1 (CRM) is unrestricted and requires two extra fields.
 * Project 2 (PAY) is the restricted one, allowing two of the eleven — so both
 * states are reachable without building a fixture here.
 */
function renderPage(projectId = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/masters/projects/${projectId}/settings`]}>
        <Routes>
          <Route path="/masters/projects/:projectId/settings" element={<ProjectSettingsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const saveButton = () => screen.getByRole('button', { name: /^Save|^Saved|^Saving/ })

const taskTypeBox = (name: string) => screen.getByRole('checkbox', { name: new RegExp(name) })

describe('the project Settings tab', () => {
  it('renders a checkbox for every active task type', async () => {
    renderPage()

    await screen.findByText('Allowed task types')
    expect(taskTypeBox('Change Request')).toBeInTheDocument()
    expect(taskTypeBox('Production Bug')).toBeInTheDocument()
  })

  it('shows an unrestricted project with nothing ticked, and says what that means', async () => {
    // The sentence is the screen's whole job here. Eleven empty boxes read as
    // "nothing may be raised", and they mean the opposite.
    renderPage()

    await screen.findByText(/no restriction/i)
    expect(screen.getByText(/every active task type may be raised/i)).toBeInTheDocument()
    expect(taskTypeBox('Change Request')).not.toBeChecked()
  })

  it('does not offer "0 of 11", which would read as a restriction permitting nothing', async () => {
    renderPage()

    await screen.findByText(/no restriction/i)
    expect(screen.queryByText(/^0 of/)).not.toBeInTheDocument()
  })

  it('shows a restricted project with exactly its allowed types ticked', async () => {
    renderPage(2)

    await screen.findByText(/2 of 11 task types/i)
    expect(taskTypeBox('Production Bug')).toBeChecked()
    expect(taskTypeBox('Internal Bug')).toBeChecked()
    expect(taskTypeBox('Change Request')).not.toBeChecked()
  })

  it('starts saved — an untouched screen has nothing to write', async () => {
    // The mirror of the draft rule. If the draft copied `isAllowed`, an
    // untouched unrestricted project would look dirty and its first save would
    // materialise an allow-list nobody asked for.
    renderPage()

    await screen.findByText(/no restriction/i)
    expect(saveButton()).toBeDisabled()
  })

  it('ticking a task type restricts the project, and the sentence changes with it', async () => {
    renderPage()

    await screen.findByText(/no restriction/i)
    fireEvent.click(taskTypeBox('Production Bug'))

    expect(await screen.findByText(/1 of 11 task types/i)).toBeInTheDocument()
    expect(saveButton()).toBeEnabled()
  })

  it('saves an allow-list and the server keeps it', async () => {
    renderPage()

    await screen.findByText(/no restriction/i)
    fireEvent.click(taskTypeBox('Production Bug'))
    fireEvent.click(saveButton())

    await waitFor(() => expect(
      getDb().projectTaskTypes.filter((r) => r.projectId === 1).map((r) => r.taskTypeId),
    ).toEqual([2]))
  })

  it('unticking the last task type removes the restriction rather than forbidding everything', async () => {
    // There is no other control that reaches this state, and deliberately so.
    renderPage(2)

    await screen.findByText(/2 of 11 task types/i)
    fireEvent.click(taskTypeBox('Production Bug'))
    fireEvent.click(taskTypeBox('Internal Bug'))
    fireEvent.click(saveButton())

    await waitFor(() => expect(
      getDb().projectTaskTypes.filter((r) => r.projectId === 2),
    ).toHaveLength(0))
    expect(await screen.findByText(/no restriction/i)).toBeInTheDocument()
  })

  it('renders a retired task type this project still allows, labelled', async () => {
    // The case a read filtering on `isActive` gets wrong. The PUT is assembled
    // from the rows on screen, so one that is allowed and not shown would be
    // deleted by the next save through a screen that never displayed it.
    const db = getDb()
    db.projectTaskTypes.push({ projectId: 1, taskTypeId: 9 })
    const browserIssue = db.taskTypes.find((t) => t.id === 9)!
    browserIssue.isActive = false

    try {
      renderPage()

      const box = await screen.findByRole('checkbox', { name: /Browser Issue/ })
      expect(box).toBeChecked()
      expect(within(box.closest('li') as HTMLElement).getByText('retired')).toBeInTheDocument()
      expect(screen.getByText(/Retired in the Task Type Master/i)).toBeInTheDocument()
    } finally {
      browserIssue.isActive = true
    }
  })

  it('a retired allowed type survives a save that did not touch it', async () => {
    const db = getDb()
    db.projectTaskTypes.push({ projectId: 1, taskTypeId: 9 })
    const browserIssue = db.taskTypes.find((t) => t.id === 9)!
    browserIssue.isActive = false

    try {
      renderPage()

      await screen.findByRole('checkbox', { name: /Browser Issue/ })
      fireEvent.click(taskTypeBox('Production Bug'))
      fireEvent.click(saveButton())

      await waitFor(() => expect(
        getDb().projectTaskTypes.filter((r) => r.projectId === 1).map((r) => r.taskTypeId).sort(),
      ).toEqual([2, 9]))
    } finally {
      browserIssue.isActive = true
    }
  })

  it('renders the mandatory fields the project requires, and no control for an always-required one', async () => {
    renderPage()

    expect(await screen.findByRole('checkbox', { name: /Module/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /Estimated hours/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /Description/ })).not.toBeChecked()
    // A checkbox for a field every ticket already requires could not change any
    // outcome, so there is none.
    expect(screen.queryByRole('checkbox', { name: /^Title/ })).not.toBeInTheDocument()
  })

  it('saves a mandatory field, and clearing them all stores nothing rather than an empty list', async () => {
    renderPage()

    await screen.findByText('Mandatory fields')
    fireEvent.click(screen.getByRole('checkbox', { name: /Module/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /Estimated hours/ }))
    fireEvent.click(saveButton())

    await waitFor(() =>
      expect(getDb().projects.find((p) => p.id === 1)?.mandatoryFields).toBeNull())
  })

  it('offers the auto-assign rule here, since the General tab no longer edits it', async () => {
    renderPage()

    await screen.findByText('Auto-assign rule')
    expect(screen.getByLabelText(/When a new ticket names no assignee/)).toBeInTheDocument()
  })

  it('carries the stored rule through a save that changed something else', async () => {
    // The replace sends all three settings, so a save that only touched the
    // allow-list must not reset the rule to a default. `projectSettings.test.ts`
    // covers the request shape; this is that shape reaching the server.
    //
    // The rule is asserted rather than *changed* through the UI: the control is
    // a Radix listbox, which is driven by pointer events jsdom does not
    // dispatch. Driving it here would be testing Radix.
    const db = getDb()
    db.projects.find((p) => p.id === 1)!.autoAssignRule = 'LEAST_LOADED'

    renderPage()

    await screen.findByText(/no restriction/i)
    fireEvent.click(taskTypeBox('Production Bug'))
    fireEvent.click(saveButton())

    await waitFor(() => expect(getDb().projectTaskTypes).toHaveLength(3))
    expect(getDb().projects.find((p) => p.id === 1)?.autoAssignRule).toBe('LEAST_LOADED')
  })
})
