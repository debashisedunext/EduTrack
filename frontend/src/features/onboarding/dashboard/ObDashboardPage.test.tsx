import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import type { Me } from '@/api/generated/model'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

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
 *
 * <h2>Every case names the role it renders as</h2>
 *
 * The board draws two different screens now — seven counters and the tab strip
 * for a platform Admin, five counters and nothing below them for everybody
 * else — so a case leaving the role implicit would assert whichever one the
 * store happened to be holding. `Sidebar.test.tsx` learned the rest of this
 * the expensive way: a negative assertion alone cannot tell "correctly hidden
 * from this role" from "hidden from everyone because the check is broken",
 * which is why the non-admin block below also asserts what a non-admin *does*
 * see.
 */
const ADMIN = { id: 1, displayName: 'Priya Nair', role: 'ADMIN' } as Me
/** Holds the onboarding module without being a platform Admin — the majority case. */
const MEMBER = { id: 4, displayName: 'Ravi Kumar', role: 'DEVELOPER' } as Me

/**
 * The implementor and their manager. Not platform Admins — the board reaches
 * them through their `ONBOARDING` module role, which is the division this
 * screen actually wants.
 */
const IMPLEMENTOR = {
  id: 5, displayName: 'Kavya Sharma', role: 'DEVELOPER',
  moduleRoles: { ONBOARDING: 'OB_STEP_OWNER' },
} as Me
const IMPLEMENTOR_MANAGER = {
  id: 6, displayName: 'Meera Pillai', role: 'DEVELOPER',
  moduleRoles: { ONBOARDING: 'OB_MANAGER' },
} as Me
/** Onboarding standing, but not a delivery role — stays on the counter row. */
const SALES = {
  id: 7, displayName: 'Imran Qureshi', role: 'DEVELOPER',
  moduleRoles: { ONBOARDING: 'OB_SALES' },
} as Me

/** The store is a module singleton; a role left behind would decide the next test. */
beforeEach(() => useAuthStore.setState(initialAuthState))

