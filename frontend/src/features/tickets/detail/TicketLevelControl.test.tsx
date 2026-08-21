import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import type { Ticket } from '@/api/generated/model/ticket'

import { TicketLevelControl } from './TicketLevelControl'

/**
 * C-020 · the §4B.1 editor.
 *
 * <b>There is no mock handler for `PATCH /tickets/{id}/priority`.</b>
 * `frontend/src/mocks/` is Stream D's, so the request is intercepted here rather
 * than added there — which also makes the assertions about the *body* direct,
 * since the captured request is this file's own.
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

let patched: { ticketId: string; body: { level: string; reason?: string } }[] = []

beforeEach(() => {
  patched = []
  server.use(
    http.patch('*/tickets/:ticketId/priority', async ({ params, request }) => {
      const body = (await request.json()) as { level: string; reason?: string }
      patched.push({ ticketId: String(params.ticketId), body })
      return HttpResponse.json({ data: { ...ticket(), level: body.level } })
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
    status: 'IN_PROGRESS',
    assignee: { id: 44, displayName: 'Meera Iyer' },
    cycleNo: 1,
    createdAt: '2026-08-03T09:00:00Z',
    ...overrides,
  } as Ticket
}

function renderControl(props: Partial<Parameters<typeof TicketLevelControl>[0]> = {}) {
  const onChanged = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <TicketLevelControl
        ticket={ticket()}
        clockStart="2026-08-03T09:00:00Z"
        canEdit
        onChanged={onChanged}
        {...props}
      />
      <Toaster />
    </QueryClientProvider>,
  )
  return { onChanged, user: userEvent.setup() }
}

/**
 * `GET /masters/priorities` as it will answer once its active-only default
 * widens: every level, the retired one carrying `isActive: false`. A literal
 * rather than the shared fixture, for the reason the file header gives about
 * `frontend/src/mocks/` belonging to Stream D.
 */
function retire(retired: string) {
  const levels = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
  server.use(
    http.get('*/masters/priorities', () =>
      HttpResponse.json({
        data: levels.map((level, index) => ({
          id: index + 1,
          level,
          name: level[0] + level.slice(1).toLowerCase(),
          seq: index + 1,
          isActive: level !== retired,
        })),
      }),
    ),
  )
}

const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: /change the level/i }))
  return screen.findByRole('dialog')
}

