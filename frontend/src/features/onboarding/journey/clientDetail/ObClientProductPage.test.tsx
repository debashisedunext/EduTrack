import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ObClientProductPage } from './ObClientProductPage'

/**
 * OB-05's product half against the mock server — the journeys, the ribbons and
 * the sign-offs that used to be stacked on the client page.
 *
 * Most of what is here moved from `ObClientDetailPage.test.tsx` unchanged in
 * substance: the assertions are about the accordion, the dots, the step panel
 * and §8's sign-off panel, none of which changed. What changed is the route
 * they are reached on, so the fixtures are addressed as (client, product):
 *
 * - **Client 1 (GreenValley)** — LIVE, gate cleared. Product 1 (ERP) carries
 *   the signed go-live; product 2 (Biometric) is finished and never asked, so
 *   it is the one that offers the request.
 * - **Client 2 (Sunrise)** — product 1 only, with the fixture's one open client
 *   escalation on its breached migration step.
 * - **Client 3 (Horizon)** — product 1 running, product 2 past the gate and
 *   held behind it. The sibling-hold case, and the one that proves the hold
 *   can name a sibling belonging to a *different* product.
 * - **Client 7 (Little Scholars)** — gate `LOCKED`, so its product page has to
 *   say what is holding the ribbons and point at where to clear it.
 * - **Client 8 (Trinity)** — a running journey with a BLOCKED step, which is
 *   what the ribbon centres on and the panel opens onto.
 */
