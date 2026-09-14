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
  await screen.findByRole('group', { name: 'Stages and tasks' }, SLOW)
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

/**
 * The board — a group of stage sections, where there used to be one table.
 *
 * <p>The screen is nested containers now: a stage is a `<section>`, a task is
 * an `<article>` inside its body, and a task that waits on another is an
 * `<article>` inside *that* one. So the helpers below reach for regions and
 * articles rather than rowgroups and cells.
 */
const board = () => screen.getByRole('group', { name: 'Stages and tasks' })

/**
 * A task's own card.
 *
 * <p>**It contains the cards of the tasks that wait on it**, which is the
 * point of the nesting and the one thing to keep in mind here: asserting that
 * something is *absent* from a task has to be done against
 * {@link taskHeader}, or a child's chip answers for its parent.
 */
const stepGroup = (name: string) =>
  within(board()).getByRole('article', { name })

/** A task's own header — its facts, and none of its children's. */
const taskHeader = (name: string) => stepGroup(name).querySelector('header')!

/**
 * The task names in display order, depth-first — each card's own name, read
 * off the element its card is labelled by, so a parent answers with its own
 * name rather than with everything nested inside it.
 *
 * Display order is **tree** order within a stage: a task is drawn inside the
 * one it waits for whatever the sequence says, so this only reflects a reorder
 * when the two tasks moved are siblings.
 */
const displayedStepNames = () =>
  Array.from(board().querySelectorAll('article')).map(
    (card) => card.querySelector('[id^="ob-task-"]')?.textContent ?? '',
  )

/** The stage headers, in display order — name, counts and the rolled-up span. */
const displayedStageNames = () =>
  Array.from(board().querySelectorAll('section')).map(
    (stage) => stage.querySelector('header')?.textContent ?? '',
  )

/** A task's Checklist grid — one table per task, named after it. */
const checklist = (task: string) =>
  screen.getByRole('table', { name: `Checklist for ${task}` })

/**
 * The written rows of a task's Checklist, in display order — the column header
 * dropped, and the composer with it: it is the row that holds a text box, and
 * the words on it ("Required", "Check") are its controls rather than an item's
 * facts.
 */
const checklistRows = (task: string) =>
  within(checklist(task))
    .getAllByRole('row')
    .filter((row) => within(row).queryByRole('textbox') === null)
    .slice(1)
    .map((row) => row.textContent ?? '')

const savedStepNames = (templateId: number) =>
  getDb()
    .obJourneyTemplateSteps.filter((s) => s.templateId === templateId)
    .sort((a, b) => a.sequence - b.sequence)
    .map((s) => s.name)

describe('the step list renders a draft template', () => {
  it('renders every task in sequence order, under its stage', async () => {
    await openDesigner(2)
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
    const names = displayedStepNames()
    expect(names[0]).toContain('Device Rollout')
    expect(names[1]).toContain('Attendance Policy Mapping')
    // Both tasks live in Configuration; the other five stages are empty and
    // still draw, which is what the add-task control hangs off.
    expect(displayedStageNames()[0]).toContain('Configuration')
    // Counted by kind, not by row: Device Rollout waits for nothing and is a
    // Step, Attendance Policy Mapping waits for it and is a Task.
    expect(displayedStageNames()[0]).toContain('1 step')
    expect(displayedStageNames()[0]).toContain('1 task')
  })

  it('shows the draft state and the back link', async () => {
    await openDesigner(2)
    expect(screen.getByText('Draft')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Module Service' })).toBeInTheDocument()
  })

  it('totals TAT in the header caption — C-120', async () => {
    // Attendance Policy Mapping (3) waits for Device Rollout (6), so the two
    // really are consecutive: 9 days. The figure below asserts the other half
    // of the rule, where they are not.
    await openDesigner(2)
    expect(screen.getByText('Total TAT: 9d')).toBeInTheDocument()
    expect(screen.getByText(/across 2 tasks in 6 stages/)).toBeInTheDocument()
  })

  /*
    The case Σ tatDays got wrong, and the reason the header no longer prints
    one. Both tasks of template 2 depend on nothing once the chain is cut, so
    they run at the same time: 6 days, not 9.
  */
  it('does not add the TAT of two tasks that run alongside each other', async () => {
    getDb().obJourneyTemplateSteps.find((s) => s.name === 'Attendance Policy Mapping')!
      .dependsOnStepId = null
    await openDesigner(2)

    expect(screen.getByText('Total TAT: 6d')).toBeInTheDocument()
  })

  /*
    Two figures where there is one question. "Total TAT" was Σ of the task TATs
    and "Plan runs to day N" was the critical path; the pair left the reader
    working out which meant how long the service takes.
  */
  it('prints one TAT figure, not a sum beside a span', async () => {
    await openDesigner(2)

    expect(screen.queryByText(/Plan runs to day/)).not.toBeInTheDocument()
  })

  it('nests a step under the one it waits for, and calls out a parallel one', async () => {
    await openDesigner(2)
    // The dependency is no longer a cell naming a row number — it is the
    // nesting itself. Only a root says anything, because indentation cannot
    // say "waits for nothing", and null means parallel rather than first.
    expect(
      within(stepGroup('Device Rollout')).getByText('No dependency, runs in parallel'),
    ).toBeInTheDocument()
    expect(
      within(stepGroup('Attendance Policy Mapping')).queryByText('No dependency, runs in parallel'),
    ).not.toBeInTheDocument()
    // Indentation says "waits for that one" to the eye and nothing at all to
    // a screen reader, so the name the removed column printed is still there.
    expect(
      within(stepGroup('Attendance Policy Mapping')).getByText('Dependency: Device Rollout'),
    ).toBeInTheDocument()
  })

  it('schedules each step from the day its predecessor ends', async () => {
    await openDesigner(2)
    // Device Rollout is 6 working days and runs from the start; Attendance
    // Policy Mapping waits for it, so it cannot begin before day 7.
    expect(within(stepGroup('Device Rollout')).getByText('Day 1–6')).toBeInTheDocument()
    expect(within(stepGroup('Attendance Policy Mapping')).getByText('Day 7–9')).toBeInTheDocument()
    // The last day of the schedule and the header's Total TAT are one figure
    // now, and this is it.
    expect(screen.getByText('Total TAT: 9d')).toBeInTheDocument()
  })

  it('collapses a step, taking its tasks and its subtree with it', async () => {
    await openDesigner(2)
    expect(screen.getByText('Confirm device count against the purchase order')).toBeInTheDocument()
    expect(displayedStepNames()).toHaveLength(2)

    fireEvent.click(screen.getByRole('button', { name: 'Collapse Device Rollout' }))

    expect(
      screen.queryByText('Confirm device count against the purchase order'),
    ).not.toBeInTheDocument()
    // Attendance Policy Mapping hangs off Device Rollout, so it goes too.
    expect(displayedStepNames()).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: 'Expand Device Rollout' }))
    expect(displayedStepNames()).toHaveLength(2)
  })
})

