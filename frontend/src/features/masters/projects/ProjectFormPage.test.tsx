import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ProjectFormPage } from './ProjectFormPage'

/**
 * B-016 · S-10's create/edit form against the mock server.
 *
 * The behaviour worth a test here is the immutability rule as the admin meets
 * it: the code input closes itself on a project that has issued a ticket ID, and
 * says how many, rather than accepting a new value and refusing the save.
 */
function renderForm(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/masters/projects/new" element={<ProjectFormPage />} />
          <Route path="/masters/projects/:projectId/edit" element={<ProjectFormPage />} />
          <Route path="/masters/projects" element={<p>the grid</p>} />
        </Routes>
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('the project code', () => {
  it('is editable on a create', async () => {
    renderForm('/masters/projects/new')

    expect(await screen.findByLabelText(/project code/i)).toBeEnabled()
  })

  it('is disabled on a project that has issued ticket IDs, and says how many', async () => {
    // CRM's ticketSeq is 347. Disabled rather than hidden: a missing input
    // reads as a rendering bug, and the count is the kind of thing an admin
    // should see rather than discover from a 409 after typing.
    renderForm('/masters/projects/1/edit')

    const code = await screen.findByLabelText(/project code/i)
    await waitFor(() => expect(code).toBeDisabled())
    expect(screen.getByText(/347 ticket IDs already carry this prefix/i)).toBeInTheDocument()
  })

  it('stays editable while the project has issued none', async () => {
    // ARCH has ticketSeq 0. The rule is `ticketsIssued > 0`, not "is it old" or
    // "does it have tickets".
    const arch = getDb().projects.find((p) => p.projectCode === 'ARCH')!
    expect(arch.ticketSeq).toBe(0)

    renderForm(`/masters/projects/${arch.id}/edit`)

    const code = await screen.findByLabelText(/project code/i)
    await waitFor(() => expect(code).toHaveValue('ARCH'))
    expect(code).toBeEnabled()
  })

  it('upper-cases what is typed, because it becomes a ticket-ID prefix', async () => {
    renderForm('/masters/projects/new')

    const code = await screen.findByLabelText(/project code/i)
    fireEvent.change(code, { target: { value: 'billing' } })

    await waitFor(() => expect(code).toHaveValue('BILLING'))
  })
})

describe('the edit form', () => {
  it('loads every stored field, not only the ones the grid shows', async () => {
    renderForm('/masters/projects/1/edit')

    await waitFor(() => expect(screen.getByLabelText(/project name/i)).toHaveValue('Client CRM Platform'))
    expect(screen.getByLabelText(/^client/i)).toHaveValue('Acme Retail Ltd')
    expect(screen.getByLabelText(/description/i)).toHaveValue('The client-facing CRM, and the busiest project on the desk.')
    expect(screen.getByLabelText(/start date/i)).toHaveValue('2026-01-05')
    expect(screen.getByLabelText(/target end date/i)).toHaveValue('2026-12-18')
  })

  it('shows the issued count as a read-only fact', async () => {
    renderForm('/masters/projects/1/edit')

    expect(await screen.findByText('Ticket IDs issued')).toBeInTheDocument()
  })

  it('saves and returns to the grid', async () => {
    renderForm('/masters/projects/1/edit')

    await waitFor(() => expect(screen.getByLabelText(/project name/i)).toHaveValue('Client CRM Platform'))
    fireEvent.change(screen.getByLabelText(/project name/i), { target: { value: 'CRM, renamed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save project' }))

    expect(await screen.findByText('the grid')).toBeInTheDocument()
    expect(getDb().projects.find((p) => p.id === 1)?.name).toBe('CRM, renamed')
  })
})

describe('validation', () => {
  it('refuses a target end date before the start date, on the field', async () => {
    renderForm('/masters/projects/new')

    fireEvent.change(await screen.findByLabelText(/project code/i), { target: { value: 'NEW' } })
    fireEvent.change(screen.getByLabelText(/project name/i), { target: { value: 'Greenfield' } })
    fireEvent.change(screen.getByLabelText(/start date/i), { target: { value: '2026-09-01' } })
    fireEvent.change(screen.getByLabelText(/target end date/i), { target: { value: '2026-08-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }))

    expect(await screen.findByText(/cannot be before the start date/i)).toBeInTheDocument()
  })

  it('will not create without a project manager', async () => {
    // S-10 asterisks it, and without one there is nobody for the SLA engine to
    // escalate to.
    renderForm('/masters/projects/new')

    fireEvent.change(await screen.findByLabelText(/project code/i), { target: { value: 'NEW' } })
    fireEvent.change(screen.getByLabelText(/project name/i), { target: { value: 'Greenfield' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }))

    expect(await screen.findByText(/project manager is required/i)).toBeInTheDocument()
    expect(screen.queryByText('the grid')).not.toBeInTheDocument()
  })
})
