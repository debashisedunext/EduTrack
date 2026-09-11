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

/**
 * MSW adds latency and the suite is heavily parallel — `ClientListPage.test.tsx`'s
 * convention. Widened from 5000: the RAG board, part of the default Summary
 * tab, adds three more concurrent requests to the page's first paint (the
 * other three tabs mount, and fetch, only once selected), and the slowest of
 * them is what this budget has to cover.
 */
const SLOW = { timeout: 10000 }

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
      expect.stringContaining('Client escalations'),
      expect.stringContaining('Live'),
      expect.stringContaining('At risk'),
    ])
  }, SLOW.timeout)

  /**
   * The numbers are up to one refresh interval stale by design, and a board
   * that cannot say so invites somebody to compare it against a client list and
   * file a bug about the difference.
   */
  it('says how stale the figures are and what they counted', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const staleness = screen.getByText(/Counting all clients/)
    expect(staleness).toBeInTheDocument()
    /*
      Scoped to the staleness line rather than the page. `getByRole('time')`
      was unambiguous only while B-128's grids had no rows to draw — the moment
      the fixture gives them start dates and recomputed finishes, the page
      carries five more `<time>` cells. That is a property of the fixture, not
      of the board.
    */
    expect(within(staleness).getByRole('time')).toHaveAttribute(
      'datetime',
      '2026-08-20T06:00:00.000Z',
    )
  }, SLOW.timeout)

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
  }, SLOW.timeout)

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
  }, SLOW.timeout)

  /**
   * The four tabs, in plan §9's own order — mirroring `DashboardPage`'s
   * Today's Progress / Ticket Overview / Weekly Progress / Analytics strip.
   */
  it('shows the four tabs, Summary selected by default', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      'Summary',
      "Where it's stuck",
      'Delayed projects',
      'Implementor workload & performance',
    ])
    expect(screen.getByRole('tab', { name: 'Summary' })).toHaveAttribute('aria-selected', 'true')
  }, SLOW.timeout)

  /**
   * Each of the other three tabs mounts the standalone panel it already had —
   * this only proves the wiring, since `ObDashboardStuckPanel`,
   * `ObDelayedProjectsGrid` and `ObImplementorWorkloadGrid` cover their own
   * content in their own test files.
   */
  it('switches to the Delayed projects tab and mounts its grid', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)
    await userEvent.click(screen.getByRole('tab', { name: 'Delayed projects' }))

    expect(
      await screen.findByRole('heading', { name: 'Delayed projects' }, SLOW),
    ).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Onboarding summary' })).not.toBeInTheDocument()
  }, SLOW.timeout)
})
