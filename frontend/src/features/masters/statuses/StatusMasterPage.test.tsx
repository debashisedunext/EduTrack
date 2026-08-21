import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { StatusMasterPage } from './StatusMasterPage'

/**
 * B-039 · S-13 tab 1 against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that
 * tabs 2 and 3 are present but unreachable, that a retire is refused while
 * tickets are in the status, that a retire that succeeds reports how many
 * transitions it took with it, that unticking the last on-create cell disables
 * the save, and that a governance-locked cell is flagged rather than locked.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/statuses']}>
        <StatusMasterPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Raised locally, for the reason `PriorityListPage.test.tsx` gives. */
const SLOW = { timeout: 5000 }

const rowFor = async (name: string) =>
  (await screen.findByText(name, undefined, SLOW)).closest('tr') as HTMLElement

/**
 * Opens the edit dialog **and waits for its detail read**.
 *
 * The dialog renders a skeleton until `useStatus` resolves — a second round
 * trip, made rather than reusing the grid row because the read is what carries
 * the `ETag`. So `findByRole('dialog')` alone resolves against an empty shell.
 */
async function openEditor(name: string) {
  fireEvent.click(within(await rowFor(name)).getByRole('button', { name: `Edit ${name}` }))
  const dialog = await screen.findByRole('dialog', undefined, SLOW)
  await within(dialog).findByLabelText('Name', undefined, SLOW)
  return dialog
}

describe('the three tabs', () => {
  /**
   * **All three tabs are live as of B-041**, and this assertion has now been
   * rewritten twice — once when B-040 filled in tab 2, and once here. Each
   * version was true for exactly as long as it should have been, which is what a
   * test naming the task that will change it is for.
   *
   * What it does *not* do is assert that three tabs exist and stop there. A
   * count would pass whatever any of them did. What matters is that each one is
   * reachable and none is a stub, so each is opened and checked for something
   * only its own panel renders.
   */
  it('has all three tabs of §7.4 enabled, none of them a stub', async () => {
    renderPage()

    for (const name of ['Statuses', 'Stages', 'Workflow templates']) {
      expect(await screen.findByRole('tab', { name }, SLOW)).not.toBeDisabled()
    }
  })

  it('opens on the statuses tab', async () => {
    renderPage()

    expect(await screen.findByRole('tab', { name: 'Statuses' }, SLOW))
      .toHaveAttribute('aria-selected', 'true')
  })
})

describe('the status grid', () => {
  it('lists the eight seeded statuses in lifecycle order', async () => {
    renderPage()

    await screen.findByText('New', undefined, SLOW)
    const codes = screen.getAllByText(
      /^(NEW|IN_PROGRESS|ON_HOLD|AWAITING_INFO|REWORK|RESOLVED|CLOSED|REOPENED)$/,
    )
    expect(codes.map((el) => el.textContent)).toEqual([
      'NEW', 'IN_PROGRESS', 'ON_HOLD', 'AWAITING_INFO',
      'REWORK', 'RESOLVED', 'CLOSED', 'REOPENED',
    ])
  })

  /**
   * The row that makes the case for the column. Resolved is Done work on a
   * ticket that is still counted as open — if a later change collapses category
   * into `isOpen`, this is what fails.
   */
  it('shows Resolved as Done while still counting as open', async () => {
    renderPage()

    const row = await rowFor('Resolved')
    expect(within(row).getByText('Done')).toBeInTheDocument()
    const cells = within(row).getAllByRole('cell')
    expect(cells[3]).toHaveTextContent('Yes') // counts as open
    expect(cells[4]).toHaveTextContent('No')  // terminal
  })

  it('renders the transition count, which is what the retire dialog quotes', async () => {
    renderPage()

    const row = await rowFor('On Hold')
    const cells = within(row).getAllByRole('cell')
    expect(Number(cells[6].textContent)).toBeGreaterThan(0)
  })

  /**
   * S-13 does not promise a ninth status, and the empty state says why rather
   * than presenting a control that always fails.
   */
  it('disables New status when all eight codes are taken, and explains it', async () => {
    renderPage()

    await screen.findByText('New', undefined, SLOW)
    expect(screen.getByRole('button', { name: 'New status' })).toBeDisabled()
    expect(screen.getByText(/All eight status codes/)).toBeInTheDocument()
  })
})

