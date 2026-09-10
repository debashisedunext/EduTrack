import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { JourneyTemplateDesignerPage } from './JourneyTemplateDesignerPage'

/**
 * C-102 · OB-07's template designer against the mock server, laid out to the
 * prototype's `vTplEdit()` — back link, versioned header, the step table
 * with the Task List chip editor under each row, "+ Add step" at the bottom.
 *
 * <p>Mounted through `Routes`, not called as a component with a prop —
 * `WorkflowDesignerPage.test.tsx`'s own reason: the template id arrives
 * through `useParams`, and a test that passed it directly would not notice
 * the route path and the designer disagreeing about its own param name.
 *
 * <p>Every reorder case drives the **buttons**, never a synthetic drag — there
 * is no drag gesture on this screen to begin with, only Move up / Move down,
 * which is the whole point: this designer never ships a pointer-only path to
 * lose keyboard parity from.
 *
 * <p>Fixture note — `db.ts`'s `OB_JOURNEY_TEMPLATES`: template **1** (ERP) is
 * published and active, five steps, shaped so `parallelGroups` has more than
 * one layer. Template **2** (Biometric Attendance) is a draft, two steps
 * (the second depending on the first), one Task List item and one required
 * document already seeded — every write route is reachable from it.
 */
function renderDesigner(templateId = 2) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/onboarding/journey-templates/${templateId}`]}>
        <Routes>
          <Route
            path="/onboarding/journey-templates/:templateId"
            element={<JourneyTemplateDesignerPage />}
          />
          {/* Deleting a service navigates back to the catalogue — a real
              destination, so the test does not have to read a route that
              matched nothing as a pass. */}
          <Route path="/onboarding/journey-templates" element={<p>Module Service catalogue</p>} />
        </Routes>
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

vi.setConfig({ testTimeout: 20000 })
const SLOW = { timeout: 5000 }

async function openDesigner(templateId = 2) {
  renderDesigner(templateId)
  await screen.findByRole('table', { name: 'Services' }, SLOW)
}

/**
 * For the service-level section — rename, move, delete — which is on the page
 * whether or not the version has any steps. Template 3 (LMS) deliberately has
 * none, so waiting on the table would wait forever; the header carries the
 * service name either way.
 */
async function openService(templateId: number, name: string) {
  renderDesigner(templateId)
  await screen.findByRole('heading', { name, level: 1 }, SLOW)
}

const stepsTable = () => screen.getByRole('table', { name: 'Services' })

/**
 * The step's own `<tbody>` — each service renders as its own rowgroup (field
 * row + Task List row), so a step's controls are scoped by its group. The
 * thead is a rowgroup too; it never contains a step name.
 */
const stepGroup = (name: string) =>
  within(stepsTable())
    .getAllByRole('rowgroup')
    .find((group) => within(group).queryByText(name))!

/** The step names in displayed table order — the first cell of each body row. */
const displayedStepNames = () =>
  within(stepsTable())
    .getAllByRole('rowgroup')
    .slice(1) // drop the thead
    .map((group) => within(group).getAllByRole('cell')[1].textContent ?? '')

const savedStepNames = (templateId: number) =>
  getDb()
    .obJourneyTemplateSteps.filter((s) => s.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence)
    .map((s) => s.name)

describe('the step list renders a draft template', () => {
  it('renders every step in sequence order', async () => {
    await openDesigner(2)
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
    const names = displayedStepNames()
    expect(names[0]).toContain('Device Rollout')
    expect(names[1]).toContain('Attendance Policy Mapping')
  })

  it('shows the draft state, the back link and the Add step control', async () => {
    await openDesigner(2)
    expect(screen.getByText('Draft')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Module Service' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Add step' })).toBeInTheDocument()
  })

  it('totals TAT in the header caption — C-120', async () => {
    // Device Rollout (6) + Attendance Policy Mapping (3) = 9.
    await openDesigner(2)
    expect(screen.getByText('Total TAT: 9d')).toBeInTheDocument()
    expect(screen.getByText(/across 2 services/)).toBeInTheDocument()
  })

  it('names what a step depends on, and calls out a parallel one', async () => {
    await openDesigner(2)
    expect(within(stepGroup('Device Rollout')).getByText('∥ none — runs parallel')).toBeInTheDocument()
    expect(
      within(stepGroup('Attendance Policy Mapping')).getByText('↳ 1. Device Rollout'),
    ).toBeInTheDocument()
  })
})

describe('a published, active version is read-only', () => {
  it('offers Begin revision and no write controls', async () => {
    await openDesigner(1)
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Begin revision' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Add step' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Publish/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Move .* up$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Remove /u })).toBeNull()
  })

  it('totals TAT for a five-step published template too — C-120', async () => {
    // 3 + 4 + 8 + 5 + 4 = 24, same figure a client's own journey would show
    // on OB-05's strip once instantiated from this exact template.
    await openDesigner(1)
    expect(screen.getByText('Total TAT: 24d')).toBeInTheDocument()
  })
})

describe('adding a step', () => {
  it('writes immediately and appears at the end of the list', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: '+ Add step' }))

    const form = screen.getByRole('form', { name: 'Add step' })
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Go-live Sign-off' } })
    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '2' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add step' }))

    await waitFor(() => {
      expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping', 'Go-live Sign-off'])
    }, SLOW)
    await screen.findByText('Go-live Sign-off added', undefined, SLOW)
  })

  it('refuses an empty name before the request', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: '+ Add step' }))
    const form = screen.getByRole('form', { name: 'Add step' })
    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '2' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add step' }))

    expect(await within(form).findByText('Name is required')).toBeInTheDocument()
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
  })
})

describe("the step owner comes from the Role Master", () => {
  const openAddForm = async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: '+ Add step' }))
    const form = screen.getByRole('form', { name: 'Add step' })
    // The picker fills from `/masters/roles`, so the options are not there on
    // the first paint. Waiting on a role name is waiting on that read.
    await within(form).findByRole('option', { name: /Support Desk/ }, SLOW)
    return form
  }

  const savedStep = (name: string) =>
    getDb().obJourneyTemplateSteps.find((s) => s.templateId === 2 && s.name === name)

  it('offers the roles an admin defined, not a free-text box', async () => {
    // The whole point of the change: `owner_role` carries no foreign key, so a
    // typed value was never checked against anything. A closed list of the
    // master's own rows is the check.
    const form = await openAddForm()
    const owner = within(form).getByLabelText('Owner')

    const offered = within(owner)
      .getAllByRole('option')
      .map((o) => o.textContent ?? '')
    for (const role of getDb().roles.filter((r) => r.isActive)) {
      expect(offered.some((text) => text.includes(role.name))).toBe(true)
    }
    expect(owner.tagName).toBe('SELECT')
  })

  it('fills the owner role from the id, without it being typed', async () => {
    const form = await openAddForm()
    const support = getDb().roles.find((r) => r.code === 'SUPPORT')!

    fireEvent.change(within(form).getByLabelText('Owner'), {
      target: { value: String(support.id) },
    })

    // Read-only and derived — the id is chosen, the code follows from it.
    const roleField = within(form).getByLabelText('Owner role')
    expect(roleField).toHaveValue('SUPPORT')
    expect(roleField).toHaveAttribute('readonly')
  })

  it('writes the selected role code onto the step', async () => {
    const form = await openAddForm()
    const pm = getDb().roles.find((r) => r.code === 'PM')!

    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Kickoff Call' } })
    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '1' } })
    fireEvent.change(within(form).getByLabelText('Owner'), { target: { value: String(pm.id) } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add step' }))

    await screen.findByText('Kickoff Call added', undefined, SLOW)
    // The code, not the id: `ob_journey_template_steps.owner_role` stores what
    // the ribbon and the scanners match on.
    expect(savedStep('Kickoff Call')?.ownerRole).toBe('PM')
  })

  it('leaves the owner unset when none is chosen', async () => {
    const form = await openAddForm()

    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Data Cleanup' } })
    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '3' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add step' }))

    await screen.findByText('Data Cleanup added', undefined, SLOW)
    expect(savedStep('Data Cleanup')?.ownerRole ?? null).toBeNull()
  })

  it('offers a way to the Role Master when the wanted role is not listed', async () => {
    // A closed list needs an answer to "mine is not here", on the form rather
    // than left to be guessed — and in a new tab, because this form holds a
    // half-written step that navigating away in place would discard.
    const form = await openAddForm()

    const link = within(form).getByRole('link', { name: /Role not listed/ })
    expect(link).toHaveAttribute('href', '/masters/roles')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('names the owning role rather than printing its code', async () => {
    // A code is what the column stores; a name is what the admin typed into
    // the master and expects to read back in the grid.
    const db = getDb()
    const step = db.obJourneyTemplateSteps.find((s) => s.templateId === 2)!
    step.ownerUserId = null
    step.ownerRole = 'SUPPORT'

    await openDesigner(2)
    // The role list is a second read; the table paints before it lands.
    await waitFor(
      () => expect(within(stepGroup(step.name)).getByText('Support Desk')).toBeInTheDocument(),
      SLOW,
    )
  })
})

describe('removing a step', () => {
  it('removes a step nothing depends on', async () => {
    await openDesigner(2)
    // Remove the dependent first so Device Rollout has none left.
    fireEvent.click(within(stepGroup('Attendance Policy Mapping')).getByRole('button', { name: 'Remove Attendance Policy Mapping' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual(['Device Rollout']), SLOW)

    fireEvent.click(within(stepGroup('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual([]), SLOW)
  })

  it('names the dependents rather than a bare conflict', async () => {
    await openDesigner(2)
    fireEvent.click(within(stepGroup('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))

    expect(
      await screen.findByText('Device Rollout still has dependents', undefined, SLOW),
    ).toBeInTheDocument()
    expect(await screen.findByText(/Re-point Attendance Policy Mapping/)).toBeInTheDocument()
    // Nothing removed.
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
  })
})

describe('reordering is staged, then saved in one request with If-Match', () => {
  it('moves a step without saving it', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Move Attendance Policy Mapping up' }))

    const names = displayedStepNames()
    expect(names[0]).toContain('Attendance Policy Mapping')
    expect(names[1]).toContain('Device Rollout')
    expect(screen.getByRole('button', { name: 'Save order' })).toBeInTheDocument()
    // Not written yet.
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
  })

  it('saves the staged order in one request and sends the cached ETag as If-Match', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Move Attendance Policy Mapping up' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save order' }))

    await screen.findByText('Step order saved', undefined, SLOW)
    await waitFor(() => {
      expect(savedStepNames(2)).toEqual(['Attendance Policy Mapping', 'Device Rollout'])
    }, SLOW)

    const orderCall = fetchSpy.mock.calls.find(([input]) =>
      typeof input === 'string' && input.includes('/steps/order'),
    )
    expect(orderCall).toBeDefined()
    const init = orderCall?.[1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers['If-Match']).toBeTruthy()
    fetchSpy.mockRestore()
  })
})

describe('the Task List chip editor', () => {
  it('adds a mandatory item and removes an existing one', async () => {
    await openDesigner(2)
    const row = stepGroup('Device Rollout')
    const itemInput = within(row).getByLabelText('New task list item for Device Rollout')
    fireEvent.change(itemInput, { target: { value: 'Confirm power backup' } })
    fireEvent.click(within(row).getAllByRole('button', { name: '+ Add' })[0])

    await waitFor(() => {
      const item = getDb().obJourneyTemplateStepItems.find((i) => i.label === 'Confirm power backup')
      expect(item?.mandatory).toBe(true)
    }, SLOW)

    fireEvent.click(within(row).getByRole('button', { name: 'Remove Confirm device count against the purchase order' }))
    await waitFor(() => {
      expect(
        getDb().obJourneyTemplateStepItems.some((i) => i.label === 'Confirm device count against the purchase order'),
      ).toBe(false)
    }, SLOW)
  })
})

describe('the required-document chip editor', () => {
  it('adds a required doc and removes an existing one', async () => {
    await openDesigner(2)
    const row = stepGroup('Device Rollout')
    const docInput = within(row).getByLabelText('New required document for Device Rollout')
    fireEvent.change(docInput, { target: { value: 'Insurance certificate' } })
    fireEvent.click(within(row).getAllByRole('button', { name: '+ Add' })[1])

    await waitFor(() => {
      const doc = getDb().obJourneyTemplateStepDocs.find((d) => d.label === 'Insurance certificate')
      expect(doc?.required).toBe(true)
    }, SLOW)

    fireEvent.click(within(row).getByRole('button', { name: 'Remove Device delivery challan' }))
    await waitFor(() => {
      expect(getDb().obJourneyTemplateStepDocs.some((d) => d.label === 'Device delivery challan')).toBe(false)
    }, SLOW)
  })
})

describe('the parallel groups panel', () => {
  it('renders the layered groups, not a straight line', async () => {
    await openDesigner(1)
    const panel = await screen.findByRole('region', { name: 'Parallel groups' }, SLOW)
    const groups = within(panel).getAllByRole('listitem')

    expect(groups[0]).toHaveTextContent('Group 1 (layer 0)')
    expect(groups[0]).toHaveTextContent('Kickoff & Requirement Sign-off')
    expect(groups[0]).toHaveTextContent('User Training')
    expect(groups[1]).toHaveTextContent('Group 2 (layer 1)')
    expect(groups[1]).toHaveTextContent('Environment Provisioning')
  })
})

describe('the Publish button, by template state', () => {
  it('is offered on an editable draft with at least one step, naming the version', async () => {
    await openDesigner(2)
    expect(screen.getByRole('button', { name: 'Publish v1' })).toBeEnabled()
  })

  it('is hidden on a published, active version', async () => {
    await openDesigner(1)
    expect(screen.queryByRole('button', { name: /^Publish/ })).toBeNull()
  })

  it('is disabled once a draft has no steps left', async () => {
    await openDesigner(2)
    fireEvent.click(within(stepGroup('Attendance Policy Mapping')).getByRole('button', { name: 'Remove Attendance Policy Mapping' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual(['Device Rollout']), SLOW)
    fireEvent.click(within(stepGroup('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual([]), SLOW)

    expect(await screen.findByText('No steps yet', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Publish/ })).toBeDisabled()
  })
})

/**
 * C-124 · editing and deleting a whole Module Service.
 *
 * The rule these all turn on: a service a client has been boarded on can be
 * neither renamed nor deleted, and the page says so before the admin clicks
 * rather than after a `409`. The fixture journeys carry no `templateId`, so
 * every seeded service starts unused and the in-use case is set up explicitly
 * — which is the honest way round, since it makes the gate visible in the test
 * rather than inherited from a fixture nobody reads.
 */
describe('editing a module service', () => {
  it('renames every version of the service, not the version being viewed', async () => {
    // A second version of the ERP service, so the rename has a chain to move
    // rather than one row — the whole reason the route is chain-wide.
    getDb().obJourneyTemplates.push({
      id: 98, productId: 1, name: 'ERP Suite onboarding', version: 2, isActive: false,
      sequence: 1, dependsOnTemplateId: null, publishedBy: null, publishedAt: null,
    })
    await openService(1, 'ERP Suite onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit ERP Suite onboarding' }, SLOW)
    fireEvent.change(within(form).getByLabelText('Name'), {
      target: { value: 'ERP Suite rollout' },
    })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      const templates = getDb().obJourneyTemplates
      expect(templates.find((t) => t.id === 1)!.name).toBe('ERP Suite rollout')
      // v2 moved too. A rename that touched only the viewed row would leave
      // this one behind and the catalogue would draw two cards for one service.
      expect(templates.find((t) => t.id === 98)!.name).toBe('ERP Suite rollout')
    }, SLOW)
  })

  it('moves the service to another product', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    fireEvent.change(within(form).getByLabelText('Product'), { target: { value: '4' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 3)!.productId).toBe(4)
    }, SLOW)
  })

  it('surfaces the server refusal when the new name is already taken', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    // Product 3 has no sibling, so move it onto product 1 under a name that
    // product already sells — the collision the unique index would raise.
    fireEvent.change(within(form).getByLabelText('Name'), {
      target: { value: 'ERP Suite onboarding' },
    })
    fireEvent.change(within(form).getByLabelText('Product'), { target: { value: '1' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Could not update that module service/, undefined, SLOW))
      .toBeInTheDocument()
    // Nothing moved. The refusal is the server's, and the page does not guess.
    expect(getDb().obJourneyTemplates.find((t) => t.id === 3)!.name).toBe('LMS onboarding')
  })

  it('reopening the form discards an abandoned edit', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    let form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Half-typed' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Cancel' }))

    fireEvent.click(screen.getByRole('button', { name: '✎ Edit details' }))
    form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    expect((within(form).getByLabelText('Name') as HTMLInputElement).value).toBe('LMS onboarding')
  })
})

describe('deleting a module service', () => {
  it('deletes every version of the service and returns to the catalogue', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: 'Delete LMS onboarding' }, SLOW))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete service' }, SLOW))

    await waitFor(() => {
      const db = getDb()
      expect(db.obJourneyTemplates.find((t) => t.id === 3)).toBeUndefined()
      expect(db.obJourneyTemplateSteps.filter((s) => s.templateId === 3)).toHaveLength(0)
    }, SLOW)
    // The page it was deleted from no longer has a subject, so it is left.
    expect(await screen.findByText('Module Service catalogue', undefined, SLOW)).toBeInTheDocument()
  })

  it('refuses while another service depends on it, naming the dependent', async () => {
    // The fixture's "Enterprise (data migration)" waits on ERP Suite
    // onboarding, so deleting the latter would leave a dangling dependency —
    // fk_ob_journey_templates_depends_on is RESTRICT for exactly this.
    await openService(1, 'ERP Suite onboarding')

    fireEvent.click(await screen.findByRole('button', { name: 'Delete ERP Suite onboarding' }, SLOW))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete service' }, SLOW))

    expect(
      await screen.findByText(/cannot be deleted — Enterprise \(data migration\)/, undefined, SLOW),
    ).toBeInTheDocument()
    expect(getDb().obJourneyTemplates.find((t) => t.id === 1)).toBeDefined()
  })

  it('the confirmation can be dismissed without deleting anything', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: 'Delete LMS onboarding' }, SLOW))
    const dialog = await screen.findByRole('dialog', undefined, SLOW)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(getDb().obJourneyTemplates.find((t) => t.id === 3)).toBeDefined()
  })
})

describe('a service a client is already on', () => {
  /**
   * The gate, from the service page's side. `serviceJourneyCount` is
   * chain-wide, so pinning a journey to *any* version locks the service —
   * including a retired v1 while the page shows v2, which is the case a
   * per-row count would have got wrong.
   */
  function boardAClientOn(templateId: number) {
    const client = getDb().obClients[0]
    client.journeys[0].templateId = templateId
  }

  it('disables Edit details and Delete, and says how many clients are on it', async () => {
    boardAClientOn(1)
    await openService(1, 'ERP Suite onboarding')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '✎ Edit details' })).toBeDisabled()
    }, SLOW)
    expect(screen.getByRole('button', { name: 'Delete ERP Suite onboarding' })).toBeDisabled()
    // Disabled *and* explained. A tooltip alone is invisible to a keyboard
    // user tabbing past a disabled control.
    expect(screen.getByText(/1 client journey has been instantiated/)).toBeInTheDocument()
  })

  it('locks the service through a retired version, not only the head', async () => {
    getDb().obJourneyTemplates.push({
      id: 97, productId: 3, name: 'LMS onboarding', version: 2, isActive: false,
      sequence: 3, dependsOnTemplateId: null, publishedBy: null, publishedAt: null,
    })
    // The client is on v1; the page below is v2 of the same service.
    boardAClientOn(3)
    await openService(97, 'LMS onboarding')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Delete LMS onboarding' })).toBeDisabled()
    }, SLOW)
  })

  it('leaves Begin revision offered — a new version is how it changes', async () => {
    boardAClientOn(1)
    await openService(1, 'ERP Suite onboarding')

    // Not disabled: publishing over it is precisely the supported way to
    // change a service somebody is on.
    expect(screen.getByRole('button', { name: 'Begin revision' })).toBeEnabled()
  })
})
