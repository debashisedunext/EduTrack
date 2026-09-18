import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ObMyTaskFocusPage } from './ObMyTaskFocusPage'

/**
 * One task, on its own.
 *
 * <p>The assertion that matters most is the negative one: a journey holding
 * four tasks must put exactly one of them on this page. That is the whole
 * difference between this screen and the project page opened with `?task=`,
 * and it is the requirement it was built for.
 */
const TASK = {
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
}

function step(id: number, name: string) {
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

/** A journey of four tasks, only one of which belongs on this page. */
const JOURNEY = {
  id: 500,
  templateId: 9,
  templateVersion: 1,
  steps: [
    step(1840, 'Admission No Scheme'),
    step(1841, 'Week off'),
    step(1842, 'School Calendar'),
    step(1843, 'Student master import'),
  ],
  parallelGroups: [],
}

function stub(task: unknown = TASK, escalations: unknown[] = []) {
  server.use(
    http.get('*/onboarding/my-tasks/:taskId', () =>
      task ? HttpResponse.json({ data: task }) : HttpResponse.json({ title: 'x' }, { status: 404 }),
    ),
    http.get('*/onboarding/journeys/:journeyId', () => HttpResponse.json({ data: JOURNEY })),
    /*
      The page reads this client's open escalations so the panel can draw the
      same banner the project page does. Stubbed rather than left to the
      module's own mock handler, which walks the mock database for a list this
      screen only ever filters to one task.
    */
    http.get('*/onboarding/client-escalations', () => HttpResponse.json({ data: escalations })),
  )
}

function renderFocus(taskId = 1841) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/onboarding/my-tasks/${taskId}`]}>
        <Routes>
          <Route path="/onboarding/my-tasks/:taskId" element={<ObMyTaskFocusPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('one task on its own', () => {
  it('leads with the task, not with the project', async () => {
    stub()
    renderFocus()

    expect(await screen.findByRole('heading', { level: 1, name: 'Week off' })).toBeInTheDocument()
  })

  /** The requirement this screen exists for. */
  it('shows no other task from the same journey', async () => {
    stub()
    renderFocus()

    await screen.findByRole('heading', { level: 1, name: 'Week off' })

    expect(screen.queryByText('Admission No Scheme')).not.toBeInTheDocument()
    expect(screen.queryByText('School Calendar')).not.toBeInTheDocument()
    expect(screen.queryByText('Student master import')).not.toBeInTheDocument()
  })

  it('places the task under its module service and its step', async () => {
    stub()
    renderFocus()

    expect(await screen.findByTestId('ob-focus-service')).toHaveTextContent('Student Attendance')
    expect(screen.getByTestId('ob-focus-step')).toHaveTextContent('Configuration')
  })

  /**
   * The banner the project page draws on an escalated task, drawn here too.
   * It was passed from one of the two screens that open this panel, so the
   * same task looked ordinary from the queue and alarming from the project —
   * and which one a reader saw depended only on how they had arrived.
   */
  it('draws the client escalation the project page would have shown', async () => {
    stub(TASK, [
      {
        id: 77,
        stepId: 1841,
        raisedAt: '2026-09-15T09:00:00Z',
        comment: 'No progress for a fortnight',
        raisedByContact: { name: 'Meera Rao' },
      },
    ])
    renderFocus()

    expect(await screen.findByText(/No progress for a fortnight/)).toBeInTheDocument()
    expect(screen.getByText(/Meera Rao/)).toBeInTheDocument()
  })

  /** An escalation on a sibling task is not this task's. */
  it('draws no escalation banner for another task’s escalation', async () => {
    stub(TASK, [
      {
        id: 78,
        stepId: 1842,
        raisedAt: '2026-09-15T09:00:00Z',
        comment: 'Belongs to School Calendar',
        raisedByContact: { name: 'Meera Rao' },
      },
    ])
    renderFocus()

    await screen.findByRole('heading', { level: 1, name: 'Week off' })
    expect(screen.queryByText(/Belongs to School Calendar/)).not.toBeInTheDocument()
  })

  it('opens onto the task’s own check list', async () => {
    stub()
    renderFocus()

    expect(
      await screen.findByText('Week off — confirmed with the school'),
    ).toBeInTheDocument()
    // And not the sibling tasks' items, which share the journey read.
    expect(
      screen.queryByText('School Calendar — confirmed with the school'),
    ).not.toBeInTheDocument()
  })

  it('carries the way back to the queue and out to the project', async () => {
    stub()
    renderFocus()

    expect(await screen.findByRole('link', { name: 'My Tasks' })).toHaveAttribute(
      'href',
      '/onboarding/my-tasks',
    )
    expect(screen.getByRole('link', { name: 'DAV Proj' })).toHaveAttribute(
      'href',
      '/onboarding/projects/7',
    )
  })

  /**
   * Somebody else's task and a task that does not exist answer identically —
   * the server returns 404 for both, because a 403 would confirm the task
   * exists and these ids are sequential.
   */
  it('says not found for a task that is not the caller’s', async () => {
    stub(null)
    renderFocus(9999)

    expect(await screen.findByText(/task not found/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to my tasks/i })).toBeInTheDocument()
  })

  it('marks an overdue task on the header', async () => {
    stub({ ...TASK, isOverdue: true, status: 'BLOCKED' })
    renderFocus()

    expect(await screen.findByText('Overdue')).toBeInTheDocument()
    expect(screen.getByText('Blocked')).toBeInTheDocument()
  })
})
