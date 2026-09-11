import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { ObClientDetailPage } from './ObClientDetailPage'

/**
 * C-110 · OB-05 against the mock server — the **client** half.
 *
 * The journeys, ribbons, step panels and §8 sign-offs moved to
 * `ObClientProductPage` and are tested in its own file. What is asserted here
 * is the client page as it stands: the gate, the escalation
 * banner, the LIVE banner and §9's closing pair.
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

  /**
   * §9's middle row is **not on this page**.
   *
   * The journey accordions, the ribbons, the step panels and §8's sign-off
   * panel live on `ObClientProductPage` and are tested there. The product
   * chooser that used to stand in for them here is gone too: OB-03's Products
   * Bought column links straight into a product's ribbons, so a second grid of
   * the same links was restating what the header already counts.
   */
  describe('no product section', () => {
    it('draws no Products region and no product links', async () => {
      renderClient(1)
      await screen.findByText('GreenValley International School', undefined, SLOW)
      await screen.findByRole('heading', { name: 'Client info' }, SLOW)

      expect(screen.queryByRole('region', { name: 'Products' })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: /Open EduTrack ERP/ })).not.toBeInTheDocument()
    })

    /** The header still says what was bought — the count is a fact about the
     * client, and it is the one thing the removed grid carried that a reader
     * cannot get from the gate or the info card. */
    it('still counts the purchased products in the header', async () => {
      renderClient(1)
      expect(await screen.findByText('2 products', undefined, SLOW)).toBeInTheDocument()
    })

    /**
     * The contract splits the strip from the ribbon so that "a client with six
     * journeys does not pay for six ribbons on first paint". With the ribbons
     * on their own page this one pays for **none** — the strongest form of
     * that rule, and the one that regresses the moment somebody puts a ribbon
     * back on this page to preview it.
     */
    it('fetches no journey ribbon at all', async () => {
      renderClient(1)
      await screen.findByRole('heading', { name: 'Client info' }, SLOW)

      expect(screen.queryByRole('list', { name: 'Journey steps' })).not.toBeInTheDocument()
      expect(screen.queryByTestId('journey-step-panel')).not.toBeInTheDocument()
    })
  })


  /**
   * C-126 · Sunrise's "Data migration" step (journey 21, step 214) carries
   * the fixture's one seeded open escalation (`OB_CLIENT_ESCALATIONS` in the
   * mock db) — the banner plan §4/§9 ask for, and resolve-and-acknowledge.
   */
  describe('escalations', () => {
    it('shows the open escalation in a banner, and resolving it clears the banner', async () => {
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

      // The dialog closes only once the resolve has landed *and* the
      // invalidated read has come back — `EscalationBanner` awaits both before
      // it clears `resolving`. Waiting for it is what keeps this test from
      // ending with its own write still in flight, which the next test then
      // inherits.
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), SLOW)

      // The dot that stops ringing red is on the product page, where the
      // ribbon is — `ObClientProductPage.test.tsx` carries that half.
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
