import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ObMyTasksPage } from './ObMyTasksPage'

/**
 * The implementor's queue — the five columns, and the two things that are easy
 * to get wrong about them: the status is a coloured circle that must never be
 * colour alone, and the rows are the caller's because the endpoint says so
 * rather than because the page filtered them.
 */
const ROWS = [
  {
    taskId: 1841,
    taskName: 'Week off',
    status: 'PENDING',
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
function stubQueue(rows: unknown[] = ROWS, escalations: unknown[] = []) {
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
        meta: { nextCursor: hasMore ? String(end) : null, hasMore },
      })
    }),
  )

  return asked
}

function renderQueue() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding/my-tasks']}>
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
      'Module service step',
      'Due date',
      'Status',
    ])
  })

  /** Client with project, service with step — one cell each, and the code kept. */
  it('lists a task with its client and project, and its service and step', async () => {
    stubQueue()
    renderQueue()

    const row = (await screen.findByText('Week off')).closest('tr')!
    expect(within(row).getByText('DAV School — DAV Proj')).toBeInTheDocument()
    expect(within(row).getByText('(DAV-101)')).toBeInTheDocument()
    expect(within(row).getByText('Student Attendance — Configuration')).toBeInTheDocument()
    expect(within(row).getByRole('img', { name: 'Pending' })).toBeInTheDocument()
  })

  /**
   * Four hues in a fixed column, the task strip's own — scanned rather than
   * read. The six statuses survive as each dot's name, which is what keeps the
   * column readable without colour (blueprint §12.1).
   */
  it('shows the status as a coloured circle, named', async () => {
    stubQueue()
    renderQueue()

    const pending = (await screen.findByText('Week off')).closest('tr')!
    expect(within(pending).getByTestId('ob-task-dot')).toHaveAttribute('data-state', 'PENDING')

    const waiting = screen.getByText('Report card template').closest('tr')!
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
    expect(within(row).getByText('DAV School — EDUNEXT-ERP')).toBeInTheDocument()
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
    expect(screen.getByRole('link', { name: 'DAV School — DAV Proj' })).toHaveAttribute(
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
})
