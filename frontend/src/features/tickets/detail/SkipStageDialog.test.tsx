import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import type { Ticket } from '@/api/generated/model/ticket'

import { SkipStageDialog } from './SkipStageDialog'

/**
 * C-047 · the Skip Stage dialog. `POST /tickets/{ticketId}/skip-stage` is
 * intercepted directly here rather than through the shared mock db —
 * `HandoffDialog.test.tsx`'s own reason: these assertions care about the
 * request this dialog builds and what it renders from a controlled
 * response, not the fixture db's bookkeeping.
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

type SkipStageResponseBody = { reason: string; toStageCode?: string }

let posted: { ticketId: string; body: SkipStageResponseBody }[] = []
let respondWith: (() => Response) | null = null

beforeEach(() => {
  posted = []
  respondWith = null
  server.use(
    http.post('*/tickets/:ticketId/skip-stage', async ({ params, request }) => {
      const body = (await request.json()) as SkipStageResponseBody
      posted.push({ ticketId: String(params.ticketId), body })
      if (respondWith) return respondWith()
      return HttpResponse.json({
        data: {
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          currentStageCode: body.toStageCode ?? 'DEPLOY',
          canAdvance: true,
          segments: [],
        },
      })
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
    status: 'IN_PROGRESS',
    assignee: { id: 44, displayName: 'Meera Iyer' },
    cycleNo: 1,
    createdAt: '2026-08-03T09:00:00Z',
    ...overrides,
  } as Ticket
}

function renderDialog(props: Partial<Parameters<typeof SkipStageDialog>[0]> = {}) {
  const onSkipped = () => renderDialog.calls++
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <SkipStageDialog ticket={ticket()} onSkipped={onSkipped} {...props} />
      <Toaster />
    </QueryClientProvider>,
  )
  return { user: userEvent.setup() }
}
renderDialog.calls = 0

const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: /skip stage/i }))
  return screen.findByRole('dialog')
}

describe('SkipStageDialog — C-047', () => {
  it('refuses to submit with no reason', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    await user.click(within(dialog).getByRole('button', { name: /^skip stage$/i }))

    expect(await within(dialog).findByText(/explain why this stage is being skipped/i)).toBeInTheDocument()
    expect(posted).toEqual([])
  })

  it('sends the reason, with no toStageCode when the field is left blank', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    fireEvent.change(within(dialog).getByLabelText(/reason/i), {
      target: { value: 'Client waived UAT for this release' },
    })
    await user.click(within(dialog).getByRole('button', { name: /^skip stage$/i }))

    await waitFor(() =>
      expect(posted).toEqual([
        { ticketId: TICKET_CODE, body: { reason: 'Client waived UAT for this release' } },
      ]),
    )
  })

  it('carries an explicit destination stage onto the wire', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'Straight to sign-off' } })
    fireEvent.change(within(dialog).getByLabelText(/land on stage/i), { target: { value: 'SIGNOFF' } })
    await user.click(within(dialog).getByRole('button', { name: /^skip stage$/i }))

    await waitFor(() =>
      expect(posted).toEqual([
        { ticketId: TICKET_CODE, body: { reason: 'Straight to sign-off', toStageCode: 'SIGNOFF' } },
      ]),
    )
  })

  it('closes the dialog and reports success on a 200', async () => {
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'No longer needed' } })
    await user.click(within(dialog).getByRole('button', { name: /^skip stage$/i }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText(/stage skipped/i)).toBeInTheDocument()
  })

  it('shows the server’s refusal — a non-optional stage — without losing what was typed', async () => {
    respondWith = () =>
      HttpResponse.json(
        {
          type: 'https://edutrack/errors/stage-not-optional',
          title: 'This stage may not be skipped',
          status: 422,
          detail: 'Quality Assurance (QA) is not an optional stage on this ticket’s workflow.',
        },
        { status: 422 },
      )
    const { user } = renderDialog()
    const dialog = await openDialog(user)

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'Behind schedule' } })
    await user.click(within(dialog).getByRole('button', { name: /^skip stage$/i }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/not an optional stage/i)
    expect(within(dialog).getByLabelText(/reason/i)).toHaveValue('Behind schedule')
  })
})
