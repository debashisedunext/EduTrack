import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { resetDb } from '@/mocks/db'

import { ObImplementationStagePage } from './ObImplementationStagePage'

/**
 * OB-15, against the mock server rather than against mocked hooks.
 *
 * <p>The reason this file is written this way rather than with `vi.mock` over
 * the generated module is the same one `ObPrereqMasterPage.msw.test.tsx`
 * gives, and it is sharper here: <b>the behaviour worth testing on this screen
 * is the renumbering, and the renumbering happens on the server.</b> A test
 * that stubbed the mutation would assert that the form sends `sequence: 2` —
 * which is not the requirement. The requirement is that the master ends up in
 * the order the admin asked for, and only a real round trip can show that.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObImplementationStagePage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SLOW = { timeout: 5000 }

/** The stage names in the order the table draws them. */
function renderedOrder(): string[] {
  return screen
    .getAllByRole('row')
    // Skip the header, which has no data cells to read a name out of.
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[1].textContent ?? '')
}

async function waitForSeeded() {
  await waitFor(
    () => expect(screen.getByRole('cell', { name: 'Configuration' })).toBeInTheDocument(),
    SLOW,
  )
}

describe('ObImplementationStagePage', () => {
  beforeEach(() => {
    // The renumbering writes to the shared fixture, so a test that reordered
    // the master would hand the next one a list in an order it did not choose.
    resetDb()
  })

  it('lists the six seeded stages in their seeded order', async () => {
    renderPage()
    await waitForSeeded()

    expect(renderedOrder()).toEqual([
      'Configuration',
      'Data Migration',
      'Reports',
      'Training',
      'Communication',
      'Third Party Integration',
    ])
  })

  it('adds a stage at the end of the list', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'New stage' }))
    await user.type(screen.getByLabelText('Stage name'), 'UAT')
    await user.click(screen.getByRole('button', { name: 'Add stage' }))

    await waitFor(() => expect(renderedOrder()).toHaveLength(7), SLOW)
    expect(renderedOrder()[6]).toBe('UAT')
  })

  /**
   * The requirement in one test: an admin types a different position and the
   * list comes back in that order, with everything in between shifted rather
   * than overwritten.
   */
  it('moves a stage when its position is changed, shifting the rest', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'Edit Training' }))
    const position = await screen.findByLabelText('Position')
    await user.clear(position)
    await user.type(position, '1')
    await user.click(screen.getByRole('button', { name: 'Save stage' }))

    await waitFor(
      () =>
        expect(renderedOrder()).toEqual([
          'Training',
          'Configuration',
          'Data Migration',
          'Reports',
          'Communication',
          'Third Party Integration',
        ]),
      SLOW,
    )
  })

  /**
   * Positions stay 1..N after a move. Asserted separately from the order
   * because the two can disagree: a screen sorting correctly while the numbers
   * it prints have a gap in them looks right and is not, and the gap is what
   * the next admin types against.
   */
  it('renumbers the master contiguously after a move', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'Edit Third Party Integration' }))
    const position = await screen.findByLabelText('Position')
    await user.clear(position)
    await user.type(position, '2')
    await user.click(screen.getByRole('button', { name: 'Save stage' }))

    await waitFor(() => expect(renderedOrder()[1]).toBe('Third Party Integration'), SLOW)

    const positions = screen
      .getAllByRole('row')
      .slice(1)
      .map((row) => within(row).getAllByRole('cell')[0].textContent)
    expect(positions).toEqual(['1', '2', '3', '4', '5', '6'])
  })

  it('refuses a duplicate name and says so on the field', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'New stage' }))
    await user.type(screen.getByLabelText('Stage name'), 'reports')
    await user.click(screen.getByRole('button', { name: 'Add stage' }))

    // Case-insensitively, like the unique index — 'reports' collides with the
    // seeded 'Reports'.
    await waitFor(() => expect(screen.getByText('Already in use')).toBeInTheDocument(), SLOW)

    // The list is only readable once the dialog is closed — an open modal
    // hides the rest of the page from the accessibility tree, which is the
    // modal behaving correctly rather than a problem to work around.
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(renderedOrder()).toHaveLength(6), SLOW)
  })

  /**
   * Retiring is the only way a stage goes away, and it must not move it: a
   * retired stage keeps its slot so bringing it back puts it where it was.
   */
  it('retires a stage in place rather than removing it', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitForSeeded()

    await user.click(screen.getByRole('button', { name: 'Edit Reports' }))
    await user.click(await screen.findByLabelText(/Available for selection/))
    await user.click(screen.getByRole('button', { name: 'Save stage' }))

    await waitFor(() => expect(screen.getByText('Retired')).toBeInTheDocument(), SLOW)
    expect(renderedOrder()[2]).toBe('Reports')
  })

  /** There is no delete route, so there must be no delete control. */
  it('offers no way to delete a stage', async () => {
    renderPage()
    await waitForSeeded()

    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /remove/i })).not.toBeInTheDocument()
  })
})
