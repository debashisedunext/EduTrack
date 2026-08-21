import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { WorkflowDesignerPage } from './WorkflowDesignerPage'

/**
 * B-043 · S-30 against the mock server.
 *
 * <p>Mounted through a `Routes` rather than by calling the component, because the
 * template id arrives through `useParams` and a test that passed it as a prop
 * would pass on the day the route path and the link in tab 3 stop agreeing —
 * which is the one thing about a screen reached only by link that nothing else
 * checks.
 *
 * <p>Every reorder case drives the **buttons**, not synthetic drag events, for the
 * reason `StagesTab.test.tsx` gives: the keyboard path is the one that has to
 * keep working, so it should be the one under test rather than the one alongside
 * it.
 *
 * <p>Fixture note — Standard Dev Flow (template 1) uses all eight stage codes in
 * the fixture, so its palette is legitimately empty. Support Fast-Track
 * (template 2) is missing QA, DEPLOY and VERIFY, so the palette cases use that
 * one. Both states are worth asserting and neither is a contrivance.
 */
function renderDesigner(templateId = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/masters/workflow/designer/${templateId}`]}>
        <Routes>
          <Route path="/masters/workflow/designer/:templateId" element={<WorkflowDesignerPage />} />
        </Routes>
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Raised locally, for the reason `StagesTab.test.tsx` gives — this page is four
 * reads deep (templates, template, stages, the selected stage) before a write. */
vi.setConfig({ testTimeout: 20000 })

const SLOW = { timeout: 5000 }

async function openDesigner(templateId = 1) {
  renderDesigner(templateId)
  await screen.findByRole('region', { name: 'Workflow canvas' }, SLOW)
}

const canvas = () => screen.getByRole('region', { name: 'Workflow canvas' })

const nodeNames = () =>
  within(canvas())
    .getAllByRole('button', { name: /^Stage \d+ of \d+:/ })
    .map((b) => b.getAttribute('aria-label')!.replace(/^Stage \d+ of \d+: /, ''))

/**
 * The node's own select button, by its full accessible name.
 *
 * A looser `{ name: /Development/ }` also matches *"Move Development left"* and
 * *"Draw a return path from Development"* — three buttons per node, all naming
 * the stage on purpose. Anchoring on the `Stage n of m:` prefix is what keeps a
 * click on the node a click on the node.
 */
const nodeButton = (name: string) =>
  within(canvas()).getByRole('button', {
    name: new RegExp(`^Stage \\d+ of \\d+: ${name}$`),
  })

/** The template's stages in the order the server now holds them. */
const savedOrder = (templateId: number) =>
  getDb()
    .templateStages.filter((s) => s.templateId === templateId)
    .sort((a, b) => a.seq - b.seq)
    .map((s) => s.stageCode)

describe('the canvas draws the flow', () => {
  it('renders every stage of the template in order', async () => {
    await openDesigner()
    expect(nodeNames()).toEqual([
      'Intake',
      'Triage / Planning',
      'Development',
      'QA / Testing',
      'Deployment',
      'Verification',
      'Sign-off',
      'Closed',
    ])
  })

  it('puts the owner role and the SLA on the face of the node, not behind a click', async () => {
    await openDesigner()
    const node = nodeButton('QA / Testing').closest('div')!
    expect(within(node).getByText('QA')).toBeInTheDocument()
    expect(within(node).getByText('8 h')).toBeInTheDocument()
  })

  it('says "No SLA" rather than 0 h — an absent SLA and a zero are different things', async () => {
    await openDesigner()
    const node = nodeButton('Development').closest('div')!
    expect(within(node).getByText('No SLA')).toBeInTheDocument()
  })

  it('names the return paths on the node that owns them', async () => {
    await openDesigner()
    const node = nodeButton('QA / Testing').closest('div')!
    expect(within(node).getByText('Returns to DEV')).toBeInTheDocument()
  })

  it('offers an empty canvas rather than an error on a template with no stages', async () => {
    const db = getDb()
    db.workflowTemplates.push({
      id: 99,
      name: 'Blank',
      description: null,
      isDefault: false,
      isActive: true,
    } as (typeof db.workflowTemplates)[number])

    await openDesigner(99)
    expect(screen.getByText('An empty canvas')).toBeInTheDocument()
  })
})

describe('reordering is staged, then saved in one request', () => {
  it('moves a node without saving it', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Move Deployment left' }))

    expect(nodeNames().slice(3, 5)).toEqual(['Deployment', 'QA / Testing'])
    expect(screen.getByRole('button', { name: 'Save flow' })).toBeEnabled()
  })

  it('announces where the node went, for anyone not watching the canvas', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Move Deployment left' }))

    expect(await screen.findByText('Deployment moved to position 4 of 8.')).toBeInTheDocument()
  })

  it('saves the whole order once, and the flow comes back reordered', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Move Deployment left' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save flow' }))

    await screen.findByText('Flow saved', undefined, SLOW)
    await waitFor(() => {
      expect(savedOrder(1)).toEqual(
        ['INTAKE', 'TRIAGE', 'DEV', 'DEPLOY', 'QA', 'VERIFY', 'SIGNOFF', 'CLOSED'],
      )
    }, SLOW)
  })

  it('refuses an order that would point a return path forwards, before the request', async () => {
    await openDesigner()
    // DEV → TRIAGE exists. Dragging DEV above TRIAGE inverts it.
    fireEvent.click(screen.getByRole('button', { name: 'Move Development left' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('DEV → TRIAGE')
    expect(screen.getByRole('button', { name: 'Save flow' })).toBeDisabled()
  })

  it('keeps the broken arrow on the canvas rather than letting it vanish mid-drag', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Move Development left' }))

    const node = nodeButton('Development').closest('div')!
    expect(within(node).getByText('Returns to TRIAGE')).toBeInTheDocument()
  })

  it('cannot move the first node left or the last one right', async () => {
    await openDesigner()
    expect(screen.getByRole('button', { name: 'Move Intake left' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move Closed right' })).toBeDisabled()
  })
})

describe('the palette is a vocabulary of codes the organisation already uses', () => {
  it('offers what this template is missing and nothing it already has', async () => {
    await openDesigner(2) // Support Fast-Track — no QA, DEPLOY or VERIFY.
    const palette = screen.getByRole('region', { name: 'Stage palette' })

    expect(within(palette).getByRole('button', { name: /Add QA \/ Testing/ })).toBeInTheDocument()
    expect(within(palette).queryByRole('button', { name: /Add Development/ })).toBeNull()
  })

  it('names which templates a code came from, because dropping it does not link to them', async () => {
    await openDesigner(2)
    const palette = screen.getByRole('region', { name: 'Stage palette' })
    expect(within(palette).getByText(/QA · on Standard Dev Flow/)).toBeInTheDocument()
  })

  it('says so plainly when a template already uses every code', async () => {
    await openDesigner(1) // Standard Dev Flow holds all eight.
    expect(
      screen.getByText(/already uses every stage code in the organisation/),
    ).toBeInTheDocument()
  })

  it('adds the stage, carrying the owner role its other uses agree on', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: /Add QA \/ Testing/ }))

    await waitFor(() => {
      const added = getDb().templateStages.find(
        (s) => s.templateId === 2 && s.stageCode === 'QA',
      )
      expect(added?.ownerRole).toBe('QA')
    }, SLOW)
  })
})

describe('the inspector sets owner role and SLA — S-30’s middle clause', () => {
  it('opens on the stage that was picked', async () => {
    await openDesigner()
    fireEvent.click(nodeButton('QA / Testing'))

    const inspector = await screen.findByRole('region', { name: 'QA / Testing' }, SLOW)
    expect(within(inspector).getByLabelText('Stage SLA (working hours)')).toHaveValue('8')
  })

  it('saves a changed SLA', async () => {
    await openDesigner()
    fireEvent.click(nodeButton('QA / Testing'))
    const inspector = await screen.findByRole('region', { name: 'QA / Testing' }, SLOW)

    fireEvent.change(within(inspector).getByLabelText('Stage SLA (working hours)'), {
      target: { value: '12' },
    })
    fireEvent.click(within(inspector).getByRole('button', { name: 'Save stage' }))

    await waitFor(() => {
      expect(getDb().templateStages.find((s) => s.id === 4)?.slaHours).toBe(12)
    }, SLOW)
  })

  it('refuses a zero SLA before the request — it would breach on entry', async () => {
    await openDesigner()
    fireEvent.click(nodeButton('QA / Testing'))
    const inspector = await screen.findByRole('region', { name: 'QA / Testing' }, SLOW)

    fireEvent.change(within(inspector).getByLabelText('Stage SLA (working hours)'), {
      target: { value: '0' },
    })
    fireEvent.click(within(inspector).getByRole('button', { name: 'Save stage' }))

    expect(await within(inspector).findByText(/zero would breach on entry/i)).toBeInTheDocument()
    expect(getDb().templateStages.find((s) => s.id === 4)?.slaHours).toBe(8)
  })

  it('offers only the stages above this one as return targets', async () => {
    await openDesigner()
    fireEvent.click(nodeButton('Development'))
    const inspector = await screen.findByRole('region', { name: 'Development' }, SLOW)

    // Development is third: Intake and Triage are above it, QA is not.
    expect(within(inspector).getByLabelText('Triage / Planning')).toBeInTheDocument()
    expect(within(inspector).queryByLabelText('QA / Testing')).toBeNull()
  })
})

describe('drawing a return path', () => {
  it('draws a backward arrow and saves it', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Draw a return path from Sign-off' }))
    fireEvent.click(nodeButton('QA / Testing'))

    await waitFor(() => {
      expect(getDb().templateStages.find((s) => s.id === 7)?.canReturnTo).toContain('QA')
    }, SLOW)
  })

  it('refuses a forward one, naming the pair rather than reporting a 409', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Draw a return path from Intake' }))
    fireEvent.click(nodeButton('QA / Testing'))

    expect(await screen.findByText(/INTAKE → QA points forwards/, undefined, SLOW)).toBeInTheDocument()
    expect(getDb().templateStages.find((s) => s.id === 1)?.canReturnTo).toEqual([])
  })

  it('refuses one that is already drawn rather than sending a duplicate', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Draw a return path from QA / Testing' }))
    fireEvent.click(nodeButton('Development'))

    expect(await screen.findByText(/already drawn/, undefined, SLOW)).toBeInTheDocument()
  })
})

describe('preview and mapping — S-30’s last two clauses', () => {
  it('previews the ribbon with no current segment, so no handoff is invited on a template', async () => {
    await openDesigner()
    const strip = await screen.findByRole('list', { name: 'Workflow stages' }, SLOW)
    expect(within(strip).getAllByRole('listitem')).toHaveLength(8)
    expect(screen.queryByRole('button', { name: /hand ?off/i })).toBeNull()
  })

  it('redraws the preview from the staged order, not the saved one', async () => {
    await openDesigner()
    fireEvent.click(screen.getByRole('button', { name: 'Move Deployment left' }))

    expect(
      await screen.findByText(/Development → Deployment → QA \/ Testing/),
    ).toBeInTheDocument()
  })

  it('carries the routing rules, so the designer ends where §7.4 says it ends', async () => {
    await openDesigner()
    expect(
      await screen.findByRole('region', { name: 'Routing rules' }, SLOW),
    ).toBeInTheDocument()
  })
})

describe('versioned by copy, never edited in place', () => {
  it('duplicates the template with its whole ribbon', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate as new version' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Duplicate' }, SLOW))

    await waitFor(() => {
      const copy = getDb().workflowTemplates.find((t) => t.name === 'Support Fast-Track v2')
      expect(copy).toBeDefined()
      expect(getDb().templateStages.filter((s) => s.templateId === copy!.id)).toHaveLength(5)
    }, SLOW)
  })
})
