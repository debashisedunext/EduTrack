import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import type { ObProjectModuleService } from '@/api/generated/model/obProjectModuleService'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'
import type { UserRef } from '@/api/generated/model/userRef'

import { ObProjectWorkspace } from './ObProjectWorkspace'
import type { ProjectTask } from './useProjectTasks'
import type { ObViewerScope } from './viewerScope'

/**
 * The four levels — Module Service → Step → Task → Check list — as an
 * accordion of timelines, and who each of them is drawn for.
 *
 * <p>Rendered against a real `QueryClientProvider` because the task popup
 * mutates through one. Nothing here fetches — the workspace is a fold of
 * props — so an empty client with retries off is enough.
 */

const ME = 41
const OTHER = 42
const SIS = 500
const ATTENDANCE = 501
const CONFIG = 1
const REPORTS = 3

const USERS: UserRef[] = [
  { id: ME, displayName: 'Kavya Sharma' },
  { id: OTHER, displayName: 'Arjun Mehta' },
]

const ALL: ObViewerScope = { kind: 'ALL', meId: ME, showsBreakdown: false }
const MINE: ObViewerScope = {
  kind: 'IMPLEMENTOR',
  meId: ME,
  showsBreakdown: false,
}

function stage(stageKey: number, name: string, sequence: number, taskCount = 1): ObProjectStage {
  return {
    stageKey,
    name,
    sequence,
    taskCount,
    tasksOutstanding: 0,
    isComplete: false,
    isCurrent: false,
  }
}

const MASTER = [
  stage(CONFIG, 'Configuration', 1),
  stage(2, 'Data Migration', 2),
  stage(REPORTS, 'Reports', 3),
]

function service(journeyId: number, serviceName: string): ObProjectModuleService {
  return {
    journeyId,
    templateId: 9,
    serviceName,
    gateStatus: 'OPEN',
    isComplete: false,
    stages: MASTER,
  }
}

function task(over: Partial<ProjectTask> & Pick<ProjectTask, 'id' | 'journeyId' | 'name'>): ProjectTask {
  return {
    serviceName: 'SIS',
    sequence: 1,
    status: 'PENDING',
    ownerUserId: ME,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: CONFIG,
    stageName: 'Configuration',
    items: [],
    docs: [],
    ...over,
  }
}

const SERVICES = [service(SIS, 'SIS'), service(ATTENDANCE, 'Student Attendance')]

const TASKS: ProjectTask[] = [
  task({ id: 1, journeyId: SIS, name: 'Admission No Scheme', status: 'DONE' }),
  task({ id: 2, journeyId: SIS, name: 'Report card template', stageKey: REPORTS, stageName: 'Reports' }),
  task({
    id: 3,
    journeyId: ATTENDANCE,
    name: 'Week off',
    serviceName: 'Student Attendance',
    ownerUserId: OTHER,
  }),
  /*
    A colleague's, and finished — so SIS has a Data Migration Step that exists,
    is not the reader's, and is complete. That is what makes "hidden from you"
    distinguishable from "nobody scheduled it".
  */
  task({
    id: 4,
    journeyId: SIS,
    name: 'Legacy extract archived',
    status: 'DONE',
    ownerUserId: OTHER,
    stageKey: 2,
    stageName: 'Data Migration',
  }),
]

function renderWorkspace(over: Partial<React.ComponentProps<typeof ObProjectWorkspace>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ObProjectWorkspace
        services={SERVICES}
        tasks={TASKS}
        scope={ALL}
        users={USERS}
        isPending={false}
        revealTaskId={null}
        {...over}
      />
    </QueryClientProvider>,
  )
}

const serviceButton = (name: string) =>
  screen.getAllByTestId('ob-tree-service').find((row) => row.textContent?.includes(name))!

/** The whole header row — the disclosure plus the figures beside it. */
const serviceStrip = (name: string) =>
  screen.getAllByTestId('ob-module-strip').find((card) => card.textContent?.includes(name))!

