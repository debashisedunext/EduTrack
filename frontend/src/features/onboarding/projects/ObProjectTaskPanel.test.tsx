import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'

import type { UserRef } from '@/api/generated/model/userRef'
import { server } from '@/mocks/server'

import { ObProjectTaskPanel } from './ObProjectTaskPanel'
import type { ProjectTask } from './useProjectTasks'

/**
 * The check list, open in the task's own section.
 *
 * <p>Rendered against a real `QueryClientProvider` because an answer mutates
 * through one — nothing here fetches, so an empty client with retries off is
 * enough.
 */

const ME = 41
const COLLEAGUE = 42

const USERS: UserRef[] = [
  { id: ME, displayName: 'Vikram Mehta' },
  { id: COLLEAGUE, displayName: 'Priya Nair' },
]

function task(over: Partial<ProjectTask> = {}): ProjectTask {
  return {
    id: 900,
    journeyId: 500,
    serviceName: 'Student Dataport',
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
      {
        id: 1,
        stepId: 900,
        sequence: 1,
        label: 'Data Sanitization',
        isMandatory: true,
        isDone: true,
        answer: true,
        remark: null,
      },
      {
        id: 2,
        stepId: 900,
        sequence: 2,
        label: 'Master Data verification',
        isMandatory: true,
        isDone: false,
        answer: null,
        remark: null,
      },
    ],
    docs: [],
    ...over,
  } as ProjectTask
}

function renderPanel(over: Partial<ProjectTask> = {}, yours = true, canReview = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ObProjectTaskPanel
        task={task(over)}
        users={USERS}
        yours={yours}
        canReview={canReview}
      />
    </QueryClientProvider>,
  )
}

/** A row a manager has sent back, with the reason they wrote on it. */
function returnedRow(over: Record<string, unknown> = {}) {
  return {
    id: 1,
    stepId: 900,
    sequence: 1,
    label: 'ERP Details Communicated',
    isMandatory: true,
    isDone: false,
    // Handed back unanswered — that is what REJECTED means, and it is what
    // makes the remark unambiguously the reviewer's.
    answer: null,
    remark: 'some of the work is not completed yet',
    reviewState: 'REJECTED',
    rowState: 'REJECTED',
    reviewedBy: { id: COLLEAGUE, displayName: 'Priya Nair' },
    reviewedAt: '2026-09-17T06:00:00Z',
    ...over,
  }
}

/** A row the same review passed and closed. */
function verifiedRow(over: Record<string, unknown> = {}) {
  return {
    id: 2,
    stepId: 900,
    sequence: 2,
    label: 'Master Data verification',
    isMandatory: true,
    isDone: true,
    answer: true,
    remark: null,
    reviewState: 'VERIFIED',
    rowState: 'VERIFIED',
    reviewLocked: true,
    reviewedBy: { id: COLLEAGUE, displayName: 'Priya Nair' },
    reviewedAt: '2026-09-17T06:00:00Z',
    ...over,
  }
}

const header = () => screen.getByTestId('ob-check-list-header')

