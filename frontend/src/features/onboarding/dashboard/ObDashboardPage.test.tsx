import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { ObDashboardPage } from './ObDashboardPage'

/**
 * B-121 · OB-02 against the mock server.
 *
 * A-118's `obAdminHandlers` derive the card counts from the same rows the
 * slide-over lists, which is deliberately *not* what the server does — it reads
 * `ob_dashboard_summary`. The mock has no refresh job, so deriving is the only
 * way to keep the two consistent. That difference is why nothing here asserts a
 * specific figure: what this file is about is the board's shape, its staleness
 * line and the three card states, all of which are this screen's own work.
 */
function renderBoard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObDashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** MSW adds latency and the suite is heavily parallel — `ClientListPage.test.tsx`'s convention. */
const SLOW = { timeout: 5000 }

describe('ObDashboardPage', () => {
  /**
   * All seven, always. An absent card and a card reading nought are different
   * claims, and only one of them is true when nothing is overdue — so a board
   * that dropped its zeroes would be a board that hid its good news.
   */
  it('draws all seven cards, in the order the server sends them', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const tiles = within(screen.getByRole('list', { name: 'Onboarding summary' })).getAllByRole(
      'listitem',
    )
    expect(tiles).toHaveLength(7)
    expect(tiles.map((tile) => tile.textContent)).toEqual([
      expect.stringContaining('Ongoing projects'),
      expect.stringContaining("This week's deadlines"),
      expect.stringContaining("Today's delivery"),
      expect.stringContaining('Overdue clients'),
      expect.stringContaining('Live'),
      expect.stringContaining('At risk'),
      expect.stringContaining('Client escalations'),
    ])
  })

  /**
   * The numbers are up to one refresh interval stale by design, and a board
   * that cannot say so invites somebody to compare it against a client list and
   * file a bug about the difference.
   */
  it('says how stale the figures are and what they counted', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    expect(screen.getByText(/Counting all clients/)).toBeInTheDocument()
    expect(screen.getByRole('time')).toHaveAttribute('datetime', '2026-09-05T06:00:00.000Z')
  })

  it('shows a skeleton of the same shape while the first request is in flight', () => {
    renderBoard()

    // Seven, not one — a single spinner would reflow the whole grid on arrival.
    expect(
      within(screen.getByRole('list', { name: 'Onboarding summary' })).getAllByRole('listitem'),
    ).toHaveLength(7)
  })

  /**
   * B-127 · every card is now a real control — `ObDashboardCardTile`'s own
   * rule is that a tile renders as a button only once it is given `onOpen`,
   * so this is the test that proves the wiring landed rather than merely
   * compiled.
   *
   * <p>Scoped to the card list itself, not the whole page: B-128 added two
   * grids below the board whose own rows and cells are legitimately buttons
   * too (a client name, a workload count), and this test is about the seven
   * cards, not a count of every control on the screen.
   */
  it('every card is a real button now that the slide-over exists', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const cardList = screen.getByRole('list', { name: 'Onboarding summary' })
    expect(within(cardList).getAllByRole('button')).toHaveLength(7)
    expect(within(cardList).queryAllByRole('group')).toHaveLength(0)
  })

  /**
   * The click-through: a card names its own key, and the panel that opens
   * reads that exact card — never a card the screen guesses at.
   */
  it('opens the slide-over for the card that was clicked', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)
    await userEvent.click(screen.getByRole('button', { name: /This week's deadlines/ }))

    expect(await screen.findByRole('heading', { name: "This week's deadlines" }, SLOW))
      .toBeInTheDocument()
  })
})
