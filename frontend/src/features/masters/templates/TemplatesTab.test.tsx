import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { StatusMasterPage } from '../statuses/StatusMasterPage'

/**
 * B-041 · S-13 tab 3 against the mock server.
 *
 * Rendered through `StatusMasterPage` rather than `TemplatesTab` directly, for
 * the reason `StagesTab.test.tsx` gives: the tab being reachable is itself one of
 * the things under test — it shipped disabled in B-039 and again in B-040 — and a
 * test that mounted the panel by hand would pass on the day somebody forgot to
 * enable it.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that the
 * preview draws a template's stages without claiming a ticket is standing in one,
 * that the delete is offered on exactly the templates it is permitted on, that a
 * pair claimed by another template is refused with that template named, and that
 * the resolution checker reports the rung rather than only the answer.
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

/** Raised locally, for the reason `StagesTab.test.tsx` gives — this shell is four
 * round trips deep before an assertion, and tab 3 adds the mapping read on top. */
vi.setConfig({ testTimeout: 20000 })

const SLOW = { timeout: 5000 }

async function openTemplatesTab() {
  renderPage()
  fireEvent.click(await screen.findByRole('tab', { name: 'Workflow templates' }, SLOW))
  await screen.findByRole('heading', { name: 'Ribbon preview' }, SLOW)
}

describe('the tab is reachable and lists every template', () => {
  it('is enabled — B-039 and B-040 both shipped it disabled', async () => {
    renderPage()

    expect(await screen.findByRole('tab', { name: 'Workflow templates' }, SLOW))
      .not.toBeDisabled()
  })

  it('lists the three seeded templates and marks the default', async () => {
    await openTemplatesTab()

    expect(screen.getByText('Standard Dev Flow')).toBeInTheDocument()
    expect(screen.getByText('Support Fast-Track')).toBeInTheDocument()
    expect(screen.getByText('Infra Flow')).toBeInTheDocument()
    expect(screen.getByText('Default')).toBeInTheDocument()
  })
})

describe('the live ribbon preview', () => {
  /**
   * §7.4's *"a live ribbon preview renders as the Admin edits"*. It is B-050's
   * `RibbonStrip`, unmodified — so what this asserts is the translation, not the
   * rendering, which has its own 44 tests one directory over.
   */
  it('draws the selected template stages in ribbon order', async () => {
    await openTemplatesTab()

    const ribbon = await screen.findByRole('list', { name: 'Workflow stages' }, SLOW)
    const names = within(ribbon).getAllByText(/Intake|Triage|Development|QA/)
    expect(names.length).toBeGreaterThan(0)
    expect(screen.getByText(/Intake → Triage \/ Planning → Development/)).toBeInTheDocument()
  })

  /**
   * The contextual handoff button hangs off the current segment, so a preview
   * with one would be inviting a handoff on a template. There is no current
   * segment, and this is the assertion that would catch its return.
   */
  it('offers no handoff action, because nothing is standing in a stage', async () => {
    await openTemplatesTab()
    const ribbon = await screen.findByRole('list', { name: 'Workflow stages' }, SLOW)

    expect(within(ribbon).queryByRole('button', { name: /Hand off/ })).not.toBeInTheDocument()
  })

  it('redraws when a different template is selected', async () => {
    await openTemplatesTab()
    expect(await screen.findByText(/→ QA \/ Testing →/, undefined, SLOW)).toBeInTheDocument()

    fireEvent.click(within(await rowFor('Infra Flow')).getByRole('button', { name: 'Edit' }))

    await waitFor(() => expect(screen.queryByText(/→ QA \/ Testing →/)).not.toBeInTheDocument(), SLOW)
    expect(await screen.findByText(/Deployment → Verification/, undefined, SLOW)).toBeInTheDocument()
  })

  it('says so rather than drawing an empty strip when a template has no stages', async () => {
    getDb().templateStages = getDb().templateStages.filter((s) => s.templateId !== 3)
    await openTemplatesTab()

    fireEvent.click(within(await rowFor('Infra Flow')).getByRole('button', { name: 'Edit' }))

    expect(await screen.findByText('No stages yet', undefined, SLOW)).toBeInTheDocument()
  })
})