describe('the check list', () => {
  /**
   * It was a disclosure, closed on every open task. The task has a section of
   * its own now, so the rows are drawn open — and the header still states what
   * is in them.
   */
  it('is open, and its header states what is inside', () => {
    renderPanel()

    expect(screen.getByText('Master Data verification')).toBeVisible()
    expect(within(header()).getByText(/1 of 2 answered/)).toBeInTheDocument()
    expect(within(header()).getByText(/1 outstanding/)).toBeInTheDocument()
  })

  /** Steps are numbered and tasks lettered, so the items take i, ii, iii. */
  it('numbers the items in roman numerals', () => {
    renderPanel()

    expect(screen.getByText('i.')).toBeInTheDocument()
    expect(screen.getByText('ii.')).toBeInTheDocument()
    // The label is still its own text, so the item reads as itself.
    expect(screen.getByText('Master Data verification')).toBeInTheDocument()
  })

  /**
   * Named once at the top rather than guessed at per row.
   *
   * <p>Send is last because it is the last thing done to a row — answer it,
   * let it be reviewed, say why, hand it over — and it is drawn only for a
   * reader who has something to send. The manager's Review column sits between
   * Action and Remark and appears only once a row has been out.
   */
  it('is a grid, with its columns named', () => {
    renderPanel()

    const columns = screen.getAllByRole('columnheader').map((c) => c.textContent)
    expect(columns).toEqual(['#', 'Checklist item', 'Action', 'Remark'])
    expect(screen.getAllByTestId('ob-check-list-row')).toHaveLength(2)
  })

  /**
   * The list goes to its reviewer as a unit, from the task's own button —
   * there is no per-row hand-over left to give a column to.
   */
  it('draws no Send column at all', () => {
    renderPanel({}, false)

    const columns = screen.getAllByRole('columnheader').map((c) => c.textContent)
    expect(columns).toEqual(['#', 'Checklist item', 'Action', 'Remark'])
    expect(screen.queryByTestId('ob-check-list-row-send')).not.toBeInTheDocument()
  })

  /**
   * One button per row, not two. It states the answer it is holding and says
   * what pressing it will do — the name carries the state because
   * `aria-pressed` has two values and this has three.
   */
  it('carries one status button per row, which states the answer it holds', () => {
    renderPanel()

    expect(
      screen.getByRole('button', { name: 'Data Sanitization — Completed. Press to set Not completed.' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', {
        name: 'Master Data verification — Not answered. Press to set Completed.',
      }),
    ).toBeInTheDocument()
    // The pair it replaced, in the words it replaced.
    expect(screen.queryByRole('button', { name: /Verified/ })).not.toBeInTheDocument()
  })

  /**
   * The button says the same thing whatever it holds — Mark complete, the
   * act rather than the state — and the fill is what changed. Grey and dashed
   * on a row not marked complete, solid green on one that is.
   */
  it('says Mark complete on every row, and fills green only on a completed one', () => {
    renderPanel()

    const [answered, untouched] = screen.getAllByTestId('ob-check-item-answer')
    expect(answered).toHaveTextContent('Mark complete')
    expect(untouched).toHaveTextContent('Mark complete')

    expect(answered).toHaveAttribute('data-answer', 'true')
    expect(answered!.className).toContain('bg-success')

    expect(untouched).toHaveAttribute('data-answer', 'unset')
    expect(untouched!.className).toContain('border-dashed')
    expect(untouched!.className).not.toContain('bg-success')

    // The ring is gone, and so are the words that described a row's state
    // rather than saying what pressing it would do.
    expect(screen.queryByTestId('ob-check-item-dot')).toBeNull()
    expect(screen.queryByText('Not answered')).not.toBeInTheDocument()
    expect(screen.queryByText('Not completed')).not.toBeInTheDocument()
  })

  /**
   * A rejection is the second press, and it puts the control back on the look
   * it started from — there is no third appearance to learn. What tells it
   * from an untouched row is ↺ beside it, the row's own tint, and the header's
   * exception count.
   */
  it('draws a rejected row exactly as it draws an untouched one', () => {
    renderPanel({
      items: [
        {
          id: 1,
          stepId: 900,
          sequence: 1,
          label: 'Data Sanitization',
          isMandatory: true,
          isDone: false,
          answer: false,
          remark: null,
        },
        {
          id: 2,
          stepId: 900,
          sequence: 2,
          label: 'Master Data verification',
          isMandatory: true,
          isDone: false,
          answer: null,
          remark: null,
        },
      ],
    } as Partial<ProjectTask>)

    const [rejected, untouched] = screen.getAllByTestId('ob-check-item-answer')
    expect(rejected).toHaveAttribute('data-answer', 'false')
    expect(untouched).toHaveAttribute('data-answer', 'unset')
    expect(rejected).toHaveTextContent('Mark complete')
    expect(rejected!.className).toBe(untouched!.className)
    expect(rejected!.className).not.toContain('bg-success')

    // ↺ is the difference a reader can see: an untouched row has nothing to
    // clear, a rejected one does.
    const [rejectedRow, untouchedRow] = screen.getAllByTestId('ob-check-list-row')
    expect(within(rejectedRow!).getByRole('button', { name: /Clear the answer/ })).toBeInTheDocument()
    expect(within(untouchedRow!).queryByRole('button', { name: /Clear the answer/ })).toBeNull()
  })

  it('holds a recorded Not completed in the answer it was given', () => {
    renderPanel({
      items: [
        {
          id: 1,
          stepId: 900,
          sequence: 1,
          label: 'Data Sanitization',
          isMandatory: true,
          isDone: false,
          answer: false,
          remark: 'Source file was short',
        },
      ],
    })

    const answer = screen.getByTestId('ob-check-item-answer')
    expect(answer).toHaveAttribute('data-answer', 'false')
    // Unfilled, like an untouched row — the state survives in the control's
    // name, which is what a screen reader announces.
    expect(answer.className).not.toContain('bg-success')
    expect(answer).toHaveAccessibleName(
      'Data Sanitization — Not completed. Press to set Completed.',
    )
    // The row it sits on is the thing that reddens.
    expect(screen.getAllByTestId('ob-check-list-row')[0]!.className).toContain('bg-danger-soft')
  })

  /**
   * Never colour alone (blueprint §12.1). The green fill is never the only
   * difference — the word changes with it, and ✓ against ○ carries it with
   * the hue off. The control's own name still carries the whole sentence.
   */
  it('keeps the state in the control’s name, word and mark, not only in the colour', () => {
    renderPanel()

    const button = screen.getByRole('button', {
      name: 'Data Sanitization — Completed. Press to set Not completed.',
    })
    expect(button).toHaveAttribute('title', 'Press to set Not completed')
    expect(button).toHaveTextContent('✓')
    expect(screen.getAllByTestId('ob-check-item-answer')[1]).toHaveTextContent('○')
  })

  /** Press Completed and it becomes Not completed. */
  it('flips an answered item to the other answer', () => {
    renderPanel()

    fireEvent.click(
      screen.getByRole('button', { name: 'Data Sanitization — Completed. Press to set Not completed.' }),
    )

    // The mutation is what carries the answer; the row re-reads from the query
    // once it lands, so what this asserts is that the press was not refused.
    expect(
      screen.getByRole('button', { name: 'Data Sanitization — Completed. Press to set Not completed.' }),
    ).toBeEnabled()
  })

  /**
   * The remark is optional on both answers — PLAN.md §4, D-17. Answering Not
   * completed with an empty remark used to be stopped here and refused by the
   * server; now it is simply the answer.
   */
  it('records Not completed with no remark, and never asks for one', () => {
    renderPanel()

    const row = screen.getAllByTestId('ob-check-list-row')[1]!
    fireEvent.click(
      within(row).getByRole('button', {
        name: 'Master Data verification — Not answered. Press to set Completed.',
      }),
    )

    expect(within(row).getByRole('textbox')).not.toHaveFocus()
    expect(within(row).getByPlaceholderText('Remark (optional)…')).toBeInTheDocument()
    expect(screen.queryByText(/required/i)).not.toBeInTheDocument()
  })

  /**
   * ↺ is the only way back to unanswered, since the status button flips
   * between the two answers. It exists only where there is an answer to clear
   * — hiding it in CSS would leave a tab stop on every unanswered row.
   */
  it('offers a clear on an answered row and none on an unanswered one', () => {
    renderPanel()

    const [answered, unanswered] = screen.getAllByTestId('ob-check-list-row')
    expect(within(answered!).getByRole('button', { name: /Clear the answer/ })).toBeInTheDocument()
    expect(within(unanswered!).queryByRole('button', { name: /Clear the answer/ })).toBeNull()
  })

  it('holds the answers for anybody but the owner', () => {
    renderPanel({}, false)

    expect(
      screen.getByRole('button', { name: 'Data Sanitization — Completed. Press to set Not completed.' }),
    ).toBeDisabled()
    expect(screen.getByRole('button', { name: /Clear the answer for Data Sanitization/ })).toBeDisabled()
  })

  /** A recorded Not completed is a problem, and the header counts it. */
  it('reports an exception on the header', () => {
    renderPanel({
      items: [
        {
          id: 1,
          stepId: 900,
          sequence: 1,
          label: 'Data Sanitization',
          isMandatory: true,
          isDone: false,
          answer: false,
          remark: 'Source file short by 40 rows',
        },
      ],
    } as Partial<ProjectTask>)

    expect(within(header()).getByText(/1 not completed/)).toBeInTheDocument()
  })

  /** Nothing mandatory left blank, so there is nothing to go in and answer. */
  it('reports no outstanding count when every mandatory item is answered', () => {
    renderPanel({
      items: [
        {
          id: 1,
          stepId: 900,
          sequence: 1,
          label: 'Data Sanitization',
          isMandatory: true,
          isDone: true,
          answer: true,
          remark: null,
        },
      ],
    } as Partial<ProjectTask>)

    expect(within(header()).getByText(/1 of 1 answered/)).toBeInTheDocument()
    expect(within(header()).queryByText(/outstanding/)).not.toBeInTheDocument()
  })

  /**
   * The header carries the gate's own state. Asserted through `data-gate`
   * rather than through class names: the tones are a design decision and may
   * be restyled, but which of the three a check list is in is behaviour.
   */
  describe('the header says whether the gate is clear', () => {
    const one = (over: Record<string, unknown>) =>
      ({
        items: [
          {
            id: 1,
            stepId: 900,
            sequence: 1,
            label: 'Data Sanitization',
            isMandatory: true,
            isDone: false,
            answer: null,
            remark: null,
            ...over,
          },
        ],
      }) as Partial<ProjectTask>

    it('is clear when every mandatory item is answered', () => {
      renderPanel(one({ isDone: true, answer: true }))

      expect(header()).toHaveAttribute('data-gate', 'clear')
    })

    it('is outstanding while a mandatory item is unanswered', () => {
      renderPanel(one({}))

      expect(header()).toHaveAttribute('data-gate', 'outstanding')
    })

    /** A recorded Not completed outranks an unanswered item: it is a known problem. */
    it('reports exceptions ahead of anything outstanding', () => {
      renderPanel(one({ isDone: true, answer: false, remark: 'Short by 40 rows' }))

      expect(header()).toHaveAttribute('data-gate', 'exceptions')
    })
  })

  /**
   * The old panel called this a Task list, which read as a list of tasks on a
   * screen where the row above it is the task.
   */
  it('is called a Check List', () => {
    renderPanel()

    expect(screen.getByText('Check List')).toBeInTheDocument()
    expect(screen.queryByText('Task list')).not.toBeInTheDocument()
  })
})

