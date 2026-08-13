import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { RolePermissionsPage } from './RolePermissionsPage'

/**
 * B-015 · S-09's matrix against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that
 * `history.edit_delete` renders disabled rather than being omitted, that the
 * save is replace-all, and that a stale `ETag` produces an explanation rather
 * than a silent overwrite.
 */
function renderPage(roleId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/masters/roles/${roleId}`]}>
        <Routes>
          <Route path="/masters/roles/:roleId" element={<RolePermissionsPage />} />
        </Routes>
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const roleId = (code: string) => getDb().roles.find((r) => r.code === code)!.id

const checkbox = (name: RegExp | string) => screen.getByRole('checkbox', { name })

describe('the matrix', () => {
  it('renders every capability grouped by module', async () => {
    renderPage(roleId('DEVELOPER'))

    expect(await screen.findByText('Tickets')).toBeInTheDocument()
    expect(screen.getByText('History & ribbon')).toBeInTheDocument()
    expect(screen.getByText('Administration')).toBeInTheDocument()
    expect(screen.getByText('Audit')).toBeInTheDocument()
  })

  it('ticks exactly the capabilities the role holds', async () => {
    renderPage(roleId('DEVELOPER'))

    expect(await screen.findByRole('checkbox', { name: /Hand off to next stage/ })).toBeChecked()
    expect(checkbox(/Force-move ribbon backwards/)).not.toBeChecked()
  })

  it('renders history.edit_delete disabled rather than omitting it', async () => {
    // Blueprint §2: "Edit / delete history or ribbon — ❌ (nobody can)". A row
    // that is simply absent reads as a permission somebody forgot; a disabled
    // one shows the guarantee on the screen you would go looking for it on.
    renderPage(roleId('ADMIN'))

    const editHistory = await screen.findByRole('checkbox', {
      name: /Edit \/ delete history or ribbon/,
    })
    expect(editHistory).toBeDisabled()
    expect(editHistory).not.toBeChecked()
    expect(screen.getByText(/Cannot be granted to anyone/)).toBeInTheDocument()
  })

  it('cannot be saved until something changes', async () => {
    renderPage(roleId('QA'))

    expect(await screen.findByRole('button', { name: 'Save permissions' })).toBeDisabled()
  })

  it('saves as replace-all — an unticked capability is revoked', async () => {
    const id = roleId('QA')
    renderPage(id)

    fireEvent.click(await screen.findByRole('checkbox', { name: /Send back for rework/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save permissions' }))

    expect(await screen.findByText('Permissions saved')).toBeInTheDocument()
    await waitFor(() =>
      expect(getDb().roleGrants[id]).not.toContain('ticket.rework'),
    )
    // The rest survived — this is replace-all, not clear-all.
    expect(getDb().roleGrants[id]).toContain('ticket.handoff')
  })

  it('grants a whole module from the section header, skipping the ungrantable row', async () => {
    const id = roleId('QA')
    renderPage(id)

    fireEvent.click(
      await screen.findByRole('checkbox', { name: /Grant every permission under History/ }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Save permissions' }))

    await waitFor(() => expect(getDb().roleGrants[id]).toContain('history.view_team'))
    expect(getDb().roleGrants[id]).not.toContain('history.edit_delete')
  })

  it('discards changes back to what the server holds', async () => {
    renderPage(roleId('QA'))

    fireEvent.click(await screen.findByRole('checkbox', { name: /Send back for rework/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Discard changes' }))

    expect(checkbox(/Send back for rework/)).toBeChecked()
    expect(screen.getByRole('button', { name: 'Save permissions' })).toBeDisabled()
  })

  it('explains a stale ETag instead of overwriting somebody else', async () => {
    server.use(
      http.put('/api/v1/masters/roles/:roleId/permissions', () =>
        HttpResponse.json(
          { type: 'https://edutrack/errors/precondition-failed', title: 'Stale', status: 412 },
          { status: 412, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderPage(roleId('QA'))

    fireEvent.click(await screen.findByRole('checkbox', { name: /Send back for rework/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save permissions' }))

    expect(await screen.findByText('Somebody else changed this role')).toBeInTheDocument()
    expect(screen.getByText(/your edit was not saved/i)).toBeInTheDocument()
  })
})

describe('the role itself', () => {
  it('shows the code read-only, with the reason it cannot change', async () => {
    renderPage(roleId('DEVELOPER'))

    const code = await screen.findByLabelText('Code')
    expect(code).toBeDisabled()
    expect(screen.getByText(/carried in access tokens/i)).toBeInTheDocument()
  })

  it('renames a system role — isSystem guards deletion, not editing', async () => {
    const id = roleId('SUPPORT')
    renderPage(id)

    fireEvent.change(await screen.findByLabelText('Name'), {
      target: { value: 'Support Desk (APAC)' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save role' }))

    expect(await screen.findByText('Role saved')).toBeInTheDocument()
    await waitFor(() =>
      expect(getDb().roles.find((r) => r.id === id)?.name).toBe('Support Desk (APAC)'),
    )
  })

  it('deactivates a system role without breaking the resources that hold it', async () => {
    const id = roleId('QA')
    const holders = getDb().users.filter((u) => u.role === 'QA').length

    renderPage(id)

    fireEvent.click(await screen.findByRole('checkbox', { name: /^Active/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save role' }))

    await waitFor(() => expect(getDb().roles.find((r) => r.id === id)?.isActive).toBe(false))
    expect(getDb().users.filter((u) => u.role === 'QA')).toHaveLength(holders)
  })

  it('reports an unknown role rather than rendering an empty form', async () => {
    renderPage(9999)

    expect(await screen.findByText('This role could not be loaded.')).toBeInTheDocument()
  })
})
