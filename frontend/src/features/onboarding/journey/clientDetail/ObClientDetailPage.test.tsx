import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ObClientDetailPage } from './ObClientDetailPage'

/**
 * C-110 · OB-05 against the mock server.
 *
 * The seeded clients are the fixture, deliberately, because they are the ones
 * the rest of the module was built against:
 *
 * - **Client 1 (GreenValley)** — LIVE, gate cleared, two finished journeys.
 *   The quiet baseline for the header and the collapsed prerequisites strip.
 * - **Client 3 (Horizon)** — gate cleared, two journeys, one running and one
 *   `OPEN` but held behind its sibling — the sibling-hold case.
 * - **Client 7 (Little Scholars)** — gate `LOCKED` with one SUBMITTED, one
 *   VERIFIED and three PENDING tasks. The verifier's queue, and the only
 *   client where the prerequisite actions on this page do anything.
 * - **Client 8 (Trinity)** — a running journey with a BLOCKED step, which is
 *   what the ribbon centres on and the dots have to colour.
 */
function renderClient(id: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/onboarding/clients/${id}`]}>
        <Routes>
          <Route path="/onboarding/clients/:obClientId" element={<ObClientDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** MSW adds latency and the suite is heavily parallel — `ClientListPage.test.tsx`'s convention. */
const SLOW = { timeout: 5000 }

describe('ObClientDetailPage', () => {
  describe('the header', () => {
    it('names the client and its gate without putting identity data on screen', async () => {
      renderClient(1)
      await screen.findByText('GreenValley International School', undefined, SLOW)

      /**
       * §9's OB-05 row opens with this: "**PAN is not shown in the header**".
       * The API masks it for all but two roles and the unmasked value has its
       * own audited reveal — so this assertion is not what protects the value.
       * It is what keeps a masked identity string off a screen that gets
       * shared and screenshotted all day.
       */
      expect(screen.queryByText(/AAGCG/)).not.toBeInTheDocument()
      expect(screen.queryByText(/••••/)).not.toBeInTheDocument()
    })

    /**
     * Plan §1.2 removed financial tracking from the module. The prototype had
     * a payments card and the prototype is not the specification.
     */
    it('has no payments card', async () => {
      renderClient(1)
      await screen.findByText('GreenValley International School', undefined, SLOW)
      expect(screen.queryByText(/payment/i)).not.toBeInTheDocument()
    })
  })

  describe('the prerequisites accordion', () => {
    /**
     * §9: "defaults open until the gate clears, collapsed after". Little
     * Scholars' gate is locked, and the tasks are the only thing on the page
     * anybody can act on while it stays that way.
     */
    it('opens itself on a client whose gate is still locked', async () => {
      renderClient(7)
      const trigger = await screen.findByRole(
        'button',
        { name: /Prerequisites — \d of \d mandatory tasks verified/ },
        SLOW,
      )
      expect(trigger).toHaveAttribute('aria-expanded', 'true')
    })

    it('collapses itself on a client whose gate has cleared', async () => {
      renderClient(1)
      const trigger = await screen.findByRole('button', { name: 'Prerequisites — cleared' }, SLOW)
      expect(trigger).toHaveAttribute('aria-expanded', 'false')
    })

    /**
     * The strip has to say both numbers. A bar reading 4/4 beside a locked
     * gate looks broken unless the screen can also say what else is holding
     * it — `ObClientPrereqs.optionalOutstanding`'s whole reason for existing.
     */
    it('reports mandatory progress as a meter in words, not only as a bar', async () => {
      renderClient(7)
      const meter = await screen.findByRole('meter', { name: 'Mandatory prerequisites verified' }, SLOW)
      expect(meter).toHaveAttribute('aria-valuetext', expect.stringMatching(/^\d of \d verified$/))
    })

    /**
     * Plan §5.3 has no override: a mandatory task cannot be waived, and the
     * server answers 422 as a fact about the row. Offering the button and
     * letting the refusal arrive from the network would advertise a valve
     * that does not exist.
     */
    it('offers no waiver on a mandatory task', async () => {
      renderClient(7)
      await screen.findByRole('button', { name: /Prerequisites/ }, SLOW)

      const rows = await screen.findAllByRole('listitem', undefined, SLOW)
      const mandatory = rows.filter((row) => within(row).queryByText(/^Mandatory/))
      expect(mandatory.length).toBeGreaterThan(0)
      for (const row of mandatory) {
        expect(within(row).queryByRole('button', { name: 'Waive' })).not.toBeInTheDocument()
      }
    })

    /**
     * Verify and return exist only on a SUBMITTED task. Rendering them
     * disabled on every pending row would put two dead controls on the page —
     * B-121's line about a dead control teaching the user the board is broken.
     */
    it('offers verify and return only on a submitted task', async () => {
      renderClient(7)
      await screen.findByRole('button', { name: /Prerequisites/ }, SLOW)

      const verifyButtons = await screen.findAllByRole('button', { name: 'Verify' }, SLOW)
      expect(verifyButtons).toHaveLength(1)
      expect(screen.getAllByRole('button', { name: 'Return' })).toHaveLength(1)
    })

    /**
     * `ObPrereqReturnRequest.comment` is `minLength: 1`, and the contract says
     * why: this is the one message the client is guaranteed to read, and
     * "returned" with no reason is a round trip that teaches them nothing. The
     * requirement is the point of the dialog rather than a rule the user
     * broke, so the button says so before it is pressed.
     */
    it('will not send a return with no reason', async () => {
      const user = userEvent.setup()
      renderClient(7)
      await screen.findByRole('button', { name: /Prerequisites/ }, SLOW)

      await user.click(await screen.findByRole('button', { name: 'Return' }, SLOW))

      const confirm = await screen.findByRole('button', { name: 'Return submission' })
      expect(confirm).toBeDisabled()

      await user.type(screen.getByRole('textbox'), 'The certificate is unsigned.')
      expect(confirm).toBeEnabled()
    })

    it('sends a submission back and the task returns to pending', async () => {
      const user = userEvent.setup()
      renderClient(7)
      await screen.findByRole('button', { name: /Prerequisites/ }, SLOW)

      await user.click(await screen.findByRole('button', { name: 'Return' }, SLOW))
      await user.type(await screen.findByRole('textbox'), 'The certificate is unsigned.')
      await user.click(screen.getByRole('button', { name: 'Return submission' }))

      // The one submitted task is now pending, so nothing is verifiable.
      await waitFor(
        () => expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument(),
        SLOW,
      )
    })
  })

  describe('the journey accordions', () => {
    it('draws one per purchased product, with its own step dots', async () => {
      renderClient(1)
      await screen.findByText('GreenValley International School', undefined, SLOW)

      const dotStrips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      expect(dotStrips).toHaveLength(2)
      // The ERP journey seeds eight services, the attendance one five.
      expect(within(dotStrips[0]).getAllByRole('img')).toHaveLength(8)
      expect(within(dotStrips[1]).getAllByRole('img')).toHaveLength(5)
    })

    /**
     * Every dot names its service, its state and its colour. Nine dots in
     * three colours is exactly the chart CLAUDE.md's WCAG line exists to stop.
     *
     * Client 1's "Data Migration" step (`OB_CLIENT_ESCALATIONS` in the mock
     * db) carries an open C-126 escalation, so its label carries that too —
     * colour is never the only signal, and a red ring on a dot means nothing
     * to a screen-reader user unless the name says why.
     */
    it('names every dot rather than leaving colour to carry it', async () => {
      renderClient(8)
      const strips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      const labels = within(strips[0])
        .getAllByRole('img')
        .map((dot) => dot.getAttribute('aria-label'))
      expect(labels).toContain('5. Configuration & branding — Blocked · red')
      expect(labels[0]).toMatch(/^1\. Kickoff call — Done/)
    })

    /**
     * C-126 · Sunrise's "Data migration" step (journey 21, step 214) carries
     * the fixture's one open `OB_CLIENT_ESCALATIONS` row — raised from the
     * portal against the same breach `OB_ESCALATIONS`' L1/L2 rungs already
     * fire on. Colour is never the only signal (the class-level note above),
     * so an escalated dot's name has to say so too, not just ring red.
     */
    it('names an escalated step rather than leaving the red ring to carry it', async () => {
      renderClient(2)
      const strips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      const labels = await waitFor(() => {
        const found = within(strips[0])
          .getAllByRole('img')
          .map((dot) => dot.getAttribute('aria-label'))
        expect(found).toContain('4. Data migration — In progress · red — escalated, awaiting staff')
        return found
      }, SLOW)
      expect(labels[0]).toMatch(/^1\. Kickoff call — Done/)
    })

    /**
     * A journey past the gate and held behind a sibling (plan §5.5) is not a
     * journey with prerequisites pending. A reader told the wrong one goes and
     * clears prerequisites that were already cleared.
     */
    it('tells a sibling hold apart from a locked gate, and names the sibling', async () => {
      renderClient(3)
      await screen.findByText('Horizon Academy', undefined, SLOW)

      expect(await screen.findByText(/^Held for /, undefined, SLOW)).toBeInTheDocument()
      expect(screen.queryByText('Prerequisites pending')).not.toBeInTheDocument()
    })

    it('shows a locked journey as prerequisites pending rather than as held', async () => {
      renderClient(7)
      await screen.findByText('Little Scholars Preschool', undefined, SLOW)

      expect(await screen.findAllByText('Prerequisites pending', undefined, SLOW)).not.toHaveLength(0)
      expect(screen.queryByText(/^Held for /)).not.toBeInTheDocument()
    })

    /**
     * The contract splits the strip from the ribbon so "a client with six
     * journeys does not pay for six ribbons on first paint". A collapsed
     * accordion that fetched its ribbon anyway would keep the split on the
     * wire and lose the whole benefit of it.
     */
    it('does not fetch a ribbon until its accordion is expanded', async () => {
      const user = userEvent.setup()
      renderClient(1)
      await screen.findByText('GreenValley International School', undefined, SLOW)

      expect(screen.queryByRole('list', { name: 'Journey steps' })).not.toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /^EduTrack ERP —/ }))
      expect(await screen.findByRole('list', { name: 'Journey steps' }, SLOW)).toBeInTheDocument()
    }, 15000)

    /**
     * Expanding lands the reader on the journey's current state rather than on
     * an empty panel asking them to pick a tile the ribbon has already
     * centred on.
     */
    it('opens the step panel on the journey it is already showing', async () => {
      const user = userEvent.setup()
      renderClient(8)
      await screen.findByText('Trinity College of Commerce', undefined, SLOW)

      await user.click(screen.getByRole('button', { name: /^EduTrack ERP —/ }))

      const panel = await screen.findByTestId('journey-step-panel', undefined, SLOW)
      // Configuration & branding is the ERP journey's BLOCKED step and the one
      // the ribbon centres on, so it is the one the panel should already be on.
      expect(within(panel).getByText('5. Configuration & branding')).toBeInTheDocument()
    }, 15000)
  })

  /**
   * §9's accordion UX rule, and the reason `useAnchoredToggle` exists:
   *
   * > expanding/collapsing or selecting a step never scrolls the page — scroll
   * > position is preserved on all same-page interactions.
   *
   * jsdom performs no layout, so every `getBoundingClientRect` reads 0 and the
   * correction is always a no-op here. What this can prove is the half that
   * actually regresses: that nothing on the page *asks* the window to scroll.
   * A `scrollIntoView` reintroduced into the ribbon — which is what OB-05
   * would have inherited from C-109 — fails this immediately.
   */
  describe('the accordion never scrolls the page', () => {
    it('asks nothing to scroll into view when a journey is expanded or a step selected', async () => {
      const user = userEvent.setup()
      const scrollIntoView = vi.fn()
      const original = Element.prototype.scrollIntoView
      Element.prototype.scrollIntoView = scrollIntoView

      try {
        renderClient(1)
        await screen.findByText('GreenValley International School', undefined, SLOW)

        const triggers = screen.getAllByRole('button', { name: /complete/ })
        await user.click(triggers[0])
        await screen.findByRole('list', { name: 'Journey steps' }, SLOW)

        const segments = screen.getAllByRole('button', { name: /^Step \d/ })
        await user.click(segments[segments.length - 1])

        await user.click(triggers[0])

        expect(scrollIntoView).not.toHaveBeenCalled()
      } finally {
        Element.prototype.scrollIntoView = original
      }
    })
  })

  /**
   * C-126 · client 1's "Data Migration" step carries a seeded open
   * escalation (`OB_CLIENT_ESCALATIONS` in the mock db) — the banner plan
   * §4/§9 ask for, and resolve-and-acknowledge.
   */
  describe('escalations', () => {
    it('shows the open escalation in a banner, and resolving it clears the banner and the dot', async () => {
      const user = userEvent.setup()
      renderClient(1)
      await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)

      expect(
        await screen.findByText(/eight working days/, undefined, SLOW),
      ).toBeInTheDocument()
      expect(screen.getByRole('alert', { name: 'Open client escalations' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Resolve' }))
      const dialog = await screen.findByRole('dialog', undefined, SLOW)
      await user.type(within(dialog).getByLabelText('Resolution note'), 'Ran the migration overnight; verified with the client.')
      await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))

      await waitFor(
        () => expect(screen.queryByRole('alert', { name: 'Open client escalations' })).not.toBeInTheDocument(),
        SLOW,
      )

      const strips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      const labels = within(strips[0])
        .getAllByRole('img')
        .map((dot) => dot.getAttribute('aria-label'))
      expect(labels).toContain('3. Data Migration — Blocked · red')
      expect(labels).not.toContain('3. Data Migration — Blocked · red — escalated, awaiting staff')
    })

    it('will not resolve with no note', async () => {
      const user = userEvent.setup()
      renderClient(1)
      await screen.findByRole('alert', { name: 'Open client escalations' }, SLOW)

      await user.click(screen.getByRole('button', { name: 'Resolve' }))
      const dialog = await screen.findByRole('dialog', undefined, SLOW)
      expect(within(dialog).getByRole('button', { name: 'Resolve' })).toBeDisabled()
    })
  })

  it('says so rather than rendering an empty page when the client is out of scope', async () => {
    renderClient(9999)
    expect(await screen.findByText('Client not found', undefined, SLOW)).toBeInTheDocument()
  })
})
