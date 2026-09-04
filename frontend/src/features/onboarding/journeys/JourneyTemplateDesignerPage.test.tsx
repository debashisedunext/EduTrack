import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { JourneyTemplateDesignerPage } from './JourneyTemplateDesignerPage'

/**
 * C-102 · OB-07's template designer against the mock server.
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
  await screen.findByRole('heading', { name: /^Steps \(/ }, SLOW)
}

const stepsRegion = () => screen.getByRole('heading', { name: /^Steps \(/ }).closest('section')!

/** The step's own row, found by the numbered heading text it renders as `"1. Name"`. */
const stepRow = (name: string) =>
  within(stepsRegion())
    .getAllByRole('listitem')
    .find((li) => within(li).queryByText(new RegExp(`\\. ${escapeRegExp(name)}$`)))!

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const savedStepNames = (templateId: number) =>
  getDb()
    .obJourneyTemplateSteps.filter((s) => s.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence)
    .map((s) => s.name)

describe('the step list renders a draft template', () => {
  it('renders every step in sequence order', async () => {
    await openDesigner(2)
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
    expect(screen.getByText(/1\. Device Rollout/)).toBeInTheDocument()
    expect(screen.getByText(/2\. Attendance Policy Mapping/)).toBeInTheDocument()
  })

  it('shows the draft state and the Add step control', async () => {
    await openDesigner(2)
    expect(screen.getByText('Draft')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add step' })).toBeInTheDocument()
  })

  it('names what a step depends on, and calls out a parallel one', async () => {
    await openDesigner(2)
    expect(within(stepRow('Device Rollout')).getByText('Parallel from journey start')).toBeInTheDocument()
    expect(
      within(stepRow('Attendance Policy Mapping')).getByText('Runs after: Device Rollout'),
    ).toBeInTheDocument()
  })
})

describe('a published, active version is read-only', () => {
  it('offers Begin revision and no write controls', async () => {
    await openDesigner(1)
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Begin revision' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add step' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Move .* up$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Remove /u })).toBeNull()
  })
})

describe('adding a step', () => {
  it('writes immediately and appears at the end of the list', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Add step' }))

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
    fireEvent.click(screen.getByRole('button', { name: 'Add step' }))
    const form = screen.getByRole('form', { name: 'Add step' })
    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '2' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add step' }))

    expect(await within(form).findByText('Name is required')).toBeInTheDocument()
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
  })
})

describe('removing a step', () => {
  it('removes a step nothing depends on', async () => {
    await openDesigner(2)
    // Remove the dependent first so Device Rollout has none left.
    fireEvent.click(within(stepRow('Attendance Policy Mapping')).getByRole('button', { name: 'Remove Attendance Policy Mapping' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual(['Device Rollout']), SLOW)

    fireEvent.click(within(stepRow('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual([]), SLOW)
  })

  it('names the dependents rather than a bare conflict', async () => {
    await openDesigner(2)
    fireEvent.click(within(stepRow('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))

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

    expect(screen.getByText(/1\. Attendance Policy Mapping/)).toBeInTheDocument()
    expect(screen.getByText(/2\. Device Rollout/)).toBeInTheDocument()
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

describe('the Task List', () => {
  it('adds a mandatory item and removes an existing one', async () => {
    await openDesigner(2)
    const row = stepRow('Device Rollout')
    const itemForm = within(row).getByLabelText('New task list item for Device Rollout')
    fireEvent.change(itemForm, { target: { value: 'Confirm power backup' } })
    fireEvent.click(within(row).getAllByRole('button', { name: 'Add' })[0])

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

describe('the required-document checklist', () => {
  it('adds a required doc and removes an existing one', async () => {
    await openDesigner(2)
    const row = stepRow('Device Rollout')
    const docForm = within(row).getByLabelText('New required document for Device Rollout')
    fireEvent.change(docForm, { target: { value: 'Insurance certificate' } })
    fireEvent.click(within(row).getAllByRole('button', { name: 'Add' })[1])

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
  it('is offered on an editable draft with at least one step', async () => {
    await openDesigner(2)
    expect(screen.getByRole('button', { name: 'Publish' })).toBeEnabled()
  })

  it('is hidden on a published, active version', async () => {
    await openDesigner(1)
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
  })

  it('is disabled once a draft has no steps left', async () => {
    await openDesigner(2)
    fireEvent.click(within(stepRow('Attendance Policy Mapping')).getByRole('button', { name: 'Remove Attendance Policy Mapping' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual(['Device Rollout']), SLOW)
    fireEvent.click(within(stepRow('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual([]), SLOW)

    expect(await screen.findByText('No steps yet', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled()
  })
})
