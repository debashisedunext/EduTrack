import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { server } from '@/mocks/server'

import type { ObJourneyStepDoc } from '@/api/generated/model/obJourneyStepDoc'
import type { UserRef } from '@/api/generated/model/userRef'

import { ObTaskActionBar } from './ObTaskActionBar'
import type { ProjectTask } from './useProjectTasks'

/**
 * The row, and the dialogs behind it.
 *
 * <p>Which buttons exist for which reader is `taskActions`' and is tested
 * there. What is asserted here is that the row draws them, that the ones
 * needing input open a dialog and the ones that do not have none, and that the
 * read-only sentence reaches a non-owner.
 *
 * <p>`/me` is mocked rather than served: the bar reads it only to decide
 * whether the reader may reassign, and letting a shared mock handler decide
 * that would make this file fail whenever somebody edited the fixture user.
 */

const moduleRole = vi.hoisted(() => ({ current: 'OB_STEP_OWNER' as string | undefined }))

vi.mock('@/api/generated/auth/auth', () => ({
  useGetMe: () => ({
    data: {
      data: { id: 41, displayName: 'Vikram Mehta', moduleRoles: { ONBOARDING: moduleRole.current } },
    },
  }),
}))

const ME = 41
const COLLEAGUE = 42

const USERS: UserRef[] = [
  { id: ME, displayName: 'Vikram Mehta' },
  { id: COLLEAGUE, displayName: 'Priya Nair' },
]

function doc(over: Partial<ObJourneyStepDoc> = {}): ObJourneyStepDoc {
  return { id: 1, stepId: 9, label: 'Validation report', isRequired: true, isSatisfied: false, ...over }
}

function task(over: Partial<ProjectTask> = {}): ProjectTask {
  return {
    id: 9,
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: 'Student Dataport',
    status: 'IN_PROGRESS',
    ownerUserId: ME,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: 1,
    stageName: 'Data Migration',
    items: [],
    docs: [],
    ...over,
  }
}

function renderBar(
  over: Partial<React.ComponentProps<typeof ObTaskActionBar>> = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ObTaskActionBar task={task()} users={USERS} yours blockers={[]} {...over} />
    </QueryClientProvider>,
  )
}

/** Block, Reassign and Attach sit behind More; this opens it. */
async function openMore(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId('ob-task-action-more'))
  await screen.findByRole('menu')
}

beforeEach(() => {
  moduleRole.current = 'OB_STEP_OWNER'
})