function renderBoard(user: Me = ADMIN) {
  useAuthStore.setState({ status: 'authenticated', user })
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
   * An Admin's standing figures are the **project** band — six counters on each
   * project's own completion date — and not the seven journey-level counters,
   * which are what everybody else gets.
   *
   * <p>The division is asserted from both sides here and in the non-admin
   * block below, because the reason for it is a collision rather than a
   * permission: both bands open with a card called "Ongoing projects" and the
   * two numbers are counted differently, so drawing them together would put
   * two cards of the same name and different values on one screen. A test that
   * only counted tiles would let that back in.
   */
  it('draws the six project figures, in the order a reader scans them', async () => {
    renderBoard()

    const band = await screen.findByRole('list', { name: 'Project figures' }, SLOW)
    await within(band).findByText('Ongoing projects', undefined, SLOW)

    const tiles = within(band).getAllByRole('listitem')
    expect(tiles).toHaveLength(6)
    expect(tiles.map((tile) => tile.textContent)).toEqual([
      expect.stringContaining('Ongoing projects'),
      expect.stringContaining("This week's deadlines"),
      expect.stringContaining("Today's delivery"),
      expect.stringContaining('Overdue'),
      expect.stringContaining('At risk'),
      expect.stringContaining('Client escalations'),
    ])
  }, SLOW.timeout)

  /** The journey-level row is the other band, and an Admin does not get both. */
  it('draws the seven-counter row for nobody on this screen', async () => {
    renderBoard()

    await screen.findByRole('list', { name: 'Project figures' }, SLOW)

    expect(screen.queryByRole('list', { name: 'Onboarding summary' })).not.toBeInTheDocument()
  }, SLOW.timeout)

  /**
   * Three donuts, each with a legend that is a real list of controls — the
   * drawing itself is `aria-hidden`, so the legend is the whole accessible
   * surface and its absence would make the chart unreachable rather than
   * merely unlabelled.
   */
  it('draws the three donuts with their legends', async () => {
    renderBoard()

    for (const chart of ['Project Schedule health', 'By salesperson', 'By implementor']) {
      expect(await screen.findByRole('region', { name: chart }, SLOW)).toBeInTheDocument()
    }
    expect(await screen.findByRole('list', { name: 'Implementor shares' }, SLOW)).toBeInTheDocument()
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

    await screen.findByRole('list', { name: 'Project figures' }, SLOW)

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

    // Six, not one — a single spinner would reflow the whole band on arrival,
    // and the count is the number of tiles this reader actually gets.
    expect(
      within(screen.getByRole('list', { name: 'Project figures' })).getAllByRole('listitem'),
    ).toHaveLength(6)
  })

  /**
   * Every project card is a real control — `ObProjectCardBand` renders a tile
   * as a region rather than a button when it is given no handler, so this
   * proves the wiring landed rather than merely compiled.
   */
  it('every project card is a real button', async () => {
    renderBoard()

    const band = await screen.findByRole('list', { name: 'Project figures' }, SLOW)
    await within(band).findByText('Ongoing projects', undefined, SLOW)

    expect(within(band).getAllByRole('button')).toHaveLength(6)
    expect(within(band).queryAllByRole('group')).toHaveLength(0)
  }, SLOW.timeout)

  /**
   * A lateness card opens the Summary tab, where the rows it counted are
   * listed — deliberately not the slide-over, which lists steps and
   * prerequisite tasks and would answer with a list whose length disagrees
   * with the number that was clicked.
   */
  /*
    A card used to select the Summary tab, which answered "which ones?" by
    moving the reader somewhere else. It opens the projects it counted in a
    panel now, over the board, and the tab underneath is left alone — which
    the second half asserts, because a panel that also navigated would pass
    the first half on its own.
  */
  it('opens a project card on the projects it counted, leaving the tab alone', async () => {
    renderBoard()

    const band = await screen.findByRole('list', { name: 'Project figures' }, SLOW)
    await within(band).findByText('Ongoing projects', undefined, SLOW)
    await userEvent.click(screen.getByRole('tab', { name: 'Delayed projects' }))
    await userEvent.click(within(band).getByRole('button', { name: /^At risk/ }))

    // Headed with the words that were on the card.
    expect(await screen.findByRole('dialog', { name: 'At risk' }, SLOW)).toBeInTheDocument()

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull(), SLOW)
    expect(screen.getByRole('tab', { name: 'Delayed projects' }))
      .toHaveAttribute('aria-selected', 'true')
  }, SLOW.timeout)

  /*
    The other half of "every figure opens a panel". A slice used to leave for
    the Projects grid, and the schedule donut had no slice action at all
    because the grid has no filter for its buckets — the panel takes its rows
    from this response, so all three charts behave alike now.
  */
  it('opens a donut slice on the projects behind it', async () => {
    renderBoard()

    const chart = await screen.findByRole('region', { name: 'Project Schedule health' }, SLOW)
    /*
      The legend, not every button in the card — the card also carries the
      chart/table toggle now, and its first button is "chart".
    */
    const legend = within(chart).getByRole('list', { name: 'Status shares' })
    const slices = within(legend).queryAllByRole('button')
    // The fixture world may have every project in one bucket, so this asserts
    // the wiring on whichever slice is drawn rather than on a named one.
    if (slices.length === 0) return

    await userEvent.click(slices[0])
    expect(await screen.findByRole('dialog', undefined, SLOW)).toBeInTheDocument()
  }, SLOW.timeout)

  /**
   * The four tabs, in plan §9's own order — mirroring `DashboardPage`'s
   * Today's Progress / Ticket Overview / Weekly Progress / Analytics strip.
   */
  it('shows the four tabs, Summary selected by default', async () => {
    renderBoard()

    await screen.findByRole('list', { name: 'Project figures' }, SLOW)

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

    await screen.findByRole('list', { name: 'Project figures' }, SLOW)
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
  it('keeps the cards on screen on every tab', async () => {
    renderBoard()

    await screen.findByRole('list', { name: 'Project figures' }, SLOW)
    await userEvent.click(screen.getByRole('tab', { name: 'Implementor workload & performance' }))

    const band = await screen.findByRole('list', { name: 'Project figures' }, SLOW)
    expect(within(band).getAllByRole('listitem')).toHaveLength(6)
  }, SLOW.timeout)

  /**
   * Summary is the three project lists — today's deliveries, the at-risk
   * clients and the overdue ones — and nothing else.
   *
   * <p>The RAG board sat underneath: Breached / blocked · At risk · On
   * track, a second cut of the same clients by journey colour. Both halves
   * are asserted, because "the lists are there" would pass just as happily
   * against a tab that still carried the board.
   */
  it('opens Summary on the three project lists, and no RAG board', async () => {
    renderBoard()

    for (const list of ["Today's delivery", 'Project at Risk', 'Project Overdue']) {
      expect(await screen.findByRole('region', { name: list }, SLOW)).toBeInTheDocument()
    }
    for (const column of ['Breached / blocked', 'At risk', 'On track']) {
      expect(screen.queryByRole('region', { name: column })).not.toBeInTheDocument()
    }
  }, SLOW.timeout)

  /**
   * Each at-risk row names the person delivering it. The list exists to answer
   * "what is going wrong and who do I talk to", and the second half is the one
   * a status-only list leaves out.
   */
  it('names the implementor on every at-risk row', async () => {
    renderBoard()

    const list = await screen.findByRole('region', { name: 'Project at Risk' }, SLOW)
    const rows = within(list).queryAllByRole('button')

    // The fixture world may have nothing at risk, which is a real state and not
    // a failure — what must never happen is a row that omits the person.
    for (const row of rows) {
      expect(row).toHaveAccessibleName(/Implementor |No implementor assigned/)
    }
  }, SLOW.timeout)
})

