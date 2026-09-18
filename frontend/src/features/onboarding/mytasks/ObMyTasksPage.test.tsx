import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ObMyTasksPage } from './ObMyTasksPage'

/**
 * The implementor's queue — the five columns, the four tabs it is cut into,
 * and two things that are easy to get wrong: the status is a coloured circle
 * that must never be colour alone, and the rows are the caller's because the
 * endpoint says so rather than because the page filtered them.
 *
 * `ROWS` below all fall in the default "Work in progress" tab (statuses
 * `IN_PROGRESS`/`BLOCKED`/`WAITING_ON_CLIENT`), so the general column,
 * popup and pager tests never have to switch tabs to find their row.
 * `TAB_ROWS`, further down, is what exercises the four-way split itself.
 */
const ROWS = [
  {
    taskId: 1841,
    taskName: 'Week off',
    status: 'IN_PROGRESS',
    dueAt: '2026-09-16T13:00:00Z',
    isOverdue: false,
    projectId: 7,
    projectName: 'DAV Proj',
    obClientId: 3,
    obClientName: 'DAV School',
    obClientCode: 'DAV-101',
    journeyId: 500,
    serviceName: 'Student Attendance',
    stepKey: 1,
    stepName: 'Configuration',
    stepSequence: 1,
  },
  {
    taskId: 1790,
    taskName: 'Fee heads import',
    status: 'BLOCKED',
    dueAt: '2026-09-11T13:00:00Z',
    isOverdue: true,
    projectId: 9,
    projectName: 'Horizon ERP Rollout',
    obClientId: 4,
    obClientName: 'Horizon Academy',
    obClientCode: null,
    journeyId: 501,
    serviceName: 'SIS',
    stepKey: 2,
    stepName: 'Data Migration',
    stepSequence: 2,
  },
  {
    taskId: 1802,
    taskName: 'Report card template',
    status: 'WAITING_ON_CLIENT',
    dueAt: null,
    isOverdue: false,
    projectId: 9,
    projectName: 'Horizon ERP Rollout',
    obClientId: 4,
    obClientName: 'Horizon Academy',
    obClientCode: null,
    journeyId: 501,
    serviceName: 'SIS',
    stepKey: 3,
    stepName: 'Reports',
    stepSequence: 3,
  },
]

/** One task of the journey the popup reads, with a single check list item. */
function journeyStep(id: number, name: string) {
  return {
    id,
    journeyId: 500,
    sequence: id,
    name,
    status: 'PENDING',
    tatDays: 1,
    ownerUserId: 3,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    requiresSignoff: false,
    dueAt: '2026-09-16T13:00:00Z',
    tatUsedPercent: null,
    stageKey: 1,
    stageName: 'Configuration',
    items: [
      {
        id: id * 10,
        stepId: id,
        sequence: 1,
        label: `${name} — confirmed with the school`,
        isMandatory: true,
        isDone: false,
        answer: null,
        remark: null,
      },
    ],
    docs: [],
  }
}

/** Every query string the page asked for, in order. */
function stubQueue(
  rows: unknown[] = ROWS,
  escalations: unknown[] = [],
  metaOverrides: Record<string, unknown> = {},
) {
  const asked: string[] = []

  server.use(
    /*
      The popup reads this client's open escalations so it can draw the same
      banner the project page does. Stubbed here rather than left to the
      module's own handler, which walks the mock database for a list the popup
      only ever filters to one task.
    */
    http.get('*/onboarding/client-escalations', () => HttpResponse.json({ data: escalations })),
    http.get('*/onboarding/my-tasks', ({ request }) => {
      const url = new URL(request.url)
      asked.push(url.search)

      const limit = Number(url.searchParams.get('limit')) || 50
      const from = Number(url.searchParams.get('cursor') ?? '0')
      const page = rows.slice(from, from + limit)
      const end = from + page.length
      const hasMore = end < rows.length

      return HttpResponse.json({
        data: page,
        meta: { nextCursor: hasMore ? String(end) : null, hasMore, ...metaOverrides },
      })
    }),
  )

  return asked
}