describe('the project workspace', () => {
  /**
   * One row per service, and the page opens onto a task rather than onto a
   * list: the first module in the list is open, its first drawn Step with it.
   * Every other row stays closed and carries its own figures and a dot per
   * Step, so the project still reads without opening anything.
   */
  it('opens the first module in the list, and no other', async () => {
    renderWorkspace()

    const rows = await screen.findAllByTestId('ob-tree-service')
    expect(rows).toHaveLength(2)
    expect(serviceButton('SIS')).toHaveAttribute('aria-expanded', 'true')
    expect(serviceButton('Student Attendance')).toHaveAttribute('aria-expanded', 'false')
    // SIS's outstanding Step, open, with its task on the page.
    expect(screen.getByText('Report card template')).toBeInTheDocument()
    expect(screen.queryByText('Week off')).not.toBeInTheDocument()
  })

  /**
   * The page used to open the first module holding outstanding work, so a
   * finished module 1 was skipped and module 2 opened in its place — which
   * every reader met as the page opening the wrong row.
   */
  it('opens the first module even when its work is all finished', async () => {
    renderWorkspace({
      tasks: [
        task({ id: 1, journeyId: SIS, name: 'Admission No Scheme', status: 'DONE' }),
        task({ id: 3, journeyId: ATTENDANCE, name: 'Week off', serviceName: 'Student Attendance' }),
      ],
    })

    await screen.findAllByTestId('ob-tree-service')
    expect(serviceButton('SIS')).toHaveAttribute('aria-expanded', 'true')
    expect(serviceButton('Student Attendance')).toHaveAttribute('aria-expanded', 'false')
    // Opens onto an answer rather than a blank: Pending draws none of its Steps.
    expect(
      screen.getByText('Nothing outstanding here — every task of this service is finished.'),
    ).toBeInTheDocument()
  })

  /** The finished work is one press away, and the same press on every module. */
  it('draws only outstanding tasks until Show all is pressed', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    expect(screen.queryByText('Admission No Scheme')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Show all' }))

    // The finished Step is back, and its first one is the Step that opens.
    expect(await screen.findByText('Admission No Scheme')).toBeInTheDocument()
    expect(screen.getAllByTestId('ob-timeline-step')).toHaveLength(3)
  })

  it('keeps that answer when the reader opens another module', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    await user.click(screen.getByRole('button', { name: 'Show all' }))
    await user.click(serviceButton('Student Attendance'))

    const switches = screen.getAllByRole('button', { name: 'Show all' })
    expect(switches).toHaveLength(2)
    switches.forEach((button) => expect(button).toHaveAttribute('aria-pressed', 'true'))
  })

  it('lists a module’s Steps in order, each disclosing its own tasks', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    await user.click(screen.getByRole('button', { name: 'Show all' }))

    const steps = screen.getAllByTestId('ob-timeline-step')
    expect(steps.map((s) => within(s).getByRole('heading').textContent)).toEqual([
      'Configuration',
      'Data Migration',
      'Reports',
    ])
    // The first Step is open; the others wait for their own chevron.
    expect(screen.getByText('Admission No Scheme')).toBeInTheDocument()
    expect(screen.queryByText('Report card template')).not.toBeInTheDocument()

    await user.click(within(steps[2]).getByTestId('ob-step-disclosure'))
    expect(screen.getByText('Report card template')).toBeInTheDocument()
    // Attendance is closed, so its task is not drawn.
    expect(screen.queryByText('Week off')).not.toBeInTheDocument()
  })

  it('opens and closes a module on its row, independently of the others', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    await user.click(serviceButton('Student Attendance'))

    // Both open — modules do not close each other.
    expect(await screen.findByText('Week off')).toBeInTheDocument()
    expect(screen.getByText('Report card template')).toBeInTheDocument()

    await user.click(serviceButton('SIS'))

    expect(serviceButton('SIS')).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Report card template')).not.toBeInTheDocument()
    expect(screen.getByText('Week off')).toBeInTheDocument()
  })

  /** A dot per Step on the row, whether or not the timeline shows it. */
  it('carries a dot per Step on each module’s row', async () => {
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    const dots = within(serviceStrip('SIS')).getByTestId('ob-strip-dots')
    expect(dots).toHaveAttribute(
      'aria-label',
      'Configuration Complete, Data Migration Complete, Reports Not started',
    )
  })

  it('counts each service’s own Steps on its row', async () => {
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    // Two of SIS's three Steps are complete — Configuration and Data Migration.
    expect(within(serviceStrip('SIS')).getByText('2/3 steps')).toBeInTheDocument()
  })

  it('opens a task in a dialog once its row is pressed', async () => {
    const user = userEvent.setup()
    const withItems = [
      task({
        id: 1,
        journeyId: SIS,
        name: 'Admission No Scheme',
        items: [
          {
            id: 11,
            stepId: 1,
            sequence: 1,
            label: 'Admission number format agreed',
            isMandatory: true,
            isDone: false,
            answer: null,
            remark: null,
          },
        ],
      }),
    ]
    renderWorkspace({ services: [service(SIS, 'SIS')], tasks: withItems })

    await screen.findByTestId('ob-tree-service')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    const row = await screen.findByRole('button', { name: /admission no scheme/i })
    expect(row).toHaveAttribute('aria-haspopup', 'dialog')
    await user.click(row)

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Admission No Scheme' })).toBeInTheDocument()
    expect(within(dialog).getByText('SIS · Configuration · Task 1 of 1')).toBeInTheDocument()
    expect(within(dialog).getByText('Admission number format agreed')).toBeInTheDocument()
    // The row behind the scrim stays marked — hidden from the accessibility
    // tree while the dialog is up, which is the modal doing its job.
    expect(
      screen.getByRole('button', { name: /admission no scheme/i, hidden: true }),
    ).toHaveAttribute('aria-pressed', 'true')
  })

  it('closes the dialog and keeps the reader where they were', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await screen.findAllByTestId('ob-tree-service')
    await user.click(await screen.findByRole('button', { name: /report card template/i }))
    await screen.findByRole('dialog')

    await user.click(screen.getByRole('button', { name: 'Close' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(serviceButton('SIS')).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: /report card template/i })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  describe('an implementor', () => {
    it('is shown their own tasks and no one else’s', async () => {
      const user = userEvent.setup()
      renderWorkspace({ scope: MINE })

      await screen.findAllByTestId('ob-tree-service')
      // Attendance's only task belongs to somebody else.
      expect(
        within(serviceStrip('Student Attendance')).getByText(/no tasks assigned to you/i),
      ).toBeInTheDocument()

      // SIS opens on its outstanding Step, and Data Migration is a colleague's.
      expect(await screen.findByText('Report card template')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Show all' }))
      expect(screen.getByText('Admission No Scheme')).toBeInTheDocument()
      expect(screen.queryByText('Legacy extract archived')).not.toBeInTheDocument()
    })

    /** A filter that cannot account for what it removed is a lost-work bug. */
    it('is told how many Steps of a service are somebody else’s', async () => {
      renderWorkspace({ scope: MINE })

      await screen.findAllByTestId('ob-tree-service')
      expect(
        await screen.findByText('1 more Step of this service is somebody else’s.'),
      ).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /show all steps/i })).not.toBeInTheDocument()
    })
  })

  describe('a named task', () => {
    it('opens its module and the task itself', async () => {
      renderWorkspace({ revealTaskId: 3 })

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByRole('heading', { name: 'Week off' })).toBeInTheDocument()
      expect(serviceButton('Student Attendance')).toHaveAttribute('aria-expanded', 'true')
      expect(screen.getByRole('button', { name: /week off/i, hidden: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
    })

    /**
     * A mailed link can name a task that has since been finished, and Pending
     * would draw neither its Step nor its row. The link wins.
     */
    it('asks for everything when the task it names is already finished', async () => {
      renderWorkspace({ revealTaskId: 1 })

      const dialog = await screen.findByRole('dialog')
      expect(
        within(dialog).getByRole('heading', { name: 'Admission No Scheme' }),
      ).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Show all', hidden: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
    })

    it('changes nothing when the task belongs to another project', async () => {
      renderWorkspace({ revealTaskId: 9999 })

      const rows = await screen.findAllByTestId('ob-tree-service')
      rows.forEach((row) => expect(row).toHaveAttribute('aria-expanded', 'false'))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('says so rather than rendering nothing when a project has no service', () => {
    renderWorkspace({ services: [], tasks: [] })

    expect(screen.getByText(/boarded through no module service/i)).toBeInTheDocument()
  })
})
