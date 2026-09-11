import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { resetDb } from '@/mocks/db'

import { ObProductMasterPage } from './ObProductMasterPage'

/**
 * OB-07's product half, against the mock server rather than mocked hooks —
 * the same reason `ObImplementationStagePage.test.tsx` gives. The behaviour
 * worth testing is that a product added here is a product the catalogue
 * lists, and that a duplicate code is refused by the server's rule, not by
 * a stub.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObProductMasterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SLOW = { timeout: 5000 }

/** The product names in the order the table draws them. */
function renderedNames(): string[] {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[0].textContent ?? '')
}

async function waitForSeeded() {
  await waitFor(
    () => expect(screen.getByRole('cell', { name: 'EduTrack ERP' })).toBeInTheDocument(),
    SLOW,
  )
}

describe('ObProductMasterPage', () => {
  beforeEach(() => {
    resetDb()
  })

  it('lists every seeded product, retired ones included, by name', async () => {
    renderPage()
    await waitForSeeded()

    expect(renderedNames()).toEqual([
      'Biometric Attendance',
      'EduTrack ERP',
      'HR & Payroll',
      'Learning Management',
    ])
    // The retired product is still here — retiring hides it from pickers, not
    // from the master.
    expect(screen.getByText('Retired')).toBeInTheDocument()
  })

  it('says which products have no Module Service yet', async () => {
    renderPage()
    await waitForSeeded()

    const biometric = screen.getByRole('cell', { name: 'Biometric Attendance' }).closest('tr')!
    expect(within(biometric).getByText('None yet')).toBeInTheDocument()

    const erp = screen.getByRole('cell', { name: 'EduTrack ERP' }).closest('tr')!
    expect(within(erp).getByRole('link', { name: /Published/ })).toHaveAttribute(
      'href',
      '/onboarding/journey-templates/1',
    )
  })

  /** The form is a name and nothing else — no code is asked for or shown. */
  it('adds a product from its name alone and lists it', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'New product' }))
    expect(screen.queryByLabelText(/code/i)).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Product name'), 'Payroll Suite')
    await user.click(screen.getByRole('button', { name: 'Add product' }))

    await waitFor(() => expect(renderedNames()).toHaveLength(5), SLOW)
    const row = screen.getByRole('cell', { name: 'Payroll Suite' }).closest('tr')!
    expect(within(row).getByText('None yet')).toBeInTheDocument()
    expect(screen.queryByText('PAYROLL_SUITE')).not.toBeInTheDocument()
  })

  it('refuses a blank name before sending it', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'New product' }))
    await user.click(screen.getByRole('button', { name: 'Add product' }))

    expect(screen.getByText('A name is required.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(renderedNames()).toHaveLength(4), SLOW)
  })

  /**
   * The server refuses on the derived code, and the page says so on the name,
   * which is the only thing the admin typed. 'lms' derives to `LMS`, which the
   * seeded Learning Management product already holds.
   */
  it('refuses a name whose code is already taken, on the name field', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'New product' }))
    await user.type(screen.getByLabelText('Product name'), 'lms')
    await user.click(screen.getByRole('button', { name: 'Add product' }))

    await waitFor(
      () => expect(screen.getByText('A product with this name already exists.')).toBeInTheDocument(),
      SLOW,
    )

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(renderedNames()).toHaveLength(4), SLOW)
  })

  it('renames a product', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'Edit Biometric Attendance' }))
    const name = await screen.findByLabelText('Product name')
    expect(screen.queryByLabelText(/code/i)).not.toBeInTheDocument()
    await user.clear(name)
    await user.type(name, 'Biometric Attendance Pro')
    await user.click(screen.getByRole('button', { name: 'Save product' }))

    await waitFor(
      () =>
        expect(screen.getByRole('cell', { name: 'Biometric Attendance Pro' })).toBeInTheDocument(),
      SLOW,
    )
  })

  it('retires a product in place rather than removing it', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'Edit HR & Payroll' }))
    await user.click(await screen.findByLabelText(/Available for selection/))
    await user.click(screen.getByRole('button', { name: 'Save product' }))

    await waitFor(() => expect(screen.getAllByText('Retired')).toHaveLength(2), SLOW)
    expect(renderedNames()).toHaveLength(4)
  })

  /** There is no delete route, so there must be no delete control. */
  it('offers no way to delete a product', async () => {
    renderPage()
    await waitForSeeded()

    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /remove/i })).not.toBeInTheDocument()
  })
})
