import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { ProjectListPage } from './ProjectListPage'

/**
 * B-016 · S-10's grid against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * closed project is still listed here while being absent from every picker, and
 * that On Hold reads as its own state rather than as "not active".
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/projects']}>
        <ProjectListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const rowFor = async (name: string) =>
  (await screen.findByRole('link', { name })).closest('tr') as HTMLElement

describe('the project grid', () => {
  it('lists every project with its code, client and manager', async () => {
    renderPage()

    const crm = await rowFor('Client CRM Platform')
    expect(within(crm).getByText('CRM')).toBeInTheDocument()
    expect(within(crm).getByText('Acme Retail Ltd')).toBeInTheDocument()
    expect(within(crm).getByText('Meera Iyer')).toBeInTheDocument()
  })

  it('renders all three statuses, On Hold as its own', async () => {
    // The reason `status` exists at all: a boolean cannot hold On Hold, and
    // S-10 asks for three states.
    renderPage()

    expect(within(await rowFor('Client CRM Platform')).getByText('Active')).toBeInTheDocument()
    expect(within(await rowFor('Marketing Website')).getByText('On hold')).toBeInTheDocument()
    expect(within(await rowFor('Archived Pilot')).getByText('Closed')).toBeInTheDocument()
  })

  it('shows closed projects by default', async () => {
    // A master screen whose purpose includes reopening things must not hide the
    // things to reopen — the same reason the resource grid defaults to showing
    // inactive people.
    renderPage()

    expect(await screen.findByRole('link', { name: 'Archived Pilot' })).toBeInTheDocument()
  })

  it('filters to one status', async () => {
    renderPage()
    await rowFor('Client CRM Platform')

    fireEvent.click(screen.getByRole('button', { name: /status/i }))
    fireEvent.click(await screen.findByRole('option', { name: 'On hold' }))

    // Changing the filter changes the query key, so the grid goes back through
    // its loading state — `find`, not `get`, or this races the refetch.
    expect(await screen.findByRole('link', { name: 'Marketing Website' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Client CRM Platform' })).not.toBeInTheDocument()
  })

  it('searches the code as well as the name', async () => {
    renderPage()
    await rowFor('Client CRM Platform')

    fireEvent.change(screen.getByLabelText('Search projects'), { target: { value: 'pay' } })

    await waitFor(() => {
      expect(screen.queryByRole('link', { name: 'Client CRM Platform' })).not.toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: 'Payments Gateway' })).toBeInTheDocument()
  })

  it('says so when nothing matches, rather than showing an empty table', async () => {
    renderPage()
    await rowFor('Client CRM Platform')

    fireEvent.change(screen.getByLabelText('Search projects'), {
      target: { value: 'no such project anywhere' },
    })

    expect(await screen.findByText('No projects match')).toBeInTheDocument()
  })

  it('links every row into the edit form', async () => {
    renderPage()

    const crm = await rowFor('Client CRM Platform')
    expect(within(crm).getByRole('link', { name: 'Client CRM Platform' }))
      .toHaveAttribute('href', '/masters/projects/1/edit')
  })
})