/**
 * A task that came back — the popup an implementor opens from My Tasks after a
 * manager has returned a row.
 *
 * <p>Three things were wrong with it and all three are the same mistake in
 * different places: the screen drew controls and form fields for a reader who
 * had no part in them, and said things about rows that were not there.
 */
describe('a task that came back from review', () => {
  const returned = { items: [returnedRow()] } as unknown as Partial<ProjectTask>

  it('states the manager’s verdict rather than offering a button nobody can press', () => {
    renderPanel(returned)

    const verdict = screen.getByTestId('ob-check-list-row-verdict')
    expect(verdict).toHaveTextContent('Rejected')
    // Whose call it was. The implementor is about to reply to this person.
    expect(verdict).toHaveTextContent('Priya Nair')
    // And no control, greyed or otherwise, in a column that is not theirs.
    expect(screen.queryByRole('button', { name: /Rejected/ })).not.toBeInTheDocument()
  })

  it('says whose words are sitting in the remark box', () => {
    renderPanel(returned)

    expect(screen.getByTestId('ob-check-item-reason-from')).toHaveTextContent(
      'Priya Nair’s reason for sending it back',
    )
    // Still theirs to replace as they rework it — the server allows it, and the
    // caption is what stops them doing so without knowing what they are
    // overwriting.
    expect(screen.getByLabelText('Remark for ERP Details Communicated')).toHaveValue(
      'some of the work is not completed yet',
    )
  })

  /**
   * A reader who is neither the owner nor the reviewer had the reason served as
   * a greyed text box — a form field nobody can fill. It is a sentence somebody
   * wrote, so it is drawn as one.
   */
  it('draws the reason as text, not a dead input, for a reader with no part in it', () => {
    renderPanel(returned, false)

    expect(screen.getByTestId('ob-check-item-remark-text')).toHaveTextContent(
      'some of the work is not completed yet',
    )
    expect(
      screen.queryByLabelText('Remark for ERP Details Communicated'),
    ).not.toBeInTheDocument()
  })

  /**
   * The banner claimed "the rest were verified and are locked" on a one-item
   * check list, where there is no rest. A banner describing rows a reader can
   * see are not on the screen is the first thing they stop believing.
   */
  it('does not claim other rows are locked when the whole list came back', () => {
    renderPanel(returned)

    const banner = screen.getByText('1 item came back').closest('[role="status"]')
    expect(banner).not.toHaveTextContent('the rest were verified')
    expect(banner).toHaveTextContent('the check list is open again')
    // And what to do about it, which it never said.
    expect(banner).toHaveTextContent('Fix it, answer it again, then press Send for Verification')
  })

  it('still says the rest are locked when there actually are others', () => {
    renderPanel({ items: [returnedRow(), verifiedRow()] } as unknown as Partial<ProjectTask>)

    const banner = screen.getByText('1 item came back').closest('[role="status"]')
    expect(banner).toHaveTextContent('the rest were verified and are locked')
  })

  /**
   * The reviewer gets the control, and it is now three options rather than one
   * cycling button — every verdict reachable in one press, none of them hidden
   * behind the one the row is currently holding.
   */
  it('gives the manager holding the review all three verdicts at once', () => {
    renderPanel(
      { status: 'PENDING_REVIEW', items: [returnedRow({ rowState: 'SENT' })] } as unknown as Partial<ProjectTask>,
      false,
      true,
    )

    const group = screen.getByRole('radiogroup')
    expect(within(group).getAllByRole('radio').map((r) => r.textContent)).toEqual([
      '○Not Reviewed',
      '✓Verified',
      '↻Rejected',
    ])
    expect(screen.queryByTestId('ob-check-list-row-verdict')).not.toBeInTheDocument()
  })

  /**
   * The Action column is the implementor's control, and a manager may not press
   * it in any state — so it is not drawn for them at all.
   */
  it('drops the Action column for the manager holding the review', () => {
    renderPanel(
      { status: 'PENDING_REVIEW', items: [returnedRow({ rowState: 'SENT' })] } as unknown as Partial<ProjectTask>,
      false,
      true,
    )

    expect(screen.getAllByRole('columnheader').map((c) => c.textContent)).toEqual([
      '#',
      'Checklist item',
      'Review · manager',
      'Remark',
    ])
  })
})