/**
 * The five levels the screen now draws — Module Service, stage, Step, Task,
 * sub-task — and the three controls that decide how much of them is on it.
 *
 * <p>Fixture: template 2's Configuration holds Device Rollout (waits for
 * nothing, so a **Step**, Day 1–6) and, under it, Attendance Policy Mapping
 * (a **Task**, Day 7–9). Device Rollout carries two task list items and one
 * required document; the other five stages are empty.
 */
describe('the tree shows every level', () => {
  /** A stage's header strip — its name, its counts and its rolled-up span. */
  const stageRow = (name: string) =>
    Array.from(board().querySelectorAll('section'))
      .map((stage) => stage.querySelector('header')!)
      .find((header) => header.textContent?.includes(name))!

  const showTo = (level: string) =>
    fireEvent.click(screen.getByRole('button', { name: level }))

  it('rolls the stage up to a day span and a summed TAT', async () => {
    await openDesigner(2)
    // Day 1 is the day the journey starts, so the span is read off the
    // template-wide tree: Device Rollout opens it, Attendance Policy Mapping
    // closes it on day 9. 6 + 3 of work inside that.
    expect(stageRow('Configuration')).toHaveTextContent('Day 1–9')
    expect(stageRow('Configuration')).toHaveTextContent('9')
  })

  it('marks the step that runs in parallel, and not the one that waits', async () => {
    await openDesigner(2)
    expect(within(stepGroup('Device Rollout')).getByText('Parallel')).toBeInTheDocument()
    expect(
      within(stepGroup('Attendance Policy Mapping')).queryByText('Parallel'),
    ).not.toBeInTheDocument()
  })

  it('marks the task the plan ends on, which is not the longest one', async () => {
    await openDesigner(2)
    // Device Rollout is the longer task at 6 days, but the plan runs to day 9
    // and that is Attendance Policy Mapping's last day.
    expect(
      within(taskHeader('Attendance Policy Mapping')).getByText('Plan ends here'),
    ).toBeInTheDocument()
    // Against the header, not the card: Attendance Policy Mapping is nested
    // *inside* Device Rollout's card, so a card-wide query would find the
    // child's chip and call it the parent's.
    expect(within(taskHeader('Device Rollout')).queryByText('Plan ends here')).toBeNull()
  })

  it('collapses a whole stage, taking every task in it', async () => {
    await openDesigner(2)
    expect(displayedStepNames()).toHaveLength(2)

    fireEvent.click(screen.getByRole('button', { name: 'Collapse Configuration' }))
    expect(displayedStepNames()).toHaveLength(0)
    // The stage itself stays — a service with six stages still has six.
    expect(displayedStageNames()).toHaveLength(6)

    fireEvent.click(screen.getByRole('button', { name: 'Expand Configuration' }))
    expect(displayedStepNames()).toHaveLength(2)
  })

  it('offers no chevron on a stage with nothing in it', async () => {
    await openDesigner(2)
    expect(screen.queryByRole('button', { name: /^(Collapse|Expand) Reports$/ })).toBeNull()
  })

  it('opens the tree to each level in turn', async () => {
    await openDesigner(2)

    showTo('Stage')
    expect(displayedStepNames()).toHaveLength(0)

    showTo('Task')
    expect(displayedStepNames()).toHaveLength(2)
    // Checklist items are a level further down and stay shut.
    expect(screen.queryByText('Confirm device count against the purchase order')).toBeNull()

    showTo('Checklist')
    expect(
      screen.getByText('Confirm device count against the purchase order'),
    ).toBeInTheDocument()
  })

  it('highlights the level it is at, and nothing once a row is toggled by hand', async () => {
    await openDesigner(2)
    showTo('Task')
    expect(screen.getByRole('button', { name: 'Task' })).toHaveAttribute('aria-pressed', 'true')

    // The tree is now at no level at all, and no segment should claim it is.
    fireEvent.click(screen.getByRole('button', { name: 'Collapse Device Rollout' }))
    for (const level of ['Stage', 'Task', 'Checklist']) {
      expect(screen.getByRole('button', { name: level })).toHaveAttribute('aria-pressed', 'false')
    }
  })

  /*
    The strip is Stage / Task / Checklist. A fourth segment called "Step" sat a
    row above the Step badge on the cards, one of them a filter and the other a
    label, both saying "Step" about different things.
  */
  it('offers three levels, and Step is not one of them', async () => {
    await openDesigner(2)

    expect(screen.queryByRole('button', { name: 'Step' })).not.toBeInTheDocument()
    for (const level of ['Stage', 'Task', 'Checklist']) {
      expect(screen.getByRole('button', { name: level })).toBeInTheDocument()
    }
  })

  it('expands and collapses everything from the toolbar', async () => {
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Collapse all' }))
    expect(displayedStepNames()).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: 'Expand all' }))
    expect(displayedStepNames()).toHaveLength(2)
    expect(
      screen.getByText('Confirm device count against the purchase order'),
    ).toBeInTheDocument()
  })

  it('marks the checklist items that gate their task, and leaves the optional one plain', async () => {
    await openDesigner(2)
    const rows = checklistRows('Device Rollout')
    /*
      Two of the three gate it: the mandatory item and the required document.
      They read the same word now — a tick and a file gate a task in exactly
      the same way, and calling one "Mandatory" and the other "Required" was
      two names for one rule.
    */
    expect(rows[0]).toContain('Confirm device count against the purchase order')
    expect(rows[0]).toContain('Required')
    expect(rows[1]).toContain('Optional')
    expect(rows[2]).toContain('Required')
  })
})