function renderQueue(initialPath = '/onboarding/my-tasks') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <ObMyTasksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('My Tasks', () => {
  it('draws the five columns, in the order somebody reads them', async () => {
    stubQueue()
    renderQueue()

    const headers = await screen.findAllByRole('columnheader')
    expect(headers.map((h) => h.textContent)).toEqual([
      'Project',
      'Task',
      'Module',
      'Due date',
      'Status',
    ])
  })

  /**
   * Project with its client beneath it, step with its module beneath it —
   * each pair two lines in one cell, the specific value on top and the code
   * kept with the client.
   */
  it('lists a task with its client below the project, and its module below the step', async () => {
    stubQueue()
    renderQueue()

    const row = (await screen.findByText('Week off')).closest('tr')!
    expect(within(row).getByText('DAV Proj')).toBeInTheDocument()
    expect(within(row).getByText('DAV School')).toBeInTheDocument()
    expect(within(row).getByText('(DAV-101)')).toBeInTheDocument()
    expect(within(row).getByText('Configuration')).toBeInTheDocument()
    expect(within(row).getByText('Student Attendance')).toBeInTheDocument()
    expect(within(row).getByRole('img', { name: 'In process' })).toBeInTheDocument()
  })

  /**
   * Four hues in a fixed column, the task strip's own — scanned rather than
   * read. The six statuses survive as each dot's name, which is what keeps the
   * column readable without colour (blueprint §12.1). The `PENDING` dot is
   * covered on the "Pending task" tab further down, where a `PENDING` row
   * actually lives.
   */
  it('shows the status as a coloured circle, named', async () => {
    stubQueue()
    renderQueue()

    const waiting = (await screen.findByText('Report card template')).closest('tr')!
    const dot = within(waiting).getByTestId('ob-task-dot')
    expect(dot).toHaveAttribute('data-state', 'IN_PROCESS')
    expect(dot).toHaveAccessibleName('In process — waiting on client')

    // The word it replaced is gone from the row — the dot is the column now.
    expect(within(waiting).queryByText('Waiting on client')).not.toBeInTheDocument()
  })

  /**
   * Provisioning names a project after its client, so joining the two blindly
   * would print the school twice on exactly those rows.
   */
  it('does not repeat a client the project name already carries', async () => {
    stubQueue([{ ...ROWS[0], taskName: 'Fee structure', projectName: 'DAV School — EDUNEXT-ERP' }])
    renderQueue()

    const row = (await screen.findByText('Fee structure')).closest('tr')!
    expect(within(row).getByText('EDUNEXT-ERP')).toBeInTheDocument()
    expect(within(row).getByText('DAV School')).toBeInTheDocument()
  })

  /**
   * The dot has one colour to spend, so red says "this one is late" and the
   * date beside it still says which day it was due. The status the colour
   * replaced rides on the dot's name rather than being lost to it.
   */
  it('reddens an overdue row and keeps the date’s own warning', async () => {
    stubQueue()
    renderQueue()

    const row = (await screen.findByText('Fee heads import')).closest('tr')!
    expect(within(row).getByText(/overdue/i)).toBeInTheDocument()

    const dot = within(row).getByTestId('ob-task-dot')
    expect(dot).toHaveAttribute('data-state', 'OVERDUE')
    expect(dot).toHaveAccessibleName('Overdue — blocked')
  })

  /** Null until the task activates — a dash, never "Invalid Date". */
  it('prints a dash for a task with no due date', async () => {
    stubQueue()
    renderQueue()

    const row = (await screen.findByText('Report card template')).closest('tr')!
    expect(within(row).getByText('—')).toBeInTheDocument()
    // No due date is not late: the dot reads off the status alone.
    expect(within(row).getByTestId('ob-task-dot')).toHaveAttribute('data-state', 'IN_PROCESS')
  })

  it('keeps the way to the task on its own, and to the project beside it', async () => {
    stubQueue()
    renderQueue()

    expect(await screen.findByRole('link', { name: 'Open Week off on its own' })).toHaveAttribute(
      'href',
      '/onboarding/my-tasks/1841',
    )
    expect(screen.getByRole('link', { name: 'DAV Proj' })).toHaveAttribute(
      'href',
      '/onboarding/projects/7',
    )
  })

  /**
   * The same popup the project page opens — the check list, the facts and the
   * action bar — over the queue, so working through a list never leaves it.
   */
  it('opens a task in the popup, with its own check list and nothing else’s', async () => {
    const user = userEvent.setup()
    stubQueue()
    server.use(
      http.get('*/onboarding/journeys/:journeyId', () =>
        HttpResponse.json({
          data: {
            id: 500,
            templateId: 9,
            templateVersion: 1,
            steps: [journeyStep(1840, 'Admission No Scheme'), journeyStep(1841, 'Week off')],
            parallelGroups: [],
          },
        }),
      ),
    )
    renderQueue()

    const name = await screen.findByRole('button', { name: 'Week off' })
    expect(name).toHaveAttribute('aria-haspopup', 'dialog')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.click(name)

    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('Week off — confirmed with the school')).toBeInTheDocument()
    /*
      The project page's own crumb — service, step, and the task's position in
      that step — with the project on the front, which is the one segment this
      queue needs and that page does not. Two of the journey's steps share
      stage 1, and 1841 is the second of them.
    */
    expect(
      within(dialog).getByText(
        'DAV School — DAV Proj · Student Attendance · Configuration · Task 2 of 2',
      ),
    ).toBeInTheDocument()
    expect(within(dialog).queryByText('Admission No Scheme')).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Close' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Week off' })).toBeInTheDocument()
  })

  /**
   * The bug this queue actually had, and the reason the two screens disagreed.
   *
   * <p>A verdict is recorded in the *manager's* session, which invalidates the
   * manager's cache. The implementor's browser knows nothing about it — so a
   * task they had open before it came back reopened afterwards showing the
   * state they left it in: answered, sent, waiting on somebody. The project
   * page looked right only because it tends to be open where the writing
   * happened.
   *
   * <p>The client here keeps the app's own 30s `staleTime` deliberately. With
   * the default 0 every reopen refetches anyway and this would pass against the
   * code that had the bug.
   */
  it('reads the task again each time the popup opens, so a verdict that landed elsewhere shows', async () => {
    const user = userEvent.setup()
    stubQueue()

    /* Out with the reviewer, then handed back — the same step, two reads. */
    const reads = [
      {
        ...journeyStep(1841, 'Week off'),
        status: 'PENDING_REVIEW',
        items: [
          {
            id: 18410,
            stepId: 1841,
            sequence: 1,
            label: 'Week off — confirmed with the school',
            isMandatory: true,
            isDone: true,
            answer: true,
            remark: null,
            rowState: 'SENT',
            reviewState: 'NOT_REVIEWED',
          },
        ],
      },
      {
        ...journeyStep(1841, 'Week off'),
        status: 'IN_PROGRESS',
        items: [
          {
            id: 18410,
            stepId: 1841,
            sequence: 1,
            label: 'Week off — confirmed with the school',
            isMandatory: true,
            isDone: false,
            answer: null,
            remark: 'some of the work is not completed yet',
            rowState: 'REJECTED',
            reviewState: 'REJECTED',
            reviewedBy: { id: 9, displayName: 'Priya Nair' },
          },
        ],
      },
    ]
    let call = 0
    server.use(
      http.get('*/onboarding/journeys/:journeyId', () => {
        const step = reads[Math.min(call, reads.length - 1)]
        call += 1
        return HttpResponse.json({
          data: { id: 500, templateId: 9, templateVersion: 1, steps: [step], parallelGroups: [] },
        })
      }),
    )

    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
          })
        }
      >
        <MemoryRouter initialEntries={['/onboarding/my-tasks']}>
          <ObMyTasksPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await user.click(await screen.findByRole('button', { name: 'Week off' }))
    const first = await screen.findByRole('dialog')
    expect(await within(first).findByText('Awaiting review')).toBeInTheDocument()
    expect(within(first).queryByTestId('ob-task-came-back')).not.toBeInTheDocument()

    await user.click(within(first).getByRole('button', { name: 'Close' }))
    await user.click(screen.getByRole('button', { name: 'Week off' }))

    const second = await screen.findByRole('dialog')
    /*
      The whole point: within the stale window, and it is the new read that is
      drawn. The chip beside the title, the banner naming what to do, and the
      reviewer's own reason on the row.
    */
    expect(await within(second).findByTestId('ob-task-came-back')).toHaveTextContent(
      '1 row came back',
    )
    expect(within(second).getByText(/1 item came back/)).toBeInTheDocument()
    expect(
      within(second).getByText(/some of the work is not completed yet/),
    ).toBeInTheDocument()
    expect(within(second).queryByText('Awaiting review')).not.toBeInTheDocument()
  })

  /**
   * The endpoint has no `ownerUserId` parameter, and this asserts the page
   * never invents one — a filter applied on this side would be one an
   * implementor could change to read a colleague's queue.
   */
  it('asks for a page and nothing else', async () => {
    const asked = stubQueue()
    renderQueue()

    await screen.findByText('Week off')
    expect(asked[0]).toContain('limit=10')
    expect(asked[0]).not.toContain('ownerUserId')
  })

  it('pages ten at a time', async () => {
    const user = userEvent.setup()
    const many = Array.from({ length: 12 }, (_, i) => ({
      ...ROWS[0],
      taskId: 2000 + i,
      taskName: `Task ${String(i + 1).padStart(2, '0')}`,
    }))
    stubQueue(many)
    renderQueue()

    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /next/i }))

    expect(await screen.findByText('Rows 11–12')).toBeInTheDocument()
    expect(screen.getByText('Task 11')).toBeInTheDocument()
    expect(screen.queryByText('Task 01')).not.toBeInTheDocument()
  })

  it('says so, rather than showing an empty grid, when nothing is open', async () => {
    stubQueue([])
    renderQueue()

    expect(await screen.findByText(/nothing open against you/i)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  /**
   * The legend is the page's key, so what it must never do is teach a colour
   * the rows do not use. Both halves are asserted by their words rather than
   * by a class: the words are what a reader with no colour vision has, and a
   * Tailwind string is not a fact about the screen.
   */
  describe('the legend', () => {
    it('names every colour the queue paints', async () => {
      stubQueue()
      renderQueue()

      const legend = await screen.findByTestId('ob-my-tasks-legend')

      // The four the Status column draws.
      expect(within(legend).getByText('Pending')).toBeInTheDocument()
      expect(within(legend).getByText('In process')).toBeInTheDocument()
      expect(within(legend).getByText('Overdue')).toBeInTheDocument()
      expect(within(legend).getByText('Completed')).toBeInTheDocument()

      // And the three a review tints a whole row with.
      expect(within(legend).getByText('Came back')).toBeInTheDocument()
      expect(within(legend).getByText('Approved')).toBeInTheDocument()
      expect(within(legend).getByText('Out for verification')).toBeInTheDocument()
    })

    /**
     * One swatch per entry and the same four states the rows carry — the
     * assertion that would catch a legend drawing a hue the column dropped.
     */
    it('draws its dots from the same four states the rows do', async () => {
      stubQueue()
      renderQueue()

      const legend = await screen.findByTestId('ob-my-tasks-legend')
      const states = within(legend)
        .getAllByTestId('ob-task-dot')
        .map((dot) => dot.getAttribute('data-state'))

      expect(states).toEqual(['PENDING', 'IN_PROCESS', 'OVERDUE', 'COMPLETED'])
    })

    /** Nothing to key when there is no table under it. */
    it('is not drawn over an empty queue', async () => {
      stubQueue([])
      renderQueue()

      expect(await screen.findByText(/nothing open against you/i)).toBeInTheDocument()
      expect(screen.queryByTestId('ob-my-tasks-legend')).not.toBeInTheDocument()
    })
  })

  it('reports a failed load rather than an empty queue', async () => {
    server.use(
      http.get('*/onboarding/my-tasks', () =>
        HttpResponse.json({ title: 'Boom' }, { status: 500 }),
      ),
    )
    renderQueue()

    expect(await screen.findByText(/could not be loaded/i)).toBeInTheDocument()
  })

  /**
   * One row per tab, plus a fifth that belongs to two at once — an
   * `IN_PROGRESS` task with a row a manager has already verified, which is
   * what "Approved by manager" actually shows (see `myTasks.ts` for why: a
   * task only reaches `DONE`, and leaves this endpoint, once every row is
   * verified — this is the earlier signal, on a task still being worked).
   */
  const TAB_ROWS = [
    {
      ...ROWS[0],
      taskId: 3001,
      taskName: 'Configure fee heads',
      status: 'BLOCKED',
      dueAt: '2026-09-20T10:00:00Z',
      rowsApproved: 0,
    },
    {
      ...ROWS[0],
      taskId: 3002,
      taskName: 'Import gradebook',
      status: 'IN_PROGRESS',
      dueAt: '2026-09-18T10:00:00Z',
      rowsApproved: 2,
    },
    {
      ...ROWS[0],
      taskId: 3003,
      taskName: 'Attendance policy',
      status: 'WAITING_ON_CLIENT',
      dueAt: null,
      rowsApproved: 0,
    },
    {
      ...ROWS[0],
      taskId: 3004,
      taskName: 'Timetable review',
      status: 'PENDING_REVIEW',
      dueAt: '2026-09-19T10:00:00Z',
      rowsApproved: 0,
    },
    {
      ...ROWS[0],
      taskId: 3005,
      taskName: 'Website domain',
      status: 'PENDING',
      dueAt: '2026-09-25T10:00:00Z',
      rowsApproved: 0,
    },
  ]

  describe('the tabs', () => {
    it('opens on Work in progress, showing only the tasks being worked', async () => {
      stubQueue(TAB_ROWS)
      renderQueue()

      expect(await screen.findByRole('tab', { selected: true })).toHaveAccessibleName(
        /Work in progress/,
      )
      expect(await screen.findByText('Configure fee heads')).toBeInTheDocument()
      expect(screen.getByText('Import gradebook')).toBeInTheDocument()
      expect(screen.getByText('Attendance policy')).toBeInTheDocument()

      expect(screen.queryByText('Timetable review')).not.toBeInTheDocument()
      expect(screen.queryByText('Website domain')).not.toBeInTheDocument()
    })

    /**
     * The one tab that reorders its rows — soonest due date first, a task
     * with no due date yet sorting last, same as the queue's own rule for a
     * task that has not activated.
     */
    it('sorts Work in progress by due date, soonest first', async () => {
      stubQueue(TAB_ROWS)
      renderQueue()

      const table = await screen.findByRole('table')
      const names = within(table)
        .getAllByRole('row')
        .slice(1) // drop the header row
        .map((row) => within(row).getByRole('button').textContent)

      expect(names).toEqual(['Import gradebook', 'Configure fee heads', 'Attendance policy'])
    })

    it('shows only rows a manager has approved under Approved by manager', async () => {
      const user = userEvent.setup()
      stubQueue(TAB_ROWS)
      renderQueue()

      await user.click(await screen.findByRole('tab', { name: /Approved by manager/ }))

      expect(await screen.findByText('Import gradebook')).toBeInTheDocument()
      expect(screen.queryByText('Configure fee heads')).not.toBeInTheDocument()
      expect(screen.queryByText('Attendance policy')).not.toBeInTheDocument()
      expect(screen.queryByText('Timetable review')).not.toBeInTheDocument()
      expect(screen.queryByText('Website domain')).not.toBeInTheDocument()
    })

    it('shows only tasks submitted for review under Pending for approval', async () => {
      const user = userEvent.setup()
      stubQueue(TAB_ROWS)
      renderQueue()

      await user.click(await screen.findByRole('tab', { name: /Pending for approval/ }))

      expect(await screen.findByText('Timetable review')).toBeInTheDocument()
      expect(screen.queryByText('Configure fee heads')).not.toBeInTheDocument()
      expect(screen.queryByText('Import gradebook')).not.toBeInTheDocument()
    })

    /** Also where the `PENDING` dot itself is covered — see the note above. */
    it('shows only tasks nobody has started under Pending task', async () => {
      const user = userEvent.setup()
      stubQueue(TAB_ROWS)
      renderQueue()

      await user.click(await screen.findByRole('tab', { name: /Pending task/ }))

      const row = (await screen.findByText('Website domain')).closest('tr')!
      expect(within(row).getByTestId('ob-task-dot')).toHaveAttribute('data-state', 'PENDING')
      expect(screen.queryByText('Timetable review')).not.toBeInTheDocument()
    })

    it('counts each tab against this page', async () => {
      stubQueue(TAB_ROWS)
      renderQueue()

      expect(await screen.findByRole('tab', { name: 'Work in progress (3)' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Approved by manager (1)' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Pending for approval (1)' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Pending task (1)' })).toBeInTheDocument()
    })

    /** A tab is a link a colleague can paste, same as the dashboard's strip. */
    it('opens directly on the tab named in the URL', async () => {
      stubQueue(TAB_ROWS)
      renderQueue('/onboarding/my-tasks?tab=not-started')

      expect(await screen.findByText('Website domain')).toBeInTheDocument()
      expect(screen.queryByText('Configure fee heads')).not.toBeInTheDocument()
    })

    it('says so, rather than showing an empty grid, when a tab has nothing on this page', async () => {
      const user = userEvent.setup()
      stubQueue([TAB_ROWS[0]])
      renderQueue()

      await user.click(await screen.findByRole('tab', { name: /Pending task/ }))

      expect(
        await screen.findByText(/nothing still to be started on this page/i),
      ).toBeInTheDocument()
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
    })
  })

  /**
   * The manager-only fifth tab. `pendingMyVerification` — not a role — is
   * what a row belongs on it for, and `meta.isReviewerForAnyProject` — not a
   * role either — is what the tab shows at all. Neither is spelled
   * "OB_MANAGER" anywhere in this suite, on purpose: the whole point is that
   * an implementor who also manages one project gets the tab from the same
   * response an ordinary implementor gets, with nothing on this page having
   * decided so from who they are.
   */
  describe('Pending for verification', () => {
    const MANAGER_ROWS = [
      ...TAB_ROWS,
      {
        ...ROWS[0],
        taskId: 3006,
        taskName: 'Verify onboarding checklist',
        status: 'PENDING_REVIEW',
        dueAt: '2026-09-21T10:00:00Z',
        rowsApproved: 0,
        pendingMyVerification: true,
      },
    ]

    it('is absent for a caller who reviews no project', async () => {
      stubQueue(TAB_ROWS)
      renderQueue()

      await screen.findByText('Configure fee heads')
      expect(screen.queryByRole('tab', { name: /Pending for verification/ })).not.toBeInTheDocument()
    })

    it('is drawn first, ahead of Work in progress, for a caller who reviews at least one project', async () => {
      stubQueue(MANAGER_ROWS, [], { isReviewerForAnyProject: true })
      renderQueue()

      const tabs = await screen.findAllByRole('tab')
      expect(tabs[0]).toHaveAccessibleName(/Pending for verification/)
      // Position, not default: Work in progress still opens first.
      expect(screen.getByRole('tab', { selected: true })).toHaveAccessibleName(/Work in progress/)
    })

    /**
     * `Timetable review` is `PENDING_REVIEW` too but `pendingMyVerification`
     * is false on it — the caller's own submission, not a review sitting on
     * their desk. Only the row the server actually flagged shows here.
     */
    it('shows only the rows the server flagged as needing this caller’s verification', async () => {
      const user = userEvent.setup()
      stubQueue(MANAGER_ROWS, [], { isReviewerForAnyProject: true })
      renderQueue()

      await user.click(await screen.findByRole('tab', { name: /Pending for verification/ }))

      expect(await screen.findByText('Verify onboarding checklist')).toBeInTheDocument()
      expect(screen.queryByText('Timetable review')).not.toBeInTheDocument()
      expect(screen.queryByText('Configure fee heads')).not.toBeInTheDocument()
    })

    /** The automatic case: a dual-hat caller keeps their own queue too. */
    it('leaves the caller’s own Work in progress tab exactly as it was', async () => {
      stubQueue(MANAGER_ROWS, [], { isReviewerForAnyProject: true })
      renderQueue()

      expect(await screen.findByText('Configure fee heads')).toBeInTheDocument()
      expect(screen.getByText('Import gradebook')).toBeInTheDocument()
      expect(screen.getByText('Attendance policy')).toBeInTheDocument()
      expect(screen.queryByText('Verify onboarding checklist')).not.toBeInTheDocument()
    })

    it('opens directly on it from a pasted link', async () => {
      stubQueue(MANAGER_ROWS, [], { isReviewerForAnyProject: true })
      renderQueue('/onboarding/my-tasks?tab=pending-verification')

      expect(await screen.findByText('Verify onboarding checklist')).toBeInTheDocument()
      expect(screen.queryByText('Configure fee heads')).not.toBeInTheDocument()
    })

    /**
     * A stale bookmark from when the caller used to review something, or a
     * link a manager shared with an implementor who does not. The tab is
     * gone rather than crashing or showing somebody else's queue.
     */
    it('falls back to Work in progress when linked for a caller who reviews nothing', async () => {
      stubQueue(TAB_ROWS)
      renderQueue('/onboarding/my-tasks?tab=pending-verification')

      expect(await screen.findByRole('tab', { selected: true })).toHaveAccessibleName(
        /Work in progress/,
      )
      expect(screen.getByText('Configure fee heads')).toBeInTheDocument()
    })
  })
})