describe('TicketLevelControl — §4B.1', () => {
  /**
   * The three roles §4B.1 excludes, and every viewer whose role has not resolved
   * yet, see exactly what C-019 drew. Asserted as "no button at all" rather than
   * "a disabled button": a disabled control is an invitation with a refusal
   * attached, and the request path those roles actually have does not exist yet.
   */
  it('renders a plain chip with no affordance when the viewer may not change it', () => {
    renderControl({ canEdit: false })

    expect(screen.getByText('MEDIUM')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('opens a dialog offering every level from the priority master', async () => {
    const { user } = renderControl()

    await openDialog(user)

    const group = await screen.findByRole('radiogroup')
    await waitFor(() =>
      expect(within(group).getAllByRole('radio').map((r) => r.textContent)).toEqual([
        'LOW',
        'MEDIUM',
        'HIGH',
        'CRITICAL',
      ]),
    )
    // The ticket's own level opens selected, so the dialog is a picker rather
    // than a blank form the user has to re-answer.
    expect(within(group).getByRole('radio', { name: 'MEDIUM' })).toHaveAttribute('aria-checked', 'true')
  })

  /**
   * C-072 · a level S-12 has retired is not offered here either. The override
   * is `GET /masters/priorities` once its active-only default widens to match
   * `listTaskTypes` and `listModules` (`DEPENDENCIES.md` row 23) — until then
   * the endpoint hides the row and this defect cannot be reached, which is
   * exactly why it has to be tested against the widened shape.
   */
  it('does not offer a level the priority master has retired', async () => {
    retire('CRITICAL')
    const { user } = renderControl()

    await openDialog(user)

    const group = await screen.findByRole('radiogroup')
    await waitFor(() =>
      expect(within(group).getAllByRole('radio').map((r) => r.textContent)).toEqual([
        'LOW',
        'MEDIUM',
        'HIGH',
      ]),
    )
  })

  /**
   * The exception that makes `levelsIncludingCurrent` a separate function from
   * `selectableLevels`.
   *
   * Retiring CRITICAL does not move the tickets already there. If the filter
   * were applied flat, this dialog would open on a born-critical ticket with
   * nothing checked — which reads as "this ticket has no level" — and the user
   * could not close it back onto the level the ticket already holds without
   * changing it to something else. The retired level is appended rather than
   * inserted, because a retired row has no place in a sequence it has left.
   */
  it('still offers the level the ticket is at, even once it is retired', async () => {
    retire('CRITICAL')
    const { user } = renderControl({ ticket: ticket({ level: 'CRITICAL', originalLevel: 'CRITICAL' }) })

    await openDialog(user)

    const group = await screen.findByRole('radiogroup')
    await waitFor(() =>
      expect(within(group).getAllByRole('radio').map((r) => r.textContent)).toEqual([
        'LOW',
        'MEDIUM',
        'HIGH',
        'CRITICAL',
      ]),
    )
    expect(within(group).getByRole('radio', { name: 'CRITICAL' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
  })

  /**
   * §4B.1: "recomputes the Planned Close Date and shows the new date inline
   * before the user commits". The point of the whole preview is that a support
   * agent picking Critical sees what they are committing somebody to *first*.
   *
   * The preview endpoint is stubbed rather than left to the shared mock db —
   * this ticket is a fixture of this file's own and its project id is not in
   * that store, so the real handler would answer with a refusal and the test
   * would be asserting `SlaPreview`'s error state. Stubbing also lets the
   * request itself be asserted, which is where C-020's own rule lives:
   * **`from` must be the cycle's start, not now.** A preview measured from now
   * shows a comfortable date for a ticket the server is about to mark breached,
   * and only the dialog would be visible.
   */
  it('previews the recomputed date from the cycle’s clock start, before anything is written', async () => {
    const asked: URL[] = []
    server.use(
      http.get('*/tickets/planned-close-date', ({ request }) => {
        asked.push(new URL(request.url))
        return HttpResponse.json({
          data: {
            from: '2026-08-03T09:00:00Z',
            plannedCloseDate: '2026-08-03T13:00:00Z',
            source: 'ORG_DEFAULT',
            resolutionHrs: 4,
          },
        })
      }),
    )
    const { user } = renderControl()
    await openDialog(user)

    await user.click(await screen.findByRole('radio', { name: 'CRITICAL' }))

    // Asserted on the SLA figure and its source rather than on the formatted
    // date, which `SlaPreview` renders in the *viewer's* timezone — a literal
    // would pin this test to whichever zone the machine running it is in.
    const preview = await screen.findByRole('status', undefined, { timeout: 4000 })
    expect(preview.textContent).toMatch(/Planned close date/i)
    expect(preview.textContent).toMatch(/4 working hours/i)

    const asking = asked.at(-1)
    expect(asking?.searchParams.get('level')).toBe('CRITICAL')
    expect(asking?.searchParams.get('from')).toBe('2026-08-03T09:00:00Z')
    // Shown, not written. §4B.1's whole point is that the date arrives before
    // the commit rather than after it.
    expect(patched).toEqual([])
  })

  /**
   * The rule that has no Bean Validation expression and therefore only exists if
   * something asserts it. The dialog must refuse before the request, not send a
   * body it knows will earn a 400.
   */
  it('refuses to submit an assigned ticket without a reason', async () => {
    const { user } = renderControl()
    await openDialog(user)

    await user.click(await screen.findByRole('radio', { name: 'HIGH' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/a reason is required/i)).toBeInTheDocument()
    expect(patched).toEqual([])
  })

  it('sends the level and the reason, then asks the page to refetch', async () => {
    const { user, onChanged } = renderControl()
    await openDialog(user)

    await user.click(await screen.findByRole('radio', { name: 'CRITICAL' }))
    await user.type(screen.getByLabelText(/reason/i), 'Client escalated — production is down.')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(patched).toEqual([
        {
          ticketId: TICKET_CODE,
          body: { level: 'CRITICAL', reason: 'Client escalated — production is down.' },
        },
      ]),
    )
    // Refetch rather than a cache patch: the planned close date and the history
    // moved too, and both render elsewhere on the page.
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
  })

  /** "Optional at creation" — an unassigned ticket sends no `reason` key at all. */
  it('lets an unassigned ticket change level with no reason', async () => {
    const { user } = renderControl({ ticket: ticket({ assignee: undefined }) })
    await openDialog(user)

    await user.click(await screen.findByRole('radio', { name: 'HIGH' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patched).toEqual([{ ticketId: TICKET_CODE, body: { level: 'HIGH' } }]))
  })

  /**
   * Re-picking the level the ticket already holds must not write. The server
   * treats it as a no-op too, but a request that exists only to be ignored is
   * still a round trip that can fail.
   */
  it('cannot submit the level the ticket already holds', async () => {
    const { user } = renderControl()
    await openDialog(user)

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  /** An abandoned edit must not be waiting inside the dialog the next time it opens. */
  it('reopens on the ticket’s level after a cancel, not on the abandoned pick', async () => {
    const { user } = renderControl()
    await openDialog(user)

    await user.click(await screen.findByRole('radio', { name: 'CRITICAL' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    await openDialog(user)
    const group = await screen.findByRole('radiogroup')
    expect(within(group).getByRole('radio', { name: 'MEDIUM' })).toHaveAttribute('aria-checked', 'true')
  })

  /** A refused write leaves the dialog open with the user's typing intact. */
  it('keeps the dialog open and reports a server refusal', async () => {
    server.use(
      http.patch('*/tickets/:ticketId/priority', () =>
        HttpResponse.json(
          { error: { title: 'Validation failed', detail: 'That level is not in the master.' } },
          { status: 400 },
        ),
      ),
    )
    const { user, onChanged } = renderControl()
    await openDialog(user)

    await user.click(await screen.findByRole('radio', { name: 'HIGH' }))
    await user.type(screen.getByLabelText(/reason/i), 'Escalating after the client call.')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(onChanged).not.toHaveBeenCalled()
  })
})
