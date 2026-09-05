import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'

import { ObNotificationCentrePage } from './ObNotificationCentrePage'

/**
 * B-112 · OB-13's full page against the mock server.
 *
 * <p>Mounted through `Routes` rather than rendered as a bare component, on
 * `JourneyTemplateDesignerPage.test.tsx`'s reason: the tab arrives through
 * `useSearchParams`, and a test that passed it as a prop would not notice the
 * page and the URL disagreeing about the parameter name.
 *
 * <p>Fixture note — `db.ts`'s `OB_NOTIFICATIONS`: six entries belong to user 3
 * (`currentUserId`) and two belong to user 5. Three of the six are unread, one
 * in each tabbed category, so no assertion here can pass by accident on a list
 * that is all one thing.
 */
function renderCentre(initialPath = '/onboarding/notifications') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/onboarding/notifications" element={<ObNotificationCentrePage />} />
          <Route path="/onboarding/clients/:id" element={<p>client detail</p>} />
        </Routes>
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

const panel = () => screen.getByRole('tabpanel')

describe('ObNotificationCentrePage', () => {
  it('lists the caller’s own entries, newest first', async () => {
    renderCentre()

    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    const titles = within(panel())
      .getAllByRole('link')
      .map((row) => within(row).getAllByText(/./)[0].textContent)
    expect(titles[0]).toBe('Contoso Education Trust has raised an escalation')
    expect(titles).toHaveLength(6)
  })

  /**
   * The assertion that matters most. Two fixture entries belong to user 5, and
   * a page that showed them would work here and turn out empty — or worse, full
   * of somebody else's clients — on the real server.
   */
  it('never shows an entry addressed to somebody else', async () => {
    renderCentre()

    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    expect(screen.queryByText('Northwind Technologies Pvt Ltd is live')).not.toBeInTheDocument()
    expect(screen.queryByText('Escalated to L2: User Training')).not.toBeInTheDocument()
  })

  it('shows the unread total in the header, not the count in the open tab', async () => {
    renderCentre('/onboarding/notifications?tab=reminders')

    // Three unread across the whole bell; the Reminders tab holds none of them.
    await screen.findByText('3 unread across your onboarding clients', undefined, SLOW)
    expect(within(panel()).queryAllByRole('link')).toHaveLength(1)
  })

  it('filters to one category when a tab is selected', async () => {
    renderCentre()
    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    fireEvent.click(screen.getByRole('tab', { name: 'Escalations' }))

    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(2), SLOW)
    expect(screen.getByText('Overdue by 2 days: Data Migration')).toBeInTheDocument()
    // An ASSIGNMENT is not an ESCALATION.
    expect(screen.queryByText('Ready to verify: Signed statement of work')).not.toBeInTheDocument()
  })

  /**
   * `UPDATE` has no tab and appears under All, which is what All is for. The
   * check is that it is reachable at all — a category with no tab and no home
   * would be an entry nobody could ever find.
   */
  it('shows an uncategorised update under All and under no tab', async () => {
    renderCentre()
    await screen.findByText('Acme Private Limited has cleared prerequisites', undefined, SLOW)

    fireEvent.click(screen.getByRole('tab', { name: 'Assignments' }))
    await waitFor(
      () =>
        expect(
          screen.queryByText('Acme Private Limited has cleared prerequisites'),
        ).not.toBeInTheDocument(),
      SLOW,
    )
  })

  it('puts the tab in the URL so it can be pasted', async () => {
    renderCentre('/onboarding/notifications?tab=escalations')

    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(2), SLOW)
    expect(screen.getByRole('tab', { name: 'Escalations' })).toHaveAttribute('aria-selected', 'true')
  })

  it('falls back to All for a tab the screen does not have', async () => {
    // Not an error page: a stale bookmark should show the top of the list.
    renderCentre('/onboarding/notifications?tab=mentions')

    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(6), SLOW)
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true')
  })

  it('filters to unread only', async () => {
    renderCentre()
    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    fireEvent.click(screen.getByLabelText('Unread only'))

    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(3), SLOW)
  })

  it('clears the badge when everything is marked read', async () => {
    renderCentre()
    await screen.findByText('3 unread across your onboarding clients', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Mark all read' }))

    await screen.findByText('Everything on your onboarding clients is read', undefined, SLOW)
    // And it stopped at the boundary — user 5's two entries are untouched.
    expect(getDb().obNotifications.filter((n) => n.recipientUserId === 5 && !n.isRead))
      .toHaveLength(2)
  })

  it('marks one entry read when it is opened', async () => {
    renderCentre()
    const row = await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    fireEvent.click(row)

    await waitFor(() => expect(getDb().obNotifications.find((n) => n.id === 6)?.isRead).toBe(true), SLOW)
  })

  it('shows an empty state rather than a bare list when nothing is unread', async () => {
    getDb().obNotifications.forEach((n) => {
      n.isRead = true
    })
    renderCentre('/onboarding/notifications?unreadOnly=true')

    await screen.findByText('Nothing unread', undefined, SLOW)
  })

  // ── paging ────────────────────────────────────────────────────────────────

  /**
   * The fixture is smaller than one page, so these two seed their own. 30
   * entries is one full page of 25 plus a short second one, which is the shape
   * that catches an off-by-one at the boundary.
   */
  function seedManyEntries() {
    const db = getDb()
    for (let i = 0; i < 30; i += 1) {
      db.obNotifications.push({
        id: 100 + i,
        recipientUserId: 3,
        eventKey: 'TAT_REMINDER',
        category: 'REMINDER',
        title: `Reminder ${i}`,
        body: 'A client is waiting on this one.',
        linkUrl: '/onboarding/clients/1',
        obClientId: 1,
        journeyId: 1,
        stepId: 3,
        isRead: false,
        createdAt: '2026-09-04T05:00:00Z',
      })
    }
  }

  it('appends an older page rather than replacing what is shown', async () => {
    seedManyEntries()
    renderCentre()

    await screen.findByText('Reminder 29', undefined, SLOW)
    expect(within(panel()).getAllByRole('link')).toHaveLength(25)

    fireEvent.click(screen.getByRole('button', { name: 'Load older' }))

    // The first page is still there, with the second below it — "load older"
    // that swapped the list would be paging by another name.
    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(36), SLOW)
    expect(screen.getByText('Reminder 29')).toBeInTheDocument()
    expect(screen.getByText('Reminder 0')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load older' })).not.toBeInTheDocument()
  })

  /**
   * `older` is a snapshot, and invalidation refetches only the current page —
   * so without the paging reset this leaves every previously loaded page
   * rendering as unread under a header saying nothing is.
   */
  it('does not leave stale unread rows below a mark-all-read', async () => {
    seedManyEntries()
    renderCentre()

    await screen.findByText('Reminder 29', undefined, SLOW)
    fireEvent.click(screen.getByRole('button', { name: 'Load older' }))
    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(36), SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Mark all read' }))

    await screen.findByText('Everything on your onboarding clients is read', undefined, SLOW)
    await waitFor(() => expect(within(panel()).getAllByRole('link')).toHaveLength(25), SLOW)
    expect(within(panel()).queryByText('Unread')).not.toBeInTheDocument()
  })

  // ── keyboard ──────────────────────────────────────────────────────────────

  /**
   * Manual activation, which is the whole reason this strip is not the shared
   * `Tabs` component: that one selects on focus, and this screen fetches per
   * tab, so arrowing across four tabs would fire four requests and land on
   * whichever answered last.
   */
  it('arrow keys move focus between tabs without selecting', async () => {
    renderCentre()
    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    const all = screen.getByRole('tab', { name: 'All' })
    all.focus()
    fireEvent.keyDown(all, { key: 'ArrowRight' })

    expect(screen.getByRole('tab', { name: 'Assignments' })).toHaveFocus()
    // Focused, not selected — nothing was refetched.
    expect(screen.getByRole('tab', { name: 'Assignments' })).toHaveAttribute('aria-selected', 'false')
    expect(all).toHaveAttribute('aria-selected', 'true')
  })

  it('keeps one tab stop for the whole strip', async () => {
    renderCentre()
    await screen.findByText('Contoso Education Trust has raised an escalation', undefined, SLOW)

    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Escalations' })).toHaveAttribute('tabindex', '-1')
  })

  it('wraps from the last tab to the first', async () => {
    renderCentre('/onboarding/notifications?tab=reminders')
    await screen.findByText('Due 05 Sep 2026: Environment Provisioning', undefined, SLOW)

    const reminders = screen.getByRole('tab', { name: 'Reminders' })
    reminders.focus()
    fireEvent.keyDown(reminders, { key: 'ArrowRight' })

    expect(screen.getByRole('tab', { name: 'All' })).toHaveFocus()
  })
})