/**
 * The board a non-admin gets: the five counters that are about their own work,
 * and nothing under them.
 *
 * <p>Both halves are asserted, not only the absences. A file that checked
 * "no tab strip" and "no At risk tile" would pass just as happily against a
 * board that rendered nothing at all, which is the failure this division makes
 * easy to ship and impossible to see in review.
 *
 * <p>What is hidden here is hidden because it is a reading of other people's
 * work, not because it is privileged — the server scopes and refuses on its
 * own regardless. So nothing in this block should be read as a test of a
 * permission; it is a test of what the screen offers.
 */
describe('ObDashboardPage, as a non-admin', () => {
  /**
   * The journey-level counters are this reader's band and the project one is
   * not drawn at all — its figures are a cross-team reading of every client's
   * delivery, and its donuts name people whose work is not theirs to manage.
   */
  it('keeps the counter row and draws no project band', async () => {
    renderBoard(MEMBER)

    await screen.findByText('Ongoing projects', undefined, SLOW)

    expect(screen.getByRole('list', { name: 'Onboarding summary' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Project figures' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Project Schedule health' })).not.toBeInTheDocument()
  }, SLOW.timeout)

  it('draws five counters — the two management figures come off', async () => {
    renderBoard(MEMBER)

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const tiles = within(screen.getByRole('list', { name: 'Onboarding summary' })).getAllByRole(
      'listitem',
    )
    expect(tiles.map((tile) => tile.textContent)).toEqual([
      expect.stringContaining('Ongoing projects'),
      expect.stringContaining("This week's deadlines"),
      expect.stringContaining("Today's delivery"),
      expect.stringContaining('Overdue clients'),
      expect.stringContaining('Live'),
    ])
  }, SLOW.timeout)

  /**
   * Named individually rather than left to the count above: the server still
   * sends both keys and always will — the contract's enum is unchanged — so
   * this is the case that catches the filter being dropped in a refactor,
   * which a length check alone would not.
   */
  it('drops At risk and Client escalations, which the server still sends', async () => {
    renderBoard(MEMBER)

    await screen.findByText('Ongoing projects', undefined, SLOW)

    const cardList = screen.getByRole('list', { name: 'Onboarding summary' })
    expect(within(cardList).queryByText('At risk')).not.toBeInTheDocument()
    expect(within(cardList).queryByText('Client escalations')).not.toBeInTheDocument()
  }, SLOW.timeout)

  /**
   * The whole strip, not an emptied one. `Tabs` given nothing would still draw
   * a segmented bar and a named tablist with no tabs in it, which reads as a
   * screen that failed to load rather than one that has nothing more to show.
   */
  it('draws no tab strip at all — no tablist, no panel, no RAG board', async () => {
    renderBoard(MEMBER)

    await screen.findByText('Ongoing projects', undefined, SLOW)

    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Breached / blocked' })).not.toBeInTheDocument()
  }, SLOW.timeout)

  /** Five tracks, so the row does not reflow from seven when the figures land. */
  it('lays the five tiles out as a single row, skeleton included', async () => {
    renderBoard(MEMBER)

    const cardList = screen.getByRole('list', { name: 'Onboarding summary' })
    expect(within(cardList).getAllByRole('listitem')).toHaveLength(5)

    await screen.findByText('Ongoing projects', undefined, SLOW)
    expect(cardList.style.gridTemplateColumns).toBe('repeat(5, minmax(0, 1fr))')
  }, SLOW.timeout)

  /**
   * The counters they keep are still the controls they always were — the
   * slide-over is how a count becomes a worklist, and removing the tabs was
   * never meant to take that with it.
   */
  it('still opens the slide-over from a card', async () => {
    renderBoard(MEMBER)

    await screen.findByText('Ongoing projects', undefined, SLOW)
    await userEvent.click(screen.getByRole('button', { name: /Overdue clients/ }))

    expect(await screen.findByRole('heading', { name: 'Overdue clients' }, SLOW))
      .toBeInTheDocument()
  }, SLOW.timeout)
})


