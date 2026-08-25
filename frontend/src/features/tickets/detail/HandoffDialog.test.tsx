import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import type { Ticket } from '@/api/generated/model/ticket'
import type { Ribbon } from '@/api/generated/model/ribbon'

import { HandoffDialog } from './HandoffDialog'

/**
 * C-044 · S-29's handoff dialog.
 *
 * `POST /tickets/{ticketId}/handoff`, `GET /projects/{projectId}/members` and
 * `GET /masters/workflow-templates` are all intercepted directly here rather
 * than exercised through the shared mock db — `ReopenDialog.test.tsx`'s own
 * reason: the assertions this file cares about are on the request this
 * dialog builds and what it renders from a controlled response, not on the
 * fixture db's own bookkeeping.
 */

const TICKET_CODE = 'CRM-26-00347'
const PROJECT_ID = 1

/** Radix's popover/dialog need APIs jsdom lacks. */
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

type HandoffBody = {
  toStageCode: string
  toUserId: number
  note?: string
  effortHours: number
  attachmentIds?: number[]
}

let posted: { ticketId: string; body: HandoffBody }[] = []

beforeEach(() => {
  posted = []
  server.use(
    http.post('*/tickets/:ticketId/handoff', async ({ params, request }) => {
      const body = (await request.json()) as HandoffBody
      posted.push({ ticketId: String(params.ticketId), body })
      return HttpResponse.json({
        data: { cycleNo: 1, iterationNo: 1, isSealed: false, currentStageCode: body.toStageCode, canAdvance: true, segments: [] },
      })
    }),
    http.get('*/projects/:projectId/members', () =>
      HttpResponse.json({
        data: [
          { userId: 10, displayName: 'Priya Nair', role: 'QA', openTicketCount: 2, isActive: true, addedAt: '2026-08-01T00:00:00Z' },
          { userId: 11, displayName: 'Ravi Kumar', role: 'DEVELOPER', openTicketCount: 7, isActive: true, addedAt: '2026-08-01T00:00:00Z' },
        ],
      }),
    ),
    http.get('*/masters/workflow-templates', () => HttpResponse.json({ data: [] })),
  )
})

function ticket(overrides: Partial<Ticket> = {}): Ticket {
  return {
    ticketId: TICKET_CODE,
    title: 'Checkout fails with 500 on the payment step',
    project: { id: PROJECT_ID, name: 'Client CRM Platform', projectCode: 'CRM' },
    taskTypeId: 2,
    level: 'HIGH',
    originalLevel: 'HIGH',
    status: 'IN_PROGRESS',
    assignee: { id: 44, displayName: 'Meera Iyer' },
    cycleNo: 1,
    createdAt: '2026-08-03T09:00:00Z',
    ...overrides,
  } as Ticket
}

function ribbon(): Ribbon {
  return {
    cycleNo: 1,
    iterationNo: 1,
    isSealed: false,
    currentStageCode: 'DEV',
    canAdvance: true,
    segments: [
      { stageCode: 'TRIAGE', sequence: 1, state: 'COMPLETED', ownerRole: 'SUPPORT' },
      { stageCode: 'DEV', sequence: 2, state: 'CURRENT', ownerRole: 'DEVELOPER' },
      { stageCode: 'QA', sequence: 3, state: 'PENDING', ownerRole: 'QA' },
    ],
  }
}

function renderDialog(props: Partial<Parameters<typeof HandoffDialog>[0]> = {}) {
  const onHandedOff = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <HandoffDialog ticket={ticket()} onHandedOff={onHandedOff} {...props} />
      <Toaster />
    </QueryClientProvider>,
  )
  return { onHandedOff, user: userEvent.setup() }
}

const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: /hand off/i }))
  return screen.findByRole('dialog')
}

async function withOpenDropdown<T>(fieldId: string, use: () => T): Promise<T> {
  const trigger = document.getElementById(fieldId)!
  return waitFor(
    () => {
      if (trigger.getAttribute('data-state') !== 'open') fireEvent.click(trigger)
      return use()
    },
    { timeout: 4000 },
  )
}

async function pickAssignee(name: RegExp) {
  await withOpenDropdown('ticket-handoff-assignee', () =>
    fireEvent.click(screen.getByRole('option', { name })),
  )
}

