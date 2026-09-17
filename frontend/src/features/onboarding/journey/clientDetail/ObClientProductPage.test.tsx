import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ObClientProductPage } from './ObClientProductPage'

/**
 * OB-05 against the mock server — the whole of it, since the client page was
 * removed and its gate, portal login, client info, communications panel and
 * LIVE banner came here to stand beside the journeys and ribbons.
 *
 * Nearly all of this moved from `ObClientDetailPage.test.tsx` unchanged in
 * substance: the assertions are about the same accordions, dots, step panels
 * and cards, none of which changed. What changed is the route they are reached
 * on, so the fixtures are addressed as (client, product):
 *
 * - **Client 1 (GreenValley)** — LIVE, gate cleared. Product 1 (ERP) carries
 *   the signed go-live; product 2 (Biometric) is finished and never asked, so
 *   it is the one that offers the request.
 * - **Client 2 (Sunrise)** — product 1 only, with the fixture's one open client
 *   escalation on its breached migration step.
 * - **Client 3 (Horizon)** — product 1 running, product 2 past the gate and
 *   held behind it. The sibling-hold case, and the one that proves the hold
 *   can name a sibling belonging to a *different* product.
 * - **Client 7 (Little Scholars)** — gate `LOCKED` with one SUBMITTED, one
 *   VERIFIED and three PENDING tasks. The checklist that used to be one page up
 *   is on this page now, and this is the only fixture where its actions do
 *   anything.
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
      // needs to know whose product this is. It is the only place the name is
      // promised: the back link goes to the roster and says so.
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

    /**
     * Back to the roster, not to the client: there is no client page to return
     * to any more. `/onboarding/clients/1` would be `ObClientRedirect`, which
     * resolves the client's first product and would bounce a reader on the
     * biometric page straight back to the ERP.
     */
    it('offers the way back to the list', async () => {
      renderProduct(1, 2)
      const back = await screen.findByRole('link', { name: /All clients/ }, SLOW)
      expect(back).toHaveAttribute('href', '/onboarding/clients')
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

      /*
        The other product *is* named on this page, and legitimately, in both of
        the client-level panels that came here when the client page was removed:
        the info card lists everything bought, and the communications filter
        lists every service it could filter to. What must not appear is a second
        product's *journey* — so the claim is about where the name is allowed to
        be, not whether it occurs at all.

        Asserted as containment rather than as a count, because a count would
        pass for the wrong reason the moment either panel changed how many times
        it prints a name.
      */
      const clientLevel = [
        screen.getByRole('region', { name: 'Client info' }),
        screen.getByTestId('client-communications'),
      ]
      for (const mention of screen.getAllByText('Biometric Attendance')) {
        expect(clientLevel.some((panel) => panel.contains(mention))).toBe(true)
      }
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
   * The gate, which used to be one page up.
   *
   * These assertions moved here wholesale from `ObClientDetailPage.test.tsx`
   * when that page was removed: the checklist did not change, only the screen
   * it stands on. It is addressed as (client 7, product 1) now — Little
   * Scholars' gate is `LOCKED` with one SUBMITTED, one VERIFIED and three
   * PENDING tasks, which is the verifier's queue and the only fixture where the
   * prerequisite actions do anything.
   *
   * <p>The old banner here — "prerequisites have not cleared … Open the
   * checklist" — is gone with the page it linked to. It was a signpost, and the
   * thing it pointed at is now on this screen.
   */
  describe('the prerequisites accordion', () => {
    /** The checklist and the link to it cannot both exist; this is the one that
     * survived. A banner offering to open what is already open would send a
     * reader somewhere they are standing. */
    it('draws the checklist itself rather than a link to it', async () => {
      renderProduct(7, 1)
      await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)
      expect(screen.queryByRole('link', { name: 'Open the checklist' })).not.toBeInTheDocument()
      expect(screen.queryByText(/prerequisites have not cleared/i)).not.toBeInTheDocument()
    })

    /**
     * §9: "defaults open until the gate clears, collapsed after". Little
     * Scholars' gate is locked, and its tasks are the only thing on the page
     * anybody can act on while it stays that way.
     */
    it('opens itself on a client whose gate is still locked', async () => {
      renderProduct(7, 1)
      const trigger = await screen.findByRole(
        'button',
        { name: /Prerequisites — \d of \d mandatory tasks verified/ },
        SLOW,
      )
      expect(trigger).toHaveAttribute('aria-expanded', 'true')
    })

    it('collapses itself on a client whose gate has cleared', async () => {
      renderProduct(1, 1)
      const trigger = await screen.findByRole('button', { name: 'Prerequisites — cleared' }, SLOW)
      expect(trigger).toHaveAttribute('aria-expanded', 'false')
    })

    /**
     * The strip has to say both numbers. A bar reading 4/4 beside a locked gate
     * looks broken unless the screen can also say what else is holding it —
     * `ObClientPrereqs.optionalOutstanding`'s whole reason for existing.
     */
    it('reports mandatory progress as a meter in words, not only as a bar', async () => {
      renderProduct(7, 1)
      const meter = await screen.findByRole('meter', { name: 'Mandatory prerequisites verified' }, SLOW)
      expect(meter).toHaveAttribute('aria-valuetext', expect.stringMatching(/^\d of \d verified$/))
    })

    /**
     * The checklist's job is to say what to send and what to send it on, so a
     * row carries the Admin's wording, its TAT budget beside the due date, and
     * the reference document by name.
     */
    it('gives each task its description, its TAT budget and its reference document', async () => {
      renderProduct(7, 1)
      const list = await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)

      const rows = within(list).getAllByRole('listitem')
      const masterData = rows.find((r) => within(r).queryByText(/Master data extract/))
      expect(masterData).toBeDefined()

      expect(within(masterData!).getByText(/Staff, student and department masters/)).toBeInTheDocument()
      // The budget and the date together — one without the other cannot say
      // whether a task is nearly out of time.
      expect(within(masterData!).getByText(/TAT 7d · due/)).toBeInTheDocument()
      expect(within(masterData!).getByText('master-data-format.xlsx')).toBeInTheDocument()
    })

    /**
     * A chip, not a link. A-102 refuses to serve bytes for an attachment that
     * is not CLEAN, so a download control here would be one that refuses — the
     * name is what the reader needs, without the broken promise.
     */
    it('names the reference document without offering a download', async () => {
      renderProduct(7, 1)
      const doc = await screen.findByText('master-data-format.xlsx', undefined, SLOW)
      expect(doc.closest('a')).toBeNull()
    })

    /**
     * Plan §5.3 has no override: a mandatory task cannot be waived, and the
     * server answers 422 as a fact about the row. Offering the button and
     * letting the refusal arrive from the network would advertise a valve that
     * does not exist.
     */
    it('offers no waiver on a mandatory task', async () => {
      renderProduct(7, 1)
      const list = await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)

      const mandatory = within(list)
        .getAllByRole('listitem')
        .filter((row) => within(row).queryByText(/^Mandatory/))
      expect(mandatory.length).toBeGreaterThan(0)
      for (const row of mandatory) {
        expect(within(row).queryByRole('button', { name: 'Waive' })).not.toBeInTheDocument()
      }
    })

    /**
     * Verify and return exist only on a SUBMITTED task. Rendering them disabled
     * on every pending row would put two dead controls on the page — B-121's
     * line about a dead control teaching the user the board is broken.
     *
     * Scoped to the checklist rather than to the page, which the client page
     * did not have to do: the journey ribbons below this carry controls of
     * their own, and a page-wide count would be asserting about both.
     */
    it('offers verify and return only on a submitted task', async () => {
      renderProduct(7, 1)
      const list = await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)

      expect(within(list).getAllByRole('button', { name: 'Verify' })).toHaveLength(1)
      expect(within(list).getAllByRole('button', { name: 'Return' })).toHaveLength(1)
    })

    /**
     * `ObPrereqReturnRequest.comment` is `minLength: 1`, and the contract says
     * why: this is the one message the client is guaranteed to read, and
     * "returned" with no reason is a round trip that teaches them nothing. The
     * requirement is the point of the dialog rather than a rule the user broke,
     * so the button says so before it is pressed.
     */
    it('will not send a return with no reason', async () => {
      const user = userEvent.setup()
      renderProduct(7, 1)
      const list = await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)

      await user.click(within(list).getByRole('button', { name: 'Return' }))

      const confirm = await screen.findByRole('button', { name: 'Return submission' })
      expect(confirm).toBeDisabled()

      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByRole('textbox'), 'The certificate is unsigned.')
      expect(confirm).toBeEnabled()
    })

    it('sends a submission back and the task returns to pending', async () => {
      const user = userEvent.setup()
      renderProduct(7, 1)
      const list = await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)

      await user.click(within(list).getByRole('button', { name: 'Return' }))
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByRole('textbox'), 'The certificate is unsigned.')
      await user.click(screen.getByRole('button', { name: 'Return submission' }))

      // The one submitted task is now pending, so nothing is verifiable.
      await waitFor(
        () => expect(within(list).queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument(),
        SLOW,
      )
    })

    it('shows the journey as prerequisites pending rather than as held', async () => {
      renderProduct(7, 1)
      expect(await screen.findAllByText('Prerequisites pending', undefined, SLOW)).not.toHaveLength(0)
      expect(screen.queryByText(/^Held for /)).not.toBeInTheDocument()
    })
  })

  /**
   * §9's closing pair and C-112's panel, which came here with the gate when the
   * client page was removed. They are client-level on a page that is one
   * product, and drawn below the ribbons deliberately: none of the three is
   * worked down, so they go where they push nothing actionable off the fold.
   */
  describe('the closing grid — §9\'s "client portal access + client info"', () => {
    it('mounts B-126\'s portal-login panel with the account it reads', async () => {
      renderProduct(1, 1)
      await screen.findByRole('heading', { name: 'Client portal login' }, SLOW)
      expect(await screen.findAllByText(/GREENVALLEY\.deepa/, undefined, SLOW)).not.toHaveLength(0)
    })

    it('draws the client info card from the detail document', async () => {
      renderProduct(1, 1)
      await screen.findByRole('heading', { name: 'Client info' }, SLOW)

      // SPOCs — the primary flagged, and the departed one still visible: the
      // contract sends inactive contacts so a past sign-off stays explicable.
      expect(screen.getByText('Deepa Kulkarni')).toBeInTheDocument()
      expect(screen.getByText('PRIMARY')).toBeInTheDocument()
      expect(screen.getByText('Farida Qureshi')).toBeInTheDocument()

      // Requirements and the address; attachments are the card's one extra
      // read, listed by name.
      expect(screen.getByText('Single sign-on')).toBeInTheDocument()
      expect(screen.getByText(/14 Ridge Rd/)).toBeInTheDocument()
      expect(await screen.findByText('greenvalley-msa-signed.pdf', undefined, SLOW)).toBeInTheDocument()
    })

    /** C-112's stitched view is the client's, not the product's — "everything
     * said to this client, across every service" is the question it answers, so
     * it is handed every journey rather than this product's. */
    it("mounts the client's communications panel", async () => {
      renderProduct(1, 1)
      const panel = await screen.findByTestId('client-communications', undefined, SLOW)
      expect(within(panel).getByRole('heading', { name: 'Communications' })).toBeInTheDocument()
    })

    /**
     * §9's OB-05 row opens with this: "**PAN is not shown in the header**". The
     * API masks it for all but two roles and the unmasked value has its own
     * audited reveal — so this assertion is not what protects the value. It is
     * what keeps a masked identity string off a screen that gets shared and
     * screenshotted all day.
     */
    it('keeps identity data out of the header', async () => {
      renderProduct(1, 1)
      await screen.findByRole('heading', { name: 'EduTrack ERP', level: 1 }, SLOW)
      expect(screen.queryByText(/AAGCG/)).not.toBeInTheDocument()
    })

    /** Plan §1.2 removed financial tracking from the module. The prototype had
     * a payments card and the prototype is not the specification. */
    it('has no payments card', async () => {
      renderProduct(1, 1)
      await screen.findByRole('heading', { name: 'Client info' }, SLOW)
      expect(screen.queryByText(/payment/i)).not.toBeInTheDocument()
    })
  })

  /**
   * The LIVE banner, also rescued from the client page. It carries the go-live
   * date and the CSAT score — B-119's survey answer from the GO_LIVE sign-off,
   * seeded 5 for GreenValley — and nothing else on any screen carries either.
   */
  describe('the LIVE banner', () => {
    it('celebrates a LIVE client, with the go-live date and the CSAT score', async () => {
      renderProduct(1, 1)
      const banner = await screen.findByText(/Fully onboarded & LIVE since 7 Aug 2026/, undefined, SLOW)
      expect(banner).toHaveTextContent(/all 2 journeys complete, sign-offs on record · CSAT 5\/5/)
    })

    it('does not appear on a client still onboarding', async () => {
      renderProduct(7, 1)
      await screen.findByRole('list', { name: 'Prerequisite tasks' }, SLOW)
      expect(screen.queryByText(/Fully onboarded/)).not.toBeInTheDocument()
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
    expect(screen.getByRole('link', { name: /All clients/ }))
      .toHaveAttribute('href', '/onboarding/clients')
  })

  it('says so rather than rendering an empty page when the client is out of scope', async () => {
    renderProduct(9999, 1)
    expect(await screen.findByText('Client not found', undefined, SLOW)).toBeInTheDocument()
  })
})
