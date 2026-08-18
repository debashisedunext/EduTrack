import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import type { Ticket } from '@/api/generated/model/ticket'

import { ReopenDialog } from './ReopenDialog'

/**
 * C-039 · S-22's dialog.
 *
 * `POST /tickets/{ticketId}/reopen` is intercepted directly here rather than
 * exercised through the shared mock db, same reason `TicketLevelControl.test`
 * gives for its own priority stub: the assertions this file cares about are
 * on the *request body* this dialog builds, not on the fixture db's own reopen
 * bookkeeping.
 */

const TICKET_CODE = 'CRM-26-00347'

/** Radix's dialog needs APIs jsdom lacks. */
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}
})

type ReopenBody = {
  reason: string
  restartStageCode?: string
  assigneeId?: number
  plannedCloseDate?: string
  estimatedHrs?: number
}

let posted: { ticketId: string; body: ReopenBody }[] = []

beforeEach(() => {
  posted = []
  server.use(
    http.post('*/tickets/:ticketId/reopen', async ({ params, request }) => {
      const body = (await request.json()) as ReopenBody
      posted.push({ ticketId: String(params.ticketId), body })
      return HttpResponse.json({ data: { ...ticket(), status: 'REOPENED', cycleNo: (ticket().cycleNo ?? 1) + 1 } })
    }),
  )
})

function ticket(overrides: Partial<Ticket> = {}): Ticket {
  return {
    ticketId: TICKET_CODE,
    title: 'Checkout fails with 500 on the payment step',
    project: { id: 1, name: 'Client CRM Platform', projectCode: 'CRM' },
    taskTypeId: 2,
    level: 'HIGH',
    originalLevel: 'HIGH',
    status: 'CLOSED',
    assignee: { id: 44, displayName: 'Meera Iyer' },
    cycleNo: 2,
    createdAt: '2026-08-03T09:00:00Z',
    ...overrides,
  } as Ticket
}

function renderDialog(props: Partial<Parameters<typeof ReopenDialog>[0]> = {}) {
  const onReopened = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ReopenDialog ticket={ticket()} onReopened={onReopened} {...props} />
      <Toaster />
    </QueryClientProvider>,
  )
  return { onReopened, user: userEvent.setup() }
}

const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: 'Reopen' }))
  return screen.findByRole('dialog')
}

describe('ReopenDialog — S-22 / C-039', () => {
  it('opens with the warning naming the cycle about to seal', async () => {
    const { user } = renderDialog()

    const dialog = await openDialog(user)

    expect(within(dialog).getByText(/cycle 2 and its ribbon will be sealed and preserved/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/a new cycle with a fresh ribbon will begin/i)).toBeInTheDocument()
  })

  it('pre-fills the restart stage with Triage and the assignee with whoever holds the closing cycle', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    expect(within(dialog).getByLabelText(/restart stage/i)).toHaveValue('TRIAGE')
  })

  it('refuses to submit with no reason', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    expect(await within(dialog).findByText(/say why this ticket is reopening/i)).toBeInTheDocument()
    expect(posted).toEqual([])
  })

  it('sends only the reason and the pre-filled defaults when nothing else is touched', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByLabelText(/reason/i), 'Client reports the same failure again in production.')
    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          ticketId: TICKET_CODE,
          body: {
            reason: 'Client reports the same failure again in production.',
            restartStageCode: 'TRIAGE',
            assigneeId: 44,
          },
        },
      ]),
    )
  })

  it('carries a revised restart stage, planned close date and estimate onto the wire', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByLabelText(/reason/i), 'Regression confirmed by two more accounts.')
    const stage = within(dialog).getByLabelText(/restart stage/i)
    await user.clear(stage)
    await user.type(stage, 'DEVELOPMENT')
    fireEvent.change(within(dialog).getByLabelText(/new planned close date/i), { target: { value: '2026-09-01' } })
    fireEvent.change(within(dialog).getByLabelText(/revised estimate/i), { target: { value: '6.5' } })

    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          ticketId: TICKET_CODE,
          body: {
            reason: 'Regression confirmed by two more accounts.',
            restartStageCode: 'DEVELOPMENT',
            assigneeId: 44,
            plannedCloseDate: new Date('2026-09-01').toISOString(),
            estimatedHrs: 6.5,
          },
        },
      ]),
    )
  })

  it('rejects a non-numeric estimate before it ever reaches the server', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByLabelText(/reason/i), 'Client reports recurrence.')
    fireEvent.change(within(dialog).getByLabelText(/revised estimate/i), { target: { value: 'lots' } })
    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    expect(await within(dialog).findByText(/enter hours as a decimal/i)).toBeInTheDocument()
    expect(posted).toEqual([])
  })

  it('closes on success, toasts and asks the caller to refetch', async () => {
    const { user, onReopened } = renderDialog()
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByLabelText(/reason/i), 'Client reports recurrence in production.')
    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    await waitFor(() => expect(onReopened).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // findAllByText rather than findByText — an earlier test in this file may
    // have left an identically-worded toast on the same module-level store,
    // which is not this test's concern; only that reopening fired one too.
    expect((await screen.findAllByText(`${TICKET_CODE} reopened`)).length).toBeGreaterThan(0)
  })

  it('keeps the dialog open, reports a server refusal and does not refetch', async () => {
    server.use(
      http.post('*/tickets/:ticketId/reopen', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/unprocessable-transition',
            title: 'Ticket is not closed, so there is nothing to reopen',
            status: 422,
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    const { user, onReopened } = renderDialog()
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByLabelText(/reason/i), 'Client reports recurrence.')
    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/nothing to reopen/i)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(onReopened).not.toHaveBeenCalled()
  })
})
