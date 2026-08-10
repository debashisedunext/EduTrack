import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import { WorkingCalendarPage } from './WorkingCalendarPage'

/**
 * B-023 · S-14 against the mock server.
 *
 * The two behaviours worth a test are the ones a screenshot would not show: the
 * weekly-off toggles speak ISO, and a stale `ETag` produces an explanation
 * rather than a silent overwrite.
 */
/**
 * `Toaster` is mounted once in `AppShell` in the real app, so the harness has
 * to supply it — several of these assertions are about what the user is told
 * when a write fails, and without it those messages have nowhere to render.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/calendar']}>
        <WorkingCalendarPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const saveButton = () => screen.findByRole('button', { name: /save working week/i })

describe('the working week', () => {
  it('marks Saturday and Sunday pressed for the seeded [6, 7] pattern', async () => {
    renderPage()
    await saveButton()

    expect(screen.getByRole('button', { name: 'Saturday' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Sunday' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Monday' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('shows the working day the server sent, seconds trimmed', async () => {
    renderPage()
    await saveButton()

    expect(screen.getByLabelText('Working day starts')).toHaveValue('09:30')
    expect(screen.getByLabelText('Working day ends')).toHaveValue('18:30')
    expect(screen.getByText(/9h per working day/)).toBeInTheDocument()
  })

  it('cannot be saved until something changes', async () => {
    renderPage()

    expect(await saveButton()).toBeDisabled()
  })

  it('refuses a working day that ends before it starts, and says why', async () => {
    renderPage()
    await saveButton()

    fireEvent.change(screen.getByLabelText('Working day ends'), { target: { value: '08:00' } })

    expect(await screen.findByRole('alert')).toHaveTextContent(/end after it starts/i)
    expect(await saveButton()).toBeDisabled()
  })

  it('refuses a week with every day off', async () => {
    renderPage()
    await saveButton()

    for (const day of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday']) {
      fireEvent.click(screen.getByRole('button', { name: day }))
    }

    expect(await screen.findByRole('alert')).toHaveTextContent(/at least one working day/i)
  })

  it('saves a changed pattern and confirms what it affects', async () => {
    renderPage()
    await saveButton()

    fireEvent.click(screen.getByRole('button', { name: 'Friday' }))
    fireEvent.click(await saveButton())

    expect(await screen.findByText(/working week saved/i)).toBeInTheDocument()
  })

  /**
   * The lost-update case. Two admins in two tabs is not exotic for a master
   * screen, and silently discarding one of them changes every SLA computed
   * afterwards — so the failure has to be legible and recoverable.
   */
  it('explains a stale ETag instead of overwriting somebody else’s edit', async () => {
    server.use(
      http.put('/api/v1/masters/working-calendar', () =>
        HttpResponse.json(
          { type: 'https://edutrack/errors/precondition-failed', title: 'Precondition failed', status: 412 },
          { status: 412, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderPage()
    await saveButton()

    fireEvent.click(screen.getByRole('button', { name: 'Friday' }))
    fireEvent.click(await saveButton())

    expect(await screen.findByText(/somebody else changed the working week/i)).toBeInTheDocument()
    expect(screen.getByText(/reapply your change/i)).toBeInTheDocument()
  })
})

describe('holidays', () => {
  it('lists the seeded holidays and flags the recurring ones', async () => {
    renderPage()

    expect(await screen.findByText('Independence Day')).toBeInTheDocument()
    const row = screen.getByText('Independence Day').closest('li')!
    expect(within(row).getByText(/every year/i)).toBeInTheDocument()
  })

  it('adds a holiday and clears the form', async () => {
    renderPage()
    await screen.findByText('Independence Day')

    fireEvent.change(screen.getByLabelText('Holiday date'), { target: { value: '2026-12-25' } })
    fireEvent.change(screen.getByLabelText('Holiday name'), { target: { value: 'Christmas' } })
    fireEvent.click(screen.getByRole('button', { name: /add holiday/i }))

    expect(await screen.findByText('Christmas')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Holiday name')).toHaveValue(''))
  })

  it('reports a duplicate date rather than adding a second row', async () => {
    renderPage()
    await screen.findByText('Independence Day')

    fireEvent.change(screen.getByLabelText('Holiday date'), { target: { value: '2026-08-15' } })
    fireEvent.change(screen.getByLabelText('Holiday name'), { target: { value: 'Duplicate' } })
    fireEvent.click(screen.getByRole('button', { name: /add holiday/i }))

    expect(await screen.findByText(/already a holiday/i)).toBeInTheDocument()
  })

  it('removes a holiday', async () => {
    renderPage()
    await screen.findByText('Diwali')

    fireEvent.click(screen.getByRole('button', { name: 'Remove Diwali' }))

    await waitFor(() => expect(screen.queryByText('Diwali')).not.toBeInTheDocument())
  })
})

describe('resource leave', () => {
  it('lists recorded leave', async () => {
    renderPage()

    expect(await screen.findByText(/Resource 4 · 2026-08-24 → 2026-08-28/)).toBeInTheDocument()
  })

  it('records leave', async () => {
    renderPage()
    await screen.findByText(/Resource 4/)

    fireEvent.change(screen.getByLabelText('Resource ID'), { target: { value: '9' } })
    fireEvent.change(screen.getByLabelText('Leave starts'), { target: { value: '2026-10-05' } })
    fireEvent.change(screen.getByLabelText('Leave ends'), { target: { value: '2026-10-09' } })
    fireEvent.click(screen.getByRole('button', { name: /record leave/i }))

    expect(await screen.findByText(/Resource 9 · 2026-10-05 → 2026-10-09/)).toBeInTheDocument()
  })

  it('refuses leave that ends before it starts', async () => {
    renderPage()
    await screen.findByText(/Resource 4/)

    fireEvent.change(screen.getByLabelText('Resource ID'), { target: { value: '9' } })
    fireEvent.change(screen.getByLabelText('Leave starts'), { target: { value: '2026-10-09' } })
    fireEvent.change(screen.getByLabelText('Leave ends'), { target: { value: '2026-10-05' } })
    fireEvent.click(screen.getByRole('button', { name: /record leave/i }))

    expect(await screen.findByText(/could not record the leave/i)).toBeInTheDocument()
  })
})
