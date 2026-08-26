import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { getDb } from '@/mocks/db'
import type { Ticket } from '@/api/generated/model/ticket'

import { QuickUpdateTrigger } from './QuickUpdatePanel'

/**
 * C-037 · S-21's own exception — "level (unless PM)" — asserted where it
 * actually matters: the affordance either exists in the tree or it does not.
 * `quickUpdatePermissions.test.ts` covers the rule as a pure function;
 * `TicketLevelControl.test.tsx` covers what the control does once reached.
 * This is the seam between the two — whether *this panel*, for *this
 * viewer*, offers it at all.
 */

const TICKET_CODE = 'CRM-26-00347'
// Meera — the PM — per `db.ts`'s USERS seed.
const PM_USER_ID = 2
// Ravi — a Developer — the mock db's own default, kept explicit here so the
// test does not depend on that default staying what it is today.
const DEVELOPER_USER_ID = 3

/** Radix's dialog and slide-over need APIs jsdom lacks — same list `TicketLevelControl.test.tsx` carries. */
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

beforeEach(() => {
  getDb().currentUserId = DEVELOPER_USER_ID
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
    pctComplete: 40,
    createdAt: '2026-08-03T09:00:00Z',
    ...overrides,
  } as Ticket
}

async function openPanel(props: Partial<Parameters<typeof QuickUpdateTrigger>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={queryClient}>
      <QuickUpdateTrigger ticket={ticket()} {...props} />
    </QueryClientProvider>,
  )
  await user.click(screen.getByRole('button', { name: /quick update/i }))
  await screen.findByRole('dialog')
  return { user }
}

describe('QuickUpdatePanel — S-21', () => {
  it('does not offer the level control to a Developer', async () => {
    await openPanel()

    expect(screen.queryByText('Level')).not.toBeInTheDocument()
    expect(screen.getByText(/and level\./i)).toBeInTheDocument()
  })

  it('does not offer the level control to Admin or Support either — S-21 names only PM', async () => {
    // Anita — Admin — per `db.ts`'s USERS seed. Admin and Support hold the
    // detail page's own chip (`ticket.assign`); S-21's field list names
    // neither, so this panel draws a tighter line than the route does.
    getDb().currentUserId = 1

    await openPanel()

    expect(screen.queryByText('Level')).not.toBeInTheDocument()
  })

  it('offers the level control to a PM, and drops it from the excluded-fields caption', async () => {
    getDb().currentUserId = PM_USER_ID

    await openPanel()

    expect(await screen.findByText('Level')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /change the level/i })).toBeInTheDocument()
    expect(screen.queryByText(/and level\./i)).not.toBeInTheDocument()
  })

  it('calls onChanged once the update succeeds, so a caller holding its own copy of the ticket can refetch', async () => {
    // The seeded walkthrough ticket is assigned to Meera the PM — a Developer
    // is out of scope for it and would 404, same as the server's own guard.
    getDb().currentUserId = PM_USER_ID
    let changedCount = 0
    const { user } = await openPanel({ onChanged: () => { changedCount += 1 } })

    await user.click(screen.getByRole('button', { name: 'Update ✓' }))

    await waitFor(() => expect(changedCount).toBe(1))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