describe('filtering the tree', () => {
  const filterBox = () => screen.getByLabelText('Filter tasks and checklist items')

  it('keeps the ancestors of a hit, so nothing floats out of its stage', async () => {
    await openDesigner(2)
    fireEvent.change(filterBox(), { target: { value: 'Attendance' } })

    const names = displayedStepNames()
    expect(names).toHaveLength(2)
    expect(names[0]).toContain('Device Rollout')
    expect(names[1]).toContain('Attendance Policy Mapping')
    // Device Rollout is carried for context only — its own sub-tasks did not
    // match, so they stay shut rather than burying the hit.
    expect(screen.queryByText('Confirm device count against the purchase order')).toBeNull()
  })

  it('matches a sub-task and shows only the one that matched', async () => {
    await openDesigner(2)
    fireEvent.change(filterBox(), { target: { value: 'electrician' } })

    expect(displayedStepNames()).toHaveLength(1)
    expect(screen.getByText(/Note the site electrician/)).toBeInTheDocument()
    expect(screen.queryByText('Confirm device count against the purchase order')).toBeNull()
  })

  it('says a stage has no matches rather than dropping it off the page', async () => {
    await openDesigner(2)
    fireEvent.change(filterBox(), { target: { value: 'nothing matches this' } })

    expect(displayedStepNames()).toHaveLength(0)
    expect(displayedStageNames()[0]).toContain('No matches')
    expect(screen.getByText('Nothing matches')).toBeInTheDocument()
  })

  it('puts the tree back exactly as it was when the box is cleared', async () => {
    await openDesigner(2)
    // Collapsed by hand first: a filter narrows the tree, it never rewrites
    // what the reader had closed.
    fireEvent.click(screen.getByRole('button', { name: 'Collapse Device Rollout' }))
    expect(displayedStepNames()).toHaveLength(1)

    fireEvent.change(filterBox(), { target: { value: 'Attendance' } })
    expect(displayedStepNames()).toHaveLength(2)

    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(displayedStepNames()).toHaveLength(1)
  })

  it('hides the composers while a filter is narrowing the tree', async () => {
    await openDesigner(2)
    expect(screen.getByRole('button', { name: '+ Add a task to Configuration' })).toBeInTheDocument()

    fireEvent.change(filterBox(), { target: { value: 'Attendance' } })
    // A composer is not a search result, and one under a stage the filter
    // emptied invites a task nobody was looking at.
    expect(screen.queryByRole('button', { name: '+ Add a task to Configuration' })).toBeNull()
  })
})

