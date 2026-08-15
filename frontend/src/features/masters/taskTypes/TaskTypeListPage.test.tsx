import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { TaskTypeListPage } from './TaskTypeListPage'

/**
 * B-020 · S-11 against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * retired type is still listed, that the ticket count is on the row rather than
 * only in the confirmation, that the code is locked on edit, and that retiring
 * is a `PATCH` and not a delete.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/task-types']}>
        <TaskTypeListPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * The default 1 s async timeout is not enough for the assertions that follow a
 * mutation: the write, the invalidation and the refetch are three round trips
 * through MSW, and this page renders twelve rows plus a portalled dialog each
 * time. Raised locally rather than globally — the other suites are not slow,
 * and a shared `configure({ asyncUtilTimeout })` would hide a genuinely hanging
 * query somewhere else.
 */
const SLOW = { timeout: 5000 }

const rowFor = async (name: string) =>
  (await screen.findByText(name, undefined, SLOW)).closest('tr') as HTMLElement

describe('the task type grid', () => {
  it('lists the seeded types with their codes', async () => {
    renderPage()

    const bug = await rowFor('Production Bug')
    expect(within(bug).getByText('PRODUCTION_BUG')).toBeInTheDocument()
    expect(within(bug).getByText('HIGH')).toBeInTheDocument()
  })

  it('lists retired types too, marked as retired', async () => {
    // The endpoint deliberately returns inactive rows: a ticket raised against
    // a since-retired type still has to render its name. A grid that filtered
    // them out would leave an admin unable to see, or undo, a retirement.
    expect(getDb().taskTypes.some((t) => !t.isActive)).toBe(true)

    renderPage()

    const retired = await rowFor('Fax Request')
    expect(within(retired).getByText('Retired')).toBeInTheDocument()
  })

  it('shows the ticket count on the row, before anything is clicked', async () => {
    // Retiring is the consequential act here and its blast radius is invisible
    // from the row. Discovering the count only in the confirmation is how an
    // admin retires the type half the organisation is using.
    const db = getDb()
    const bug = db.taskTypes.find((t) => t.code === 'PRODUCTION_BUG')!
    const raised = db.tickets.filter((t) => t.taskTypeId === bug.id).length
    expect(raised).toBeGreaterThan(0)

    renderPage()

    expect(within(await rowFor('Production Bug')).getByText(String(raised))).toBeInTheDocument()
  })

  it('offers no delete anywhere on the grid', async () => {
    // There is no delete route, and three foreign keys into `task_types` are
    // the reason. A button that looked like one would be a lie whichever way
    // it was wired.
    renderPage()
    await rowFor('Production Bug')

    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
  })
})

describe('create', () => {
  it('refuses a duplicate code on the code input rather than in a toast', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New task type' }))

    fireEvent.change(screen.getByPlaceholderText('DATA_FIX'), {
      target: { value: 'PRODUCTION_BUG' },
    })
    fireEvent.change(screen.getByPlaceholderText('Data Fix'), { target: { value: 'Another Bug' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create task type' }))

    expect(await screen.findByRole('alert', undefined, SLOW)).toHaveTextContent(/already exists/i)
  })

  it('refuses a duplicate name, because every picker renders it', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New task type' }))

    fireEvent.change(screen.getByPlaceholderText('DATA_FIX'), { target: { value: 'CLIENT_DEFECT' } })
    fireEvent.change(screen.getByPlaceholderText('Data Fix'), { target: { value: 'client bug' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create task type' }))

    expect(await screen.findByRole('alert', undefined, SLOW)).toHaveTextContent(/already exists/i)
  })

  it('adds the type to the grid', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'New task type' }))

    fireEvent.change(screen.getByPlaceholderText('DATA_FIX'), { target: { value: 'data_fix' } })
    fireEvent.change(screen.getByPlaceholderText('Data Fix'), { target: { value: 'Data Fix' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create task type' }))

    const row = await rowFor('Data Fix')
    // Lower-cased in, upper-cased out — the server does it, and so does the form.
    expect(within(row).getByText('DATA_FIX')).toBeInTheDocument()
  })
})

describe('edit', () => {
  it('locks the code and says why', async () => {
    renderPage()
    fireEvent.click(within(await rowFor('Production Bug')).getByRole('button', { name: 'Edit' }))

    const code = await screen.findByDisplayValue('PRODUCTION_BUG', undefined, SLOW)
    expect(code).toBeDisabled()
    expect(screen.getByText(/Permanent/)).toBeInTheDocument()
  })

  it('renames without touching anything else', async () => {
    renderPage()
    fireEvent.click(within(await rowFor('Browser Issue')).getByRole('button', { name: 'Edit' }))

    fireEvent.change(await screen.findByDisplayValue('Browser Issue', undefined, SLOW), {
      target: { value: 'Browser Defect' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await rowFor('Browser Defect')
    // The icon and default SLA were sent as explicit values, not cleared — a
    // rename that silently blanked them would echo back a correct-looking
    // response, which is the whole reason the patch DTO is a POJO.
    const stored = getDb().taskTypes.find((t) => t.code === 'BROWSER_ISSUE')!
    expect(stored.icon).toBe('globe')
    expect(stored.defaultSlaHrs).toBe(72)
  })

  it('retires a type through the patch, and says what that costs', async () => {
    renderPage()
    fireEvent.click(within(await rowFor('Network Issue')).getByRole('button', { name: 'Edit' }))

    expect(
      await screen.findByText(/removes this type from the create-ticket form/i, undefined, SLOW),
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retire this type' }))

    await waitFor(
      () => expect(getDb().taskTypes.find((t) => t.code === 'NETWORK_ISSUE')!.isActive).toBe(false),
      SLOW,
    )
    // Retired, not removed. The row is still there, which is what makes it
    // undoable — and what lets an old ticket still name it.
    await waitFor(
      async () =>
        expect(within(await rowFor('Network Issue')).getByText('Retired')).toBeInTheDocument(),
      SLOW,
    )
  })

  it('brings a retired type back', async () => {
    renderPage()
    fireEvent.click(within(await rowFor('Fax Request')).getByRole('button', { name: 'Edit' }))

    fireEvent.click(await screen.findByRole('button', { name: 'Bring it back' }, SLOW))

    await waitFor(
      () => expect(getDb().taskTypes.find((t) => t.code === 'FAX_REQUEST')!.isActive).toBe(true),
      SLOW,
    )
  })

  it('sends the ETag it read, so a concurrent edit is caught', async () => {
    renderPage()
    fireEvent.click(within(await rowFor('Other')).getByRole('button', { name: 'Edit' }))
    await screen.findByDisplayValue('OTHER', undefined, SLOW)

    // Somebody else saves while the dialog is open. The mock enforces If-Match
    // exactly as the server does, so this is a real 412 rather than a stub.
    getDb().taskTypes.find((t) => t.code === 'OTHER')!.name = 'Other (edited elsewhere)'

    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(
      await screen.findByText(/Somebody else changed this task type/i, undefined, SLOW),
    ).toBeInTheDocument()
  })
})
