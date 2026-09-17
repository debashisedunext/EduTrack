import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import { ObModuleStrip } from './ObModuleStrip'
import type { TreeService, TreeStage } from './projectTree'
import { formatDay } from './taskDates'
import type { ProjectTask } from './useProjectTasks'
import type { ObViewerScope } from './viewerScope'

/**
 * The Module strip's one line, and the popover a name opens.
 *
 * <p>The figures themselves are `moduleStripStats`' and are tested there. What
 * is asserted here is who sees which of them, which names are controls, and
 * that the strip never grows a second row to say any of it.
 */

const PRIYA = 41
const ARJUN = 42
const ZOYA = 43
const DEV = 44
const RAVI = 45

const NAMES: Record<number, string> = {
  [PRIYA]: 'Priya Nair',
  [ARJUN]: 'Arjun Mehta',
  [ZOYA]: 'Zoya Khan',
  [DEV]: 'Dev Prasad',
  [RAVI]: 'Ravi Iyer',
}
const nameOf = (id: number) => NAMES[id] ?? null

const ADMIN: ObViewerScope = { kind: 'ALL', meId: PRIYA, showsBreakdown: true }
const SALES: ObViewerScope = { kind: 'ALL', meId: PRIYA, showsBreakdown: false }
const MINE: ObViewerScope = {
  kind: 'IMPLEMENTOR',
  meId: PRIYA,
  showsBreakdown: false,
}

function task(over: Partial<ProjectTask> & Pick<ProjectTask, 'id'>): ProjectTask {
  return {
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: `Task ${over.id}`,
    status: 'PENDING',
    ownerUserId: PRIYA,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: 1,
    stageName: 'Configuration',
    items: [],
    docs: [],
    ...over,
  }
}

const stage: ObProjectStage = {
  stageKey: 1,
  name: 'Configuration',
  sequence: 1,
  taskCount: 4,
  tasksOutstanding: 3,
  isComplete: false,
  isCurrent: true,
}

const TASKS = [
  task({ id: 1, status: 'DONE' }),
  task({ id: 2, status: 'IN_PROGRESS' }),
  task({ id: 3, ownerUserId: ARJUN }),
  task({ id: 4, ownerUserId: ARJUN }),
]

function node(
  tasks: ProjectTask[] = TASKS,
  /* The whole service's, whatever `tasks` was narrowed to — see `TreeService.totalTatDays`. */
  totalTatDays: number = TASKS.reduce((sum, t) => sum + t.tatDays, 0),
  /* Likewise: the service's whole list, which the dates and the status read. */
  allTasks: ProjectTask[] = TASKS,
): TreeService {
  const step: TreeStage = {
    stage,
    tasks,
    settled: tasks.filter((t) => t.status === 'DONE' || t.status === 'SKIPPED').length,
    hasMine: tasks.some((t) => t.ownerUserId === PRIYA),
  }
  return {
    service: {
      journeyId: 500,
      templateId: 9,
      serviceName: 'SIS',
      gateStatus: 'OPEN',
      isComplete: false,
      stages: [stage],
    },
    stages: [step],
    tasks,
    // The whole service's, whatever `tasks` was narrowed to — the unscoped
    // figures (TAT, the two dates, the status) are folded from this one.
    allTasks,
    taskCount: tasks.length,
    settled: step.settled,
    stagesComplete: 0,
    stageCount: 1,
    hiddenStageCount: 0,
    totalTatDays,
    hasMine: step.hasMine,
  }
}

function renderStrip(
  scope: ObViewerScope,
  tasks: ProjectTask[] = TASKS,
  totalTatDays?: number,
  allTasks: ProjectTask[] = tasks,
) {
  return render(
    <ObModuleStrip
      node={node(tasks, totalTatDays, allTasks)}
      scope={scope}
      nameOf={nameOf}
      isOpen={false}
      onToggle={vi.fn()}
      panelId="ob-service-500"
    />,
  )
}

