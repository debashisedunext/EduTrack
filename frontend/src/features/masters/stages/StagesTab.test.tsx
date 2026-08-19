import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { StatusMasterPage } from '../statuses/StatusMasterPage'

/**
 * B-040 · S-13 tab 2 against the mock server.
 *
 * Rendered through `StatusMasterPage` rather than `StagesTab` directly, because
 * the tab being reachable is itself one of the things under test — B-039 shipped
 * it disabled, and a test that mounted the panel by hand would pass on the day
 * somebody forgot to enable it.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * frozen code is disabled with the count that froze it, that dragging is staged
 * rather than saved, that an order inverting a return path is refused before the
 * request, that the keyboard path does the same thing the drag does, and — since
 * B-042 — which row offers Deprecate and which offers Delete.
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

/**
 * Raised locally, for the reason `NotificationTemplateListPage.test.tsx` gives:
 * every case here mounts the whole S-13 shell, switches tab, waits on the
 * template list *and* the stage list, and the write cases add a detail read and
 * a mutation on top. That is four round trips through MSW before an assertion,
 * and the default 5 s is a flake waiting for a slower machine rather than a
 * budget anything here is close to using.
 */
vi.setConfig({ testTimeout: 20000 })

/** Raised locally, for the reason `PriorityListPage.test.tsx` gives. */
const SLOW = { timeout: 5000 }

async function openStagesTab() {
  renderPage()
  fireEvent.click(await screen.findByRole('tab', { name: 'Stages' }, SLOW))
  await screen.findByText('Development', undefined, SLOW)
}

const rowFor = async (name: string) =>
  (await screen.findByText(name, undefined, SLOW)).closest('li') as HTMLElement

/**
 * Opens the edit dialog **and waits for its detail read**.
 *
 * The dialog renders a skeleton until `useStage` resolves — a second round trip,
 * made rather than reusing the row because that read is what carries the `ETag`
 * the `PATCH` preconditions on.
 */
/**
 * Opens the create dialog **and waits for the role list**.
 *
 * The owner-role picker is populated by a separate `GET /masters/roles`, so a
 * `fireEvent.change` fired before it resolves targets a `<select>` that has only
 * the placeholder option — and silently does nothing, leaving the form invalid
 * and the submit a no-op with no error anywhere. Both create cases failed exactly
 * that way before this wait existed.
 */
async function openCreator() {
  fireEvent.click(await screen.findByRole('button', { name: 'Add stage' }, SLOW))
  const dialog = await screen.findByRole('dialog', undefined, SLOW)
  await within(dialog).findByRole('option', { name: 'Developer' }, SLOW)
  return dialog
}

async function openEditor(name: string) {
  fireEvent.click(within(await rowFor(name)).getByRole('button', { name: `Edit ${name}` }))
  const dialog = await screen.findByRole('dialog', undefined, SLOW)
  await within(dialog).findByLabelText('Display name', undefined, SLOW)
  return dialog
}

