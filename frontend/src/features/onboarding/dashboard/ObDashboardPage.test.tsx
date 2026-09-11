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
 * specific figure: what this file is about is the board's shape, its tabs and
 * the three card states, all of which are this screen's own work.
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
 * convention. Widened from 5000: the RAG board, which is the default Summary
 * tab, adds three more concurrent requests to the page's first paint (the
 * other three tabs mount, and fetch, only once selected), and the slowest of
 * them is what this budget has to cover.
 */
const SLOW = { timeout: 10000 }

describe('ObDashboardPage', () => {
  /**
   * Every card the server sends except `live`, always, and in the order it
   * sent them. An absent card and a card reading nought are different claims,
   * and only one of them is true when nothing is overdue — so a board that
   * dropped its zeroes would be a board that hid its good news. `live` is the
   * one deliberate omission: a count of finished clients is not a call to
   * action, and leaving it out is what lets the rest sit on a single row.
   */
  it('draws every card but Live, in the order the server sends them', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const tiles = within(screen.getByRole('list', { name: 'Onboarding summary' })).getAllByRole(
      'listitem',
    )
    expect(tiles).toHaveLength(6)
    expect(tiles.map((tile) => tile.textContent)).toEqual([
      expect.stringContaining('Ongoing projects'),
      expect.stringContaining("This week's deadlines"),
      expect.stringContaining("Today's delivery"),
      expect.stringContaining('Overdue clients'),
      expect.stringContaining('Client escalations'),
      expect.stringContaining('At risk'),
    ])
  }, SLOW.timeout)

  /**
   * Asserted on its own rather than left implied by the count above: the
   * server still sends `live` and always will — the contract's enum is
   * unchanged — so this is the test that catches the filter being dropped
   * during a refactor, which a length check alone would not.
   */
  it('hides the Live card the server still sends', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const cardList = screen.getByRole('list', { name: 'Onboarding summary' })
    expect(within(cardList).queryByText('Live')).not.toBeInTheDocument()
  }, SLOW.timeout)

  /**
   * One row, not two. The tiles used to be fixed 190px tracks that wrapped,
   * which put "Live" and "At risk" on a second line of their own.
   */
  it('lays the cards out as a single row of equal tracks', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const cardList = screen.getByRole('list', { name: 'Onboarding summary' })
    expect(cardList.style.gridTemplateColumns).toBe('repeat(6, minmax(0, 1fr))')
  }, SLOW.timeout)

  /**
   * The page header is gone — title, strapline, staleness line and the
   * boarding button. Asserted rather than left implied: the module shell names
   * the screen in the sidebar and OB-03 owns boarding a client, and a header
   * quietly reappearing is exactly the kind of regression a layout refactor
   * reintroduces.
   */
  it('draws no page header — no strapline, no staleness line, no boarding button', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    expect(screen.queryByText(/Every client in flight/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Counting all clients/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Board a new client/ })).not.toBeInTheDocument()
  }, SLOW.timeout)

  /** Invisible, but the page still has a name for anyone navigating by heading. */
  it('keeps the page named for screen readers', async () => {
    renderBoard()

    expect(
      await screen.findByRole('heading', { name: 'Onboarding dashboard', level: 1 }, SLOW),
    ).toBeInTheDocument()
  }, SLOW.timeout)

  it('shows a skeleton of the same shape while the first request is in flight', () => {
    renderBoard()

    // Six, not one — a single spinner would reflow the whole grid on arrival,
    // and the count is the number of tiles the board actually draws.
    expect(
      within(screen.getByRole('list', { name: 'Onboarding summary' })).getAllByRole('listitem'),
    ).toHaveLength(6)
  })

  /**
   * B-127 · every card is now a real control — `ObDashboardCardTile`'s own
   * rule is that a tile renders as a button only once it is given `onOpen`,
   * so this is the test that proves the wiring landed rather than merely
   * compiled.
   *
   * <p>Scoped to the card list itself, not the whole page: B-128 added two
   * grids below the board whose own rows and cells are legitimately buttons
   * too (a client name, a workload count), and this test is about the cards,
   * not a count of every control on the screen.
   */
  it('every card is a real button now that the slide-over exists', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const cardList = screen.getByRole('list', { name: 'Onboarding summary' })
    expect(within(cardList).getAllByRole('button')).toHaveLength(6)
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
  }, SLOW.timeout)

  /**
   * The counters are the page's, not Summary's — the point of lifting them out
   * of the tab. A manager reading Delayed projects can still see how many
   * clients are overdue without navigating away and back.
   */
  it('keeps the six cards on screen on every tab', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)
    await userEvent.click(screen.getByRole('tab', { name: 'Implementor workload & performance' }))

    const cardList = await screen.findByRole('list', { name: 'Onboarding summary' }, SLOW)
    expect(within(cardList).getAllByRole('listitem')).toHaveLength(6)
  }, SLOW.timeout)

  /**
   * Summary is the RAG board and nothing else now that the counters are above
   * the strip — the three columns plan §9 draws, and the reason the tab still
   * earns its place.
   */
  it('opens Summary on the RAG board', async () => {
    renderBoard()

    await screen.findByText('Ongoing projects', undefined, SLOW)

    for (const column of ['Breached / blocked', 'At risk', 'On track']) {
      expect(await screen.findByRole('region', { name: column }, SLOW)).toBeInTheDocument()
    }
  }, SLOW.timeout)
})
