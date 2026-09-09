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

/**
 * OB-05 drives more reads per mount than any other screen in the module — the
 * client, its prerequisites, the directory, the open escalations, then the
 * opened journey's ribbon, its step detail, that step's communications and its
 * sign-off. Every one is an MSW round trip with latency, and vitest runs the
 * files in parallel.
 *
 * Vitest's 5s default therefore sat exactly on `SLOW`, so a `findBy` that used
 * its full budget timed the *test* out before it could report which query
 * failed. The extra headroom buys a real assertion failure instead of a
 * stopwatch one; it does not make any individual wait longer.
 */
vi.setConfig({ testTimeout: 20_000 })

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
     * The checklist's job is to say what to send and what to send it on, so a
     * row carries the Admin's wording, its TAT budget beside the due date, and
     * the reference document by name.
     */
    it('gives each task its description, its TAT budget and its reference document', async () => {
      renderClient(7)
      await screen.findByRole('button', { name: /Prerequisites/ }, SLOW)

      const rows = await screen.findAllByRole('listitem', undefined, SLOW)
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
     * is not CLEAN, so a download control here would be one that refuses —
     * the name is what the reader needs, without the broken promise.
     */
    it('names the reference document without offering a download', async () => {
      renderClient(7)
      await screen.findByRole('button', { name: /Prerequisites/ }, SLOW)

      const doc = await screen.findByText('master-data-format.xlsx', undefined, SLOW)
      expect(doc.closest('a')).toBeNull()
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
    /**
     * The page opens one journey on first paint, the way the mockup's
     * `vClient` does — a client detail page with no ribbon on it is missing
     * the thing the screen is for.
     *
     * The contract this must not break is the *other* half: the journey read
     * is per-journey and fired on expand, so "a client with six journeys does
     * not pay for six ribbons on first paint". One open by default is one
     * ribbon, not six — so the assertion is that exactly one is drawn, and
     * the second arrives only when it is asked for.
     */
    it('opens one journey on load, and fetches no other ribbon until it is expanded', async () => {
      const user = userEvent.setup()
      renderClient(1)
      await screen.findByText('GreenValley International School', undefined, SLOW)

      // GreenValley has two journeys. Exactly one ribbon, unasked.
      expect(await screen.findAllByRole('list', { name: 'Journey steps' }, SLOW)).toHaveLength(1)

      const collapsed = screen
        .getAllByRole('button', { name: /complete/ })
        .find((b) => b.getAttribute('aria-expanded') === 'false')
      expect(collapsed).toBeDefined()

      await user.click(collapsed!)
      await waitFor(
        async () => expect(await screen.findAllByRole('list', { name: 'Journey steps' })).toHaveLength(2),
        SLOW,
      )
    }, 20000)

    /**
     * The reader lands on the journey's current state rather than on an empty
     * panel asking them to pick a tile the ribbon has already centred on.
     */
    it('opens the step panel on the journey it is already showing', async () => {
      renderClient(8)
      await screen.findByText('Trinity College of Commerce', undefined, SLOW)

      const panel = await screen.findByTestId('journey-step-panel', undefined, SLOW)
      // Configuration & branding is the ERP journey's BLOCKED step and the one
      // the ribbon centres on, so it is the one the panel should already be on.
      expect(within(panel).getByText('5. Configuration & branding')).toBeInTheDocument()
    }, 15000)

    /**
     * The running journey, not merely the first — a client whose first product
     * is finished and whose second is mid-flight should open on the one
     * somebody has work to do in.
     */
    it('opens the journey that is running rather than the first one listed', async () => {
      renderClient(3)
      await screen.findByText('Horizon Academy', undefined, SLOW)

      const triggers = await screen.findAllByRole('button', { name: /complete/ }, SLOW)
      const expanded = triggers.filter((t) => t.getAttribute('aria-expanded') === 'true')
      expect(expanded).toHaveLength(1)
      // Horizon's second journey is held behind its sibling, so the one that
      // opens is the sibling that is actually moving.
      expect(expanded[0]).toHaveAccessibleName(/^EduTrack ERP —/)
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
   * C-126 · Sunrise's "Data migration" step (journey 21, step 214) carries
   * the fixture's one seeded open escalation (`OB_CLIENT_ESCALATIONS` in the
   * mock db) — the banner plan §4/§9 ask for, and resolve-and-acknowledge.
   */
  describe('escalations', () => {
    it('shows the open escalation in a banner, and resolving it clears the banner and the dot', async () => {
      const user = userEvent.setup()
      renderClient(2)
      await screen.findByText('Sunrise EdTech Pvt Ltd', undefined, SLOW)

      const banner = screen.getByRole('alert', { name: 'Open client escalations' })
      expect(within(banner).getByText('Data migration')).toBeInTheDocument()
      expect(
        within(banner).getByText('Migration delay is holding our launch date — please expedite.'),
      ).toBeInTheDocument()

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
      expect(labels).toContain('4. Data migration — In progress · red')
      expect(labels).not.toContain('4. Data migration — In progress · red — escalated, awaiting staff')
    })

    it('will not resolve with no note', async () => {
      const user = userEvent.setup()
      renderClient(2)
      await screen.findByRole('alert', { name: 'Open client escalations' }, SLOW)

      await user.click(screen.getByRole('button', { name: 'Resolve' }))
      const dialog = await screen.findByRole('dialog', undefined, SLOW)
      expect(within(dialog).getByRole('button', { name: 'Resolve' })).toBeDisabled()
    })
  })

  describe('the LIVE banner', () => {
    /**
     * The mockup's `banner-live` row, on the one seeded client that earned it.
     * The CSAT clause reads the detail's `csatScore` — B-119's survey answer
     * from the GO_LIVE sign-off, seeded 5 for GreenValley.
     */
    it('celebrates a LIVE client, with the go-live date and the CSAT score', async () => {
      renderClient(1)
      const banner = await screen.findByText(/Fully onboarded & LIVE since 7 Aug 2026/, undefined, SLOW)
      expect(banner).toHaveTextContent(/all 2 journeys complete, sign-offs on record · CSAT 5\/5/)
    })

    it('does not appear on a client still onboarding', async () => {
      renderClient(7)
      await screen.findByText('Little Scholars Preschool', undefined, SLOW)
      expect(screen.queryByText(/Fully onboarded/)).not.toBeInTheDocument()
    })
  })

  describe('the closing grid — §9\'s "client portal access + client info"', () => {
    it('mounts B-126\'s portal-login panel with the account it reads', async () => {
      renderClient(1)
      await screen.findByRole('heading', { name: 'Client portal login' }, SLOW)
      // The username reaches the header's caption line too, off the same
      // cached read — the mockup's "Login: CL-30412".
      expect(await screen.findAllByText(/GREENVALLEY\.deepa/, undefined, SLOW)).not.toHaveLength(0)
    })

    it('draws the client info card from the detail document', async () => {
      renderClient(1)
      await screen.findByRole('heading', { name: 'Client info' }, SLOW)

      // SPOCs — the primary flagged, and the departed one still visible: the
      // contract sends inactive contacts so a past sign-off stays explicable.
      expect(screen.getByText('Deepa Kulkarni')).toBeInTheDocument()
      expect(screen.getByText('PRIMARY')).toBeInTheDocument()
      expect(screen.getByText('Farida Qureshi')).toBeInTheDocument()

      // Products bought, requirements, and the address.
      expect(screen.getAllByText('EduTrack ERP').length).toBeGreaterThan(0)
      expect(screen.getByText('Single sign-on')).toBeInTheDocument()
      expect(screen.getByText(/14 Ridge Rd/)).toBeInTheDocument()

      // Attachments are the card's one extra read, listed by name.
      expect(await screen.findByText('greenvalley-msa-signed.pdf', undefined, SLOW)).toBeInTheDocument()
    })
  })

  /**
   * §8, the staff half. The client-facing page is `PublicSignoffPage`; this is
   * the side that asks, chases and withdraws.
   */
  describe('sign-off', () => {
    /**
     * GreenValley's ERP go-live is the seeded signed one — the sign-off behind
     * its LIVE banner and its CSAT. A settled decision is not re-askable, so
     * the panel reports it and offers the certificate rather than a button
     * whose only outcome is the server's 422.
     */
    it('reports an accepted go-live and offers its certificate, with nothing to re-ask', async () => {
      renderClient(1)
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
     * GreenValley's second journey is finished too and has never been asked,
     * so it is the one that offers the request.
     *
     * `sentToContactId` is required rather than defaulted, and the contract
     * says why: "the person who signs off a data migration is frequently not
     * the person who signs the contract, and a default that is usually right
     * is one nobody checks."
     */
    it('will not request a go-live until a contact is chosen, then chases it', async () => {
      const user = userEvent.setup()
      renderClient(1)
      await screen.findByRole('region', { name: 'Go-live sign-off' }, SLOW)

      // Expand the second journey — the one with no sign-off on it yet.
      const collapsed = screen
        .getAllByRole('button', { name: /complete/ })
        .find((b) => b.getAttribute('aria-expanded') === 'false')!
      await user.click(collapsed)

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
      renderClient(8)
      await screen.findByTestId('journey-step-panel', undefined, SLOW)
      expect(screen.queryByRole('region', { name: 'Go-live sign-off' })).not.toBeInTheDocument()
    })
  })

  it('offers the way back to the list', async () => {
    renderClient(1)
    const back = await screen.findByRole('link', { name: /All clients/ }, SLOW)
    expect(back).toHaveAttribute('href', '/onboarding/clients')
  })

  it('says so rather than rendering an empty page when the client is out of scope', async () => {
    renderClient(9999)
    expect(await screen.findByText('Client not found', undefined, SLOW)).toBeInTheDocument()
  })
})