describe('HandoffDialog — S-29 / C-044', () => {
  it('pre-fills the next stage from the ribbon’s CURRENT segment', async () => {
    const { user } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    expect(within(dialog).getByLabelText(/next stage/i)).toHaveValue('QA')
  })

  it('leaves the next stage blank with no ribbon, rather than guessing one', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    expect(within(dialog).getByLabelText(/next stage/i)).toHaveValue('')
  })

  it('filters the assignee picker to the receiving stage’s owner role and shows open load', async () => {
    const { user } = renderDialog({ ribbon: ribbon() })
    await openDialog(user)

    const options = await withOpenDropdown('ticket-handoff-assignee', () =>
      screen.getAllByRole('option').map((o) => o.textContent),
    )

    // QA owns the next stage in this ribbon; Ravi (DEVELOPER) is filtered out.
    expect(options).toEqual(['Priya Nair · 2 open'])
  })

  it('refuses to submit with no assignee, no confirmed hours', async () => {
    const { user } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    await user.click(within(dialog).getByRole('button', { name: /^hand off$/i }))

    expect(await within(dialog).findByText(/pick who is receiving this ticket/i)).toBeInTheDocument()
    expect(await within(dialog).findByText(/confirm the hours you spent/i)).toBeInTheDocument()
    expect(posted).toEqual([])
  })

  it('sends the confirmed hours, the assignee and the pre-filled stage', async () => {
    const { user } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    await pickAssignee(/Priya Nair/)
    fireEvent.change(within(dialog).getByLabelText(/effort in this stage/i), { target: { value: '3.5' } })
    await user.click(within(dialog).getByRole('button', { name: /^hand off$/i }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          ticketId: TICKET_CODE,
          body: { toStageCode: 'QA', toUserId: 10, effortHours: 3.5 },
        },
      ]),
    )
  })

  it('sends a genuine zero when no hours were logged in this stage', async () => {
    const { user } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    await pickAssignee(/Priya Nair/)
    fireEvent.change(within(dialog).getByLabelText(/effort in this stage/i), { target: { value: '0' } })
    await user.click(within(dialog).getByRole('button', { name: /^hand off$/i }))

    await waitFor(() => expect(posted[0]?.body.effortHours).toBe(0))
  })

  it('carries a handoff note onto the wire', async () => {
    const { user } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    await pickAssignee(/Priya Nair/)
    fireEvent.change(within(dialog).getByLabelText(/effort in this stage/i), { target: { value: '1' } })
    await user.type(within(dialog).getByLabelText(/handoff note/i), 'QA build is on staging.')
    await user.click(within(dialog).getByRole('button', { name: /^hand off$/i }))

    await waitFor(() => expect(posted[0]?.body.note).toBe('QA build is on staging.'))
  })

  it('closes on success, toasts and asks the caller to refetch', async () => {
    const { user, onHandedOff } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    await pickAssignee(/Priya Nair/)
    fireEvent.change(within(dialog).getByLabelText(/effort in this stage/i), { target: { value: '1' } })
    await user.click(within(dialog).getByRole('button', { name: /^hand off$/i }))

    await waitFor(() => expect(onHandedOff).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect((await screen.findAllByText(`${TICKET_CODE} handed off`)).length).toBeGreaterThan(0)
  })

  it('keeps the dialog open and reports the golden rule’s 422 without refetching', async () => {
    server.use(
      http.post('*/tickets/:ticketId/handoff', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/stage-owner-required',
            title: 'Only the current stage owner may advance this ticket',
            status: 422,
            detail: 'ticket 347 may only be advanced by its current stage owner (user 44), plus PM and Admin.',
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    const { user, onHandedOff } = renderDialog({ ribbon: ribbon() })
    const dialog = await openDialog(user)

    await pickAssignee(/Priya Nair/)
    fireEvent.change(within(dialog).getByLabelText(/effort in this stage/i), { target: { value: '1' } })
    await user.click(within(dialog).getByRole('button', { name: /^hand off$/i }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/current stage owner/i)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(onHandedOff).not.toHaveBeenCalled()
  })
})