describe('the task action bar', () => {
  /**
   * It replaced five stacked rows, four of them input controls sitting empty in
   * case somebody wanted them: three transitions, More, and Communication.
   */
  it('puts every action on one row', () => {
    renderBar({ task: task({ docs: [doc()] }) })

    const bar = screen.getByTestId('ob-task-action-bar')
    expect(within(bar).getAllByRole('button')).toHaveLength(5)
    // No select, no text field, no file input until something is opened.
    expect(within(bar).queryByRole('combobox')).not.toBeInTheDocument()
    expect(within(bar).queryByRole('textbox')).not.toBeInTheDocument()
  })

  /**
   * Each is a dialog rather than a transition, and none is what somebody opens
   * a task for most days — so they are one press further away, together.
   */
  it('folds Block, Reassign and Attach behind More', async () => {
    const user = userEvent.setup()
    renderBar({ task: task({ docs: [doc()] }) })

    expect(screen.getByTestId('ob-task-action-more')).toHaveAttribute('aria-haspopup', 'menu')
    for (const key of ['block', 'reassign', 'attach']) {
      expect(screen.queryByTestId(`ob-task-action-${key}`), key).not.toBeInTheDocument()
    }

    await openMore(user)

    const items = screen.getAllByRole('menuitem')
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining('Block'),
      expect.stringContaining('Reassign'),
      expect.stringContaining('Attach'),
    ])
  })

  it('holds Attach back where the task requires no documents', async () => {
    const user = userEvent.setup()
    renderBar()

    await openMore(user)

    expect(screen.queryByTestId('ob-task-action-attach')).not.toBeInTheDocument()
  })

  it('offers Attach with its count where the task requires documents', async () => {
    const user = userEvent.setup()
    renderBar({ task: task({ docs: [doc(), doc({ id: 2, isSatisfied: true })] }) })

    await openMore(user)

    expect(screen.getByTestId('ob-task-action-attach')).toHaveTextContent('1/2')
  })

  it('names what is holding Mark Complete, in text rather than only in a tooltip', () => {
    renderBar({ blockers: ['2 unanswered'] })

    expect(screen.getByTestId('ob-task-action-complete')).toBeDisabled()
    expect(screen.getByText(/Mark Complete is waiting on 2 unanswered/)).toBeInTheDocument()
  })

  /**
   * The owner's one button, in its first half. A check list that has not been
   * verified is not something to complete — it is something to send — and the
   * gate it names is the send's own, not Complete's.
   */
  it('offers Send for Verification while the check list still owes the reviewer', () => {
    renderBar({ checklistPhase: 'SEND', sendBlockers: ['1 unanswered row'] })

    const button = screen.getByTestId('ob-task-action-send')
    expect(button).toHaveTextContent('Send for Verification')
    expect(button).toBeDisabled()
    expect(screen.getByText(/Send for Verification is waiting on 1 unanswered row/)).toBeInTheDocument()
    expect(screen.queryByTestId('ob-task-action-complete')).not.toBeInTheDocument()
  })

  it('sends the whole check list in one request when it is pressed', async () => {
    const user = userEvent.setup()
    const sent: string[] = []
    server.use(
      http.post('*/onboarding/journey-steps/:stepId/checklist/submit', ({ params }) => {
        sent.push(String(params.stepId))
        return HttpResponse.json({ id: 9 })
      }),
    )
    renderBar({ checklistPhase: 'SEND' })

    await user.click(screen.getByTestId('ob-task-action-send'))

    await waitFor(() => expect(sent).toEqual(['9']))
  })

  /** Every row verified — the same button, now the press that closes the task. */
  it('turns into Mark Complete once the review has passed', () => {
    renderBar({ checklistPhase: 'COMPLETE' })

    expect(screen.getByTestId('ob-task-action-complete')).toHaveTextContent('Mark Complete')
    expect(screen.queryByTestId('ob-task-action-send')).not.toBeInTheDocument()
  })

  /**
   * The reviewer's word for the reviewer's act. It never said *Mark complete*
   * truthfully — this press completes nothing, it hands the task back.
   */
  it('says Verified for the manager holding the review', () => {
    renderBar({
      yours: false,
      reviewing: true,
      task: task({ status: 'PENDING_REVIEW', ownerUserId: COLLEAGUE }),
    })

    expect(screen.getByTestId('ob-task-action-complete')).toHaveTextContent('Verified')
  })

  /**
   * A task on the reviewer's desk is always mid-check-list, so the owner's
   * table offers **Send for Verification** — the owner's word for the owner's
   * act. The reviewer's override has to catch that one too, and did not: the
   * manager was left holding Send for Verification, greyed, under a blocker
   * reading "1 row still to review".
   */
  it('says Verified even though the check list is still in its send phase', () => {
    renderBar({
      yours: false,
      reviewing: true,
      checklistPhase: 'SEND',
      reviewBlockers: ['1 row still to review'],
      task: task({ status: 'PENDING_REVIEW', ownerUserId: COLLEAGUE }),
    })

    const button = screen.getByTestId('ob-task-action-complete')
    expect(button).toHaveTextContent('Verified')
    expect(button).toBeDisabled()
    expect(screen.getByText(/Verified is waiting on 1 row still to review/)).toBeInTheDocument()
    expect(screen.queryByTestId('ob-task-action-send')).not.toBeInTheDocument()
  })

  it('tells a non-owner whose task it is, and leaves them Communication', () => {
    renderBar({ yours: false, task: task({ ownerUserId: COLLEAGUE }) })

    expect(screen.getByText(/Priya Nair is the implementor on this task/)).toBeInTheDocument()
    expect(screen.getByTestId('ob-task-action-complete')).toBeDisabled()
    expect(screen.getByTestId('ob-task-action-communication')).toBeEnabled()
  })

  /**
   * The product rule, seen on the row rather than in the model: while a task
   * waits on the client, every state button but Resume is held, and the
   * tooltip on each says why. Communication survives — logging the call is
   * what somebody does while waiting.
   */
  it('while waiting on the client, holds everything but Resume and Communication', async () => {
    const user = userEvent.setup()
    renderBar({ task: task({ status: 'WAITING_ON_CLIENT' }) })

    expect(screen.getByTestId('ob-task-action-resume')).toBeEnabled()
    expect(screen.getByTestId('ob-task-action-communication')).toBeEnabled()
    expect(screen.queryByTestId('ob-task-action-waitingOnClient')).not.toBeInTheDocument()

    await openMore(user)
    for (const key of ['complete', 'waitingOnService', 'block']) {
      const button = screen.getByTestId(`ob-task-action-${key}`)
      expect(button, key).toBeDisabled()
      expect(button, key).toHaveAttribute('title', 'Waiting on the client — resume the task first')
    }
  })

  describe('a button that needs nothing', () => {
    /** Complete and Waiting on client take no body, so they ask for nothing. */
    it('does not advertise a dialog', () => {
      renderBar()

      expect(screen.getByTestId('ob-task-action-complete')).not.toHaveAttribute('aria-haspopup')
      expect(screen.getByTestId('ob-task-action-waitingOnClient')).not.toHaveAttribute(
        'aria-haspopup',
      )
    })
  })

  describe('a button that needs input', () => {
    it('opens the block dialog, with its reason list', async () => {
      const user = userEvent.setup()
      renderBar()

      await openMore(user)
      expect(screen.getByTestId('ob-task-action-block')).toHaveAttribute('aria-haspopup', 'dialog')
      await user.click(screen.getByTestId('ob-task-action-block'))

      const dialog = await screen.findByTestId('ob-task-block-dialog')
      expect(within(dialog).getByText('Block this task')).toBeInTheDocument()
      // The clock difference is the most consequential choice on the panel, so
      // the dialog says it rather than assuming the owner remembers.
      expect(dialog).toHaveTextContent(/clock keeps running/i)
      expect(within(dialog).getByRole('combobox')).toBeInTheDocument()
    })

    it('opens Waiting on service with no reason to pick', async () => {
      const user = userEvent.setup()
      renderBar()

      await user.click(screen.getByTestId('ob-task-action-waitingOnService'))

      const dialog = await screen.findByTestId('ob-task-waiting-service-dialog')
      // Same endpoint as Block, with the reason already chosen — so there is
      // nothing to choose, only something to explain.
      expect(within(dialog).queryByRole('combobox')).not.toBeInTheDocument()
      expect(within(dialog).getByRole('textbox')).toBeInTheDocument()
    })

    it('lists the required documents and offers the task upload control', async () => {
      const user = userEvent.setup()
      renderBar({ task: task({ docs: [doc(), doc({ id: 2, label: 'Signed consent' })] }) })

      await openMore(user)
      await user.click(screen.getByTestId('ob-task-action-attach'))

      const dialog = await screen.findByTestId('ob-task-docs-dialog')
      expect(within(dialog).getAllByTestId('ob-task-doc-row')).toHaveLength(2)
      expect(within(dialog).getByText('Signed consent')).toBeInTheDocument()
      expect(within(dialog).getByLabelText('Document')).toBeInTheDocument()
      expect(within(dialog).getByRole('button', { name: 'Upload' })).toBeDisabled()
    })

    it('opens the communication timeline rather than keeping it on the page', async () => {
      const user = userEvent.setup()
      renderBar()

      expect(screen.queryByTestId('ob-task-communications-dialog')).not.toBeInTheDocument()
      await user.click(screen.getByTestId('ob-task-action-communication'))

      expect(await screen.findByTestId('ob-task-communications-dialog')).toHaveTextContent(
        /append-only/i,
      )
    })

    it('closes on Cancel without acting', async () => {
      const user = userEvent.setup()
      renderBar()

      await openMore(user)
      await user.click(screen.getByTestId('ob-task-action-block'))
      await user.click(await screen.findByRole('button', { name: 'Cancel' }))

      expect(screen.queryByTestId('ob-task-block-dialog')).not.toBeInTheDocument()
    })
  })

  describe('Reassign', () => {
    it('is closed to an implementor, naming the role that would open it', async () => {
      const user = userEvent.setup()
      renderBar()

      await openMore(user)
      const button = screen.getByTestId('ob-task-action-reassign')
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('title', expect.stringMatching(/OB Manager or OB Admin/))
    })

    it('opens for a moderator, onto the directory', async () => {
      moduleRole.current = 'OB_ADMIN'
      const user = userEvent.setup()
      renderBar()

      await openMore(user)
      await user.click(screen.getByTestId('ob-task-action-reassign'))

      const dialog = await screen.findByTestId('ob-task-reassign-dialog')
      expect(within(dialog).getByRole('combobox')).toBeInTheDocument()
      expect(within(dialog).getByRole('option', { name: 'Priya Nair' })).toBeInTheDocument()
      // Unassigned is a real choice: the task then falls to the project's implementor.
      expect(within(dialog).getByRole('option', { name: 'Unassigned' })).toBeInTheDocument()
    })
  })
})