describe('the module strip', () => {
  it('counts the whole service for somebody reading the whole project', () => {
    renderStrip(ADMIN)

    const counts = screen.getByTestId('ob-strip-counts')
    expect(counts).toHaveTextContent('2 pending')
    expect(counts).toHaveTextContent('1 partial')
    expect(counts).toHaveTextContent('1 completed')
    expect(screen.getByText('of 4')).toBeInTheDocument()
    expect(screen.getByText('25%')).toBeInTheDocument()
  })

  /**
   * The same strip means two things depending on who opened it, so it says
   * which. An unlabelled 50% beside an unlabelled 25% on two people's screens
   * is the most misleading thing this page could do.
   */
  it('counts only the reader’s own work, over their own denominator', () => {
    renderStrip(
      MINE,
      TASKS.filter((t) => t.ownerUserId === PRIYA),
    )

    expect(screen.getByText('of your 2')).toBeInTheDocument()
    expect(screen.getByText('50%')).toBeInTheDocument()
    expect(screen.getByText('Priya Nair')).toBeInTheDocument()
  })

  it('says so rather than showing 0% when a reader owns nothing here', () => {
    renderStrip(MINE, [])

    expect(screen.getByText(/no tasks assigned to you in this service/i)).toBeInTheDocument()
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })

  /**
   * The row was three: the service, a stat line, and a `By implementor (3)`
   * accordion. Nine rows on a three-service project, before the first Step.
   */
  it('says all of it on one row, with no second strip under it', () => {
    renderStrip(ADMIN)

    expect(screen.queryByTestId('ob-strip-breakdown-toggle')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ob-strip-breakdown-row')).not.toBeInTheDocument()
    // The names took the implementor count's place rather than joining it.
    expect(screen.queryByText(/implementors?$/)).not.toBeInTheDocument()
    expect(screen.getByTestId('ob-strip-names')).toHaveTextContent('Arjun Mehta')
    expect(screen.getByTestId('ob-strip-names')).toHaveTextContent('Priya Nair')
  })

  /**
   * The service's TAT is the schedule, not the reader's share of it. The
   * counts on the same row shrink in the filtered view; this figure does not,
   * because it is the one under which the strips add up to the header's
   * Total TAT.
   */
  describe('the service’s TAT', () => {
    it('sums every task’s pinned TAT for somebody reading the whole project', () => {
      renderStrip(ADMIN)

      expect(screen.getByTestId('ob-strip-tat')).toHaveTextContent('TAT 4d')
    })

    it('stays the whole service’s figure in the reader’s own filtered view', () => {
      // The reader's two tasks, on a service whose four tasks total four days.
      renderStrip(MINE, [TASKS[0], TASKS[1]])

      expect(screen.getByText(/of your 2/)).toBeInTheDocument()
      expect(screen.getByTestId('ob-strip-tat')).toHaveTextContent('TAT 4d')
    })
  })

  describe('a name', () => {
    it('opens that person’s own figures, over their own denominator', async () => {
      const user = userEvent.setup()
      renderStrip(ADMIN)

      await user.click(screen.getByRole('button', { name: 'Arjun Mehta' }))

      const pop = await screen.findByTestId('ob-implementor-popover')
      expect(within(pop).getByText('Arjun Mehta')).toBeInTheDocument()
      // Arjun holds two untouched tasks; the strip above says 2 pending of 4.
      expect(pop).toHaveTextContent('Pending')
      expect(pop).toHaveTextContent('of 2 tasks')
      expect(pop).toHaveTextContent('0%')
      // And it names the service, because one person carries different figures
      // on each of a project's services.
      expect(within(pop).getByText('SIS')).toBeInTheDocument()
    })

    /**
     * With one implementor the strip's figures already are that person's, and a
     * popover repeating them teaches a reader the control is not worth pressing.
     */
    it('is plain text when this service has only one implementor', () => {
      renderStrip(
        ADMIN,
        TASKS.filter((t) => t.ownerUserId === PRIYA),
      )

      expect(screen.getByTestId('ob-strip-names')).toHaveTextContent('Priya Nair')
      expect(screen.queryByRole('button', { name: 'Priya Nair' })).not.toBeInTheDocument()
    })

    it('is plain text in the reader’s own filtered view', () => {
      renderStrip(
        MINE,
        TASKS.filter((t) => t.ownerUserId === PRIYA),
      )

      expect(screen.queryByRole('button', { name: 'Priya Nair' })).not.toBeInTheDocument()
    })

    /**
     * A salesperson chasing a project needs to know who is on it. The workload
     * split is a management reading with a screen of its own in the dashboard.
     */
    it('is plain text for sales, a viewer, or a session with no module role', () => {
      renderStrip(SALES)

      expect(screen.getByTestId('ob-strip-names')).toHaveTextContent('Arjun Mehta')
      expect(screen.queryByRole('button', { name: 'Arjun Mehta' })).not.toBeInTheDocument()
      // They still get the aggregate.
      expect(screen.getByText('of 4')).toBeInTheDocument()
    })

    it('names the Unassigned bucket rather than dropping it', async () => {
      const user = userEvent.setup()
      renderStrip(ADMIN, [...TASKS, task({ id: 5, ownerUserId: null })])

      // Last, after the people.
      expect(screen.getByTestId('ob-strip-names')).toHaveTextContent('Unassigned')

      await user.click(screen.getByRole('button', { name: 'Unassigned' }))
      expect(await screen.findByTestId('ob-implementor-popover')).toHaveTextContent('of 1 task')
    })
  })

  /**
   * The service's plan, under its name: how long it was given, when it started
   * and when it should finish. All three are the whole service's — the counts
   * beside them shrink to the reader's share, these do not, because a schedule
   * is not a share of anything.
   *
   * <p>The dates themselves are `serviceDates`' and are chosen there. What is
   * asserted here is that the row prints them, and prints the service's rather
   * than the reader's.
   */
  describe('the service’s dates', () => {
    const DAY = 86_400_000
    const START = new Date(Date.now() - 12 * DAY).toISOString()
    const LATER_START = new Date(Date.now() - 3 * DAY).toISOString()
    const DUE = new Date(Date.now() + 9 * DAY).toISOString()
    const EARLIER_DUE = new Date(Date.now() + 2 * DAY).toISOString()

    const SCHEDULED = [
      task({ id: 1, startedAt: LATER_START, dueAt: EARLIER_DUE }),
      task({ id: 2, startedAt: START, dueAt: DUE, ownerUserId: ARJUN }),
    ]

    it('starts on the earliest task start and ends on the latest task due date', () => {
      renderStrip(ADMIN, SCHEDULED, undefined, SCHEDULED)

      expect(screen.getByTestId('ob-strip-start')).toHaveTextContent(`Start ${formatDay(START)}`)
      expect(screen.getByTestId('ob-strip-end')).toHaveTextContent(
        `Expected end ${formatDay(DUE)}`,
      )
    })

    /**
     * The same reading `TAT` takes. An implementor whose own task is the one
     * due next must not read the service as ending then — that is the date
     * their work is due, and the row is answering a question about SIS.
     */
    it('stays the whole service’s schedule in the reader’s own filtered view', () => {
      renderStrip(MINE, [SCHEDULED[0]], undefined, SCHEDULED)

      expect(screen.getByTestId('ob-strip-start')).toHaveTextContent(`Start ${formatDay(START)}`)
      expect(screen.getByTestId('ob-strip-end')).toHaveTextContent(
        `Expected end ${formatDay(DUE)}`,
      )
    })

    it('says so with a dash rather than a date where nothing has started', () => {
      renderStrip(ADMIN, [task({ id: 1 })], undefined, [task({ id: 1 })])

      expect(screen.getByTestId('ob-strip-start')).toHaveTextContent('Start —')
      expect(screen.getByTestId('ob-strip-end')).toHaveTextContent('Expected end —')
    })
  })

  /**
   * On time, At risk, Delayed, Stuck — the chip at the end of the row, and the
   * only thing on it that is a verdict rather than a figure.
   *
   * <p>The fold is `deliveryHealth`'s and its boundaries are asserted there
   * against a pinned clock. These cases check the row draws what it was handed,
   * in the right words and the right colour, and that it reads the whole
   * service rather than the reader's slice of it.
   */
  describe('the delivery status', () => {
    const DAY = 86_400_000
    const overdueBy = (days: number) => new Date(Date.now() - days * DAY).toISOString()
    const dueIn = (days: number) => new Date(Date.now() + days * DAY).toISOString()

    function statusChip(tasks: ProjectTask[], scope: ObViewerScope = ADMIN, allTasks = tasks) {
      renderStrip(scope, tasks, undefined, allTasks)
      return screen.getByTestId('ob-strip-status')
    }

    it('reads On time in green while nothing is overdue', () => {
      const chip = statusChip([task({ id: 1, dueAt: dueIn(6), tatUsedPercent: 20 })])

      expect(chip).toHaveTextContent('On time')
      expect(chip).toHaveClass('text-success-text')
    })

    it('reads At risk in amber once a task has used most of its TAT', () => {
      const chip = statusChip([task({ id: 1, dueAt: dueIn(1), tatUsedPercent: 90 })])

      expect(chip).toHaveTextContent('At risk')
      expect(chip).toHaveClass('text-warning-text')
    })

    /** The one step of the ramp with no level behind it — see `DELIVERY_CHIP`. */
    it('reads Delayed in orange inside the first week past a due date', () => {
      const chip = statusChip([task({ id: 1, dueAt: overdueBy(3) })])

      expect(chip).toHaveTextContent('Delayed')
      expect(chip).toHaveClass('text-status-delayed-text')
    })

    it('reads Stuck in red beyond a week past a due date', () => {
      const chip = statusChip([task({ id: 1, dueAt: overdueBy(9) })])

      expect(chip).toHaveTextContent('Stuck')
      expect(chip).toHaveClass('text-danger-text')
    })

    /**
     * Unscoped, like the dots and the TAT. A service is late or it is not, and
     * an implementor whose own two tasks are fine reading **On time** over a
     * colleague's task nine days overdue would be the one reading on this page
     * that could lose somebody a client.
     */
    it('reads the whole service, not the reader’s share of it', () => {
      const mine = task({ id: 1, dueAt: dueIn(4) })
      const theirs = task({ id: 2, dueAt: overdueBy(9), ownerUserId: ARJUN })

      const chip = statusChip([mine], MINE, [mine, theirs])

      expect(chip).toHaveTextContent('Stuck')
    })

    /**
     * Colour is never the only signal — blueprint §12.1 — and a verdict with no
     * reason behind it is one a reader either believes or argues with.
     *
     * <p>Half a day past the mark rather than a whole one, because the count
     * rounds up: `overdueBy(2)` is two days plus however long the test took to
     * reach the assertion, which ceilings to three.
     */
    it('names the task behind the verdict, so the chip can be argued with', () => {
      const chip = statusChip([task({ id: 1, name: 'Import timetables', dueAt: overdueBy(2.5) })])

      expect(chip).toHaveAttribute('title', expect.stringContaining('Import timetables'))
      expect(chip).toHaveAttribute('title', expect.stringContaining('3 days'))
    })

    /**
     * The chip it replaced said Running / Blocked / Waiting / Complete. That
     * vocabulary is gone from the row: completion still wears its own chip
     * beside the name, and the Step states are on the dots.
     */
    it('replaced the running/blocked chip rather than joining it', () => {
      renderStrip(ADMIN)

      expect(screen.queryByText('Running')).not.toBeInTheDocument()
      expect(screen.queryByText('Not started')).not.toBeInTheDocument()
    })
  })

  describe('more implementors than the row can print', () => {
    const CROWDED = [
      task({ id: 1, ownerUserId: PRIYA }),
      task({ id: 2, ownerUserId: ARJUN }),
      task({ id: 3, ownerUserId: ZOYA }),
      task({ id: 4, ownerUserId: DEV }),
      task({ id: 5, ownerUserId: RAVI }),
      task({ id: 6, ownerUserId: null }),
    ]

    it('prints four and folds the rest, rather than wrapping to a second row', () => {
      renderStrip(ADMIN, CROWDED)

      const names = screen.getByTestId('ob-strip-names')
      expect(within(names).getByText('Arjun Mehta')).toBeInTheDocument()
      expect(within(names).queryByText('Zoya Khan')).not.toBeInTheDocument()
      expect(within(names).getByRole('button', { name: '+2 more' })).toBeInTheDocument()
    })

    it('opens every name it did not print', async () => {
      const user = userEvent.setup()
      renderStrip(ADMIN, CROWDED)

      await user.click(screen.getByRole('button', { name: '+2 more' }))

      const pop = await screen.findByTestId('ob-implementor-popover')
      expect(within(pop).getByText('Zoya Khan')).toBeInTheDocument()
      expect(within(pop).getByText('Unassigned')).toBeInTheDocument()
      expect(screen.getAllByTestId('ob-implementor-popover-row')).toHaveLength(2)
    })

    /**
     * The overflow control exists whatever the role — without it the folded
     * names would have nowhere to be read. Only the figures behind them are
     * the admin's.
     */
    it('lists the folded names for sales, without the workload split', async () => {
      const user = userEvent.setup()
      renderStrip(SALES, CROWDED)

      await user.click(screen.getByRole('button', { name: '+2 more' }))

      const pop = await screen.findByTestId('ob-implementor-popover')
      expect(within(pop).getByText('Zoya Khan')).toBeInTheDocument()
      expect(pop).not.toHaveTextContent('pending')
    })
  })
})
