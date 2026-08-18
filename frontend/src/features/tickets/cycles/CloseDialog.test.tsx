import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import type { Ticket } from '@/api/generated/model/ticket'

import { CloseDialog } from './CloseDialog'

/**
 * C-040 · S-23's close dialog.
 *
 * The shared mock already carries a real `POST /tickets/:ticketId/close`
 * handler (Stream D's `frontend/src/mocks/handlers/tickets.ts`), but every
 * test here overrides it — `TicketLevelControl.test.tsx`'s own precedent for
 * a sibling dialog: a fixture ticket built for this file has no seeded row in
 * the shared db, and overriding keeps the request body itself assertable.
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

let posted: { ticketId: string; body: Record<string, unknown> }[] = []

beforeEach(() => {
  posted = []
  server.use(
    http.post('*/tickets/:ticketId/close', async ({ params, request }) => {
      const body = (await request.json()) as Record<string, unknown>
      posted.push({ ticketId: String(params.ticketId), body })
      return HttpResponse.json({ data: { ...ticket(), status: 'CLOSED' } })
    }),
  )
})

function ticket(overrides: Partial<Ticket> = {}): Ticket {
  return {
    ticketId: TICKET_CODE,
    title: 'Checkout fails with 500 on the payment step',
    project: { id: 12, name: 'CRM Revamp', projectCode: 'CRM' },
    taskTypeId: 4,
    level: 'MEDIUM',
    originalLevel: 'MEDIUM',
    status: 'RESOLVED',
    assignee: { id: 44, displayName: 'Meera Iyer' },
    cycleNo: 1,
    createdAt: '2026-08-03T09:00:00Z',
    ...overrides,
  } as Ticket
}

function renderDialog(props: Partial<Parameters<typeof CloseDialog>[0]> = {}) {
  const onClosed = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <CloseDialog ticket={ticket()} onClosed={onClosed} {...props} />
      <Toaster />
    </QueryClientProvider>,
  )
  return { onClosed, user: userEvent.setup() }
}

const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: 'Close' }))
  return screen.findByRole('dialog')
}

describe('CloseDialog — S-23', () => {
  it('renders the trigger and opens a dialog naming the cycle it will seal', async () => {
    const { user } = renderDialog({ ticket: ticket({ cycleNo: 2 }) })

    await openDialog(user)

    expect(screen.getByText(/seals cycle 2/i)).toBeInTheDocument()
  })

  /** The only field S-23 marks with an asterisk. */
  it('refuses to submit with no resolution summary', async () => {
    const { user } = renderDialog()
    await openDialog(user)

    await user.click(screen.getByRole('button', { name: 'Close ticket' }))

    expect(await screen.findByText(/at least a few words/i)).toBeInTheDocument()
    expect(posted).toEqual([])
  })

  it('sends only the resolution summary when every other field is left blank', async () => {
    const { user, onClosed } = renderDialog()
    await openDialog(user)

    await user.type(
      screen.getByLabelText(/resolution summary/i),
      'Root cause identified and the fix deployed to production.',
    )
    await user.click(screen.getByRole('button', { name: 'Close ticket' }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          ticketId: TICKET_CODE,
          body: { resolutionSummary: 'Root cause identified and the fix deployed to production.' },
        },
      ]),
    )
    await waitFor(() => expect(onClosed).toHaveBeenCalledTimes(1))
  })

  it('carries the root cause category and the final effort confirmation when supplied', async () => {
    const { user } = renderDialog()
    await openDialog(user)

    await user.type(screen.getByLabelText(/resolution summary/i), 'Fixed the race condition.')
    await user.type(screen.getByLabelText(/root cause category/i), 'Configuration drift')
    await user.type(screen.getByLabelText(/final effort/i), '18.5')
    await user.click(screen.getByRole('checkbox', { name: /ask the client to verify/i }))
    await user.click(screen.getByRole('button', { name: 'Close ticket' }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          ticketId: TICKET_CODE,
          body: {
            resolutionSummary: 'Fixed the race condition.',
            rootCauseCategory: 'Configuration drift',
            finalEffortHours: 18.5,
            requestClientVerification: true,
          },
        },
      ]),
    )
  })

  /** A refused write leaves the dialog open with the user's typing intact. */
  it('keeps the dialog open and reports a server refusal', async () => {
    server.use(
      http.post('*/tickets/:ticketId/close', () =>
        HttpResponse.json(
          {
            error: {
              title: 'That ticket is not resolved',
              detail: 'this ticket is IN_PROGRESS, so there is no resolved cycle to close.',
            },
          },
          { status: 422 },
        ),
      ),
    )
    const { user, onClosed } = renderDialog()
    await openDialog(user)

    await user.type(screen.getByLabelText(/resolution summary/i), 'Fixed the race condition.')
    await user.click(screen.getByRole('button', { name: 'Close ticket' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(onClosed).not.toHaveBeenCalled()
  })

  /** An abandoned draft must not be waiting inside the dialog the next time it opens. */
  it('clears an abandoned draft after a cancel', async () => {
    const { user } = renderDialog()
    await openDialog(user)

    await user.type(screen.getByLabelText(/resolution summary/i), 'Abandoned draft text')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    await openDialog(user)
    expect(screen.getByLabelText(/resolution summary/i)).toHaveValue('')
  })
})
