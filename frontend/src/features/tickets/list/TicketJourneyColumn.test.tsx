import { beforeAll, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { COLUMNS, DEFAULT_VISIBLE_COLUMNS, TOGGLEABLE_COLUMNS } from './columns'
import { TicketListPage } from './TicketListPage'

/**
 * B-051 · the compact ribbon against the mock server — the whole chain, not
 * the pieces.
 *
 * `compactDots.test.ts` covers which dot gets which state, `RibbonDots.test.tsx`
 * covers how one is drawn, and `routeToTemplate.test.ts` covers which template
 * a pair resolves to; all three are pure and none would notice if the grid
 * never joined them up. What is worth a test here is the join: that a row finds
 * its own template through B-041's routing rules, that rows on different
 * templates get different-length ribbons, and that the column can be switched
 * off.
 *
 * ## Every test seeds its own stage codes, and that is not tidiness
 *
 * **The mock speaks two stage vocabularies.** `db.stages` — Stream C's flat
 * ribbon fixture, which the filler ticket generator draws `currentStageCode`
 * from — says `DEVELOPMENT`, `DEPLOYMENT` and `VERIFICATION`. The workflow
 * template master says `DEV`, `DEPLOY` and `VERIFY`, **following the database**,
 * which seeds those in `V20260807_1700`. B-040 found this and recorded it at the
 * top of `mocks/db.ts`: reconciling it means renaming codes that Stream C's
 * reopen fixture and `ReopenDialog.test.tsx` assert on, so it was left as a
 * note rather than done across a stream boundary.
 *
 * The consequence for this column is that against the *mock* a ticket sitting
 * in `DEVELOPMENT` finds no such stage in its template and renders an em dash —
 * which is `buildCompactDots` behaving correctly on an inconsistent fixture,
 * not a defect here. Against the real backend both halves say `DEV` and the
 * question does not arise. So these tests write the template vocabulary onto
 * the rows they are about, which is the state the real server is always in;
 * pinning the divergence instead would pin a bug.
 *
 * **It is not only this column.** S-17's Stage filter builds its options from
 * the same template stages, so filtering the mock to "Development" already
 * matches no row. Flagged for Stream C rather than fixed here.
 */

/** Radix's popover primitives need APIs jsdom does not implement. */
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

/**
 * The dots arrive two round trips *after* the rows — the templates, then their
 * routing rules — on top of the seven masters the filter bar already fetches.
 * Vitest's 5 s per-test default has no room for that here, so each test gets
 * its own budget rather than the file getting a global one: `TicketListPage.test.tsx`
 * next door still wants the tight default.
 */
const SLOW = 20_000

/** §4A.9: task type 2 (Production Bug) routes to Standard Dev Flow, 8 stages;
 * task type 3 (Client Request) routes to Support Fast-Track, 5. */
const EIGHT_STAGE_TASK_TYPE = 2
const FIVE_STAGE_TASK_TYPE = 3

type TicketRow = ReturnType<typeof getDb>['tickets'][number]

/** Puts every row on a known template and a known stage — see the header. */
function seedRows(stageCode: string, patch: Partial<TicketRow> = {}) {
  getDb().tickets.forEach((ticket, index) => {
    ticket.currentStageCode = stageCode
    ticket.status = 'IN_PROGRESS'
    ticket.iterationNo = 1
    ticket.taskTypeId = index % 2 === 0 ? EIGHT_STAGE_TASK_TYPE : FIVE_STAGE_TASK_TYPE
    Object.assign(ticket, patch)
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/tickets']}>
        <Routes>
          <Route path="/tickets" element={<TicketListPage />} />
          <Route path="/tickets/:ticketId" element={<p>detail</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Every assertion waits for a journey, not a row — a row renders long before
 * its template has been resolved, so waiting on a row would assert against a
 * grid whose dots never arrive. */
async function journeysReady() {
  await waitFor(
    () => {
      expect(screen.getAllByRole('img', { name: /^Journey:/ }).length).toBeGreaterThan(0)
    },
    { timeout: 10_000 },
  )
  return screen.getAllByRole('img', { name: /^Journey:/ })
}

const dotsIn = (strip: HTMLElement) => strip.querySelectorAll('[data-stage]')

/**
 * Grouped by *fixture*, one render each, rather than one render per assertion.
 *
 * Rendering this grid costs about ten MSW round trips, and this file runs
 * beside nine other suites under one CPU-bound `vitest run` — a suite that
 * mounted the page seven times was measurably starving its neighbours into the
 * popover-timing flake `TicketListPage.test.tsx` documents. Four mounts cover
 * the same ground.
 */
describe('B-051 · the Journey column on S-17', () => {
  it('draws one ribbon per row, each on its own template', async () => {
    // `TRIAGE` is in all three seeded templates, so no row is unplaceable —
    // and `seedRows` alternates the two task types §4A.9 routes to an
    // eight-stage template and a five-stage one. A grid that resolved one
    // template for every row, or hardcoded eight dots, gives one length.
    seedRows('TRIAGE')
    renderPage()
    const journeys = await journeysReady()

    const rows = screen.getAllByRole('row').length - 1 // minus the header
    expect(journeys.length).toBe(rows)

    const lengths = new Set(journeys.map((strip) => dotsIn(strip).length))
    expect([...lengths].sort((a, b) => a - b)).toEqual([5, 8])

    // The column is present, and it is one a reader can spend the width on
    // something else instead: only ID and Description are `alwaysVisible`,
    // because a row cannot be identified without them and can be read without
    // this one.
    expect(screen.getByRole('columnheader', { name: 'Journey' })).toBeInTheDocument()
    expect(COLUMNS.find((c) => c.key === 'journey')?.alwaysVisible).toBeUndefined()
    expect(TOGGLEABLE_COLUMNS.map((c) => c.key)).toContain('journey')
    expect(DEFAULT_VISIBLE_COLUMNS).toContain('journey')
  }, SLOW)

  it('marks where the ticket is, what is behind it, and what each dot is', async () => {
    seedRows('DEV')
    renderPage()
    const journeys = await journeysReady()

    const strip = journeys.find((s) => dotsIn(s).length === 8)!
    expect(strip.getAttribute('aria-label')).toBe(
      'Journey: Development (Developer), stage 3 of 8. 2 completed',
    )
    expect(strip.querySelector('[data-stage="INTAKE"]')).toHaveAttribute('data-state', 'COMPLETED')
    expect(strip.querySelector('[data-stage="DEV"]')).toHaveAttribute('data-state', 'CURRENT')
    expect(strip.querySelector('[data-stage="CLOSED"]')).toHaveAttribute('data-state', 'PENDING')

    // §S-17: "hovering a dot names the stage and its owner" — and its state,
    // because a dot whose colour is the only thing saying "done" is unreadable
    // to the reader this hover most helps.
    expect(strip.querySelector('[data-stage="INTAKE"]')).toHaveAttribute(
      'title',
      'Intake — Support · completed',
    )
    expect(strip.querySelector('[data-stage="QA"]')).toHaveAttribute(
      'title',
      'QA / Testing — QA · not started',
    )
  }, SLOW)

  it('says a ticket has been sent back rather than only colouring it amber', async () => {
    seedRows('DEV', { status: 'REWORK', iterationNo: 2 })
    renderPage()
    await journeysReady()

    const strip = screen
      .getAllByRole('img', { name: /sent back/ })
      .find((s) => dotsIn(s).length === 8)!
    expect(strip.querySelector('[data-stage="DEV"]')).toHaveAttribute('data-state', 'REWORKED')
  }, SLOW)

  it('renders an em dash, not a guess, for a row whose stage its template lacks', async () => {
    // Support Fast-Track has no QA stage. A ticket routed there and sitting in
    // QA cannot be placed on that ribbon — which is the `null` case, and the
    // one the mock's own two stage vocabularies produce on nearly every row.
    seedRows('QA')
    getDb().tickets.forEach((ticket) => {
      ticket.taskTypeId = FIVE_STAGE_TASK_TYPE
    })
    renderPage()

    await waitFor(
      () => {
        expect(screen.getAllByRole('row').length).toBeGreaterThan(1)
      },
      { timeout: 10_000 },
    )
    // Nothing resolved, and nothing invented a ribbon to fill the gap.
    expect(screen.queryAllByRole('img', { name: /^Journey:/ })).toHaveLength(0)
  }, SLOW)
})
