import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { getDb } from '@/mocks/db'

import { ObNotificationBell } from './ObNotificationBell'

/**
 * B-112 · OB-13's bell against the mock server.
 *
 * <p>The component has no route of its own — it mounts on the onboarding shell
 * B-108/B-109 will build — so it is rendered directly here, inside a router
 * because every entry is a `Link`.
 *
 * <p>Fixture note — `db.ts`'s `OB_NOTIFICATIONS`: six entries for user 3
 * (`currentUserId`), three of them unread, and two for user 5 that must never
 * appear.
 */
function renderBell() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObNotificationBell />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * MSW adds latency to every request and a full-suite run is heavily parallel,
 * so the 1 s default is not enough — `ClientListPage.test.tsx`'s own convention.
 * The first assertion in a test is the exposed one: it waits for the fetch as
 * well as the render.
 */
const SLOW = { timeout: 5000 }

const openBell = async () => {
  const trigger = await screen.findByRole('button', { name: /Onboarding notifications/ }, SLOW)
  fireEvent.click(trigger)
  return trigger
}

describe('ObNotificationBell', () => {
  it('badges the unread count and names it for a screen reader', async () => {
    renderBell()

    await screen.findByRole('button', { name: 'Onboarding notifications (3 unread)' }, SLOW)
    // Not colour alone, and not the digit alone either — the accessible name
    // carries the number, so the badge is not the only way to read it.
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('drops the count from the label when nothing is unread', async () => {
    getDb().obNotifications.forEach((n) => {
      n.isRead = true
    })
    renderBell()

    await screen.findByRole('button', { name: 'Onboarding notifications' }, SLOW)
  })

  /** Eight, not the whole history — the popover is a glance. */
  it('shows at most the last few and points at the page for the rest', async () => {
    await (renderBell(), openBell())

    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)
    expect(screen.getByRole('link', { name: 'See all notifications' })).toHaveAttribute(
      'href',
      '/onboarding/notifications',
    )
  })

  it('never shows an entry addressed to somebody else', async () => {
    renderBell()
    await openBell()

    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)
    expect(screen.queryByText('Northwind Technologies Pvt Ltd is live')).not.toBeInTheDocument()
  })

  it('links an entry at the client it is about', async () => {
    renderBell()
    await openBell()

    const entry = await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)
    expect(entry.closest('a')).toHaveAttribute('href', '/onboarding/clients/3')
  })

  it('marks one read when it is opened', async () => {
    renderBell()
    await openBell()

    fireEvent.click(await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW))

    await waitFor(() => expect(getDb().obNotifications.find((n) => n.id === 6)?.isRead).toBe(true), SLOW)
    await screen.findByRole('button', { name: 'Onboarding notifications (2 unread)' }, SLOW)
  })

  it('clears the badge on mark all read', async () => {
    renderBell()
    await openBell()

    fireEvent.click(await screen.findByRole('button', { name: 'Mark all read' }, SLOW))

    await screen.findByRole('button', { name: 'Onboarding notifications' }, SLOW)
  })

  it('hides mark all read when there is nothing to mark', async () => {
    getDb().obNotifications.forEach((n) => {
      n.isRead = true
    })
    renderBell()
    await openBell()

    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)
    expect(screen.queryByRole('button', { name: 'Mark all read' })).not.toBeInTheDocument()
  })

  /**
   * "Nothing new" and "nothing ever" are different questions, and only the page
   * answers the second — so the way through to it survives an empty popover.
   */
  it('keeps the way through to the page when the popover is empty', async () => {
    getDb().obNotifications.length = 0
    renderBell()
    await openBell()

    await screen.findByText('Nothing to catch up on', undefined, SLOW)
    expect(screen.getByRole('link', { name: 'See all notifications' })).toBeInTheDocument()
  })

  it('labels each entry with its category', async () => {
    renderBell()
    await openBell()

    const entry = (await screen.findByText('Overdue by 2 days: Data Migration', undefined, SLOW)).closest('a')!
    expect(within(entry).getByText('Escalation')).toBeInTheDocument()
    // Unread is stated, not only tinted — WCAG 1.4.1.
    expect(within(entry).getByText('Unread')).toBeInTheDocument()
  })
})