describe('the tab is reachable and scoped to a template', () => {
  it('tab 2 is enabled — B-039 shipped it disabled and B-040 turns it on', async () => {
    renderPage()

    const stages = await screen.findByRole('tab', { name: 'Stages' }, SLOW)
    expect(stages).not.toBeDisabled()
  })

  it('tab 3 is still disabled and still names its task', async () => {
    renderPage()

    const templates = await screen.findByRole('tab', { name: 'Workflow templates' }, SLOW)
    expect(templates).toBeDisabled()
  })

  /**
   * §7.4 reads as one flat stage list. `workflow_stages.template_id` is `NOT
   * NULL`, so there is no such thing — and `DEV` existing on two templates as two
   * rows is what makes the selector necessary rather than decorative.
   */
  it('shows one template at a time, and switching shows a different ribbon', async () => {
    await openStagesTab()
    expect(await screen.findByText('QA / Testing', undefined, SLOW)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Workflow template'), { target: { value: '3' } })

    await waitFor(() => expect(screen.queryByText('QA / Testing')).not.toBeInTheDocument(), SLOW)
    expect(await screen.findByText('Verification', undefined, SLOW)).toBeInTheDocument()
  })
})

describe('the frozen code', () => {
  /**
   * The rule with the widest consequence in this task, and the one the screen has
   * to explain rather than merely enforce: the code is plain text on every
   * `ticket_stage_transitions` row and on the stage-SLA scanner's join.
   */
  it('disables the code on a stage tickets have been through, with the counts', async () => {
    await openStagesTab()
    const dialog = await openEditor('QA / Testing')

    expect(within(dialog).getByLabelText('Code')).toBeDisabled()
    expect(within(dialog).getByText(/41 ribbon segments/)).toBeInTheDocument()
    expect(within(dialog).getByText(/3 open tickets/)).toBeInTheDocument()
  })

  it('leaves it editable on a stage nothing has entered', async () => {
    await openStagesTab()
    const dialog = await openEditor('Deployment')

    expect(within(dialog).getByLabelText('Code')).not.toBeDisabled()
  })

  it('still lets the display name be changed on a frozen stage', async () => {
    await openStagesTab()
    const dialog = await openEditor('QA / Testing')

    fireEvent.change(within(dialog).getByLabelText('Display name'), {
      target: { value: 'QA & Testing' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('QA & Testing', undefined, SLOW)).toBeInTheDocument()
  })
})

describe('the return-target control', () => {
  it('offers only the stages before this one', async () => {
    await openStagesTab()
    const dialog = await openEditor('Development')

    const targets = within(dialog).getByRole('group', { name: 'Can return to' })
    expect(within(targets).getByLabelText(/Intake/)).toBeInTheDocument()
    expect(within(targets).getByLabelText(/Triage/)).toBeInTheDocument()
    expect(within(targets).queryByLabelText(/QA/)).not.toBeInTheDocument()
  })

  it('says so, rather than rendering an empty box, on the first stage', async () => {
    await openStagesTab()
    const dialog = await openEditor('Intake')

    expect(within(dialog).getByText(/Nothing precedes this stage yet/)).toBeInTheDocument()
  })
})

describe('reordering', () => {
  /**
   * A per-drag save would fire eight requests to move one row four places, and
   * each would move the `ETag` under the next.
   */
  it('stages the move and does not write until Save is pressed', async () => {
    await openStagesTab()
    const before = getDb().templateStages.find((s) => s.id === 5)!.seq

    fireEvent.click(await screen.findByRole('button', { name: 'Move Deployment up' }, SLOW))

    expect(await screen.findByRole('button', { name: 'Save order' }, SLOW)).toBeInTheDocument()
    expect(getDb().templateStages.find((s) => s.id === 5)!.seq).toBe(before)
  })

  it('saves the new order when Save is pressed', async () => {
    await openStagesTab()

    fireEvent.click(await screen.findByRole('button', { name: 'Move Deployment up' }, SLOW))
    fireEvent.click(await screen.findByRole('button', { name: 'Save order' }, SLOW))

    await waitFor(() => {
      const ribbon = getDb().templateStages
        .filter((s) => s.templateId === 1)
        .sort((a, b) => a.seq - b.seq)
        .map((s) => s.stageCode)
      expect(ribbon.indexOf('DEPLOY')).toBeLessThan(ribbon.indexOf('QA'))
    }, SLOW)
  })

  it('discards the staged order without writing', async () => {
    await openStagesTab()

    fireEvent.click(await screen.findByRole('button', { name: 'Move Deployment up' }, SLOW))
    fireEvent.click(await screen.findByRole('button', { name: 'Discard' }, SLOW))

    await waitFor(
      () => expect(screen.queryByRole('button', { name: 'Save order' })).not.toBeInTheDocument(),
      SLOW,
    )
  })

  /**
   * The server refuses this too. Saying it here means the Admin reads which two
   * stages are the problem instead of a 409 naming a rule.
   */
  it('refuses an order that would leave a return path pointing forwards', async () => {
    await openStagesTab()

    // Development past QA — QA → DEV now points forwards.
    fireEvent.click(await screen.findByRole('button', { name: 'Move Development down' }, SLOW))

    expect(await screen.findByRole('alert', undefined, SLOW))
      .toHaveTextContent('QA → DEV')
    expect(screen.getByRole('button', { name: 'Save order' })).toBeDisabled()
  })

  it('warns with the number of live tickets before the order is saved', async () => {
    await openStagesTab()

    fireEvent.click(await screen.findByRole('button', { name: 'Move Deployment up' }, SLOW))

    expect(await screen.findByText(/3 tickets on this template are in a stage right now/, undefined, SLOW))
      .toBeInTheDocument()
  })

  /**
   * WCAG AA, and the reason it is asserted rather than assumed: "drag to reorder"
   * is the control that most often ships with the pointer path only.
   */
  it('announces each keyboard move through a live region', async () => {
    await openStagesTab()

    fireEvent.click(await screen.findByRole('button', { name: 'Move Deployment up' }, SLOW))

    expect(await screen.findByText(/Deployment moved to position 4 of 8/, undefined, SLOW))
      .toBeInTheDocument()
  })

  it('disables Move up on the first row and Move down on the last', async () => {
    await openStagesTab()

    expect(await screen.findByRole('button', { name: 'Move Intake up' }, SLOW)).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move Closed down' })).toBeDisabled()
  })
})

describe('adding a stage', () => {
  it('appends it to the end and says so, because the form cannot choose a seq', async () => {
    await openStagesTab()

    const dialog = await openCreator()

    fireEvent.change(within(dialog).getByLabelText('Code'), { target: { value: 'HANDOVER' } })
    fireEvent.change(within(dialog).getByLabelText('Display name'), {
      target: { value: 'Handover' },
    })
    fireEvent.change(within(dialog).getByLabelText('Owner role'), { target: { value: 'PM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Handover', undefined, SLOW)).toBeInTheDocument()
    expect(await screen.findByText(/Drag it where it belongs/, undefined, SLOW)).toBeInTheDocument()
  })

  it('shows the server’s duplicate message on the code field, not a generic one', async () => {
    await openStagesTab()

    const dialog = await openCreator()

    fireEvent.change(within(dialog).getByLabelText('Code'), { target: { value: 'DEV' } })
    fireEvent.change(within(dialog).getByLabelText('Display name'), { target: { value: 'Dev 2' } })
    fireEvent.change(within(dialog).getByLabelText('Owner role'), {
      target: { value: 'DEVELOPER' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByText(/unique within its template/, undefined, SLOW))
      .toBeInTheDocument()
  })
})

/**
 * B-042 · §7.4's "deprecated, never deleted", through the screen.
 *
 * **This replaced B-040's `there is no delete`**, which asserted that no removal
 * control existed anywhere and named this task as the one that would change it.
 *
 * What replaces it is not "a delete button exists" — the useful assertions are
 * which row offers which control, and that the one refusal an Admin is most
 * likely to hit is stated before the click rather than after.
 */
describe('deprecated, never deleted', () => {
  it('offers Delete only on a stage nothing has entered, and never on a used one', async () => {
    await openStagesTab()

    // QA is the seeded row with 41 transitions and 3 open tickets.
    const qa = await rowFor('QA / Testing')
    expect(within(qa).queryByRole('button', { name: /^Delete/ })).not.toBeInTheDocument()
    expect(within(qa).getByRole('button', { name: /^Deprecate/ })).toBeInTheDocument()

    // Intake is unused and nothing returns to it.
    const intake = await rowFor('Intake')
    expect(within(intake).getByRole('button', { name: /^Delete/ })).toBeInTheDocument()
  })

  it('offers no Delete on an unused stage something still returns to — the count is not the whole rule', async () => {
    await openStagesTab()

    // Nothing has entered Triage, and DEV returns to it.
    const triage = await rowFor('Triage / Planning')
    expect(within(triage).queryByRole('button', { name: /^Delete/ })).not.toBeInTheDocument()
  })

  it('deprecates a stage with live tickets in it, and states what happens to them first', async () => {
    await openStagesTab()

    const qa = await rowFor('QA / Testing')
    fireEvent.click(within(qa).getByRole('button', { name: /^Deprecate/ }))

    // The consequence, before the click that causes it.
    expect(await screen.findByText(/standing in this stage right now/, undefined, SLOW))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Deprecate' }))

    expect(await screen.findByText('Deprecated', undefined, SLOW)).toBeInTheDocument()
  })

  it('names the return path in the way, before the request, rather than rendering a 409', async () => {
    await openStagesTab()

    // QA returns to DEV on B-004's seed, so retiring DEV would leave that arrow
    // pointing at a stage nothing may enter.
    const dev = await rowFor('Development')
    fireEvent.click(within(dev).getByRole('button', { name: /^Deprecate/ }))

    const alert = await screen.findByRole('alert', undefined, SLOW)
    expect(alert).toHaveTextContent(/QA/)
    expect(screen.getByRole('button', { name: 'Deprecate' })).toBeDisabled()
  })

  it('restores a deprecated stage, and the row stops being marked', async () => {
    await openStagesTab()

    const qa = await rowFor('QA / Testing')
    fireEvent.click(within(qa).getByRole('button', { name: /^Deprecate/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Deprecate' }, SLOW))
    await screen.findByText('Deprecated', undefined, SLOW)

    fireEvent.click(await screen.findByRole('button', { name: /^Restore/ }, SLOW))

    await waitFor(() => expect(screen.queryByText('Deprecated')).not.toBeInTheDocument(), SLOW)
  })

  it('deletes an unused stage and the row goes', async () => {
    await openStagesTab()

    const intake = await rowFor('Intake')
    fireEvent.click(within(intake).getByRole('button', { name: /^Delete/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }, SLOW))

    await waitFor(() => expect(screen.queryByText('Intake')).not.toBeInTheDocument(), SLOW)
    expect(getDb().templateStages.some((s) => s.id === 1)).toBe(false)
  })
})