function renderProduct(obClientId: number, productId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/onboarding/clients/${obClientId}/products/${productId}`]}>
        <Routes>
          <Route
            path="/onboarding/clients/:obClientId/products/:productId"
            element={<ObClientProductPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** MSW adds latency and the suite is heavily parallel — `ClientListPage.test.tsx`'s convention. */
const SLOW = { timeout: 5000 }

/** The client page's reason, and it applies to this half now: the client, the
 * directory, the escalations, then the opened journey's ribbon, its step
 * detail, that step's communications and its sign-off. */
vi.setConfig({ testTimeout: 20_000 })

describe('ObClientProductPage', () => {
  describe('the header', () => {
    it('names the product, whose client it belongs to, and what was bought', async () => {
      renderProduct(1, 1)
      expect(await screen.findByRole('heading', { name: 'EduTrack ERP', level: 1 }, SLOW))
        .toBeInTheDocument()
      // The client is the caption — a reader who arrived from a mail link
      // needs to know whose product this is. Twice over, in fact: the back
      // link is the client's name too.
      expect(screen.getAllByText(/GreenValley International School/).length).toBeGreaterThan(0)
      expect(screen.getByText(/2 module services|1 module service/)).toBeInTheDocument()
      expect(screen.getByText(/Enterprise · 120 units/)).toBeInTheDocument()
    })

    /** The figure and the count it came from, because "88%" alone cannot be
     * checked against anything a reader can see. */
    it('prints progress as services complete, not only as a percentage', async () => {
      renderProduct(1, 1)
      await screen.findByRole('heading', { name: 'EduTrack ERP', level: 1 }, SLOW)
      expect(screen.getByText('8 of 8 services complete')).toBeInTheDocument()
    })

    it('offers the way back to the client', async () => {
      renderProduct(1, 2)
      const back = await screen.findByRole(
        'link',
        { name: /GreenValley International School/ },
        SLOW,
      )
      expect(back).toHaveAttribute('href', '/onboarding/clients/1')
    })
  })

  describe('the journey accordions', () => {
    /**
     * One strip per Module Service **of this product**, and nobody else's.
     * GreenValley runs two products; the ERP page draws the ERP's eight
     * services and none of the biometric rollout's five.
     */
    it('draws this product journeys and no other product', async () => {
      renderProduct(1, 1)
      const dotStrips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      expect(dotStrips).toHaveLength(1)
      expect(within(dotStrips[0]).getAllByRole('img')).toHaveLength(8)

      expect(screen.queryByText('Biometric Attendance')).not.toBeInTheDocument()
    })

    it('draws the other product on the other product page', async () => {
      renderProduct(1, 2)
      const dotStrips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      expect(dotStrips).toHaveLength(1)
      expect(within(dotStrips[0]).getAllByRole('img')).toHaveLength(5)
    })

    /**
     * Every dot names its service, its state and its colour. Nine dots in
     * three colours is exactly the chart CLAUDE.md's WCAG line exists to stop.
     */
    it('names every dot rather than leaving colour to carry it', async () => {
      renderProduct(8, 1)
      const strips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      const labels = within(strips[0])
        .getAllByRole('img')
        .map((dot) => dot.getAttribute('aria-label'))
      expect(labels).toContain('5. Configuration & branding — Blocked · red')
      expect(labels[0]).toMatch(/^1\. Kickoff call — Done/)
    })

    /**
     * C-126 · Sunrise's "Data migration" step (journey 21, step 214) carries
     * the fixture's one open `OB_CLIENT_ESCALATIONS` row. Colour is never the
     * only signal, so an escalated dot's name has to say so too, not just ring
     * red.
     */
    it('names an escalated step rather than leaving the red ring to carry it', async () => {
      renderProduct(2, 1)
      const strips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      await waitFor(() => {
        const found = within(strips[0])
          .getAllByRole('img')
          .map((dot) => dot.getAttribute('aria-label'))
        expect(found).toContain('4. Data migration — In progress · red — escalated, awaiting staff')
      }, SLOW)
    })

    /**
     * A journey past the gate and held behind a sibling (plan §5.5) is not a
     * journey with prerequisites pending. A reader told the wrong one goes and
     * clears prerequisites that were already cleared.
     *
     * Horizon's biometric journey is held behind its **ERP** journey — a
     * different product, and therefore not on this page. Naming it anyway is
     * why the page hands `JourneyAccordion` every journey of the client as
     * siblings rather than only the ones it draws.
     */
    it('names the sibling holding this product, even across products', async () => {
      renderProduct(3, 2)

      // Two "held" statements and they are deliberately different: the header
      // says the product is held, the strip says which service holds it. The
      // strip's is the one that saves a reader a lookup.
      const held = await screen.findAllByText(/^Held for /, undefined, SLOW)
      // The ERP journey is the fixture's un-templated one, so its service name
      // falls back to the product's — `JourneyAccordion`'s stated concession.
      expect(held.map((el) => el.textContent)).toContain('Held for EduTrack ERP')
      expect(screen.queryByText('Prerequisites pending')).not.toBeInTheDocument()
    })

    /**
     * The page opens one journey on first paint, the way the mockup's
     * `vClient` does — a product page with no ribbon on it is missing the
     * thing the screen is for. Which one is
     * `journeyStrip.defaultOpenJourneyId`'s decision and is tested there.
     */
    it('opens a journey ribbon on arrival', async () => {
      renderProduct(1, 1)
      expect(await screen.findAllByRole('list', { name: 'Journey steps' }, SLOW)).toHaveLength(1)
    })

    /**
     * The reader lands on the journey's current state rather than on an empty
     * panel asking them to pick a tile the ribbon has already centred on.
     */
    it('opens the step panel on the journey it is already showing', async () => {
      renderProduct(8, 1)
      const panel = await screen.findByTestId('journey-step-panel', undefined, SLOW)
      // Configuration & branding is the ERP journey's BLOCKED step and the one
      // the ribbon centres on, so it is the one the panel should already be on.
      expect(within(panel).getByText('5. Configuration & branding')).toBeInTheDocument()
    }, 15000)
  })

  /**
   * The gate belongs to the client and is administered on the client page, so
   * this page states what is holding its ribbons and points at where to clear
   * it — rather than repeating a checklist that would then exist twice with
   * one copy always slightly behind.
   */
  describe('a locked gate', () => {
    it('says nothing here starts, and links to the checklist', async () => {
      renderProduct(7, 1)
      expect(await screen.findByText(/Nothing here starts until/, undefined, SLOW)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Open the checklist' })).toHaveAttribute(
        'href',
        '/onboarding/clients/7',
      )
    })

    it('shows the journey as prerequisites pending rather than as held', async () => {
      renderProduct(7, 1)
      expect(await screen.findAllByText('Prerequisites pending', undefined, SLOW)).not.toHaveLength(0)
      expect(screen.queryByText(/^Held for /)).not.toBeInTheDocument()
    })
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
   */
  describe('the accordion never scrolls the page', () => {
    it('asks nothing to scroll into view when a journey is expanded or a step selected', async () => {
      const user = userEvent.setup()
      const scrollIntoView = vi.fn()
      const original = Element.prototype.scrollIntoView
      Element.prototype.scrollIntoView = scrollIntoView

      try {
        renderProduct(1, 1)
        // The page opens one journey itself, so its ribbon is already here.
        await screen.findAllByRole('list', { name: 'Journey steps' }, SLOW)

        // Selecting a step, then collapsing, then expanding again — the three
        // interactions §9's rule is about.
        const segments = screen.getAllByRole('button', { name: /^Step \d/ })
        await user.click(segments[segments.length - 1])

        const trigger = screen
          .getAllByRole('button', { name: /complete/ })
          .find((b) => b.getAttribute('aria-expanded') === 'true')!
        await user.click(trigger)
        await user.click(trigger)
        await screen.findAllByRole('list', { name: 'Journey steps' }, SLOW)

        expect(scrollIntoView).not.toHaveBeenCalled()
      } finally {
        Element.prototype.scrollIntoView = original
      }
    })
  })

  /**
   * C-126 · the banner is **scoped to this product's services**. The
   * escalations are read per client, and one on an ERP migration has no
   * business shouting on the biometric page, where the ribbon it points at is
   * not even drawn.
   */
  describe('escalations', () => {
    it('shows this product open escalation, and resolving it clears the banner and the dot', async () => {
      const user = userEvent.setup()
      renderProduct(2, 1)

      const banner = await screen.findByRole('alert', { name: 'Open client escalations' }, SLOW)
      expect(within(banner).getByText('Data migration')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Resolve' }))
      const dialog = await screen.findByRole('dialog', undefined, SLOW)
      await user.type(
        within(dialog).getByLabelText('Resolution note'),
        'Ran the migration overnight; verified with the client.',
      )
      await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))

      await waitFor(
        () => expect(screen.queryByRole('alert', { name: 'Open client escalations' })).not.toBeInTheDocument(),
        SLOW,
      )

      const strips = await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      const labels = within(strips[0])
        .getAllByRole('img')
        .map((dot) => dot.getAttribute('aria-label'))
      expect(labels).toContain('4. Data migration — In progress · red')
      expect(labels).not.toContain('4. Data migration — In progress · red — escalated, awaiting staff')
    })

    /** Nothing to shout about on a product whose services carry no
     * escalation — an empty banner on every page forever trains a reader to
     * stop looking at it. */
    it('draws no banner on a product with no escalation of its own', async () => {
      renderProduct(1, 2)
      await screen.findAllByRole('list', { name: 'Service status' }, SLOW)
      expect(screen.queryByRole('alert', { name: 'Open client escalations' })).not.toBeInTheDocument()
    })
  })

  /**
   * §8, the staff half. The client-facing page is `PublicSignoffPage`; this is
   * the side that asks, chases and withdraws.
   */
  describe('sign-off', () => {
    /**
     * GreenValley's ERP go-live is the seeded signed one. A settled decision is
     * not re-askable, so the panel reports it and offers the certificate rather
     * than a button whose only outcome is the server's 422.
     */
    it('reports an accepted go-live and offers its certificate, with nothing to re-ask', async () => {
      renderProduct(1, 1)
      const panel = await screen.findByRole('region', { name: 'Go-live sign-off' }, SLOW)

      // findBy, not getBy: the section mounts before its own sign-off read
      // lands, and until then the status is a skeleton.
      expect(await within(panel).findByText('✓ Accepted', undefined, SLOW)).toBeInTheDocument()
      expect(within(panel).getByRole('link', { name: /Download the signed certificate/ }))
        .toHaveAttribute('href', expect.stringContaining('/certificate'))
      expect(within(panel).queryByRole('button', { name: /Request sign-off/ })).not.toBeInTheDocument()
      expect(within(panel).queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument()
    })

    /**
     * GreenValley's biometric journey is finished too and has never been asked,
     * so it is the one that offers the request — and on its own page it is
     * already open, which is one fewer click than the stacked accordions took.
     *
     * `sentToContactId` is required rather than defaulted, and the contract
     * says why: "the person who signs off a data migration is frequently not
     * the person who signs the contract, and a default that is usually right
     * is one nobody checks."
     */
    it('will not request a go-live until a contact is chosen, then chases it', async () => {
      const user = userEvent.setup()
      renderProduct(1, 2)

      const panel = await waitFor(
        () => {
          const panels = screen.getAllByRole('region', { name: 'Go-live sign-off' })
          const fresh = panels.find((p) => within(p).queryByText('Not requested'))
          expect(fresh).toBeDefined()
          return fresh!
        },
        SLOW,
      )

      const request = within(panel).getByRole('button', { name: /Request sign-off/ })
      expect(request).toBeDisabled()

      // By the option's own value — its label carries the designation too, and
      // pinning the whole string here would make this test fail on a wording
      // change it is not about.
      const deepa = within(panel).getByRole('option', { name: /Deepa Kulkarni/ }) as HTMLOptionElement
      await user.selectOptions(within(panel).getByLabelText('Send to'), deepa.value)
      expect(request).toBeEnabled()

      await user.click(request)

      // The request lands and the panel switches to chasing it.
      expect(await within(panel).findByText('Awaiting the client', undefined, SLOW)).toBeInTheDocument()
      expect(within(panel).getByRole('button', { name: /Email the link again/ })).toBeInTheDocument()
      // A second request would be the server's 409, so it is no longer offered.
      expect(within(panel).queryByRole('button', { name: /Request sign-off/ })).not.toBeInTheDocument()

      /**
       * A withdrawal is on the record and needs its reason, the same call
       * `PrereqReasonDialog` makes about a return: `cancelObSignoff` answers
       * 422 for a blank one.
       */
      await user.click(within(panel).getByRole('button', { name: 'Withdraw' }))
      expect(await screen.findByRole('button', { name: 'Withdraw the request' })).toBeDisabled()
    })

    /**
     * Trinity's ERP journey is mid-flight, so its go-live would be the
     * server's `ob-signoff-journey-incomplete` 422. The panel is absent rather
     * than disabled — the same call the action bar makes about a transition
     * the service refuses outright.
     */
    it('does not offer a go-live on a journey still in flight', async () => {
      renderProduct(8, 1)
      await screen.findByTestId('journey-step-panel', undefined, SLOW)
      expect(screen.queryByRole('region', { name: 'Go-live sign-off' })).not.toBeInTheDocument()
    })
  })

  /**
   * A product this client never bought is not found, not an empty page. The
   * row scope has already decided the reader may see the client, so there is
   * no existence to leak — what there is instead is a stale link, and a way
   * back to something real is worth more than a page of nothing.
   */
  it('says so when this client has no journey for the product', async () => {
    renderProduct(1, 3)
    expect(await screen.findByText('Product not found', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /GreenValley International School/ }))
      .toHaveAttribute('href', '/onboarding/clients/1')
  })

  it('says so rather than rendering an empty page when the client is out of scope', async () => {
    renderProduct(9999, 1)
    expect(await screen.findByText('Client not found', undefined, SLOW)).toBeInTheDocument()
  })
})