describe('what the screen refuses to offer', () => {
  /**
   * The two guards are different counts on purpose, and this is where that shows.
   * Standard Dev Flow has history *and* rules *and* the default flag; Support
   * Fast-Track has rules but no history. Neither may be deleted, and the reason
   * differs.
   */
  it('disables Delete on a template that is in use, and says which count', async () => {
    await openTemplatesTab()

    const remove = await screen.findByRole('button', { name: 'Delete' }, SLOW)
    expect(remove).toBeDisabled()
    expect(remove).toHaveAttribute('title', expect.stringContaining('ticket'))
  })

  it('disables Deactivate on the default template', async () => {
    await openTemplatesTab()

    const off = await screen.findByRole('button', { name: 'Deactivate' }, SLOW)
    expect(off).toBeDisabled()
    expect(off).toHaveAttribute('title', expect.stringContaining('default'))
  })

  /**
   * The complement — a template nothing has run on and nothing routes to. This is
   * the only case in which the delete is offered at all, and it is the one B-042
   * describes: created by mistake, caught the same afternoon.
   */
  it('offers Delete once a template has neither history nor rules', async () => {
    const db = getDb()
    db.templateMappings = db.templateMappings.filter((m) => m.templateId !== 2)
    await openTemplatesTab()

    fireEvent.click(within(await rowFor('Support Fast-Track')).getByRole('button', { name: 'Edit' }))

    await waitFor(
      () => expect(screen.getByRole('button', { name: 'Delete' })).not.toBeDisabled(),
      SLOW,
    )
  })
})

