import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { ObProjectStage } from '@/api/generated/model/obProjectStage'
import type { UserRef } from '@/api/generated/model/userRef'

import { ObStepTimeline } from './ObStepTimeline'
import type { TreeService, TreeStage } from './projectTree'
import type { ProjectTask } from './useProjectTasks'
import type { ObViewerScope } from './viewerScope'

/**
 * One module's timeline: its Steps down the page, and the task rows under each.
 *
 * <p>Nothing here mutates — the task's own panel lives in the popup and is
 * tested in `ObProjectTaskPanel.test.tsx` — so no query client is needed.
 */

const ME = 41
const COLLEAGUE = 42

const USERS: UserRef[] = [
  { id: ME, displayName: 'Vikram Mehta' },
  { id: COLLEAGUE, displayName: 'Priya Nair' },
]
const nameOf = (id: number) => USERS.find((u) => u.id === id)?.displayName ?? null

const ALL: ObViewerScope = { kind: 'ALL', meId: ME, showsBreakdown: true }
const MINE: ObViewerScope = { kind: 'IMPLEMENTOR', meId: ME, showsBreakdown: false }

function stage(stageKey: number, name: string, sequence: number): ObProjectStage {
  return { stageKey, name, sequence, taskCount: 1, tasksOutstanding: 1, isComplete: false, isCurrent: false }
}

function task(over: Partial<ProjectTask> = {}): ProjectTask {
  return {
    id: 900,
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: 'Student Dataport',
    status: 'IN_PROGRESS',
    ownerUserId: ME,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: true,
    dueAt: '2026-09-15T13:00:00Z',
    tatUsedPercent: 0,
    stageKey: 7,
    stageName: 'Data Migration',
    items: [
      { id: 1, stepId: 900, sequence: 1, label: 'Data Sanitization', isMandatory: true, isDone: true, answer: true, remark: null },
      { id: 2, stepId: 900, sequence: 2, label: 'Master Data verification', isMandatory: true, isDone: false, answer: null, remark: null },
    ],
    docs: [],
    ...over,
  } as ProjectTask
}

function step(s: ObProjectStage, tasks: ProjectTask[]): TreeStage {
  return {
    stage: s,
    tasks,
    settled: tasks.filter((t) => t.status === 'DONE' || t.status === 'SKIPPED').length,
    hasMine: tasks.some((t) => t.ownerUserId === ME),
  }
}

function service(stages: TreeStage[], hiddenStageCount = 0): TreeService {
  const tasks = stages.flatMap((s) => s.tasks)
  return {
    service: {
      journeyId: 500,
      templateId: 9,
      serviceName: 'SIS',
      gateStatus: 'OPEN',
      isComplete: false,
      stages: stages.map((s) => s.stage),
    },
    stages,
    tasks,
    allTasks: tasks,
    taskCount: tasks.length,
    settled: stages.reduce((n, s) => n + s.settled, 0),
    stagesComplete: stages.filter((s) => s.tasks.length > 0 && s.settled === s.tasks.length).length,
    stageCount: stages.length,
    hiddenStageCount,
    totalTatDays: tasks.reduce((n, t) => n + t.tatDays, 0),
    hasMine: stages.some((s) => s.hasMine),
  }
}

/** Far enough out that no run of this suite reads it as overdue. */
const FAR_OFF = '2999-01-01T09:00:00Z'

const ONE_STEP = service([step(stage(7, 'Data Migration', 3), [task()])])

/** A finished Step and a running one — what the two positions differ over. */
const TWO_STEPS = service([
  step(stage(1, 'Configuration', 1), [
    task({ id: 1, name: 'Admission No Scheme', status: 'DONE', stageKey: 1 }),
  ]),
  step(stage(7, 'Data Migration', 3), [task()]),
])

function renderTimeline(
  node: TreeService = ONE_STEP,
  over: Partial<React.ComponentProps<typeof ObStepTimeline>> = {},
) {
  return render(
    <ObStepTimeline
      node={node}
      scope={ALL}
      nameOf={nameOf}
      users={USERS}
      selectedTaskId={null}
      onSelectTask={vi.fn()}
      filter="PENDING"
      onFilterChange={vi.fn()}
      {...over}
    />,
  )
}

const row = (name: string) => screen.getByRole('button', { name: new RegExp(name) })