describe('the tree is walkable from the keyboard', () => {
  const toggle = (name: string) => screen.getByRole('button', { name })

  it('moves between disclosure controls with the arrow keys', async () => {
    await openDesigner(2)
    const stage = toggle('Collapse Configuration')
    stage.focus()

    fireEvent.keyDown(stage, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(toggle('Collapse Device Rollout'))

    fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(stage)
  })

  it('closes a row with ← and steps out to its parent when it is already closed', async () => {
    await openDesigner(2)
    const task = toggle('Collapse Attendance Policy Mapping')
    task.focus()

    fireEvent.keyDown(task, { key: 'ArrowLeft' })
    expect(toggle('Expand Attendance Policy Mapping')).toBeInTheDocument()

    fireEvent.keyDown(document.activeElement!, { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(toggle('Collapse Device Rollout'))
  })

  it('opens a closed row with →', async () => {
    await openDesigner(2)
    fireEvent.click(toggle('Collapse Device Rollout'))
    const closed = toggle('Expand Device Rollout')
    closed.focus()

    fireEvent.keyDown(closed, { key: 'ArrowRight' })
    expect(toggle('Collapse Device Rollout')).toBeInTheDocument()
  })

  it('leaves an arrow key typed into the filter box alone', async () => {
    await openDesigner(2)
    const box = screen.getByLabelText('Filter tasks and checklist items')
    box.focus()
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(box)
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
    /*
      19, not the 24 a sum would give. Kickoff (3) → Provisioning (4) → Data
      Migration (8) → Go-live (4) is a chain of 19 days, and User Training's 5
      days run alongside it rather than after it — shortening Training would
      not finish the service a day sooner.
    */
    await openDesigner(1)
    expect(screen.getByText('Total TAT: 19d')).toBeInTheDocument()
  })
})

/**
 * There is no add control, and that is the requirement rather than a gap: a
 * Module Service's stages come from the master it was created against, and a
 * picker here would be a second place to decide them.
 */
describe('stages are not added by hand', () => {
  it('offers no way to add a step, and says where the stages come from', async () => {
    await openDesigner(2)

    expect(
      screen.queryByRole('button', { name: /add (step|implementation stage)/i }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Implementation Stage master' })).toHaveAttribute(
      'href',
      '/onboarding/implementation-stages',
    )
  })
})


/**
 * The feature itself, end to end through the mock server: create a Module
 * Service, open it, and the six stages are already there.
 *
 * <p>Created through the API rather than by pushing rows into the fixture,
 * because the seeding is the create handler's own behaviour — a test that
 * seeded the rows itself would pass with the handler doing nothing.
 */
describe('a new Module Service arrives holding the implementation stages', () => {
  async function createService(name: string): Promise<number> {
    const response = await fetch('/api/v1/onboarding/journey-templates', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ productId: 2, name, sequence: 9 }),
    })
    const body = (await response.json()) as { data: { id: number } }
    return body.data.id
  }

  it('arrives holding every active stage as an empty group, and no tasks', async () => {
    const id = await createService('Payment Gateway')
    await openDesigner(id)

    const expected = getDb()
      .obImplementationStages.filter((st) => st.isActive)
      .sort((a, b) => a.sequence - b.sequence)
      .map((st) => st.name)

    const drawn = displayedStageNames()
    expect(drawn).toHaveLength(expected.length)
    // `contains` rather than equality: the header carries the task count chip
    // alongside the stage's name.
    expected.forEach((name, i) => expect(drawn[i]).toContain(name))
    drawn.forEach((header) => expect(header).toContain('No tasks yet'))

    /*
      Groups, not tasks. Seeding a task per stage would be inventing work
      nobody described — "Configuration" names a phase, not something to do —
      and would leave an admin clearing six out before writing the real ones.
    */
    expect(displayedStepNames()).toHaveLength(0)
    expect(getDb().obJourneyTemplateSteps.filter((st) => st.templateId === id)).toEqual([])
    expect(
      getDb()
        .obJourneyTemplateStages.filter((g) => g.templateId === id)
        .map((g) => g.name),
    ).toEqual(expected)
  })

  it('offers a way to add a task to each stage, and no way to add a stage', async () => {
    const id = await createService('Payment Gateway III')
    await openDesigner(id)

    const active = getDb().obImplementationStages.filter((st) => st.isActive)
    expect(
      screen.getAllByRole('button', { name: /^\+ Add a task to/ }),
    ).toHaveLength(active.length)
    // The level above stays closed: which stages exist is decided on OB-15.
    expect(
      screen.queryByRole('button', { name: /add (a )?(step|implementation stage|stage)$/i }),
    ).not.toBeInTheDocument()
  })

  it('writes a task into the stage it was added under', async () => {
    const id = await createService('Payment Gateway IV')
    await openDesigner(id)

    fireEvent.click(screen.getByRole('button', { name: '+ Add a task to Data Migration' }))
    fireEvent.change(screen.getByLabelText('Name of the new task in Data Migration'), {
      target: { value: 'Map legacy fee heads' },
    })
    fireEvent.change(screen.getByLabelText('TAT in working days for the new task in Data Migration'), {
      target: { value: '4' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add task' }))

    await waitFor(() => {
      const written = getDb().obJourneyTemplateSteps.find((st) => st.name === 'Map legacy fee heads')
      expect(written).toBeDefined()
      expect(written!.tatDays).toBe(4)
      const group = getDb().obJourneyTemplateStages.find((g) => g.id === written!.templateStageId)
      expect(group?.name).toBe('Data Migration')
    }, SLOW)
  })

  it('pins an implementor on the task as it is written', async () => {
    const id = await createService('Payment Gateway V')
    await openDesigner(id)
    const kavya = getDb().users.find((u) => u.displayName === 'Kavya Sharma')!

    fireEvent.click(screen.getByRole('button', { name: '+ Add a task to Configuration' }))
    fireEvent.change(screen.getByLabelText('Name of the new task in Configuration'), {
      target: { value: 'Tenant provisioning' },
    })
    // The user list is its own read; the form paints before it lands.
    await waitFor(
      () =>
        expect(
          within(
            screen.getByLabelText('Implementor for the new task in Configuration'),
          ).getAllByRole('option').length,
        ).toBeGreaterThan(1),
      SLOW,
    )
    fireEvent.change(screen.getByLabelText('Implementor for the new task in Configuration'), {
      target: { value: String(kavya.id) },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add task' }))

    await waitFor(() => {
      const written = getDb().obJourneyTemplateSteps.find((st) => st.name === 'Tenant provisioning')
      expect(written).toBeDefined()
      expect(written!.ownerUserId).toBe(kavya.id)
    }, SLOW)
  })

  /*
    The default, and the one this screen is usually left on: a service is
    authored once and boarded for many projects, so pinning a person here says
    "this one person does it for everyone, for ever". Writing null is what
    lets the project answer instead.
  */
  it('writes no implementor when the picker is left alone, so the project answers', async () => {
    const id = await createService('Payment Gateway VII')
    await openDesigner(id)

    fireEvent.click(screen.getByRole('button', { name: '+ Add a task to Configuration' }))
    fireEvent.change(screen.getByLabelText('Name of the new task in Configuration'), {
      target: { value: 'Brand the portal' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add task' }))

    await waitFor(() => {
      const written = getDb().obJourneyTemplateSteps.find((st) => st.name === 'Brand the portal')
      expect(written).toBeDefined()
      expect(written!.ownerUserId).toBeNull()
    }, SLOW)
    // The card arrives with the refetch the write invalidates, so wait for it
    // rather than reaching into a board that has not repainted.
    await screen.findByRole('article', { name: 'Brand the portal' }, SLOW)
    expect(
      within(taskHeader('Brand the portal')).getByText('The project’s implementor'),
    ).toBeInTheDocument()
  })

  /** An inactive user is not somebody work can be put on. */
  it('offers only active people in the owner picker', async () => {
    const id = await createService('Payment Gateway VI')
    await openDesigner(id)

    fireEvent.click(screen.getByRole('button', { name: '+ Add a task to Configuration' }))
    const picker = screen.getByLabelText('Implementor for the new task in Configuration')
    await waitFor(
      () => expect(within(picker).getAllByRole('option').length).toBeGreaterThan(1),
      SLOW,
    )

    const offered = within(picker).getAllByRole('option').map((o) => o.textContent)
    expect(offered).toContain('Kavya Sharma')
    // Sunil Menon is `isActive: false` in the fixture.
    expect(offered).not.toContain('Sunil Menon')
  })

  /**
   * The stage list is the master's, so the page points at the master rather
   * than offering a way to extend it here.
   */
  it('points at the master rather than offering a way to add more', async () => {
    const id = await createService('Payment Gateway II')
    await openDesigner(id)

    expect(
      screen.queryByRole('button', { name: /add (step|implementation stage)/i }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Implementation Stage master' })).toBeInTheDocument()
  })
})

/**
 * Editing a step, which the seeded stages made necessary: six steps nobody
 * typed arrive with a one-day TAT and no owner, and remove-and-re-add would
 * take the task list with them.
 */
describe('editing a step', () => {
  const openEditForm = async (stepName: string) => {
    await openDesigner(2)
    fireEvent.click(
      within(stepGroup(stepName)).getByRole('button', { name: `Edit ${stepName}` }),
    )
    return screen.getByRole('form', { name: `Edit ${stepName}` })
  }

  const savedStep = (name: string) =>
    getDb().obJourneyTemplateSteps.find((st) => st.templateId === 2 && st.name === name)

  it('saves the TAT and the sign-off flag together', async () => {
    const form = await openEditForm('Device Rollout')

    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '9' } })
    fireEvent.click(
      within(form).getByLabelText(/Client sign-off required/),
    )
    fireEvent.click(within(form).getByRole('button', { name: 'Save step' }))

    await screen.findByText('Device Rollout saved', undefined, SLOW)
    expect(savedStep('Device Rollout')?.tatDays).toBe(9)
    expect(savedStep('Device Rollout')?.requiresSignoff).toBe(true)
  })

  /*
    The implementor picker is seeded from the step itself, so it is correct the
    moment the form opens. The owning-role picker it replaced was seeded from a
    list arriving on its own request: a form opened before that landed held ''
    — initial state, so it never caught up — and Save cleared an owner nobody
    had touched. Saving without going near the picker must leave the owner
    exactly as it was.
  */
  it('leaves the implementor alone when the form is saved without touching it', async () => {
    const kavya = getDb().users.find((u) => u.displayName === 'Kavya Sharma')!
    getDb().obJourneyTemplateSteps.find((s) => s.name === 'Device Rollout')!.ownerUserId = kavya.id

    const form = await openEditForm('Device Rollout')
    // Deliberately no waitFor on the user list — that race is the bug.
    fireEvent.change(within(form).getByLabelText('TAT (working days)'), { target: { value: '5' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save step' }))

    await screen.findByText('Device Rollout saved', undefined, SLOW)
    expect(savedStep('Device Rollout')?.tatDays).toBe(5)
    expect(savedStep('Device Rollout')?.ownerUserId).toBe(kavya.id)
  })

  /*
    The exception rather than the rule: a task that always belongs to one
    particular person, whoever the project is for.
  */
  it('pins an implementor, and the column shows them instead of the project default', async () => {
    const form = await openEditForm('Device Rollout')
    const kavya = getDb().users.find((u) => u.displayName === 'Kavya Sharma')!
    await waitFor(
      () =>
        expect(
          within(within(form).getByLabelText('Implementor')).getAllByRole('option').length,
        ).toBeGreaterThan(1),
      SLOW,
    )

    fireEvent.change(within(form).getByLabelText('Implementor'), {
      target: { value: String(kavya.id) },
    })
    fireEvent.click(within(form).getByRole('button', { name: 'Save step' }))

    await screen.findByText('Device Rollout saved', undefined, SLOW)
    expect(savedStep('Device Rollout')?.ownerUserId).toBe(kavya.id)
    expect(
      await within(taskHeader('Device Rollout')).findByText('Kavya Sharma', undefined, SLOW),
    ).toBeInTheDocument()
  })

  /*
    A `number` has no blank value, so without the clear flag a person could be
    pinned and never taken off again. Cleared, the task is not unassigned — it
    goes back to whoever is running the project it is boarded for, which is
    what the column then says.
  */
  it('unpins the implementor, handing the task back to the project', async () => {
    const first = await openEditForm('Device Rollout')
    const kavya = getDb().users.find((u) => u.displayName === 'Kavya Sharma')!
    await waitFor(
      () =>
        expect(
          within(within(first).getByLabelText('Implementor')).getAllByRole('option').length,
        ).toBeGreaterThan(1),
      SLOW,
    )
    fireEvent.change(within(first).getByLabelText('Implementor'), {
      target: { value: String(kavya.id) },
    })
    fireEvent.click(within(first).getByRole('button', { name: 'Save step' }))
    await screen.findByText('Device Rollout saved', undefined, SLOW)
    expect(savedStep('Device Rollout')?.ownerUserId).toBe(kavya.id)

    /*
      Wait for the name to reach the row before reopening. The save
      invalidates the detail query, and the form's `If-Match` is the
      *template's* ETag — reopening before the refetch lands would submit the
      tag the first save already superseded, and the second edit would answer
      412 rather than clearing anything.
    */
    await within(taskHeader('Device Rollout')).findByText('Kavya Sharma', undefined, SLOW)

    const second = await openEditForm('Device Rollout')
    fireEvent.change(within(second).getByLabelText('Implementor'), {
      target: { value: '' },
    })
    fireEvent.click(within(second).getByRole('button', { name: 'Save step' }))

    await waitFor(() => expect(savedStep('Device Rollout')?.ownerUserId).toBeNull(), SLOW)
    /*
      Read off the *last* card rather than through `taskHeader`. `openEditForm`
      renders the designer afresh, so two boards are mounted by now and a
      single-match query is ambiguous; the second render is the one holding
      the clear.
    */
    await waitFor(() => {
      const cards = screen.getAllByRole('article', { name: 'Device Rollout' })
      const header = cards[cards.length - 1].querySelector('header')!
      expect(within(header).getByText('The project’s implementor')).toBeInTheDocument()
    }, SLOW)
  })

  /** The name is the stage's, so the form must not offer to change it. */
  it('does not offer the name or the stage for editing', async () => {
    const form = await openEditForm('Device Rollout')

    expect(within(form).queryByLabelText('Name')).not.toBeInTheDocument()
    expect(within(form).queryByLabelText('Implementation stage')).not.toBeInTheDocument()
  })

  it('clears a dependency, making the step run in parallel', async () => {
    const form = await openEditForm('Attendance Policy Mapping')

    fireEvent.change(within(form).getByLabelText('Dependency'), { target: { value: '' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save step' }))

    await screen.findByText('Attendance Policy Mapping saved', undefined, SLOW)
    expect(savedStep('Attendance Policy Mapping')?.dependsOnStepId).toBeNull()
  })

  /**
   * The picker cannot offer a chain that waits on itself. The server refuses
   * one too; not offering it is what stops an admin meeting the rule as an
   * error message.
   */
  it('keeps the step and its own dependents out of the dependency picker', async () => {
    const form = await openEditForm('Device Rollout')

    const picker = within(form).getByLabelText('Dependency')
    const offered = within(picker)
      .getAllByRole('option')
      .map((o) => o.textContent ?? '')
    expect(offered.some((text) => text.includes('Device Rollout'))).toBe(false)
    // Attendance Policy Mapping already waits for Device Rollout, so pointing
    // Device Rollout at it would close the loop.
    expect(offered.some((text) => text.includes('Attendance Policy Mapping'))).toBe(false)
  })

  /** The rule the whole designer is built on. */
  it('offers no edit control on a published version', async () => {
    await openDesigner(1)

    expect(
      screen.queryByRole('button', { name: /^Edit Kickoff/ }),
    ).not.toBeInTheDocument()
  })
})

/**
 * A task names one person and nothing else. The owning role and the backup
 * owner that used to sit beside it are gone: the role was a fallback
 * instantiation never consulted — no per-client role→user resolver exists —
 * so a task carrying only a role landed on nobody, and leave coverage is a
 * fact about a live journey rather than about a plan.
 */
describe('a task names one person, and usually names nobody', () => {
  it('offers no owning role and no backup owner anywhere on the form', async () => {
    await openDesigner(2)
    fireEvent.click(
      within(stepGroup('Device Rollout')).getByRole('button', { name: 'Edit Device Rollout' }),
    )
    const form = screen.getByRole('form', { name: 'Edit Device Rollout' })

    expect(within(form).queryByLabelText('Owning role')).not.toBeInTheDocument()
    expect(within(form).queryByLabelText('Backup owner')).not.toBeInTheDocument()
    expect(within(form).getByLabelText('Implementor').tagName).toBe('SELECT')
  })

  /*
    A dash read as a half-configured row and sent people hunting for a field to
    fill in. What actually happens is that the project answers, so that is what
    the column says.
  */
  it('says who an unpinned task will go to rather than drawing a dash', async () => {
    const db = getDb()
    const step = db.obJourneyTemplateSteps.find((s) => s.templateId === 2)!
    step.ownerUserId = null

    await openDesigner(2)
    expect(
      within(taskHeader(step.name)).getByText('The project’s implementor'),
    ).toBeInTheDocument()
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
    /*
      Cut the dependency first, so the two steps are siblings. The tree draws
      a step under the one it waits for whatever the sequence says, so a
      parent and its child never swap on screen however they are reordered —
      sibling order is the part of the sequence the tree can show, and the
      part the ↑/↓ pair is worth driving against.
    */
    getDb().obJourneyTemplateSteps.find((s) => s.name === 'Attendance Policy Mapping')!
      .dependsOnStepId = null
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Move Attendance Policy Mapping up' }))

    const names = displayedStepNames()
    expect(names[0]).toContain('Attendance Policy Mapping')
    expect(names[1]).toContain('Device Rollout')
    expect(screen.getByRole('button', { name: 'Save order' })).toBeInTheDocument()
    // Not written yet.
    expect(savedStepNames(2)).toEqual(['Device Rollout', 'Attendance Policy Mapping'])
  })

  it('saves the staged order per stage and sends the cached ETag as If-Match', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    await openDesigner(2)
    fireEvent.click(screen.getByRole('button', { name: 'Move Attendance Policy Mapping up' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save order' }))

    await screen.findByText('Task order saved', undefined, SLOW)
    await waitFor(() => {
      expect(savedStepNames(2)).toEqual(['Attendance Policy Mapping', 'Device Rollout'])
    }, SLOW)

    const orderCall = fetchSpy.mock.calls.find(([input]) =>
      typeof input === 'string' && input.includes('/tasks/order'),
    )
    expect(orderCall).toBeDefined()
    const init = orderCall?.[1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers['If-Match']).toBeTruthy()
    fetchSpy.mockRestore()
  })
})

/**
 * The **Checklist** grid — the two stacked composers ("Add a task…" and "Add a
 * document…") drawn as one table with a Type column.
 *
 * <p>The contract behind it is still two collections and four routes, so every
 * case here asserts against the row the mock server actually wrote: a Check
 * lands in `obJourneyTemplateStepItems`, a Document in
 * `obJourneyTemplateStepDocs`, and the grid choosing the wrong one would be
 * invisible on screen and wrong in the database.
 */
describe('the Checklist grid', () => {
  const composer = (task: string) =>
    within(checklist(task)).getByLabelText(`New checklist item for ${task}`)

  it('draws ticks and files as one numbered list, each saying which it is', async () => {
    await openDesigner(2)
    const rows = checklistRows('Device Rollout')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toContain('Confirm device count against the purchase order')
    expect(rows[0]).toContain('Check')
    expect(rows[2]).toContain('Device delivery challan')
    expect(rows[2]).toContain('Document')
    // Numbered down the # column, which is the order the client's journey
    // page will ask for them in.
    expect(rows[0].startsWith('1')).toBe(true)
    expect(rows[2].startsWith('3')).toBe(true)
  })

  it('adds a check, and removes an existing one', async () => {
    await openDesigner(2)
    const row = stepGroup('Device Rollout')
    fireEvent.change(composer('Device Rollout'), { target: { value: 'Confirm power backup' } })
    fireEvent.click(within(checklist('Device Rollout')).getByRole('button', { name: 'Add' }))

    await waitFor(() => {
      const item = getDb().obJourneyTemplateStepItems.find((i) => i.label === 'Confirm power backup')
      expect(item?.mandatory).toBe(true)
    }, SLOW)

    fireEvent.click(
      within(row).getByRole('button', {
        name: 'Remove Confirm device count against the purchase order',
      }),
    )
    await waitFor(() => {
      expect(
        getDb().obJourneyTemplateStepItems.some(
          (i) => i.label === 'Confirm device count against the purchase order',
        ),
      ).toBe(false)
    }, SLOW)
  })

  it('writes a document to the document route when the type says so', async () => {
    await openDesigner(2)
    const row = stepGroup('Device Rollout')
    fireEvent.change(composer('Device Rollout'), { target: { value: 'Insurance certificate' } })
    fireEvent.change(
      within(checklist('Device Rollout')).getByLabelText(
        'Type of the new checklist item for Device Rollout',
      ),
      { target: { value: 'document' } },
    )
    fireEvent.click(within(checklist('Device Rollout')).getByRole('button', { name: 'Add' }))

    await waitFor(() => {
      const doc = getDb().obJourneyTemplateStepDocs.find((d) => d.label === 'Insurance certificate')
      expect(doc?.required).toBe(true)
    }, SLOW)
    // And nothing was written to the other collection under the same name.
    expect(
      getDb().obJourneyTemplateStepItems.some((i) => i.label === 'Insurance certificate'),
    ).toBe(false)

    fireEvent.click(within(row).getByRole('button', { name: 'Remove Device delivery challan' }))
    await waitFor(() => {
      expect(
        getDb().obJourneyTemplateStepDocs.some((d) => d.label === 'Device delivery challan'),
      ).toBe(false)
    }, SLOW)
  })

  /*
    The reason the grid exists rather than a dialog. A checklist is written
    several items at a time, so Enter has to file one and leave the composer
    ready for the next — same type, same gate, empty label, cursor back in it.
    Re-picking "Check · Required" between items is exactly what the old pair
    of full-width composers charged for.
  */
  it('files an item on Enter and leaves the composer ready for the next one', async () => {
    await openDesigner(2)
    const kind = within(checklist('Device Rollout')).getByLabelText(
      'Type of the new checklist item for Device Rollout',
    )
    fireEvent.change(kind, { target: { value: 'document' } })
    fireEvent.change(composer('Device Rollout'), { target: { value: 'Signed handover note' } })
    fireEvent.keyDown(composer('Device Rollout'), { key: 'Enter' })

    await waitFor(() => {
      expect(
        getDb().obJourneyTemplateStepDocs.some((d) => d.label === 'Signed handover note'),
      ).toBe(true)
    }, SLOW)

    await waitFor(() => {
      expect((composer('Device Rollout') as HTMLInputElement).value).toBe('')
    }, SLOW)
    expect(composer('Device Rollout')).toHaveFocus()
    expect(
      (
        within(checklist('Device Rollout')).getByLabelText(
          'Type of the new checklist item for Device Rollout',
        ) as HTMLSelectElement
      ).value,
    ).toBe('document')
  })

  /*
    The checklist is inside the card of the task it belongs to, not a row
    somewhere under it. That containment is what the whole layout rests on —
    stage contains task contains checklist — and it is the one relationship a
    reader can be wrong about without the screen looking broken.
  */
  it('sits inside the card of the task it belongs to', async () => {
    await openDesigner(2)
    expect(stepGroup('Device Rollout')).toContainElement(checklist('Device Rollout'))
    // And a task's own checklist is not its parent's: Attendance Policy
    // Mapping is nested inside Device Rollout, and has one of its own.
    expect(stepGroup('Attendance Policy Mapping')).toContainElement(
      checklist('Attendance Policy Mapping'),
    )
    expect(stepGroup('Attendance Policy Mapping')).not.toContainElement(
      checklist('Device Rollout'),
    )
  })
})

/*
  The Parallel groups panel is off this screen.

  It listed `parallelGroups` layer by layer — "these three could all be in
  progress at once" — which the board now says in the shape it is drawn in: a
  card at a stage's top level is parallel, and a card inside another waits for
  it. The panel was the readable form of a dependency column that no longer
  exists, and a second place to read one fact is a second place for it to go
  stale. The computed field itself is untouched; `ObJourneyTemplateService`
  still returns it, and the client's own journey page still reads it.
*/
it('does not draw a Parallel groups panel', async () => {
  await openDesigner(1)
  expect(screen.queryByRole('region', { name: 'Parallel groups' })).toBeNull()
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

  /**
   * Publishing is gated on tasks, not on stages — a service whose six stages
   * are all empty describes no work, so there is nothing to activate.
   *
   * The stages themselves stay on screen throughout, which is the part worth
   * asserting: emptying a draft of tasks must not empty it of the structure
   * you refill it through.
   */
  it('is disabled once a draft has no tasks left, with the stages still drawn', async () => {
    await openDesigner(2)
    fireEvent.click(within(stepGroup('Attendance Policy Mapping')).getByRole('button', { name: 'Remove Attendance Policy Mapping' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual(['Device Rollout']), SLOW)
    fireEvent.click(within(stepGroup('Device Rollout')).getByRole('button', { name: 'Remove Device Rollout' }))
    await waitFor(() => expect(savedStepNames(2)).toEqual([]), SLOW)

    await waitFor(() => expect(displayedStepNames()).toHaveLength(0), SLOW)
    expect(displayedStageNames().length).toBeGreaterThan(0)
    expect(screen.queryByText('No implementation stages on this Module Service')).toBeNull()
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
      sequence: 1, dependsOnTemplateIds: [], publishedBy: null, publishedAt: null,
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

  /**
   * The reason this screen exists: a service 49 clients are on is exactly the
   * one worth being able to correct. The server renames the chain and
   * re-stamps the `service_name` those journeys denormalise, in one
   * transaction, so nothing is left resolving the old one.
   */
  it('still renames a service clients are on', async () => {
    boardAClientOn(1)
    await openService(1, 'ERP Suite onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit ERP Suite onboarding' }, SLOW)
    fireEvent.change(within(form).getByLabelText('Name'), {
      target: { value: 'ERP Suite implementation' },
    })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((tpl) => tpl.id === 1)!.name)
        .toBe('ERP Suite implementation')
    }, SLOW)
  })

  it('disables Delete, and says how many clients are on it', async () => {
    boardAClientOn(1)
    await openService(1, 'ERP Suite onboarding')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Delete ERP Suite onboarding' })).toBeDisabled()
    }, SLOW)
    // Disabled *and* explained. A tooltip alone is invisible to a keyboard
    // user tabbing past a disabled control.
    expect(screen.getByText(/In use by 1 client journey —/)).toBeInTheDocument()
  })

  /**
   * The product moves too. What does *not* move is the journeys' own
   * `productId` — half of `fk_ob_journeys_application`, the client's own
   * purchase — so the form says so rather than leaving an admin to discover it
   * on a client detail page a week later.
   */
  it('re-files the service under another product, leaving the journeys where they were bought',
    async () => {
      boardAClientOn(3)
      await openService(3, 'LMS onboarding')

      fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
      const form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
      const product = within(form).getByLabelText('Product')
      expect(within(form).getByLabelText('Name')).toBeEnabled()
      expect(product).toBeEnabled()
      expect(within(form).getByText(/stay under the product their client bought/))
        .toBeInTheDocument()

      fireEvent.change(product, { target: { value: '2' } })
      fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

      await waitFor(() => {
        expect(getDb().obJourneyTemplates.find((tpl) => tpl.id === 3)!.productId).toBe(2)
      }, SLOW)
    })

  it('locks the service through a retired version, not only the head', async () => {
    getDb().obJourneyTemplates.push({
      id: 97, productId: 3, name: 'LMS onboarding', version: 2, isActive: false,
      sequence: 3, dependsOnTemplateIds: [], publishedBy: null, publishedAt: null,
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

/**
 * C-124 · Edit details grew two more fields once name and product stopped
 * being the only writable facts on an unused service — the catalogue's own
 * position and its cross-service dependency, both already editable from the
 * OB-07 card, now reachable from the same form as the rename.
 *
 * Fixture note: template 1 (ERP, product 1) is active at sequence 1; template
 * 4 (Enterprise, product 1) is active at sequence 2 and depends on template 1;
 * template 3 (LMS, product 3) is active at sequence 3 and also depends on
 * template 1. Template 2 (Biometric Attendance, product 2) is the one draft.
 */
describe('editing a module service — position and dependency', () => {
  it('offers Position and Depends on for the active version', async () => {
    await openService(1, 'ERP Suite onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit ERP Suite onboarding' }, SLOW)

    expect(within(form).getByLabelText('Position in catalogue')).toBeInTheDocument()
    expect(
      within(form).getByRole('button', { name: /Services ERP Suite onboarding depends on/ }),
    ).toBeInTheDocument()
  })

  it('hides them for a draft, explaining why instead', async () => {
    await openService(2, 'Biometric Attendance onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit Biometric Attendance onboarding' }, SLOW)

    expect(within(form).queryByLabelText('Position in catalogue')).not.toBeInTheDocument()
    expect(
      within(form).queryByRole('button', { name: /depends on/ }),
    ).not.toBeInTheDocument()
    expect(within(form).getByText(/apply only to this service's active version/)).toBeInTheDocument()
  })

  it('excludes candidates that already depend on this service, directly or transitively', async () => {
    // ERP Suite (1) is depended on by both Enterprise (4) and LMS (3) — either
    // one becoming its dependency would close a cycle, so neither may appear.
    await openService(1, 'ERP Suite onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit ERP Suite onboarding' }, SLOW)
    fireEvent.click(
      within(form).getByRole('button', { name: /Services ERP Suite onboarding depends on/ }),
    )

    expect(screen.queryByRole('option', { name: 'Enterprise (data migration)' }))
      .not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'LMS onboarding' })).not.toBeInTheDocument()
  })

  it('moves the service to a new position in the catalogue', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    fireEvent.change(within(form).getByLabelText('Position in catalogue'), { target: { value: '1' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      const active = getDb()
        .obJourneyTemplates.filter((t) => t.isActive)
        .sort((a, b) => a.sequence - b.sequence)
        .map((t) => t.id)
      expect(active).toEqual([3, 1, 4])
    }, SLOW)
  })

  /** Ticks one option of the form's multi-select "Depends on" picker. */
  async function tickDependency(form: HTMLElement, serviceName: string) {
    fireEvent.click(
      within(form).getByRole('button', { name: /Services .* depends on/ }),
    )
    fireEvent.click(await screen.findByRole('option', { name: serviceName }, SLOW))
  }

  it('adds a second dependency, keeping the one already held', async () => {
    // LMS already waits for ERP Suite (template 1). Ticking Enterprise must
    // add to that set, not replace it — the whole point of the multi-select,
    // and the assertion a single-select would still pass without.
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    await tickDependency(form, 'Enterprise (data migration)')
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 3)!.dependsOnTemplateIds)
        .toEqual([1, 4])
    }, SLOW)
  })

  it('unticking the last dependency clears it back to parallel', async () => {
    await openService(4, 'Enterprise (data migration)')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit Enterprise (data migration)' }, SLOW)
    await tickDependency(form, 'ERP Suite onboarding')
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 4)!.dependsOnTemplateIds).toEqual([])
    }, SLOW)
  })

  it('renaming and repositioning in the same save both land', async () => {
    await openService(3, 'LMS onboarding')

    fireEvent.click(await screen.findByRole('button', { name: '✎ Edit details' }, SLOW))
    const form = await screen.findByRole('form', { name: 'Edit LMS onboarding' }, SLOW)
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'LMS rollout' } })
    fireEvent.change(within(form).getByLabelText('Position in catalogue'), { target: { value: '1' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      const db = getDb()
      expect(db.obJourneyTemplates.find((t) => t.id === 3)!.name).toBe('LMS rollout')
      const active = db.obJourneyTemplates
        .filter((t) => t.isActive)
        .sort((a, b) => a.sequence - b.sequence)
        .map((t) => t.id)
      expect(active).toEqual([3, 1, 4])
    }, SLOW)
  })
})