/**
 * The board an implementor and their manager get: the same screen the Admin
 * sees, holding their own work.
 *
 * <p>Nothing here asserts *which rows* — that is decided server-side, by the
 * scope each route derives from `CallerIdentity`, and a frontend test that
 * claimed to prove it would be proving the fixture instead. What these cases
 * hold is the thing the frontend does decide: that the screen is offered at
 * all, which it was not while the gate read the platform role.
 */
describe('ObDashboardPage, as an implementor and as their manager', () => {
  for (const [label, user] of [
    ['an implementor', IMPLEMENTOR],
    ['an implementor manager', IMPLEMENTOR_MANAGER],
  ] as const) {
    it(`draws the project board and a tab strip for ${label}`, async () => {
      renderBoard(user)

      await screen.findByRole('list', { name: 'Project figures' }, SLOW)
      // Which tabs differ by role — see "the implementor's own tab" below.
      expect(screen.getAllByRole('tab').length).toBeGreaterThan(0)
    }, SLOW.timeout)
  }

  /*
    The other half of the division, and the case that proves the gate is a
    gate rather than an always-true: onboarding standing on its own is not
    enough, because Sales delivers nothing the four tabs are cuts of.
  */
  it('leaves Sales on the counter row, with no board and no tabs', async () => {
    renderBoard(SALES)

    await screen.findByText('Ongoing projects', undefined, SLOW)
    expect(screen.getByRole('list', { name: 'Onboarding summary' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Project figures' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Summary' })).not.toBeInTheDocument()
  }, SLOW.timeout)
})


/**
 * The two views of a donut, and the panel that used to open under it.
 */
