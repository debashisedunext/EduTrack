import { beforeAll, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

import { BulkStatusResultDialog, DeactivationConfirmDialog } from './BulkStatusBar'

/** Radix's dialog primitive needs APIs jsdom does not implement. */
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

/**
 * B-014 · the results dialog, rendered directly.
 *
 * `ResourceListPage.test.tsx` drives the flow the admin actually takes, and it
 * cannot reach this state: the grid's pre-flight check stops a selection with a
 * known-blocked resource before the request is made, so the server's
 * `BLOCKED_OPEN_TICKETS` only comes back when the count the grid held was stale
 * — a ticket opened in the seconds between the page rendering and the click.
 *
 * That race is rare and real, and it is the one case where this dialog is the
 * only thing standing between the admin and a dead end. Reaching it through the
 * page would mean staging a mid-test data change to simulate a stale read;
 * rendering the component with the response the server would have sent asserts
 * the same claim without pretending the setup is the interesting part.
 */
const blocked = {
  results: [
    {
      userId: 42,
      displayName: 'Priya Nair',
      outcome: 'BLOCKED_OPEN_TICKETS' as const,
      openTicketCount: 3,
    },
    { userId: 43, displayName: 'Ravi Kumar', outcome: 'CHANGED' as const },
  ],
  changed: 1,
  unchanged: 0,
  blocked: 1,
  notFound: 0,
  reassignUrl: '/api/v1/tickets/bulk-reassign',
}

function renderDialog(returnSearch = '') {
  return render(
    <MemoryRouter>
      <BulkStatusResultDialog
        result={blocked}
        isActivating={false}
        returnSearch={returnSearch}
        onClose={vi.fn()}
      />
    </MemoryRouter>,
  )
}

describe('what the server refused', () => {
  it('offers the wizard for the resource it refused, and only that one', () => {
    renderDialog()
    const dialog = within(screen.getByRole('dialog'))

    const link = dialog.getByRole('link', { name: "Reassign Priya Nair's 3 open tickets" })
    const url = new URL(link.getAttribute('href')!, 'http://localhost')

    expect(url.pathname).toBe('/tickets/bulk-reassign')
    expect(url.searchParams.get('fromUserId')).toBe('42')
    expect(url.searchParams.get('returnTo')).toBe('/masters/resources?deactivate=42')

    // Ravi was deactivated. A link beside him would offer to reassign tickets
    // he does not have.
    expect(dialog.getAllByRole('link')).toHaveLength(1)
  })

  it('does not send the admin to reassign tickets one at a time any more', () => {
    renderDialog()

    // What this dialog said before B-014, for somebody holding thirty tickets.
    // Advice nobody follows is a dead end with extra words.
    expect(screen.queryByText(/reassign from each ticket/i)).not.toBeInTheDocument()
  })

  it('carries the filters home', () => {
    renderDialog('?role=DEVELOPER')

    const href = within(screen.getByRole('dialog')).getByRole('link').getAttribute('href')!
    expect(new URL(href, 'http://localhost').searchParams.get('returnTo')).toContain(
      'role=DEVELOPER',
    )
  })
})

/**
 * B-014 · the pre-flight dialog's one load-bearing prop.
 *
 * `proceedCount` is passed in rather than derived from `blocked`, and this is
 * the test that stops somebody deriving it. A selection survives paging: rows
 * ticked two pages back are still selected and are no longer on screen, so this
 * component cannot see them and any "the rest = everything I was given minus
 * the blocked ones" arithmetic would drop them. A bulk action that quietly does
 * less than the count on its own button is worse than one that refuses.
 */
describe('the pre-flight dialog', () => {
  const blockedOne = [{ id: 42, displayName: 'Priya Nair', openTicketCount: 3 }]

  it('counts what will proceed from outside its own list', () => {
    render(
      <MemoryRouter>
        <DeactivationConfirmDialog
          blocked={blockedOne}
          // One named blocker, and nine others the caller knows about and this
          // component was never shown.
          proceedCount={9}
          returnSearch=""
          isPending={false}
          onConfirm={vi.fn()}
          onCancel={vi.fn()}
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('button', { name: 'Deactivate the other 9' })).toBeInTheDocument()
  })

  it('offers no way forward when the blocked are the whole selection', () => {
    render(
      <MemoryRouter>
        <DeactivationConfirmDialog
          blocked={blockedOne}
          proceedCount={0}
          returnSearch=""
          isPending={false}
          onConfirm={vi.fn()}
          onCancel={vi.fn()}
        />
      </MemoryRouter>,
    )

    // "Deactivate the other 0" is a button that does nothing, phrased as if it
    // does something.
    expect(screen.queryByRole('button', { name: /Deactivate the other/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Reassign Priya Nair's 3 open tickets/ })).toBeInTheDocument()
  })
})
