import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { server } from '@/mocks/server'
import { http, HttpResponse } from 'msw'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { RoleListPage } from './RoleListPage'

/**
 * B-015 · S-09's role grid against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * system role's delete is refused before it is clicked, that a role somebody
 * holds is refused with a count, and that the count is on the row rather than
 * only in the refusal.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/roles']}>
        <RoleListPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const rowFor = async (name: string) =>
  (await screen.findByRole('link', { name })).closest('tr') as HTMLElement

describe('the role grid', () => {
  it('lists the six seeded roles with their grant counts', async () => {
    renderPage()

    const developer = await rowFor('Developer')
    expect(within(developer).getByText('DEVELOPER')).toBeInTheDocument()
    // Four grants in the §2 matrix, and none of them history.edit_delete.
    expect(within(developer).getByText('4')).toBeInTheDocument()
  })

  it('shows how many resources hold each role, before anything is clicked', async () => {
    // A delete that is going to be refused should be visibly going to be
    // refused. Discovering the count only in the error is how an admin clicks
    // it four times to see whether anything changed.
    const holders = getDb().users.filter((u) => u.role === 'DEVELOPER').length
    expect(holders).toBeGreaterThan(0)

    renderPage()

    const developer = await rowFor('Developer')
    expect(within(developer).getByText(String(holders))).toBeInTheDocument()
  })

  it('marks every seeded role as a system role', async () => {
    renderPage()
    await rowFor('Admin')

    expect(screen.getAllByText('System')).toHaveLength(6)
  })
})

describe('delete', () => {
  it('disables delete on a system role and says why', async () => {
    renderPage()

    const admin = await rowFor('Admin')
    const remove = within(admin).getByRole('button', { name: 'Delete' })

    expect(remove).toBeDisabled()
    expect(remove).toHaveAttribute(
      'title',
      'System roles cannot be deleted. Deactivate it instead.',
    )
  })

  it('refuses a custom role that resources still hold, naming the count', async () => {
    const db = getDb()
    db.roles.push({
      id: 90, code: 'AUDITOR', name: 'Auditor', description: null,
      isSystem: false, isActive: true,
    })
    db.roleGrants[90] = []
    db.users[0].role = 'AUDITOR' as typeof db.users[0]['role']

    renderPage()

    const auditor = await rowFor('Auditor')
    fireEvent.click(within(auditor).getByRole('button', { name: 'Delete' }))

    // The dialog says it before the request is made, and the confirm is
    // disabled — the refusal is visible rather than only reachable.
    expect(await screen.findByText(/1 resource still hold/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /delete role/i })).toBeDisabled()
  })

  it('deletes an unused custom role', async () => {
    const db = getDb()
    db.roles.push({
      id: 91, code: 'VIEWER', name: 'Viewer', description: null,
      isSystem: false, isActive: true,
    })
    db.roleGrants[91] = []

    renderPage()

    const viewer = await rowFor('Viewer')
    fireEvent.click(within(viewer).getByRole('button', { name: 'Delete' }))
    fireEvent.click(await screen.findByRole('button', { name: /delete role/i }))

    expect(await screen.findByText('Viewer deleted')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Viewer' })).toBeNull())
  })
})

describe('create', () => {
  it('creates a role with no permissions and says so', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New role' }))

    fireEvent.change(screen.getByRole('textbox', { name: /code/i }), {
      target: { value: 'auditor' },
    })
    fireEvent.change(screen.getByRole('textbox', { name: /^name$/i }), {
      target: { value: 'Auditor' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create role' }))

    expect(await screen.findByText('Auditor created')).toBeInTheDocument()
    expect(screen.getByText(/holds no permissions yet/i)).toBeInTheDocument()
    // Upper-cased on the way out — `developer` must not be stored beside
    // `DEVELOPER` and become indistinguishable in every screen.
    expect(getDb().roles.find((r) => r.name === 'Auditor')?.code).toBe('AUDITOR')
  })

  it('lands a duplicate-code 409 on the code field, not in a banner', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New role' }))

    fireEvent.change(screen.getByRole('textbox', { name: /code/i }), {
      target: { value: 'ADMIN' },
    })
    fireEvent.change(screen.getByRole('textbox', { name: /^name$/i }), {
      target: { value: 'Another admin' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create role' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/already exists/i)
    expect(screen.getByRole('textbox', { name: /code/i })).toHaveAttribute('aria-invalid', 'true')
  })

  it('warns that a custom role cannot be assigned to a resource yet', async () => {
    // The contract types user.role as a closed six-value enum. Saying so here
    // beats an admin creating one and finding it missing from the S-08 picker.
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New role' }))

    expect(
      screen.getByText(/cannot yet be assigned to a resource/i),
    ).toBeInTheDocument()
  })

  it('reports an unexpected failure without claiming the role was created', async () => {
    server.use(
      http.post('/api/v1/masters/roles', () => HttpResponse.json({}, { status: 500 })),
    )
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New role' }))

    fireEvent.change(screen.getByRole('textbox', { name: /code/i }), {
      target: { value: 'AUDITOR' },
    })
    fireEvent.change(screen.getByRole('textbox', { name: /^name$/i }), {
      target: { value: 'Auditor' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create role' }))

    expect(await screen.findByText('Could not create the role')).toBeInTheDocument()
  })
})