describe('ObDashboardPage, reading a donut', () => {
  it('toggles a chart to a table and back, without a hover', async () => {
    renderBoard()

    const chart = await screen.findByRole('region', { name: 'Project Schedule health' }, SLOW)
    expect(within(chart).getByRole('list', { name: 'Status shares' })).toBeInTheDocument()
    expect(within(chart).queryByRole('table')).not.toBeInTheDocument()

    await userEvent.click(within(chart).getByRole('button', { name: 'table' }))

    expect(within(chart).getByRole('table')).toBeInTheDocument()
    // One view at a time — the table replaces the chart rather than adding to it.
    expect(within(chart).queryByRole('list', { name: 'Status shares' })).not.toBeInTheDocument()

    await userEvent.click(within(chart).getByRole('button', { name: 'chart' }))
    expect(within(chart).getByRole('list', { name: 'Status shares' })).toBeInTheDocument()
  }, SLOW.timeout)

  /*
    The panel that pushed the board down on every pointer crossing. Hovering
    a legend row still lights its arc; what it must not do any more is put a
    list of names under the chart.
  */
  it('lists nothing under the chart on hover', async () => {
    renderBoard()

    const chart = await screen.findByRole('region', { name: 'Project Schedule health' }, SLOW)
    const legend = within(chart).getByRole('list', { name: 'Status shares' })
    const rows = within(legend).queryAllByRole('button')
    if (rows.length === 0) return

    /*
      Counted rather than compared as text: hovering still swaps the centre
      figure to the slice's own count, which is the point of the hover and
      happens inside the chart's box. What must not happen is a *list*
      arriving under it — that is what pushed the board down, and the peek
      panel was a `ul`.
    */
    const listsBefore = chart.querySelectorAll('ul').length
    await userEvent.hover(rows[0])
    expect(chart.querySelectorAll('ul')).toHaveLength(listsBefore)
  }, SLOW.timeout)
})

/**
 * What an implementor and their manager get in the two donut slots an admin
 * spends on the book: their own queue, and their own review state.
 */
describe("ObDashboardPage, the delivery roles' chart band", () => {
  for (const [label, user] of [
    ['an implementor', IMPLEMENTOR],
    ['an implementor manager', IMPLEMENTOR_MANAGER],
  ] as const) {
    it(`drops the salesperson and implementor cuts for ${label}`, async () => {
      renderBoard(user)

      await screen.findByRole('region', { name: 'Project Schedule health' }, SLOW)
      // Schedule health stays — it is about delivery, which is their work.
      expect(screen.queryByRole('region', { name: 'By salesperson' })).not.toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'By implementor' })).not.toBeInTheDocument()
    }, SLOW.timeout)
  }

  /*
    The review cards move rather than duplicate: a row above the charts for
    everybody else, the third slot in the band for these two. Both halves
    are asserted, since "they are in the band" would pass against a page
    that drew them twice.
  */
  it('moves the review cards into the band rather than leaving them above it', async () => {
    renderBoard(IMPLEMENTOR)

    const band = await screen.findByRole('region', { name: 'Project Schedule health' }, SLOW)
    expect(band).toBeInTheDocument()
    expect(screen.queryAllByTestId('ob-review-cards').length).toBeLessThanOrEqual(1)
  }, SLOW.timeout)
})


/**
 * The implementor's one tab, and the manager's four.
 *
 * <p>The division is deliberate and asserted from both sides: an implementor
 * works a queue, their manager triages across people, and a test that only
 * checked the absence of the four would pass against a page that drew no
 * tabs at all.
 */
describe("ObDashboardPage, the implementor's own tab", () => {
  it('gives an implementor Task summary and nothing else', async () => {
    renderBoard(IMPLEMENTOR)

    expect(await screen.findByRole('tab', { name: 'Task summary' }, SLOW)).toBeInTheDocument()
    for (const tab of ["Where it's stuck", 'Delayed projects', 'Implementor workload & performance']) {
      expect(screen.queryByRole('tab', { name: tab })).not.toBeInTheDocument()
    }
  }, SLOW.timeout)

  it('leaves the manager the four cross-team tabs', async () => {
    renderBoard(IMPLEMENTOR_MANAGER)

    expect(await screen.findByRole('tab', { name: 'Summary' }, SLOW)).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Implementor workload & performance' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Task summary' })).not.toBeInTheDocument()
  }, SLOW.timeout)
})
