import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { PriorityListPage } from './PriorityListPage'

/**
 * B-021 · S-12 against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that
 * the grid asks for retired levels while the ticket screens do not, that the
 * escalation flag cannot be switched off from here, that a retire is refused
 * when task types still default to the level, and that retiring is a `PATCH`
 * and not a delete.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/priorities']}>
        <PriorityListPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Raised locally, for the reason `TaskTypeListPage.test.tsx` gives. */
const SLOW = { timeout: 5000 }

const rowFor = async (name: string) =>
  (await screen.findByText(name, undefined, SLOW)).closest('tr') as HTMLElement

/**
 * Opens the edit dialog **and waits for its detail read**.
 *
 * The dialog renders a skeleton until `usePriority` resolves — it is a second
 * round trip, made rather than reusing the grid row because the read is what
 * carries the `ETag`. So `findByRole('dialog')` alone resolves against an empty
 * shell, and every field assertion after it has to be a `findBy`.
 */
async function openEditor(name: string) {
  fireEvent.click(within(await rowFor(name)).getByRole('button', { name: 'Edit' }))
  const dialog = await screen.findByRole('dialog', undefined, SLOW)
  await within(dialog).findByLabelText('Name', undefined, SLOW)
  return dialog
}

describe('the priority grid', () => {
  it('lists the four seeded levels with their codes, in severity order', async () => {
    renderPage()

    const high = await rowFor('High')
    expect(within(high).getByText('HIGH')).toBeInTheDocument()

    const codes = (await screen.findAllByText(/^(LOW|MEDIUM|HIGH|CRITICAL)$/, undefined, SLOW))
      .map((n) => n.textContent)
    expect(codes).toEqual(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
  })

  it('uses the §12.1 level chip colours, which the mock disagreed with until B-021', async () => {
    // The old handler returned #84CC16 / #F59E0B / #9A3412 / #BE185D. §12.1
    // states these four exactly, and they are what the migration seeds.
    expect(getDb().priorities.map((p) => p.colour))
      .toEqual(['#10B981', '#3B82F6', '#F59E0B', '#EF4444'])
  })

  /**
   * The invariant nothing enforced before this task. The mock flagged High
   * *and* Critical, which is not a stronger signal — §6 promotes an overdue
   * ticket *to* the flagged level, so two of them is an ambiguous pointer.
   */
  it('marks exactly one level as the escalation target', async () => {
    renderPage()

    const flagged = await screen.findAllByText('Escalation target', undefined, SLOW)
    expect(flagged).toHaveLength(1)
    expect(within(await rowFor('Critical')).getByText('Escalation target')).toBeInTheDocument()
  })

  it('shows all three usage counts on the row, before anything is clicked', async () => {
    // They are not three shades of one number: tickets never block a retire,
    // SLA rows never block one, and task types do. An admin should see which
    // is which before clicking, not after.
    const db = getDb()
    const raised = db.tickets.filter((t) => t.level === 'HIGH').length
    const defaulting = db.taskTypes.filter((t) => t.isActive && t.defaultLevel === 'HIGH').length
    expect(raised).toBeGreaterThan(0)

    renderPage()

    // By cell position rather than by text: the three counts are small integers
    // and `getByText('4')` finds the SLA hours column just as happily.
    const cells = within(await rowFor('High')).getAllByRole('cell')
    expect(cells[3]).toHaveTextContent(String(raised))
    expect(cells[4]).toHaveTextContent(String(defaulting))
  })

  it('says why a fifth level cannot be added, rather than offering a button that fails', async () => {
    renderPage()

    expect(await screen.findByText(/All four levels the API can carry/, undefined, SLOW))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New level' })).toBeDisabled()
  })
})

describe('the edit dialog', () => {
  it('locks the code, because nothing would cascade a rename', async () => {
    renderPage()

    const dialog = await openEditor('High')
    expect(within(dialog).getByDisplayValue('HIGH')).toBeDisabled()
  })

  it('renames a level through a PATCH — there is no delete on this screen', async () => {
    renderPage()

    const dialog = await openEditor('High')

    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Elevated' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(
      () => expect(getDb().priorities.find((p) => p.level === 'HIGH')?.name).toBe('Elevated'),
      SLOW,
    )
    expect(getDb().priorities).toHaveLength(4)
  })

  /**
   * The one-way control. Clearing the last escalation flag is refused by the
   * server, and a checkbox whose only outcome is a 409 should not be operable —
   * so the level that holds the flag renders it checked and disabled, and
   * moving the target is a tick on the level it moves *to*.
   */
  it('will not let the escalation flag be switched off from the level that holds it', async () => {
    renderPage()

    const dialog = await openEditor('Critical')

    const box = within(dialog).getByRole('checkbox')
    expect(box).toBeChecked()
    expect(box).toBeDisabled()
  })

  it('moves the escalation target by ticking it on another level, clearing the incumbent', async () => {
    renderPage()

    const dialog = await openEditor('Low')

    fireEvent.click(within(dialog).getByRole('checkbox'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => {
      const flagged = getDb().priorities.filter((p) => p.autoEscalates)
      expect(flagged).toHaveLength(1)
      expect(flagged[0].level).toBe('LOW')
    }, SLOW)
  })
})

describe('retiring a level', () => {
  it('blocks the retire while active task types still default to the level', async () => {
    // TaskTypeService refuses a retired level as a defaultLevel, so retiring
    // here would leave those types unsaveable on their own screen. One screen
    // must not be able to put another into a state it cannot get out of.
    expect(getDb().taskTypes.some((t) => t.isActive && t.defaultLevel === 'HIGH')).toBe(true)

    renderPage()

    const dialog = await openEditor('High')

    expect(within(dialog).getByText(/active task types? default to this level/))
      .toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Retire this level' })).toBeDisabled()
  })

  it('blocks the retire on the escalation target, and says to move the flag first', async () => {
    renderPage()

    const dialog = await openEditor('Critical')

    expect(within(dialog).getByText(/Move the target to another level/))
      .toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Retire this level' })).toBeDisabled()
  })

  it('retires an unblocked level with a PATCH, deleting nothing', async () => {
    const db = getDb()
    db.taskTypes.forEach((t) => {
      if (t.defaultLevel === 'LOW') t.defaultLevel = 'MEDIUM'
    })
    const slaRows = db.slaPolicies.filter((p) => p.level === 'LOW').length

    renderPage()

    const dialog = await openEditor('Low')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Retire this level' }))

    await waitFor(
      () => expect(getDb().priorities.find((p) => p.level === 'LOW')?.isActive).toBe(false),
      SLOW,
    )
    expect(getDb().priorities).toHaveLength(4)
    expect(getDb().slaPolicies.filter((p) => p.level === 'LOW')).toHaveLength(slaRows)
  })

  /**
   * The grid asks for retired levels; the create form and the ticket list do
   * not. That split is the one place S-12 reads its master differently from
   * S-11, and it exists because those two screens map this response straight
   * into their pickers without filtering.
   */
  it('still lists a retired level, marked as retired, so it can be brought back', async () => {
    const db = getDb()
    db.taskTypes.forEach((t) => {
      if (t.defaultLevel === 'LOW') t.defaultLevel = 'MEDIUM'
    })
    db.priorities.find((p) => p.level === 'LOW')!.isActive = false

    renderPage()

    expect(within(await rowFor('Low')).getByText('Retired')).toBeInTheDocument()

    const dialog = await openEditor('Low')
    expect(within(dialog).getByRole('button', { name: 'Bring it back' })).toBeEnabled()
  })
})