/**
 * The hand-over, in both directions, from one button each.
 *
 * <p>There is no per-row Send and no per-row Send back any more. The
 * implementor's list goes as a unit from the task's own button, and a
 * rejection goes home the moment its reason is written.
 */
describe('the hand-over', () => {
  /** A row on the reviewer's desk with no verdict on it yet. */
  const sentRow = (over: Record<string, unknown> = {}) => ({
    id: 1,
    stepId: 900,
    sequence: 1,
    label: 'ERP Details Communicated',
    isMandatory: true,
    isDone: true,
    answer: true,
    remark: null,
    reviewState: 'NOT_REVIEWED',
    rowState: 'SENT',
    ...over,
  })

  it('offers the owner Send for Verification, and names the row holding it', () => {
    renderPanel()

    const button = screen.getByTestId('ob-task-action-send')
    expect(button).toHaveTextContent('Send for Verification')
    expect(button).toBeDisabled()
    expect(
      screen.getByText(/Send for Verification is waiting on 1 unanswered row/),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('ob-task-action-complete')).not.toBeInTheDocument()
  })

  /** The other half of the same button, and the only thing that closes a task. */
  it('turns it into Mark Complete once every row is verified', () => {
    renderPanel({
      items: [verifiedRow({ id: 1 }), verifiedRow({ id: 2 })],
    } as unknown as Partial<ProjectTask>)

    expect(screen.getByTestId('ob-task-action-complete')).toHaveTextContent('Mark Complete')
    expect(screen.queryByTestId('ob-task-action-send')).not.toBeInTheDocument()
  })

  /**
   * Rejecting *is* sending back. The two presses this used to take left rows
   * sitting rejected on a manager's screen that their implementor could not
   * see — but the reason still has to come first, because a release without
   * one is refused.
   */
  it('sends a rejected row back on its reason alone, with no button to find', async () => {
    const verdicts: { itemId: string; body: unknown }[] = []
    const released: string[] = []
    server.use(
      http.patch('*/onboarding/journey-step-items/:itemId/review', async ({ params, request }) => {
        verdicts.push({ itemId: String(params.itemId), body: await request.json() })
        return HttpResponse.json({ id: Number(params.itemId) })
      }),
      http.post('*/onboarding/journey-step-items/:itemId/send-back', ({ params }) => {
        released.push(String(params.itemId))
        return HttpResponse.json({ id: Number(params.itemId) })
      }),
    )

    renderPanel(
      { status: 'PENDING_REVIEW', items: [sentRow()] } as unknown as Partial<ProjectTask>,
      false,
      true,
    )

    fireEvent.click(screen.getByRole('radio', { name: 'Rejected' }))

    await waitFor(() => expect(verdicts).toHaveLength(1))
    expect(verdicts[0]?.body).toEqual({ state: 'REJECTED' })
    // Not yet. A rejection the server would refuse is not one anybody should
    // have sent.
    expect(released).toEqual([])

    // And the box to write it in is already on the screen, with the caret in
    // it — it used to open a refetch later, which is to say after the press
    // that was supposed to put the reader in it.
    const box = screen.getByLabelText('Reason for rejecting ERP Details Communicated')
    await waitFor(() => expect(box).toHaveFocus())

    fireEvent.change(box, { target: { value: 'Sheet 2 was never shared' } })
    fireEvent.blur(box)

    await waitFor(() => expect(released).toEqual(['1']))
    expect(verdicts.at(-1)?.body).toEqual({
      state: 'REJECTED',
      remark: 'Sheet 2 was never shared',
    })
  })

  /** Verified stays reversible — it is the task's own button that releases it. */
  it('records Verified without sending anything anywhere', async () => {
    const verdicts: unknown[] = []
    const released: string[] = []
    server.use(
      http.patch('*/onboarding/journey-step-items/:itemId/review', async ({ params, request }) => {
        verdicts.push(await request.json())
        return HttpResponse.json({ id: Number(params.itemId) })
      }),
      http.post('*/onboarding/journey-step-items/:itemId/send-back', ({ params }) => {
        released.push(String(params.itemId))
        return HttpResponse.json({ id: Number(params.itemId) })
      }),
    )

    renderPanel(
      { status: 'PENDING_REVIEW', items: [sentRow()] } as unknown as Partial<ProjectTask>,
      false,
      true,
    )

    fireEvent.click(screen.getByRole('radio', { name: 'Verified' }))

    await waitFor(() => expect(verdicts).toEqual([{ state: 'VERIFIED' }]))
    expect(released).toEqual([])
  })
})