describe('a Step on the timeline', () => {
  /**
   * The same facts the old ribbon segment carried, at a size a reader can act
   * on: name, state, the review figures, who is on it, TAT, due.
   */
  it('names the Step, its state, its review figures, who is on it, the TAT and the due date', () => {
    renderTimeline()

    const item = screen.getByTestId('ob-timeline-step')
    expect(within(item).getByRole('heading', { name: 'Data Migration' })).toBeInTheDocument()
    // The state is a ring, and the ring is named — never colour alone.
    expect(within(item).getByTestId('ob-step-dot')).toHaveAccessibleName('Running')
    // Three figures, no words — the words are the group's accessible name.
    expect(within(item).getByTestId('ob-step-review-counts')).toHaveAccessibleName(
      '0 verified, 0 rejected, 1 in progress',
    )
    expect(within(item).getByText('Vikram Mehta')).toBeInTheDocument()
    expect(within(item).getByText('1d')).toBeInTheDocument()
    // The due date, on the Step header and again on the task row under it.
    expect(within(item).getAllByText(/Sep/)).toHaveLength(2)
  })

  /**
   * The figure lives in the circle, not beside it.
   *
   * <p>Asserted on the chip rather than on the group's name, because the name
   * was already right when the digit sat outside: a ring and a number side by
   * side read out identically to a number inside a ring. What changed is the
   * pairing a sighted reader has to do, and only the markup says whether it is
   * done for them.
   */
  it('puts each review figure inside its own circle', () => {
    renderTimeline()

    const tally = screen.getByTestId('ob-step-review-counts')
    const chips = within(tally).getAllByTestId('ob-status-count')

    expect(chips).toHaveLength(3)
    expect(chips.map((c) => c.getAttribute('data-bucket'))).toEqual([
      'verified',
      'rejected',
      'inProgress',
    ])
    // The count is the chip's own text, so it cannot drift from the hue.
    expect(chips.map((c) => c.textContent)).toEqual(['0', '0', '1'])
    // And each still names its bucket for anybody hovering or listening.
    expect(chips[2]).toHaveAttribute('title', '1 in progress')
  })

  /** Steps are numbered, so the tasks under one take letters. */
  it('letters the tasks under a Step — a, b, c', () => {
    const two = service([
      step(stage(7, 'Data Migration', 3), [
        task({ id: 900, name: 'Student Dataport' }),
        task({ id: 901, name: 'Student Group' }),
      ]),
    ])
    renderTimeline(two)

    expect(within(row('Student Dataport')).getByText('a.')).toBeInTheDocument()
    expect(within(row('Student Group')).getByText('b.')).toBeInTheDocument()
  })

  it('draws every Step in order, each disclosing its own tasks', () => {
    renderTimeline(TWO_STEPS, { filter: 'ALL' })

    const items = screen.getAllByTestId('ob-timeline-step')
    expect(items).toHaveLength(2)
    // The first Step is the one that opens, so its task is on the page.
    expect(within(items[0]).getByText('Admission No Scheme')).toBeInTheDocument()
    expect(within(items[0]).getByTestId('ob-step-review-counts')).toHaveAccessibleName(
      '1 verified, 0 rejected, 0 in progress',
    )
    // A finished Step wears a tick, not its number.
    expect(within(items[0]).getByTestId('ob-step-bead')).toHaveAttribute('data-state', 'complete')
    expect(within(items[0]).getByTestId('ob-step-dot')).toHaveAccessibleName('Complete')
    expect(within(items[1]).getByTestId('ob-step-bead')).toHaveTextContent('3')
    expect(within(items[1]).getByTestId('ob-step-dot')).toHaveAccessibleName('Running')

    // The second is closed, and opens on its own chevron.
    expect(screen.queryByText('Student Dataport')).not.toBeInTheDocument()
    fireEvent.click(within(items[1]).getByTestId('ob-step-disclosure'))
    expect(within(items[1]).getByText('Student Dataport')).toBeInTheDocument()
  })

  it('says when nobody at all is responsible on the Step', () => {
    renderTimeline(service([step(stage(7, 'Data Migration', 3), [task({ ownerUserId: null })])]))

    expect(screen.getByText('Nobody responsible')).toBeInTheDocument()
  })
})