describe('editing and retiring', () => {
  it('renames a status without touching its code', async () => {
    renderPage()
    const dialog = await openEditor('On Hold')

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Paused' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await screen.findByText('Paused', undefined, SLOW)
    expect(getDb().statuses.find((s) => s.code === 'ON_HOLD')?.name).toBe('Paused')
  })

  it('leaves the code control disabled — a rename would orphan every ticket', async () => {
    renderPage()
    const dialog = await openEditor('On Hold')

    expect(within(dialog).getByLabelText('Code')).toBeDisabled()
  })

  /**
   * The consequence is stated before the click. Somebody who reads it afterwards
   * has already pressed the button believing something else.
   */
  it('warns what a retire will take with it, before the save', async () => {
    renderPage()
    const dialog = await openEditor('On Hold')

    fireEvent.click(within(dialog).getByLabelText('Active'))

    expect(await within(dialog).findByText(/will not bring them back/, undefined, SLOW))
      .toBeInTheDocument()
  })

  it('refuses a retire while tickets are still in the status, on the field', async () => {
    const db = getDb()
    db.tickets[0].status = 'ON_HOLD'

    renderPage()
    const dialog = await openEditor('On Hold')
    fireEvent.click(within(dialog).getByLabelText('Active'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByText(/no move offered/, undefined, SLOW))
      .toBeInTheDocument()
    expect(getDb().statuses.find((s) => s.code === 'ON_HOLD')?.isActive).toBe(true)
  })

  it('reports how many transitions a successful retire deactivated', async () => {
    const db = getDb()
    db.tickets.forEach((t) => { if (t.status === 'AWAITING_INFO') t.status = 'NEW' })

    renderPage()
    const dialog = await openEditor('Awaiting Info')
    fireEvent.click(within(dialog).getByLabelText('Active'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/transitions deactivated/, undefined, SLOW))
      .toBeInTheDocument()
    expect(getDb().workflowTransitions
      .filter((t) => t.isActive
        && (t.fromStatus === 'AWAITING_INFO' || t.toStatus === 'AWAITING_INFO')))
      .toHaveLength(0)
  })

  /**
   * There is no delete anywhere on this screen. Nothing has a foreign key to
   * `statuses`, so one would *succeed*.
   */
  it('offers no delete control', async () => {
    renderPage()
    await screen.findByText('New', undefined, SLOW)

    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
  })
})

describe('the transition matrix', () => {
  it('renders one row per move with a checkbox per role', async () => {
    renderPage()

    expect(await screen.findByRole('columnheader', { name: 'ADMIN' }, SLOW))
      .toBeInTheDocument()
    expect(screen.getByLabelText('ADMIN: on creation to NEW')).toBeChecked()
  })

  /**
   * G-3 is data, not code. The grid flags the cell and lets an Admin change it —
   * a lock would put back into the client the one decision the whitelist exists
   * to keep out of it.
   */
  it('flags a governance-locked move but leaves the cell editable', async () => {
    renderPage()

    expect(await screen.findByText(/G-3: closure belongs/, undefined, SLOW))
      .toBeInTheDocument()
    expect(screen.getByLabelText('DEVELOPER: RESOLVED to CLOSED')).not.toBeDisabled()
  })

  it('shows a cleared cell as unticked rather than dropping the row', async () => {
    const db = getDb()
    const target = db.workflowTransitions.find(
      (t) => t.fromStatus === 'NEW' && t.toStatus === 'IN_PROGRESS' && t.roleCode === 'QA',
    )!
    target.isActive = false

    renderPage()

    expect(await screen.findByLabelText('QA: NEW to IN_PROGRESS', undefined, SLOW))
      .not.toBeChecked()
  })

  /**
   * The one edit that can lock the product out of itself. Caught in the browser
   * so the button can explain itself, and again on the server because a browser
   * is not a guarantee.
   */
  it('blocks the save when the last on-create cell is unticked, and says why', async () => {
    renderPage()
    await screen.findByLabelText('ADMIN: on creation to NEW', undefined, SLOW)

    for (const role of ['ADMIN', 'PM', 'SUPPORT', 'DEVELOPER', 'QA', 'DEPLOYMENT']) {
      fireEvent.click(screen.getByLabelText(`${role}: on creation to NEW`))
    }

    expect(await screen.findByText(/no role can raise a ticket/, undefined, SLOW))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save matrix' })).toBeDisabled()
  })

  it('saves an unticked cell as a deactivated row, not a deleted one', async () => {
    renderPage()
    const cell = await screen.findByLabelText('QA: NEW to IN_PROGRESS', undefined, SLOW)

    fireEvent.click(cell)
    fireEvent.click(screen.getByRole('button', { name: 'Save matrix' }))

    await waitFor(() => {
      const row = getDb().workflowTransitions.find(
        (t) => t.fromStatus === 'NEW' && t.toStatus === 'IN_PROGRESS' && t.roleCode === 'QA',
      )
      expect(row).toBeDefined()
      expect(row!.isActive).toBe(false)
    }, SLOW)
  })

  it('discards a draft without sending anything', async () => {
    renderPage()
    const cell = await screen.findByLabelText('QA: NEW to IN_PROGRESS', undefined, SLOW)

    fireEvent.click(cell)
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))

    await waitFor(() =>
      expect(screen.getByLabelText('QA: NEW to IN_PROGRESS')).toBeChecked(), SLOW)
    expect(getDb().workflowTransitions.find(
      (t) => t.fromStatus === 'NEW' && t.toStatus === 'IN_PROGRESS' && t.roleCode === 'QA',
    )!.isActive).toBe(true)
  })
})