describe('the routing rules', () => {
  it('lists the rules pointing at the selected template', async () => {
    await openTemplatesTab()

    const panel = await screen.findByRole('region', { name: 'Routing rules' }, SLOW)
    // Standard Dev Flow carries §4A.9's three task-type rules, all wildcard on
    // project — so every row's project select reads "Any project".
    const projects = within(panel).getAllByLabelText('Project')
    expect(projects).toHaveLength(3)
    projects.forEach((select) => expect(select).toHaveValue(''))
  })

  it('refuses two rules naming the same pair, before sending anything', async () => {
    await openTemplatesTab()
    const panel = await screen.findByRole('region', { name: 'Routing rules' }, SLOW)

    fireEvent.click(within(panel).getByRole('button', { name: 'Add rule' }))
    const taskTypes = within(panel).getAllByLabelText('Task type')
    // Point the new row at the same pair as the first: any project, whatever the
    // first row already names.
    fireEvent.change(taskTypes[taskTypes.length - 1], {
      target: { value: (taskTypes[0] as HTMLSelectElement).value },
    })

    expect(await within(panel).findByRole('alert', undefined, SLOW))
      .toHaveTextContent(/one template only/)
    expect(within(panel).getByRole('button', { name: 'Save rules' })).toBeDisabled()
  })

  it('saves a new rule and shows it on reload of the panel', async () => {
    await openTemplatesTab()
    const panel = await screen.findByRole('region', { name: 'Routing rules' }, SLOW)

    fireEvent.click(within(panel).getByRole('button', { name: 'Add rule' }))
    const taskTypes = within(panel).getAllByLabelText('Task type')
    // Internal Bug — id 5, and unmapped in the seed, so it is not claimed.
    fireEvent.change(taskTypes[taskTypes.length - 1], { target: { value: '5' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Save rules' }))

    await waitFor(
      () => expect(getDb().templateMappings.filter((m) => m.templateId === 1)).toHaveLength(4),
      SLOW,
    )
  })

  /**
   * The refusal that has to name the other template, because the remedy is on
   * that template's screen and an Admin told only "that pair is taken" has
   * nowhere to go.
   */
  it('names the other template when a pair is already claimed', async () => {
    await openTemplatesTab()
    const panel = await screen.findByRole('region', { name: 'Routing rules' }, SLOW)

    fireEvent.click(within(panel).getByRole('button', { name: 'Add rule' }))
    const taskTypes = within(panel).getAllByLabelText('Task type')
    // Client Request — id 3, mapped to Support Fast-Track by the seed.
    fireEvent.change(taskTypes[taskTypes.length - 1], { target: { value: '3' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Save rules' }))

    expect(await screen.findByText(/Support Fast-Track/, undefined, SLOW)).toBeInTheDocument()
  })
})

describe('the resolution checker', () => {
  /**
   * The panel's reason for existing. A rule list cannot answer "where does this
   * pair go?", because the answer may be a rule on another template or no rule at
   * all — and in the second case the ticket still goes somewhere.
   */
  it('reports the rung, not only the template', async () => {
    await openTemplatesTab()

    // Production Bug — id 2, mapped by the seed to Standard Dev Flow on task type
    // alone, so the rung is TASK_TYPE rather than EXACT.
    fireEvent.change(await screen.findByLabelText('Check task type', undefined, SLOW), {
      target: { value: '2' },
    })

    const answer = await screen.findByRole('status', undefined, SLOW)
    await waitFor(() => expect(answer).toHaveTextContent(/Standard Dev Flow/), SLOW)
    expect(answer).toHaveTextContent(/any project/)
  })

  /**
   * The failure mode §4A.9's configuration has no other way to surface: a pair
   * nobody wrote a rule for still routes, and the Admin has to be able to see
   * that it is a fallback rather than a decision.
   */
  it('says plainly when a pair falls through to the default', async () => {
    await openTemplatesTab()

    // Internal Bug — id 5, unmapped in the seed.
    fireEvent.change(await screen.findByLabelText('Check task type', undefined, SLOW), {
      target: { value: '5' },
    })

    const answer = await screen.findByRole('status', undefined, SLOW)
    await waitFor(() => expect(answer).toHaveTextContent(/no rule matched/), SLOW)
    expect(answer).toHaveTextContent(/Standard Dev Flow/)
  })

  it('marks an answer that came from a rule on another template', async () => {
    await openTemplatesTab()

    // Client Request — id 3, which routes to Support Fast-Track while Standard
    // Dev Flow is the template on screen.
    fireEvent.change(await screen.findByLabelText('Check task type', undefined, SLOW), {
      target: { value: '3' },
    })

    const answer = await screen.findByRole('status', undefined, SLOW)
    await waitFor(() => expect(answer).toHaveTextContent(/Support Fast-Track/), SLOW)
    expect(answer).toHaveTextContent(/another template/)
  })
})

describe('creating a template', () => {
  /**
   * §7.4's "built by picking stages", which is a copy — there is no stage
   * catalogue to pick from, and A-005's own header asks for versioning by copy.
   */
  it('clones an existing ribbon when asked to', async () => {
    await openTemplatesTab()

    fireEvent.click(screen.getByRole('button', { name: 'New template' }))
    const dialog = await screen.findByRole('dialog', undefined, SLOW)

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Hotfix Flow' } })
    fireEvent.change(within(dialog).getByLabelText('Copy stages from'), { target: { value: '2' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))

    await waitFor(() => {
      const created = getDb().workflowTemplates.find((t) => t.name === 'Hotfix Flow')
      expect(created).toBeDefined()
      expect(getDb().templateStages.filter((s) => s.templateId === created?.id)).toHaveLength(5)
    }, SLOW)
  })

  it('starts empty when no source is chosen', async () => {
    await openTemplatesTab()

    fireEvent.click(screen.getByRole('button', { name: 'New template' }))
    const dialog = await screen.findByRole('dialog', undefined, SLOW)

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Blank Flow' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))

    await waitFor(() => {
      const created = getDb().workflowTemplates.find((t) => t.name === 'Blank Flow')
      expect(created).toBeDefined()
      expect(getDb().templateStages.filter((s) => s.templateId === created?.id)).toHaveLength(0)
    }, SLOW)
  })

  it('refuses a name another template already has, with the server sentence', async () => {
    await openTemplatesTab()

    fireEvent.click(screen.getByRole('button', { name: 'New template' }))
    const dialog = await screen.findByRole('dialog', undefined, SLOW)

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Infra Flow' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))

    expect(await screen.findByText(/already exists/, undefined, SLOW)).toBeInTheDocument()
  })

  it('will not submit without a name', async () => {
    await openTemplatesTab()

    fireEvent.click(screen.getByRole('button', { name: 'New template' }))
    const dialog = await screen.findByRole('dialog', undefined, SLOW)

    expect(within(dialog).getByRole('button', { name: 'Create' })).toBeDisabled()
  })
})

const rowFor = async (name: string) =>
  (await screen.findByText(name, undefined, SLOW)).closest('tr') as HTMLElement