describe('the task row', () => {
  it('carries the status, sign-off, answered count, TAT and due date', () => {
    renderTimeline()

    const strip = row('Student Dataport')
    // The status is a dot, not a word — see `TaskStatusDot`. This one is a
    // running task whose due date has passed, so the clock wins.
    expect(within(strip).getByTestId('ob-task-dot')).toHaveAttribute('data-state', 'OVERDUE')
    expect(within(strip).queryByText('In progress')).not.toBeInTheDocument()
    expect(within(strip).getByText('Sign-off')).toBeInTheDocument()
    expect(within(strip).getByText('☑ 1/2')).toBeInTheDocument()
    expect(within(strip).getByText('1 wd')).toBeInTheDocument()
    expect(within(strip).getByText(/Sep/)).toBeInTheDocument()
  })

  /**
   * Four hues in a fixed column, each one named: a reader scanning ten rows
   * for the two that need them, and never colour alone (blueprint §12.1).
   */
  it('draws one coloured circle per state, each carrying its name', () => {
    const four = service([
      step(stage(7, 'Data Migration', 3), [
        task({ id: 1, name: 'Nobody has started this', status: 'PENDING', dueAt: FAR_OFF, items: [] }),
        task({ id: 2, name: 'Somebody is on this', status: 'IN_PROGRESS', dueAt: FAR_OFF }),
        task({ id: 3, name: 'This one is late', status: 'IN_PROGRESS', dueAt: '2020-01-01T09:00:00Z' }),
        task({ id: 4, name: 'This one is finished', status: 'DONE', dueAt: FAR_OFF }),
      ]),
    ])
    renderTimeline(four, { filter: 'ALL' })

    const dots = screen.getAllByTestId('ob-task-dot')
    expect(dots.map((d) => d.getAttribute('data-state'))).toEqual([
      'PENDING',
      'IN_PROCESS',
      'OVERDUE',
      'COMPLETED',
    ])
    expect(dots.map((d) => d.getAttribute('aria-label'))).toEqual([
      'Pending',
      'In process',
      'Overdue',
      'Completed',
    ])
  })

  /**
   * The Step header names who is on it; a service with one implementor
   * repeated that name on every row and said nothing new. It is in the popup.
   */
  it('does not carry the implementor', () => {
    renderTimeline()

    expect(within(row('Student Dataport')).queryByText('Vikram Mehta')).not.toBeInTheDocument()
  })

  it('reports a task nobody is responsible for, and one that is escalated', () => {
    renderTimeline(service([step(stage(7, 'Data Migration', 3), [task({ ownerUserId: null })])]), {
      escalations: new Map([[900, { id: 1, raisedBy: 'Client', raisedAt: '2026-09-15T13:00:00Z', note: 'x' }]]),
    })

    const strip = row('Student Dataport')
    expect(within(strip).getByText('Unassigned')).toBeInTheDocument()
    expect(within(strip).getByText(/Escalated/)).toBeInTheDocument()
  })

  it('opens the task as a dialog rather than in place', () => {
    const onSelectTask = vi.fn()
    renderTimeline(ONE_STEP, { onSelectTask })

    const strip = row('Student Dataport')
    expect(strip).toHaveAttribute('aria-haspopup', 'dialog')
    expect(strip).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(strip)

    expect(onSelectTask).toHaveBeenCalledWith(900)
    expect(screen.queryByText('Master Data verification')).not.toBeInTheDocument()
  })

  it('marks the row whose task is open', () => {
    renderTimeline(ONE_STEP, { selectedTaskId: 900 })

    expect(row('Student Dataport')).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('the Steps are accordions', () => {
  /**
   * A reader lands on a task rather than on a list of headings — the point of
   * the whole control. The default master publishes seven Steps, so opening
   * every one of them would put the Step somebody wanted four screens down.
   */
  it('opens the first Step and leaves the rest closed', () => {
    renderTimeline(TWO_STEPS, { filter: 'ALL' })

    const [first, second] = screen.getAllByTestId('ob-step-disclosure')
    expect(first).toHaveAttribute('aria-expanded', 'true')
    expect(second).toHaveAttribute('aria-expanded', 'false')
  })

  it('opens a Step on its own header, and closes it again', () => {
    renderTimeline(TWO_STEPS, { filter: 'ALL' })

    const second = screen.getAllByTestId('ob-step-disclosure')[1]
    fireEvent.click(second)
    expect(second).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Student Dataport')).toBeInTheDocument()

    fireEvent.click(second)
    expect(second).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Student Dataport')).not.toBeInTheDocument()
  })

  /** Steps do not close each other — two open Steps is a reader comparing them. */
  it('leaves the Steps independent of one another', () => {
    renderTimeline(TWO_STEPS, { filter: 'ALL' })

    fireEvent.click(screen.getAllByTestId('ob-step-disclosure')[1])

    expect(screen.getByText('Admission No Scheme')).toBeInTheDocument()
    expect(screen.getByText('Student Dataport')).toBeInTheDocument()
  })

  /**
   * A `?task=` link names one task and the workspace scrolls to its row a
   * frame later — a row inside a closed accordion is not there to scroll to.
   */
  it('opens the Step holding the task the page was asked to reveal', () => {
    renderTimeline(TWO_STEPS, { filter: 'ALL', selectedTaskId: 900 })

    const [first, second] = screen.getAllByTestId('ob-step-disclosure')
    expect(second).toHaveAttribute('aria-expanded', 'true')
    // Without closing the one that opened on arrival.
    expect(first).toHaveAttribute('aria-expanded', 'true')
    expect(row('Student Dataport')).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('the Pending / Show all switch', () => {
  /** What a reader opening their own project came to see. */
  it('opens on Pending, with the finished Step left out', () => {
    renderTimeline(TWO_STEPS)

    expect(screen.getByRole('button', { name: 'Pending' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Show all' })).toHaveAttribute('aria-pressed', 'false')

    const items = screen.getAllByTestId('ob-timeline-step')
    expect(items).toHaveLength(1)
    expect(within(items[0]).getByRole('heading', { name: 'Data Migration' })).toBeInTheDocument()
    // And the first outstanding Step is the one that opens.
    expect(screen.getByText('Student Dataport')).toBeInTheDocument()
  })

  /** A filter that cannot account for what it removed is a lost-work bug. */
  it('says how many finished Steps it is holding back', () => {
    renderTimeline(TWO_STEPS)

    expect(
      screen.getByText('Outstanding tasks only — 1 finished Step is hidden.'),
    ).toBeInTheDocument()
  })

  it('says so inside a Step that is holding a finished task back', () => {
    const mixed = service([
      step(stage(7, 'Data Migration', 3), [
        task({ id: 900, name: 'Student Dataport' }),
        task({ id: 901, name: 'Student Group', status: 'DONE' }),
      ]),
    ])
    renderTimeline(mixed)

    expect(screen.getByText('Student Dataport')).toBeInTheDocument()
    expect(screen.queryByText('Student Group')).not.toBeInTheDocument()
    expect(screen.getByText(/1 finished task is hidden/)).toBeInTheDocument()
    // The Step's own figures are the Step's, not the filter's.
    expect(screen.getByTestId('ob-step-review-counts')).toHaveAccessibleName(
      '1 verified, 0 rejected, 1 in progress',
    )
  })

  it('asks for everything when Show all is pressed', () => {
    const onFilterChange = vi.fn()
    renderTimeline(TWO_STEPS, { onFilterChange })

    fireEvent.click(screen.getByRole('button', { name: 'Show all' }))

    expect(onFilterChange).toHaveBeenCalledWith('ALL')
  })

  it('draws the finished work once it is asked for', () => {
    renderTimeline(TWO_STEPS, { filter: 'ALL' })

    expect(screen.getAllByTestId('ob-timeline-step')).toHaveLength(2)
    expect(screen.getByText('Admission No Scheme')).toBeInTheDocument()
    expect(
      screen.getByText('Every task of this service, finished and outstanding.'),
    ).toBeInTheDocument()
  })

  it('says when a service has nothing outstanding at all', () => {
    const done = service([
      step(stage(1, 'Configuration', 1), [task({ id: 1, status: 'DONE', stageKey: 1 })]),
    ])
    renderTimeline(done)

    expect(screen.getByText(/every task of this service is finished/i)).toBeInTheDocument()
    expect(screen.queryAllByTestId('ob-timeline-step')).toHaveLength(0)
  })
})

describe('an implementor', () => {
  /**
   * Stated, never offered: the page is scoped to the reader's own work, and
   * the switch above it chooses between their outstanding tasks and all of
   * theirs rather than between their work and everybody's. "My Step is waiting
   * on Step 2" at least has a Step 2 to ask about.
   */
  it('is told how many Steps of the service are somebody else’s', () => {
    renderTimeline(service([step(stage(7, 'Data Migration', 3), [task()])], 2), { scope: MINE })

    expect(
      screen.getByText('2 more Steps of this service are somebody else’s.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /show all steps/i })).not.toBeInTheDocument()
  })

  it('is told when no Step holds a task of theirs', () => {
    renderTimeline(service([], 3), { scope: MINE })

    expect(screen.getByText(/no Step of this service holds a task of yours/i)).toBeInTheDocument()
    expect(
      screen.getByText('3 more Steps of this service are somebody else’s.'),
    ).toBeInTheDocument()
  })

  it('says nothing about other people’s Steps on an unfiltered page', () => {
    renderTimeline(service([step(stage(7, 'Data Migration', 3), [task()])], 2))

    expect(screen.queryByText(/somebody else’s/)).not.toBeInTheDocument()
  })
})
